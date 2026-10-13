package com.rfsentinel.app.nav

import android.content.Context
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.service.DeviceRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The posted speed limit for the map's speedometer, from OpenStreetMap's `maxspeed` through the route server's
 * map matching (Valhalla `trace_attributes`: the road each point of a line lies on and its limit). Two uses:
 *
 *  - [RouteLimits]: while you are guided, the limits along the whole route, asked a stretch at a time ahead of you
 *    (the route server already knows the route; nothing more about you goes out).
 *  - [Live]: when you are not navigating - opt-in, as it sends where you are driving every ~12 s.
 *
 * Request building and reading are pure Kotlin and unit-tested; [fetch] is plain blocking HTTP.
 */
object SpeedLimits {

    const val URL = "https://valhalla1.openstreetmap.de/trace_attributes"

    /** Points per question (the server takes thousands; a short stretch answers quickly and kindly). */
    const val CHUNK = 1_500

    /** Valhalla's "no limit" (a German Autobahn) is 255: nothing to show. */
    private const val MAX_SHOWN_KMH = 200

    /** Tests replace the questions to the server: points in, a limit in km/h for each point out (0 = unknown). */
    internal var fetcher: ((List<Pair<Double, Double>>) -> IntArray)? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "speed-limits").apply { isDaemon = true } }

    fun request(points: List<Pair<Double, Double>>): JSONObject = JSONObject()
        .put("shape", JSONArray().also { a -> for ((la, lo) in points) a.put(JSONObject().put("lat", la).put("lon", lo)) })
        .put("costing", "auto")
        .put("shape_match", "map_snap")
        .put("filters", JSONObject().put("attributes", JSONArray(listOf("edge.speed_limit", "matched.edge_index", "matched.type"))).put("action", "include"))

    /** The limit (km/h) at each of the [count] points sent; 0 where the road has none mapped or the point didn't match a road. */
    fun parse(json: String, count: Int): IntArray {
        val out = IntArray(count)
        val root = JSONObject(json)
        val edges = root.optJSONArray("edges") ?: return out
        val matched = root.optJSONArray("matched_points") ?: return out
        for (i in 0 until minOf(count, matched.length())) {
            val m = matched.getJSONObject(i)
            if (!m.has("edge_index") || m.optString("type") == "unmatched") continue
            val limit = edges.optJSONObject(m.getInt("edge_index"))?.optInt("speed_limit", 0) ?: 0
            out[i] = if (limit in 5..MAX_SHOWN_KMH) limit else 0
        }
        return out
    }

    /** Asks the route server; blocking. Throws on a network error or an answer that isn't one. */
    fun fetch(points: List<Pair<Double, Double>>): IntArray {
        fetcher?.let { return it(points) }
        val c = (URL(URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        try {
            c.outputStream.use { it.write(request(points).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw java.io.IOException("The route server answered ${c.responseCode}")
            return parse(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, points.size)
        } finally {
            c.disconnect()
        }
    }

    /** km/h as shown: mph rounded to the nearest 5 (signs go in fives), km/h as is. */
    fun shown(kmh: Int, miles: Boolean): Int = if (miles) (Math.round(kmh / 1.609344 / 5.0) * 5).toInt() else kmh

    // ---- along a route ------------------------------------------------------------------------------

    /**
     * The limits along [route], fetched in stretches of [CHUNK] points as you get near them (the next stretch
     * is asked for while you are still in the one before). [at] answers at once from what has arrived.
     */
    class RouteLimits(val route: Route) {
        private val limits = IntArray(route.size) { -1 } // -1 = not asked yet, 0 = none mapped
        private val asked = BooleanArray(route.size / CHUNK + 1)
        private var failedAt = 0L
        @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

        /** The limit (km/h) where you are [alongM] metres along the route; null when none is mapped or it has not arrived yet. */
        fun at(alongM: Double): Int? {
            if (route.size < 2) return null
            val i = indexAt(alongM)
            ensure(i / CHUNK)
            ensure(i / CHUNK + 1) // the next stretch is on its way before you reach it
            return limits[i].takeIf { it > 0 }
        }

        private fun indexAt(alongM: Double): Int {
            var lo = 0; var hi = route.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (route.along[mid] <= alongM) lo = mid else hi = mid - 1 }
            return lo
        }

        @Synchronized private fun ensure(chunk: Int) {
            val from = chunk * CHUNK
            if (chunk < 0 || from >= route.size || asked[chunk]) return
            if (clock() - failedAt < RETRY_MS) return
            asked[chunk] = true
            val to = minOf(route.size, from + CHUNK + 1) // one point of overlap keeps the road at the seam
            val pts = (from until to).map { route.lats[it] to route.lons[it] }
            worker.execute {
                try {
                    val got = fetch(pts)
                    synchronized(this) { for (k in got.indices) if (limits[from + k] < 0 || k < CHUNK) limits[from + k] = got[k] }
                } catch (e: Exception) {
                    synchronized(this) { asked[chunk] = false; failedAt = clock() }
                }
            }
        }
    }

    // ---- when you are not navigating ----------------------------------------------------------------------

    /**
     * The limit of the road you are on, from where you drove in the last minute (opt-in: Settings > Map). It asks
     * at most every [GAP_MS] and only while you are really driving; an answer is good for [FRESH_MS] and 250 m.
     */
    object Live {
        private const val GAP_MS = 12_000L
        private const val FRESH_MS = 45_000L
        private const val MIN_SPACING_M = 20.0
        private const val KEEP_MS = 60_000L
        private const val MAX_POINTS = 8

        private class Fix(val lat: Double, val lon: Double, val at: Long)
        private val trail = ArrayList<Fix>()
        private var askedAt = 0L
        private var failedAt = 0L
        @Volatile private var limit = 0
        @Volatile private var limitAt = 0L
        @Volatile private var limitLat = 0.0
        @Volatile private var limitLon = 0.0
        @Volatile private var busy = false

        /** A fix from the screen's GPS (main thread); drives the asking. */
        fun onFix(lat: Double, lon: Double, speedMs: Float, now: Long = System.currentTimeMillis()) {
            if (speedMs < 1.4f) return // stopped: the last answer stands
            if (trail.isEmpty() || DeviceRegistry.metersBetween(trail.last().lat, trail.last().lon, lat, lon) >= MIN_SPACING_M) {
                trail += Fix(lat, lon, now)
                trail.removeAll { now - it.at > KEEP_MS }
                while (trail.size > MAX_POINTS) trail.removeAt(0)
            }
            if (busy || trail.size < 3 || now - askedAt < GAP_MS || now - failedAt < 5 * GAP_MS) return
            askedAt = now; busy = true
            val pts = trail.map { it.lat to it.lon }
            worker.execute {
                try {
                    val got = fetch(pts)
                    limit = got.lastOrNull() ?: 0; limitAt = System.currentTimeMillis()
                    limitLat = pts.last().first; limitLon = pts.last().second
                } catch (e: Exception) {
                    failedAt = System.currentTimeMillis()
                } finally { busy = false }
            }
        }

        /** The limit (km/h) now, or null when unknown or out of date. */
        fun now(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Int? {
            if (limit <= 0 || now - limitAt > FRESH_MS) return null
            if (DeviceRegistry.metersBetween(limitLat, limitLon, lat, lon) > 250.0) return null
            return limit
        }

        fun reset() { trail.clear(); limit = 0; limitAt = 0; askedAt = 0; failedAt = 0 }
    }

    private const val RETRY_MS = 60_000L

    /** Whether the speedometer asks for limits at all (it still shows your speed when it doesn't). */
    fun enabled(context: Context) = com.rfsentinel.app.util.Prefs.mapSpeedometer(context)
}
