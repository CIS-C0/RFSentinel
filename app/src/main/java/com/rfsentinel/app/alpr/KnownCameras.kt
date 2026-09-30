package com.rfsentinel.app.alpr

import com.google.gson.JsonParser
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.cos
import kotlin.math.max

/**
 * Plate-reader (ALPR) cameras mapped in OpenStreetMap - most famously by the
 * DeFlock project - with the tag `surveillance:type=ALPR`. Most plate readers
 * send their data over cellular and have no radio signature a phone can hear,
 * so a map of known locations is the only way to warn about them.
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
    val direction: Int?
) {
    val label: String get() = (brand ?: "Plate reader") + " (ALPR)"
}

object KnownCameras {

    /** Overpass QL for every mapped plate reader in a bounding box. */
    fun query(south: Double, west: Double, north: Double, east: Double): String =
        "[out:json][timeout:90];nwr[\"surveillance:type\"=\"ALPR\"]" +
            "($south,$west,$north,$east);out center tags;"

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
            KnownCamera(
                osmId = "$type/$id", lat = lat, lon = lon,
                brand = tag("manufacturer", "brand"),
                operator = tag("operator"),
                direction = parseDirection(tag("camera:direction", "direction"))
            )
        }
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
     * (capped at 600 m), so there's time to notice on a highway.
     */
    fun warnRadius(speedMs: Float?): Double = max(150.0, minOf(600.0, (speedMs ?: 0f) * 20.0))

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
