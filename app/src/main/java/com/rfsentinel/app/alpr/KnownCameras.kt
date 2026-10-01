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
    val maxspeed: Int? = null
) {
    enum class Kind { ALPR, SPEED, RED_LIGHT }

    val type: Kind get() = kind ?: Kind.ALPR

    val label: String get() = when (type) {
        Kind.ALPR -> (brand ?: "Plate reader") + " (ALPR)"
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
            ");out center tags;"
    }

    /** What an OSM element is, from its tags; null when it's none of ours. */
    fun kindOf(tag: (String) -> String?): KnownCamera.Kind? = when {
        tag("surveillance:type").equals("ALPR", ignoreCase = true) -> KnownCamera.Kind.ALPR
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
        val kept = ArrayList<KnownCamera>()
        fun isNode(c: KnownCamera) = c.osmId.startsWith("node")
        for (c in cameras.sortedByDescending { (if (it.maxspeed != null) 2 else 0) + (if (isNode(it)) 1 else 0) }) {
            val dup = c.type != KnownCamera.Kind.ALPR && kept.any {
                it.type == c.type &&
                    DeviceRegistry.metersBetween(it.lat, it.lon, c.lat, c.lon) < (if (isNode(it) && isNode(c)) 40.0 else 150.0)
            }
            if (!dup) kept += c
        }
        // Keep the exact node position when a relation won only because it had the limit.
        return kept.map { k ->
            if (isNode(k) || k.type == KnownCamera.Kind.ALPR) k
            else cameras.firstOrNull { isNode(it) && it.type == k.type && DeviceRegistry.metersBetween(it.lat, it.lon, k.lat, k.lon) < 150.0 }
                ?.let { n -> k.copy(lat = n.lat, lon = n.lon) } ?: k
        }
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
                maxspeed = if (kind == KnownCamera.Kind.SPEED) parseMaxspeed(tag("maxspeed")) else null
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
