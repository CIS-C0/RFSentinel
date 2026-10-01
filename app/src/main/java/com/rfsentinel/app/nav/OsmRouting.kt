package com.rfsentinel.app.nav

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Destination search and driving routes from OpenStreetMap's public services:
 * Nominatim (search) and the OSRM demo server (routing). Only used when you
 * look for a destination or start a route; those servers then see what you
 * searched, your position and the destination, and your IP - never your scans.
 *
 * Parsing and wording are pure Kotlin and unit-tested; the network calls are
 * plain blocking HTTP (call them off the main thread).
 */
object OsmRouting {

    const val SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    const val ROUTE_URL = "https://router.project-osrm.org/route/v1/driving/"

    data class Destination(val name: String, val address: String, val lat: Double, val lon: Double)

    data class Step(
        /** Where the manoeuvre happens. */
        val lat: Double, val lon: Double,
        val type: String, val modifier: String?, val road: String, val exit: Int?
    ) {
        val instruction: String get() = instructionFor(type, modifier, road, exit)
    }

    data class Route(
        val points: List<Pair<Double, Double>>, // lat, lon
        val distanceM: Double,
        val durationS: Double,
        val steps: List<Step>
    )

    // ---- Network -------------------------------------------------------------------

    fun search(query: String, nearLat: Double?, nearLon: Double?, userAgent: String): List<Destination> {
        val q = StringBuilder(SEARCH_URL)
            .append("?format=jsonv2&addressdetails=0&limit=6&q=").append(URLEncoder.encode(query, "UTF-8"))
        if (nearLat != null && nearLon != null) {
            // Prefer places within ~100 km, without excluding the rest.
            q.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", nearLon - 1, nearLat + 1, nearLon + 1, nearLat - 1))
        }
        return parseSearch(get(q.toString(), userAgent))
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, userAgent: String): Route {
        val url = ROUTE_URL + String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat) +
            "?overview=full&geometries=geojson&steps=true"
        return parseRoute(get(url, userAgent))
    }

    private fun get(url: String, userAgent: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent) // required by the OSM usage policies
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        try {
            if (c.responseCode != 200) error("OpenStreetMap server answered HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Parsing (pure) ---------------------------------------------------------------

    fun parseSearch(json: String): List<Destination> =
        JsonParser.parseString(json).asJsonArray.mapNotNull { e ->
            val o = e.asJsonObject
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val full = o.get("display_name")?.asString ?: return@mapNotNull null
            val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: full.substringBefore(',')
            // Keep the address short for a car screen: street and town (first two parts).
            val rest = full.removePrefix(name).trimStart(',', ' ').split(", ").filter { it.isNotBlank() }
            Destination(name, rest.take(2).joinToString(", "), lat, lon)
        }

    fun parseRoute(json: String): Route {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("code")?.asString != "Ok") error("No route found (${root.get("code")?.asString})")
        val r = root.getAsJsonArray("routes")[0].asJsonObject
        val points = r.getAsJsonObject("geometry").getAsJsonArray("coordinates").map {
            val a = it.asJsonArray; a[1].asDouble to a[0].asDouble
        }
        val steps = r.getAsJsonArray("legs").flatMap { leg ->
            leg.asJsonObject.getAsJsonArray("steps").map { st ->
                val s = st.asJsonObject
                val m: JsonObject = s.getAsJsonObject("maneuver")
                val loc = m.getAsJsonArray("location")
                Step(
                    lat = loc[1].asDouble, lon = loc[0].asDouble,
                    type = m.get("type")?.asString ?: "turn",
                    modifier = m.get("modifier")?.asString,
                    road = s.get("name")?.asString.orEmpty(),
                    exit = m.get("exit")?.asInt
                )
            }
        }
        return Route(points, r.get("distance").asDouble, r.get("duration").asDouble, steps)
    }

    /** Short spoken / displayed wording for an OSRM manoeuvre. */
    fun instructionFor(type: String, modifier: String?, road: String, exit: Int?): String {
        val onto = if (road.isNotBlank()) " onto $road" else ""
        val dir = when (modifier) {
            "left" -> "left"; "right" -> "right"
            "slight left" -> "slightly left"; "slight right" -> "slightly right"
            "sharp left" -> "sharp left"; "sharp right" -> "sharp right"
            "uturn" -> "a U-turn"; else -> null
        }
        return when (type) {
            "depart" -> "Head out" + (if (road.isNotBlank()) " on $road" else "")
            "arrive" -> "Arrive at your destination"
            "roundabout", "rotary" -> "At the roundabout, take exit ${exit ?: 1}$onto"
            "merge" -> "Merge$onto"
            "on ramp" -> "Take the ramp$onto"
            "off ramp" -> "Take the exit$onto"
            "fork" -> "Keep ${if (modifier?.contains("left") == true) "left" else "right"}$onto"
            "end of road" -> "At the end of the road, turn ${dir ?: "ahead"}$onto"
            "continue", "new name" -> if (dir == null || modifier == "straight") "Continue$onto" else "Keep $dir$onto"
            else -> when {
                modifier == "uturn" -> "Make a U-turn$onto"
                dir != null -> "Turn $dir$onto"
                else -> "Continue$onto"
            }
        }
    }
}
