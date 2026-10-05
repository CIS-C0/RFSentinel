package com.rfsentinel.app.online

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.abs

/**
 * Police and government aircraft overhead, from community ADS-B networks (adsb.fi,
 * adsb.lol: no key, and unlike the commercial flight trackers they don't hide
 * law-enforcement flights). An aircraft counts when its ICAO address is in the bundled
 * registry of law-enforcement airframes (FAA + Transport Canada), when the feed names a
 * police owner, or when an unlisted aircraft keeps circling low over the same spot.
 */
object PoliceAircraft {

    /** One aircraft from the feed. Altitude in feet, speed in knots, track in degrees. */
    data class Plane(
        val hex: String,
        val callsign: String?,
        val registration: String?,
        val type: String?,
        val owner: String?,
        val lat: Double,
        val lon: Double,
        val altitudeFt: Int?,
        val speedKt: Double?,
        val trackDeg: Double?,
        val military: Boolean
    )

    data class Entry(val registration: String, val owner: String, val model: String)

    /** Matches police / federal law-enforcement owner names (also in the feed's owner field). */
    private val LE_OWNER = Regex(
        "POLICE|SHERIFF|HIGHWAY PATROL|STATE PATROL|TROOPER|PUBLIC SAFETY|CONSTABLE|MARSHALS?\\b|" +
            "CUSTOMS|BORDER (PROTECTION|SERVICES)|HOMELAND SECURITY|FEDERAL BUREAU OF INVESTIGATION|" +
            "DRUG ENFORCEMENT|LAW ENFORCEMENT|GENDARMERIE",
        RegexOption.IGNORE_CASE
    )

    @Volatile private var registry: Map<String, Entry>? = null

    fun load(context: Context): Map<String, Entry> = registry ?: synchronized(this) {
        registry ?: parseRegistry(
            context.assets.open("aircraft/law_enforcement.tsv").bufferedReader().use { it.readText() }
        ).also { registry = it }
    }

