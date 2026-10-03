package com.rfsentinel.app.alpr

import com.google.gson.JsonParser
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.cos
import kotlin.math.max

/**
 * Enforcement cameras mapped in OpenStreetMap: plate readers (ALPR, tagged
 * `surveillance:type=ALPR`, most famously by the DeFlock project), speed
 * cameras (`highway=speed_camera`, `enforcement=maxspeed|average_speed`) and
 * red-light cameras (`enforcement=traffic_signals`). They send their data over
 * cellular or wire and have no radio signature a phone can hear, so a map of
 * known locations is the only way to warn about them.
 *
 * Pure Kotlin (parsing and geometry), so it's unit-tested on the JVM.
 * Data © OpenStreetMap contributors, ODbL.
 */
data class KnownCamera(
    val osmId: String,
    val lat: Double,
    val lon: Double,
    val brand: String?,
    val operator: String?,
    /** Direction the camera faces, degrees from north, when mapped. */
    val direction: Int?,
    /** Null in caches saved before speed cameras were added: those are all plate readers. */
    val kind: Kind? = null,
    /** Enforced speed limit (km/h) for speed cameras, when mapped. */
    val maxspeed: Int? = null,
    /** A Flock camera mapped as an ordinary camera: most are plate readers, some only record video. */
    val probable: Boolean? = null
) {
    enum class Kind { ALPR, SPEED, RED_LIGHT }

    val type: Kind get() = kind ?: Kind.ALPR

    val label: String get() = when (type) {
        Kind.ALPR -> if (probable == true) "${brand ?: "Flock"} camera (probably a plate reader)"
            else (brand ?: "Plate reader") + " (ALPR)"
        Kind.SPEED -> "Speed camera" + (maxspeed?.let { " ($it km/h)" } ?: "")
        Kind.RED_LIGHT -> "Red-light camera"
    }

    /** Short phrase for spoken alerts. */
    val spoken: String get() = when (type) {
        Kind.ALPR -> "Plate camera ahead"
        Kind.SPEED -> "Speed camera ahead" + (maxspeed?.let { ", $it" } ?: "")
        Kind.RED_LIGHT -> "Red-light camera ahead"
    }
}

object KnownCameras {

    /**
     * Overpass QL for every mapped plate reader, speed and red-light camera in
     * a bounding box. Kept cheap on purpose - simple per-type key lookups and a
     * short declared timeout - because busy public servers turn away heavy or
     * long requests first (HTTP 429 / 504). [parse] sorts out the kinds.
     */
    fun query(south: Double, west: Double, north: Double, east: Double): String {
        val b = "($south,$west,$north,$east)"
        return "[out:json][timeout:25];(" +
            "node[\"surveillance:type\"=\"ALPR\"]$b;" +
            "way[\"surveillance:type\"=\"ALPR\"]$b;" +
            "node[\"highway\"=\"speed_camera\"]$b;" +
            "node[\"enforcement\"]$b;" +
            "relation[\"type\"=\"enforcement\"]$b;" +
            BRAND_KEYS.joinToString("") { k -> "nwr[\"surveillance:type\"=\"camera\"][\"$k\"~\"^Flock\",i]$b;" } +
            ");out center tags;"
    }

    private val BRAND_KEYS = listOf("brand", "manufacturer", "surveillance:brand", "surveillance:manufacturer", "operator")

    /** A Flock-branded camera mapped as an ordinary camera (not tagged ALPR). */
    fun isFlockCamera(tag: (String) -> String?) =
        tag("surveillance:type").equals("camera", ignoreCase = true) &&
            BRAND_KEYS.any { tag(it)?.startsWith("Flock", ignoreCase = true) == true }

