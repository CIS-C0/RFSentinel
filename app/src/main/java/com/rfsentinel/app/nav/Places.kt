package com.rfsentinel.app.nav

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A place to go: what to call it, a short address, and where it is. (Same fields as saved recents always had.) */
data class Place(val name: String, val address: String, val lat: Double, val lon: Double)

/**
 * Place search. As you type: Photon (komoot's OpenStreetMap search), which allows suggestions per
 * keystroke. When you press search: Photon and OpenStreetMap's own Nominatim at once - Nominatim's rules
 * forbid asking per keystroke, but a search you submit is what it is for - and whichever answers first is
 * shown at once, the other merged in when it arrives. No key or account; the servers see what you type,
 * the rough area to prefer (rounded to about 1 km) and your IP. Only asked while you search, or when you
 * drop a pin and want to know what's there.
 *
 * Requests stop the moment a newer search replaces them. Parsing is pure Kotlin and unit-tested.
 */
object Places {

    const val SEARCH_URL = "https://photon.komoot.io/api/"
    const val REVERSE_URL = "https://photon.komoot.io/reverse"
    const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
    /** Suggestions start after this many characters. */
    const val MIN_QUERY = 3
    private const val FETCH = 10

    // ---- requests -------------------------------------------------------------------------------------

    private fun photonUrl(query: String, nearLat: Double?, nearLon: Double?, limit: Int): String {
        val url = StringBuilder(SEARCH_URL).append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8")).append("&limit=").append(limit)
        if (nearLat != null && nearLon != null) url.append(String.format(Locale.US, "&lat=%.2f&lon=%.2f", nearLat, nearLon))
        language()?.let { url.append("&lang=").append(it) }
        return url.toString()
    }

    /** Photon's languages (checked against the live server: anything else, "it" included, is answered HTTP 400); others get the place's own name. */
    internal fun language(tag: String = Locale.getDefault().language): String? = tag.takeIf { it in PHOTON_LANGUAGES }

    private val PHOTON_LANGUAGES = setOf("en", "de", "fr")

    private fun nominatimUrl(query: String, nearLat: Double?, nearLon: Double?, limit: Int): String {
        val url = StringBuilder(NOMINATIM_URL).append("?format=jsonv2&addressdetails=1&limit=").append(limit)
            .append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
        // Prefer (not require) places within a degree of you; the position is rounded to about 1 km.
        if (nearLat != null && nearLon != null) {
            val la = Math.round(nearLat * 100) / 100.0; val lo = Math.round(nearLon * 100) / 100.0
            url.append(String.format(Locale.US, "&viewbox=%.2f,%.2f,%.2f,%.2f", lo - 1, la + 1, lo + 1, la - 1))
        }
        url.append("&accept-language=").append(Locale.getDefault().toLanguageTag())
        return url.toString()
    }

    /** Photon's suggestions for [query], nearest to ([nearLat], [nearLon]) preferred; stops when the calling coroutine is cancelled. */
    suspend fun searchPhoton(query: String, nearLat: Double?, nearLon: Double?, userAgent: String, limit: Int = FETCH): List<Place> =
        parse(fetch(photonUrl(query, nearLat, nearLon, limit), userAgent, 10_000))

    /** Nominatim's answer for a search that was submitted (never per keystroke: its rules). */
    suspend fun searchNominatim(query: String, nearLat: Double?, nearLon: Double?, userAgent: String, limit: Int = FETCH): List<Place> =
        parseNominatim(fetch(nominatimUrl(query, nearLat, nearLon, limit), userAgent, 10_000))

