package com.rfsentinel.app.nav

import com.rfsentinel.app.service.DeviceRegistry
import java.text.Normalizer
import java.util.Locale

/**
 * Putting search results in the order a person expects (pure; unit-tested): a place whose name is what
 * you typed, or starts with it, before one that merely mentions it, and among equals the nearest first -
 * where the search servers only know "close to the words" or "close to you", not both. Also finds your
 * own saved places as you type (instantly, before any server answers), drops the same place listed
 * twice, and reads a pair of coordinates typed into the box.
 */
object PlaceRank {

    /** Lower case, accents and punctuation gone, single spaces: "Café-Bar  Zürich" -> "cafe bar zurich". */
    fun normalize(s: String): String {
        // Letters that don't split into a base letter and an accent.
        val folded = s.replace("ß", "ss").replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("Æ", "AE")
            .replace("œ", "oe").replace("đ", "d").replace("ł", "l").replace("Ł", "L")
        val decomposed = Normalizer.normalize(folded, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var space = true
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch)) { sb.append(ch.lowercaseChar()); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    /** How well [place] answers [query]: 0 its name is exactly it, 1 starts with it, 2 a word of it does, 3 it appears in the name or address, 4 not at all. */
    fun matchClass(query: String, place: Place): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 4
        val name = normalize(place.name)
        if (name == q) return 0
        if (name.startsWith(q)) return 1
        val words = name.split(' ')
        val tokens = q.split(' ')
        if (words.any { it.startsWith(q) } || tokens.all { t -> words.any { it.startsWith(t) } }) return 2
        if (name.contains(q)) return 3
        val all = name + " " + normalize(place.address)
        if (tokens.all { t -> all.contains(t) }) return 3
        return 4
    }

    /**
     * [places] best answer first. A place's score is how well its name answers the words ([matchClass]) plus a
     * step for how far it is from ([lat], [lon]): within 50 km none, then one for each of 250 km, 1000 km and
     * beyond. So the Biodome 20 km away beats a place of the very same name on another continent, yet when
     * everything is far (you are looking at a trip) the best name still wins. Ties go to the nearest, then to
     * the server's own order; without a position only the name counts.
     */
    fun rank(query: String, places: List<Place>, lat: Double?, lon: Double?): List<Place> {
        class Ranked(val place: Place, val index: Int, val score: Int, val meters: Double)
        val located = lat != null && lon != null
        return places.mapIndexed { i, p ->
            val d = if (located) DeviceRegistry.metersBetween(lat!!, lon!!, p.lat, p.lon) else 0.0
            Ranked(p, i, matchClass(query, p) + (if (located) distanceStep(d) else 0), d)
        }.sortedWith(compareBy<Ranked> { it.score }.thenBy { it.meters }.thenBy { it.index }).map { it.place }
    }

    /** 0 within 50 km, 1 to 250 km, 2 to 1000 km, 3 beyond: how far a place is, in match-class steps. */
    internal fun distanceStep(meters: Double): Int = when {
        meters <= 50_000.0 -> 0
        meters <= 250_000.0 -> 1
        meters <= 1_000_000.0 -> 2
        else -> 3
    }

    /** The same place once: same name within about 150 m (a park listed as a node and as an area). */
    fun dedupe(places: List<Place>): List<Place> {
        val kept = ArrayList<Place>()
        for (p in places) {
            val n = normalize(p.name)
            if (kept.none { normalize(it.name) == n && DeviceRegistry.metersBetween(it.lat, it.lon, p.lat, p.lon) < 150.0 }) kept += p
        }
        return kept
    }

    /** What to keep of an earlier answer while a longer query is still being asked: what still matches it. */
    fun stillMatching(query: String, earlier: List<Place>): List<Place> = earlier.filter { matchClass(query, it) <= 3 }

    /**
     * Your own places (Home, Work, starred, recent) that match [query], best first, each once. Home and Work
     * are found by those words too. [limit] of them.
     */
    fun mine(query: String, home: Place?, work: Place?, favorites: List<Place>, recents: List<Place>, limit: Int = 4): List<Place> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(' ')
        val named = listOfNotNull(home?.let { it.copy(name = "Home", address = it.address.ifBlank { it.name }) },
            work?.let { it.copy(name = "Work", address = it.address.ifBlank { it.name }) }) + favorites + recents
        val matching = named.filter { p ->
            val all = normalize(p.name) + " " + normalize(p.address)
            tokens.all { t -> all.split(' ').any { it.startsWith(t) } }
        }
        return rank(query, dedupe(matching), null, null).take(limit)
    }

    /** A place for "48.137, 11.575" / "48.137 11.575" / "48.137;11.575" (latitude first), or null when [text] isn't coordinates. */
    fun coordinates(text: String): Place? {
        val m = COORDS.matchEntire(text.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Place(String.format(Locale.US, "%.5f, %.5f", lat, lon), "Coordinates", lat, lon)
    }

    /** Both numbers carry decimals, so "10 20" (a house number and a postcode, say) is never taken for a position. */
    private val COORDS = Regex("""^([-+]?\d{1,3}\.\d+)\s*[,;\s]\s*([-+]?\d{1,3}\.\d+)$""")

    /** The key an answer is remembered under: the words and the ~1 km cell of the position that ranked them. */
    fun cacheKey(query: String, lat: Double?, lon: Double?): String =
        normalize(query) + "|" + (if (lat != null && lon != null) String.format(Locale.US, "%.2f,%.2f", lat, lon) else "")
}