    /**
     * What DeFlock's snapshot leaves out, for a box: plate readers without
     * `man_made=surveillance`, plate readers mapped as ways, and Flock cameras
     * mapped as ordinary cameras. Small, so it stays cheap even for a large box.
     */
    fun extrasQuery(south: Double, west: Double, north: Double, east: Double): String {
        val b = "($south,$west,$north,$east)"
        val flock = BRAND_KEYS.joinToString("") { k -> "nwr[\"surveillance:type\"=\"camera\"][\"$k\"~\"^Flock\",i]$b;" }
        return "[out:json][timeout:90];(" +
            "node[\"surveillance:type\"=\"ALPR\"][\"man_made\"!=\"surveillance\"]$b;" +
            "way[\"surveillance:type\"=\"ALPR\"]$b;" +
            "relation[\"surveillance:type\"=\"ALPR\"]$b;" +
            flock +
            ");out center tags;"
    }

    /** What an OSM element is, from its tags; null when it's none of ours. */
    fun kindOf(tag: (String) -> String?): KnownCamera.Kind? = when {
        tag("surveillance:type").equals("ALPR", ignoreCase = true) -> KnownCamera.Kind.ALPR
        isFlockCamera(tag) -> KnownCamera.Kind.ALPR
        tag("enforcement") == "traffic_signals" -> KnownCamera.Kind.RED_LIGHT
        tag("highway") == "speed_camera" || tag("enforcement") in setOf("maxspeed", "average_speed") -> KnownCamera.Kind.SPEED
        else -> null
    }

    /** "50", "50 km/h" or "30 mph" -> km/h; null otherwise. */
    fun parseMaxspeed(v: String?): Int? {
        val m = Regex("""^\s*(\d{1,3})\s*(mph|km/h|kmh)?\s*$""", RegexOption.IGNORE_CASE).find(v ?: return null) ?: return null
        val n = m.groupValues[1].toInt()
        return if (m.groupValues[2].equals("mph", ignoreCase = true)) Math.round(n * 1.609).toInt() else n
    }

    /**
     * A speed camera is often mapped twice: the camera node and an enforcement
     * relation whose centre sits somewhere along the enforced stretch. Keep one
     * per kind - within 40 m for two nodes, 150 m when one is a relation - and
     * prefer the copy that has the speed limit, then the node (exact position).
     */
    fun dedupe(cameras: List<KnownCamera>): List<KnownCamera> {
        fun isNode(c: KnownCamera) = c.osmId.startsWith("node")
        // ~150 m in degrees, generously (longitude degrees shrink away from the equator).
        fun close(a: KnownCamera, b: KnownCamera, m: Double) =
            kotlin.math.abs(a.lat - b.lat) < 0.0015 && kotlin.math.abs(a.lon - b.lon) < 0.0015 / max(0.05, cos(Math.toRadians(a.lat))) &&
                DeviceRegistry.metersBetween(a.lat, a.lon, b.lat, b.lon) < m
        // Plate readers are never merged (several often share a pole), so only speed
        // and red-light cameras - a few per city - go through the pairwise check.
        val (plates, enforcement) = cameras.partition { it.type == KnownCamera.Kind.ALPR }
        val kept = ArrayList<KnownCamera>()
        for (c in enforcement.sortedByDescending { (if (it.maxspeed != null) 2 else 0) + (if (isNode(it)) 1 else 0) }) {
            if (kept.none { it.type == c.type && close(it, c, if (isNode(it) && isNode(c)) 40.0 else 150.0) }) kept += c
        }
        // Keep the exact node position when a relation won only because it had the limit.
        val merged = kept.map { k ->
            if (isNode(k)) k
            else enforcement.firstOrNull { isNode(it) && it.type == k.type && close(it, k, 150.0) }
                ?.let { n -> k.copy(lat = n.lat, lon = n.lon) } ?: k
        }
        return plates + merged
    }

