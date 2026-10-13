package com.rfsentinel.app.nav

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.service.DeviceRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Driving routes from Valhalla on the OpenStreetMap Germany (FOSSGIS) server: up to three routes,
 * tolls, highways or ferries avoided if asked, and for each turn the lanes and ready-worded prompts
 * ("In 500 meters, keep right"). No key or account; the server sees the start, the stops and the
 * destination, and your IP. Only asked when you plan a route or leave it.
 *
 * Request building and parsing are pure Kotlin and unit-tested; [route] is plain blocking HTTP.
 */
object Valhalla {

    const val URL = "https://valhalla1.openstreetmap.de/route"

    /** Road types the route should avoid. */
    data class Options(
        val avoidTolls: Boolean = false,
        val avoidHighways: Boolean = false,
        val avoidFerries: Boolean = false,
        /** How many other routes to offer besides the best (only with no stops on the way). */
        val alternatives: Int = 2
    )

    class NoRouteException(message: String) : Exception(message)

    // ---- the request ----------------------------------------------------------------------------

    /**
     * The request: from ([fromLat], [fromLon]) - going [headingDeg] when known, so the route doesn't
     * begin with a U-turn - through [stops] to [to].
     */
    fun request(fromLat: Double, fromLon: Double, headingDeg: Double?, stops: List<Pair<Double, Double>>,
                to: Pair<Double, Double>, options: Options, miles: Boolean = false): JSONObject {
        val locations = JSONArray()
        locations.put(JSONObject().put("lat", fromLat).put("lon", fromLon).apply {
            if (headingDeg != null) { put("heading", Math.round(headingDeg).toInt()); put("heading_tolerance", 45) }
        })
        for ((la, lo) in stops) locations.put(JSONObject().put("lat", la).put("lon", lo).put("type", "break"))
        locations.put(JSONObject().put("lat", to.first).put("lon", to.second))
        fun use(avoid: Boolean) = if (avoid) 0.0 else 0.5
        return JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("costing_options", JSONObject().put("auto", JSONObject()
                .put("use_tolls", use(options.avoidTolls))
                .put("use_highways", if (options.avoidHighways) 0.0 else 1.0)
                .put("use_ferry", use(options.avoidFerries))))
            .put("directions_options", JSONObject().put("units", if (miles) "miles" else "kilometers").put("language", "en-US"))
            .put("format", "osrm")
            .put("shape_format", "polyline6")
            .put("banner_instructions", true)
            .put("voice_instructions", true)
            .put("turn_lanes", true)
            .apply {
                // Alternatives aren't offered on routes with stops.
                if (stops.isEmpty() && options.alternatives > 0) put("alternates", options.alternatives)
            }
    }

    // ---- network ----------------------------------------------------------------------------------

    /** The routes, best first; throws [NoRouteException] when there is none, or on a network error. */
    fun route(body: JSONObject, userAgent: String): List<Route> {
        val c = (URL(URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!ok) throw NoRouteException(errorOf(text) ?: "The route server answered HTTP ${c.responseCode}")
            return parse(text)
        } finally {
            c.disconnect()
        }
    }

