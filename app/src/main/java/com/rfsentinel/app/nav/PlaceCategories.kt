package com.rfsentinel.app.nav

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.service.DeviceRegistry
import java.util.Locale

/**
 * "Petrol near me", "food along my route": places of one kind from OpenStreetMap (Overpass, the query
 * server the camera downloads already use), nearest first or in the order you reach them. Photon, the
 * name search, can't do this - it matches words in names, so "gas station" finds places called that.
 *
 * Privacy: a nearby search sends your position rounded to about 1 km, and a search along the route
 * sends the road ahead as points rounded to about 200 m. Pure Kotlin (queries, parsing, ranking); the
 * request itself is made by the caller. Unit-tested.
 */
object PlaceCategories {

    enum class Category(val label: String, val tags: List<Pair<String, String>>) {
        FUEL("Petrol", listOf("amenity" to "fuel")),
        CHARGING("Charging", listOf("amenity" to "charging_station")),
        FOOD("Food", listOf("amenity" to "restaurant", "amenity" to "fast_food", "amenity" to "cafe")),
        PARKING("Parking", listOf("amenity" to "parking")),
        SUPERMARKET("Shops", listOf("shop" to "supermarket", "shop" to "convenience")),
        PHARMACY("Pharmacy", listOf("amenity" to "pharmacy")),
        HOSPITAL("Hospital", listOf("amenity" to "hospital", "amenity" to "clinic")),
        CASH("Cash", listOf("amenity" to "atm", "amenity" to "bank")),
        TOILETS("Toilets", listOf("amenity" to "toilets")),
        REST("Rest stop", listOf("highway" to "services", "highway" to "rest_area"))
    }

    /** One place found: where it is, and how it relates to you / your route. */
    data class Hit(
        val place: Place,
        val category: Category,
        /** Straight-line distance from you, when searching near you. */
        val distanceM: Double? = null,
        /** Metres ahead of you along the route, and metres off it, when searching along the route. */
        val aheadM: Double? = null,
        val offM: Double? = null
    )

    /** Search this far around you. */
    const val NEAR_RADIUS_M = 5_000
    /** ...and this far either side of the road ahead. */
    const val ALONG_RADIUS_M = 1_500
    /** The road ahead is sampled this often for the query, to at most [ALONG_MAX_POINTS] points. */
    const val ALONG_STEP_M = 1_500.0
    const val ALONG_MAX_POINTS = 40
    private const val MAX_RESULTS = 60

    /** Rounds a coordinate to [places] decimals (2 = about 1 km, 3 = about 100 m) for the query. */
    private fun fuzz(v: Double, places: Int): String = String.format(Locale.US, "%.${places}f", v)

    /**
     * Overpass QL for [category] in a box reaching [NEAR_RADIUS_M] each way from ([lat], [lon]): the
     * position rounded to ~1 km, the box widened to match and rounded outward to a 1 km grid. A box is
     * far cheaper for the busy public servers than a circle.
     */
    fun nearQuery(category: Category, lat: Double, lon: Double): String {
        val r = NEAR_RADIUS_M + 1_500.0
        val clat = Math.round(lat * 100) / 100.0; val clon = Math.round(lon * 100) / 100.0
        val dLat = r / 110_540.0
        val dLon = r / (111_320.0 * Math.cos(Math.toRadians(clat)).coerceAtLeast(0.05))
        fun down(v: Double) = Math.floor(v * 100) / 100.0
        fun up(v: Double) = Math.ceil(v * 100) / 100.0
        val box = "${fuzz(down(clat - dLat), 2)},${fuzz(down(clon - dLon), 2)},${fuzz(up(clat + dLat), 2)},${fuzz(up(clon + dLon), 2)}"
        val parts = category.tags.joinToString("") { (k, v) -> "nwr[\"$k\"=\"$v\"]($box);" }
        return "[out:json][timeout:15];($parts);out center tags $MAX_RESULTS;"
    }