    /** Parses an Overpass JSON response (nodes, and ways/relations via their centre). */
    fun parse(json: String): List<KnownCamera> {
        val root = JsonParser.parseString(json).asJsonObject
        val elements = root.getAsJsonArray("elements") ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el.asJsonObject
            val type = o.get("type")?.asString ?: return@mapNotNull null
            val id = o.get("id")?.asLong ?: return@mapNotNull null
            val pos = if (o.has("lat")) o else o.getAsJsonObject("center") ?: return@mapNotNull null
            val lat = pos.get("lat")?.asDouble ?: return@mapNotNull null
            val lon = pos.get("lon")?.asDouble ?: return@mapNotNull null
            val tags = o.getAsJsonObject("tags")
            fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { k -> tags?.get(k)?.asString?.takeIf { it.isNotBlank() } }
            val kind = kindOf { tag(it) } ?: return@mapNotNull null
            KnownCamera(
                osmId = "$type/$id", lat = lat, lon = lon,
                brand = tag("manufacturer", "brand"),
                operator = tag("operator"),
                direction = parseDirection(tag("camera:direction", "direction")),
                kind = kind,
                maxspeed = if (kind == KnownCamera.Kind.SPEED) parseMaxspeed(tag("maxspeed")) else null,
                probable = if (!tag("surveillance:type").equals("ALPR", ignoreCase = true) && isFlockCamera { tag(it) }) true else null
            )
        }.let(::dedupe)
    }

    private val CARDINAL = mapOf(
        "N" to 0, "NNE" to 22, "NE" to 45, "ENE" to 67, "E" to 90, "ESE" to 112, "SE" to 135, "SSE" to 157,
        "S" to 180, "SSW" to 202, "SW" to 225, "WSW" to 247, "W" to 270, "WNW" to 292, "NW" to 315, "NNW" to 337
    )

    /** "135", "135.5", "SE" or "90;270" (first value) -> degrees; null otherwise. */
    fun parseDirection(v: String?): Int? {
        val first = v?.split(';')?.firstOrNull()?.trim()?.uppercase() ?: return null
        first.toDoubleOrNull()?.let { return ((it % 360 + 360) % 360).toInt() }
        return CARDINAL[first]
    }

    /**
     * Warning distance: at least 150 m, or ~20 s of travel at your speed
     * (capped at 600 m), so there's time to notice on a highway. Speed cameras
     * get ~30 s (300-900 m) so there's time to slow down.
     */
    fun warnRadius(speedMs: Float?, kind: KnownCamera.Kind = KnownCamera.Kind.ALPR): Double {
        val v = (speedMs ?: 0f).toDouble()
        return if (kind == KnownCamera.Kind.ALPR) max(150.0, minOf(600.0, v * 20.0))
        else max(300.0, minOf(900.0, v * 30.0))
    }

    /**
     * The next camera on your way: the nearest within [maxM] that lies within
     * [coneDeg] of your direction of travel ([bearingDeg]). Without a bearing
     * (standing still, no GPS heading) the nearest within [maxM] / 3. [skip]
     * leaves out cameras the user silenced.
     */
    fun ahead(
        cameras: List<KnownCamera>, lat: Double, lon: Double, bearingDeg: Float?,
        maxM: Double = 3_000.0, coneDeg: Double = 45.0, skip: (KnownCamera) -> Boolean = { false }
    ): Pair<KnownCamera, Double>? {
        if (bearingDeg == null) return near(cameras, lat, lon, maxM / 3).firstOrNull { !skip(it.first) }
        return near(cameras, lat, lon, maxM).firstOrNull { (c, d) ->
            if (skip(c)) return@firstOrNull false
            if (d < 30.0) return@firstOrNull true // right here
            val diff = kotlin.math.abs(((bearingTo(lat, lon, c.lat, c.lon) - bearingDeg) % 360 + 540) % 360 - 180)
            diff <= coneDeg
        }
    }

    /** Initial great-circle bearing from one point to another, degrees from north (0-360). */
    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dl) * cos(p2)
        val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }

    /** Cameras within [radiusM] of a point, nearest first. Cheap bounding-box prefilter. */
    fun near(cameras: List<KnownCamera>, lat: Double, lon: Double, radiusM: Double): List<Pair<KnownCamera, Double>> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * max(0.01, cos(Math.toRadians(lat))))
        return cameras.asSequence()
            .filter { kotlin.math.abs(it.lat - lat) <= dLat && kotlin.math.abs(it.lon - lon) <= dLon }
            .map { it to DeviceRegistry.metersBetween(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .toList()
    }
}