    internal fun parseRegistry(text: String): Map<String, Entry> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3) null else f[0].lowercase() to Entry(f[1], f[2], f.getOrElse(3) { "" })
        }
        .toMap()

    /** Aircraft from an adsb.fi ("aircraft") or adsb.lol ("ac") response. */
    internal fun parseFeed(json: String): List<Plane> {
        val root = JsonParser.parseString(json).asJsonObject
        val list = (root.getAsJsonArray("aircraft") ?: root.getAsJsonArray("ac")) ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e.asJsonObject
            val lat = o.num("lat") ?: return@mapNotNull null
            val lon = o.num("lon") ?: return@mapNotNull null
            val hex = o.str("hex")?.lowercase()?.removePrefix("~") ?: return@mapNotNull null
            Plane(
                hex = hex,
                callsign = o.str("flight")?.trim()?.ifEmpty { null },
                registration = o.str("r"),
                type = o.str("desc") ?: o.str("t"),
                owner = o.str("ownOp"),
                lat = lat, lon = lon,
                // alt_baro is "ground" for aircraft on the ground.
                altitudeFt = o.num("alt_baro")?.toInt() ?: o.num("alt_geom")?.toInt() ?: if (o.str("alt_baro") == "ground") 0 else null,
                speedKt = o.num("gs"),
                trackDeg = o.num("track"),
                military = ((o.num("dbFlags")?.toInt() ?: 0) and 1) != 0
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }

    private fun JsonObject.num(k: String): Double? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    /**
     * What an aircraft is, or null when it's ordinary traffic. [circling] comes from
     * [LoiterTracker]; [distanceM] is the ground distance from you.
     */
    fun classify(p: Plane, registry: Map<String, Entry>, circling: Boolean, distanceM: Double): Hit? {
        val where = describe(p, distanceM)
        registry[p.hex]?.let { e ->
            return Hit(Category.AIRCRAFT, "Police aircraft overhead: ${titleCase(e.owner)}", if (circling) 90 else 85,
                "${e.registration} (${e.model.ifBlank { p.type ?: "aircraft" }}), registered to ${titleCase(e.owner)}. $where" +
                    if (circling) " It is circling." else "",
                "FAA / Transport Canada aircraft registry; ADS-B (adsb.fi / adsb.lol)")
        }
        p.owner?.takeIf { LE_OWNER.containsMatchIn(it) }?.let { owner ->
            return Hit(Category.AIRCRAFT, "Police aircraft overhead: ${titleCase(owner)}", if (circling) 85 else 80,
                "${p.registration ?: p.hex.uppercase()} (${p.type ?: "aircraft"}), operated by ${titleCase(owner)}. $where" +
                    if (circling) " It is circling." else "",
                "ADS-B owner / operator (adsb.fi)")
        }
        if (circling) {
            val low = (p.altitudeFt ?: 0) in 1..3_000
            return Hit(Category.AIRCRAFT, "Aircraft circling overhead", if (low) 55 else 45,
                "${p.registration ?: p.callsign ?: p.hex.uppercase()} (${p.type ?: "aircraft"}) keeps circling over the same area. $where " +
                    "Police and surveillance flights orbit like this, but so do news, training and survey flights - verify.",
                "ADS-B track (loiter detection)")
        }
        if (p.military) {
            return Hit(Category.AIRCRAFT, "Military / government aircraft nearby", 40,
                "${p.registration ?: p.callsign ?: p.hex.uppercase()} (${p.type ?: "aircraft"}). $where",
                "ADS-B database flag (military)")
        }
        return null
    }

    private fun describe(p: Plane, distanceM: Double): String =
        listOfNotNull(
            if (distanceM < 1000) "About ${(distanceM / 100).toInt() * 100} m from you"
            else String.format(java.util.Locale.US, "%.1f km from you", distanceM / 1000),
            p.altitudeFt?.let { if (it <= 0) "on the ground" else "at ${it} ft" },
            p.speedKt?.let { "${it.toInt()} kt" }
        ).joinToString(", ") + "."

    internal fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
        if (w.length <= 3 && w in setOf("us", "fbi", "dea", "cbp", "dps", "rcmp", "opp")) w.uppercase()
        else w.replaceFirstChar { it.uppercase() }
    }

    /**
     * Spots aircraft orbiting over one area: in the last 12 minutes it turned through at
     * least one and a half full circles while staying within 4 km of its own average
     * position, below 8,000 ft and slower than 180 kt (airliners and passing traffic don't).
     */
    class LoiterTracker {
        private data class Fix(val time: Long, val lat: Double, val lon: Double, val track: Double)
        private val tracks = HashMap<String, ArrayDeque<Fix>>()

        fun update(p: Plane, now: Long): Boolean {
            val q = tracks.getOrPut(p.hex) { ArrayDeque() }
            p.trackDeg?.let { q.addLast(Fix(now, p.lat, p.lon, it)) }
            while (q.isNotEmpty() && now - q.first().time > WINDOW_MS) q.removeFirst()
            if (q.size < 6 || now - q.first().time < 5 * 60_000L) return false
            if ((p.altitudeFt ?: 0) > 8_000 || (p.speedKt ?: 0.0) > 180) return false
            var turned = 0.0
            for (i in 1 until q.size) {
                var d = q[i].track - q[i - 1].track
                if (d > 180) d -= 360
                if (d < -180) d += 360
                turned += d
            }
            val cLat = q.sumOf { it.lat } / q.size
            val cLon = q.sumOf { it.lon } / q.size
            val spread = q.maxOf { DeviceRegistry.metersBetween(cLat, cLon, it.lat, it.lon) }
            return abs(turned) >= 540 && spread <= 4_000
        }

        /** Forgets aircraft not heard for a while. */
        fun prune(now: Long) {
            tracks.entries.removeAll { (_, q) -> q.isEmpty() || now - q.last().time > WINDOW_MS }
        }

        companion object { private const val WINDOW_MS = 12 * 60_000L }
    }

    /** Ground distance in metres. */
    fun distance(p: Plane, lat: Double, lon: Double): Double = DeviceRegistry.metersBetween(lat, lon, p.lat, p.lon)

    /** One aircraft as last heard, with what it matched (null: ordinary traffic). */
    data class Seen(val plane: Plane, val hit: Hit?)

    /** Every aircraft from the latest poll, for the map; empty when the source is off. */
    @Volatile var latest: List<Seen> = emptyList()
    @Volatile var latestAt = 0L

    fun feedUrls(lat: Double, lon: Double, radiusKm: Int): List<String> {
        val nm = Math.round(radiusKm / 1.852).toInt().coerceIn(1, 250)
        // Rounded to ~1 km: the feeds don't need your exact position.
        val la = String.format(java.util.Locale.US, "%.2f", lat)
        val lo = String.format(java.util.Locale.US, "%.2f", lon)
        return listOf(
            "https://opendata.adsb.fi/api/v2/lat/$la/lon/$lo/dist/$nm",
            "https://api.adsb.lol/v2/point/$la/$lo/$nm"
        )
    }

}