    /** Overpass QL for [category] within [ALONG_RADIUS_M] of [points] (lat, lon), rounded to ~200 m (the radius widened to match). */
    fun alongQuery(category: Category, points: List<Pair<Double, Double>>): String =
        around(category, "around:${ALONG_RADIUS_M + 250}," + points.joinToString(",") { "${fuzz(it.first, 3)},${fuzz(it.second, 3)}" })

    private fun around(c: Category, area: String): String {
        // One selector per tag: nwr["amenity"="fuel"](around:...) ... (an OR across the tags).
        val parts = c.tags.joinToString("") { (k, v) -> "nwr[\"$k\"=\"$v\"]($area);" }
        return "[out:json][timeout:20];($parts);out center tags $MAX_RESULTS;"
    }

    /** The route ahead of [fromAlongM] as query points, a kilometre apart, no more than [ALONG_MAX_POINTS]. */
    fun routeSample(route: Route, fromAlongM: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var m = fromAlongM.coerceAtLeast(0.0)
        while (m < route.lengthM && out.size < ALONG_MAX_POINTS) { out += RouteProgress.pointAt(route, m); m += ALONG_STEP_M }
        if (out.size < ALONG_MAX_POINTS) out += route.end
        return out
    }

    // ---- reading the answer ---------------------------------------------------------------------

    /** The places in an Overpass answer, for [category] (no distances yet). */
    fun parse(json: String, category: Category): List<Place> {
        val elements = JsonParser.parseString(json).asJsonObject.getAsJsonArray("elements") ?: return emptyList()
        val seen = HashSet<String>()
        return elements.mapNotNull { e ->
            val o = e.asJsonObject
            val tags = o.getAsJsonObject("tags") ?: JsonObject()
            val (lat, lon) = position(o) ?: return@mapNotNull null
            fun tag(k: String) = tags.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val name = tag("name") ?: tag("brand") ?: tag("operator") ?: category.label
            val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ").ifEmpty { null }
            val extra = when (category) {
                Category.FUEL -> tag("brand")?.takeIf { it != name }
                Category.CHARGING -> tag("operator")?.takeIf { it != name }
                else -> null
            }
            val address = listOfNotNull(street, tag("addr:city"), extra).take(2).joinToString(", ")
            // The same place twice (a building and its entrance node): once.
            if (!seen.add("$name|${"%.4f".format(Locale.US, lat)}|${"%.4f".format(Locale.US, lon)}")) return@mapNotNull null
            Place(name, address, lat, lon)
        }
    }

    private fun position(o: JsonObject): Pair<Double, Double>? {
        fun num(x: JsonObject, k: String) = x.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        val lat = num(o, "lat") ?: o.getAsJsonObject("center")?.let { num(it, "lat") } ?: return null
        val lon = num(o, "lon") ?: o.getAsJsonObject("center")?.let { num(it, "lon") } ?: return null
        return lat to lon
    }

    // ---- ranking --------------------------------------------------------------------------------

    /** Nearest first, within [NEAR_RADIUS_M] of the real position (the query's circle is wider). */
    fun rankNear(places: List<Place>, category: Category, lat: Double, lon: Double): List<Hit> =
        places.map { Hit(it, category, distanceM = DeviceRegistry.metersBetween(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceM!! <= NEAR_RADIUS_M }
            .sortedBy { it.distanceM }

    /**
     * In the order you reach them: those within [ALONG_RADIUS_M] of the route, and ahead of [fromAlongM]
     * (ones behind you are no use), each with how far ahead and how far off the road.
     */
    fun rankAlong(places: List<Place>, category: Category, route: Route, fromAlongM: Double): List<Hit> =
        places.mapNotNull { p ->
            val (along, off) = RouteProgress.project(route, p.lat, p.lon)
            if (off > ALONG_RADIUS_M || along < fromAlongM + 50.0) null
            else Hit(p, category, aheadM = along - fromAlongM, offM = off)
        }.sortedBy { it.aheadM }
}