    /**
     * A submitted search: Photon and Nominatim at once. [onResults] gets the merged, ranked list each time
     * one of them answers (so the first answer is on screen at once); once one has answered the other is given
     * [graceMs] more. Returns whether either answered; throws the last error when both failed.
     */
    suspend fun searchSubmitted(query: String, nearLat: Double?, nearLon: Double?, userAgent: String,
                                graceMs: Long = Long.MAX_VALUE, onResults: (List<Place>) -> Unit): Boolean = coroutineScope {
        val results = Channel<Result<List<Place>>>(2)
        val jobs = listOf(
            launch { results.send(runCatching { searchPhoton(query, nearLat, nearLon, userAgent) }) },
            launch { results.send(runCatching { searchNominatim(query, nearLat, nearLon, userAgent) }) }
        )
        val merged = ArrayList<Place>()
        var answered = 0; var any = false; var lastError: Throwable? = null
        var deadline = Long.MAX_VALUE
        try {
            while (answered < 2) {
                val next = if (deadline == Long.MAX_VALUE) results.receive()
                    else withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(0)) { results.receive() } ?: break
                answered++
                next.onSuccess { found ->
                    any = true
                    merged += found
                    onResults(PlaceRank.rank(query, PlaceRank.dedupe(merged), nearLat, nearLon))
                    if (deadline == Long.MAX_VALUE && graceMs != Long.MAX_VALUE) deadline = System.currentTimeMillis() + graceMs
                }.onFailure { if (it is CancellationException) throw it; lastError = it }
            }
        } finally {
            jobs.forEach { it.cancel() } // the slower one stops: its connection is closed
        }
        if (!any) lastError?.let { throw it }
        any
    }

    /** What's at a point (a dropped pin): the nearest address or place, or null. */
    fun reverse(lat: Double, lon: Double, userAgent: String): Place? =
        parse(getBlocking(String.format(Locale.US, "%s?lat=%.6f&lon=%.6f", REVERSE_URL, lat, lon), userAgent, 12_000)).firstOrNull()

    /** Blocking: for the places that already run on a background thread (the car screens). Photon only, as it is typed. */
    fun search(query: String, nearLat: Double?, nearLon: Double?, userAgent: String, limit: Int = FETCH): List<Place> =
        parse(getBlocking(photonUrl(query, nearLat, nearLon, limit), userAgent, 12_000))

    private fun open(url: String, userAgent: String, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = readMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }

    private fun read(c: HttpURLConnection): String {
        try {
            if (c.responseCode != 200) error("The search server answered HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun getBlocking(url: String, userAgent: String, readMs: Int): String = read(open(url, userAgent, readMs))

    /** Tests answer the requests themselves (url, user agent, read timeout). */
    internal var fetcher: (suspend (String, String, Int) -> String)? = null

    /** A GET that gives up (and closes its connection) the moment the coroutine asking is cancelled: a new keystroke. */
    private suspend fun fetch(url: String, userAgent: String, readMs: Int): String {
        fetcher?.let { return it(url, userAgent, readMs) }
        return suspendCancellableCoroutine { cont ->
            val conn = open(url, userAgent, readMs)
            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val text = read(conn)
                    if (cont.isActive) cont.resume(text)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            cont.invokeOnCancellation { job.cancel(); runCatching { conn.disconnect() } }
        }
    }

    // ---- reading answers (pure) ---------------------------------------------------------------------

    fun parse(json: String): List<Place> {
        val features = JsonParser.parseString(json).asJsonObject.getAsJsonArray("features") ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f.asJsonObject
            val c = o.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: return@mapNotNull null
            val p = o.getAsJsonObject("properties") ?: JsonObject()
            val street = listOfNotNull(p.str("housenumber"), p.str("street")).joinToString(" ").ifBlank { null }
            val town = p.str("city") ?: p.str("town") ?: p.str("village") ?: p.str("locality") ?: p.str("county")
            // A street itself, or a house without a name, is called by its address.
            val name = p.str("name") ?: street ?: town ?: return@mapNotNull null
            val parts = listOfNotNull(street?.takeIf { it != name }, town?.takeIf { it != name }, p.str("state"))
            Place(name, describe(kindLabel(p.str("osm_key"), p.str("osm_value")), parts), c[1].asDouble, c[0].asDouble)
        }.distinctBy { "${it.name}|${it.address}" }
    }

    /** Nominatim's answer (jsonv2, with address details). */
    fun parseNominatim(json: String): List<Place> {
        val arr = JsonParser.parseString(json).asJsonArray
        return arr.mapNotNull { e ->
            val o = e.asJsonObject
            val lat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val a = o.getAsJsonObject("address") ?: JsonObject()
            val street = listOfNotNull(a.str("house_number"), a.str("road")).joinToString(" ").ifBlank { null }
            val town = a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("municipality") ?: a.str("suburb") ?: a.str("county")
            val full = o.str("display_name").orEmpty()
            val name = o.str("name") ?: street ?: full.substringBefore(',').ifBlank { null } ?: return@mapNotNull null
            val parts = listOfNotNull(street?.takeIf { it != name }, town?.takeIf { it != name }, a.str("state"))
            Place(name, describe(kindLabel(o.str("category"), o.str("type")), parts), lat, lon)
        }.distinctBy { "${it.name}|${it.address}" }
    }

    /** "Park · Munich, Bavaria": what kind of place it is, then the two most useful address parts. */
    private fun describe(kind: String?, parts: List<String>): String {
        val where = parts.take(2).joinToString(", ")
        return when {
            kind != null && where.isNotEmpty() -> "$kind · $where"
            kind != null -> kind
            else -> where
        }
    }

    /** A person's word for an OpenStreetMap kind of place ("leisure=park" -> "Park"); null for houses and plain buildings (the address says it). */
    fun kindLabel(key: String?, value: String?): String? {
        if (key == null || value == null) return null
        val v = value.lowercase(Locale.ROOT)
        return when (key.lowercase(Locale.ROOT)) {
            "building", "addr", "house" -> null
            "place" -> when (v) {
                "house", "plot", "yes" -> null
                "city" -> "City"; "town" -> "Town"; "village", "hamlet" -> "Village"
                "suburb", "neighbourhood", "quarter", "borough", "city_block" -> "Neighbourhood"
                "state", "region", "province", "county" -> "Region"; "country" -> "Country"
                "square" -> "Square"; "island" -> "Island"
                else -> human(v)
            }
            "highway" -> when (v) {
                "bus_stop" -> "Bus stop"; "services", "rest_area" -> "Rest stop"
                "motorway", "trunk", "primary", "secondary", "tertiary", "residential", "unclassified", "service", "living_street",
                "pedestrian", "footway", "cycleway", "path", "track", "road" -> "Street"
                else -> human(v)
            }
            "amenity" -> when (v) {
                "restaurant" -> "Restaurant"; "cafe" -> "Cafe"; "fast_food" -> "Fast food"; "bar", "pub" -> "Bar"
                "fuel" -> "Petrol station"; "charging_station" -> "Charging"; "parking" -> "Parking"
                "pharmacy" -> "Pharmacy"; "hospital" -> "Hospital"; "clinic", "doctors" -> "Clinic"
                "bank" -> "Bank"; "atm" -> "ATM"; "post_office" -> "Post office"
                "school", "kindergarten" -> "School"; "university", "college" -> "University"
                "library" -> "Library"; "theatre" -> "Theatre"; "cinema" -> "Cinema"
                "place_of_worship" -> "Place of worship"; "toilets" -> "Toilets"; "townhall" -> "Town hall"
                else -> human(v)
            }
            "shop" -> when (v) { "supermarket" -> "Supermarket"; "convenience" -> "Convenience store"; "yes" -> "Shop"; else -> human(v) }
            "tourism" -> when (v) { "hotel" -> "Hotel"; "hostel" -> "Hostel"; "museum" -> "Museum"; "attraction" -> "Attraction"
                "viewpoint" -> "Viewpoint"; "camp_site" -> "Campsite"; "information" -> "Information"; else -> human(v) }
            "leisure" -> when (v) { "park" -> "Park"; "garden" -> "Garden"; "stadium" -> "Stadium"; "sports_centre" -> "Sports centre"
                "playground" -> "Playground"; "pitch" -> "Pitch"; "swimming_pool" -> "Pool"; "nature_reserve" -> "Nature reserve"; else -> human(v) }
            "railway" -> when (v) { "station" -> "Train station"; "halt" -> "Train stop"; "tram_stop" -> "Tram stop"; "subway_entrance" -> "Metro entrance"; else -> human(v) }
            "public_transport" -> "Transit stop"
            "aeroway" -> when (v) { "aerodrome" -> "Airport"; "terminal" -> "Terminal"; else -> human(v) }
            "natural" -> when (v) { "water" -> "Water"; "peak" -> "Peak"; "beach" -> "Beach"; "wood" -> "Wood"; else -> human(v) }
            "boundary" -> if (v == "administrative") "Area" else human(v)
            "office" -> "Office"
            "landuse" -> null
            else -> null
        }
    }

    private fun human(v: String) = v.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
}