    /** The server's own reason, when it gave one. */
    internal fun errorOf(text: String): String? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        (o.str("message") ?: o.str("error"))?.let { friendly(o.str("code"), it) }
    }.getOrNull()

    private fun friendly(code: String?, message: String) = when {
        code == "NoRoute" || message.contains("No path", true) -> "No route found between these places"
        message.contains("too far", true) || message.contains("exceeds the max", true) -> "Too far for the route server (it plans up to a few hundred km)"
        else -> message
    }

    // ---- parsing (pure) ---------------------------------------------------------------------------

    fun parse(json: String): List<Route> {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.str("code") != "Ok") throw NoRouteException(friendly(root.str("code"), root.str("message") ?: "No route found"))
        val routes = root.getAsJsonArray("routes") ?: throw NoRouteException("No route found")
        return routes.map { parseRoute(it.asJsonObject) }.ifEmpty { throw NoRouteException("No route found") }
    }

    private fun parseRoute(r: JsonObject): Route {
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val along = ArrayList<Double>()
        fun add(la: Double, lo: Double) {
            if (lats.isNotEmpty()) {
                val d = DeviceRegistry.metersBetween(lats.last(), lons.last(), la, lo)
                if (d < 0.01) return // the joint between two steps, already there
                along += along.last() + d
            } else along += 0.0
            lats += la; lons += lo
        }
        val steps = ArrayList<Step>()
        val legs = r.getAsJsonArray("legs") ?: JsonArray()
        for ((legIndex, legEl) in legs.withIndex()) {
            for (stEl in legEl.asJsonObject.getAsJsonArray("steps") ?: JsonArray()) {
                val s = stEl.asJsonObject
                val m = s.getAsJsonObject("maneuver") ?: JsonObject()
                val loc = m.getAsJsonArray("location")
                val startM = along.lastOrNull() ?: 0.0
                val geometry = s.str("geometry")?.let { decodePolyline6(it) }.orEmpty()
                if (geometry.isEmpty() && loc != null) add(loc[1].asDouble, loc[0].asDouble)
                for ((la, lo) in geometry) add(la, lo)
                steps += Step(
                    type = m.str("type") ?: "turn",
                    modifier = m.str("modifier"),
                    name = s.str("name").orEmpty(),
                    exit = m.get("exit")?.takeIf { it.isJsonPrimitive }?.asInt,
                    instruction = m.str("instruction").orEmpty(),
                    lat = loc?.get(1)?.asDouble ?: lats.lastOrNull() ?: 0.0,
                    lon = loc?.get(0)?.asDouble ?: lons.lastOrNull() ?: 0.0,
                    lengthM = s.num("distance") ?: 0.0,
                    durationS = s.num("duration") ?: 0.0,
                    startM = startM,
                    endM = along.lastOrNull() ?: startM,
                    leg = legIndex,
                    voice = (s.getAsJsonArray("voiceInstructions") ?: JsonArray()).mapNotNull { v ->
                        val o = v.asJsonObject
                        val text = o.str("announcement") ?: return@mapNotNull null
                        Step.Voice(o.num("distanceAlongGeometry") ?: 0.0, text)
                    }.sortedByDescending { it.beforeM },
                    banners = (s.getAsJsonArray("bannerInstructions") ?: JsonArray()).mapNotNull { b ->
                        val o = b.asJsonObject
                        val p = o.getAsJsonObject("primary") ?: return@mapNotNull null
                        val lanes = (o.getAsJsonObject("sub")?.getAsJsonArray("components") ?: JsonArray()).mapNotNull { c ->
                            val co = c.asJsonObject
                            if (co.str("type") != "lane") return@mapNotNull null
                            Step.Lane((co.getAsJsonArray("directions") ?: JsonArray()).map { it.asString },
                                co.get("active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true, co.str("active_direction"))
                        }
                        Step.Banner(o.num("distanceAlongGeometry") ?: 0.0, p.str("text").orEmpty(), p.str("type") ?: "turn", p.str("modifier"), lanes)
                    }.sortedByDescending { it.beforeM },
                    drivingSide = s.str("driving_side") ?: "right"
                )
            }
        }
        if (lats.isEmpty()) throw NoRouteException("The route came back empty")
        val summary = legs.mapNotNull { it.asJsonObject.str("summary") }.filter { it.isNotBlank() }.joinToString(", ")
        return Route(lats.toDoubleArray(), lons.toDoubleArray(), along.toDoubleArray(),
            r.num("distance") ?: along.last(), r.num("duration") ?: 0.0, steps, summary, lats.last() to lons.last())
    }

    /** Google's encoded-polyline format at six decimals (Valhalla's default): (lat, lon) pairs. */
    fun decodePolyline6(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next()
            if (i >= s.length) break
            lon += next()
            out += lat / 1e6 to lon / 1e6
        }
        return out
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}
