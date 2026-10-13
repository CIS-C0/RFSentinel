package com.rfsentinel.app.nav

import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.online.RoadMatch
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Routes around the known speed cameras and plate readers (Settings > Navigation, or the chips when
 * planning). The route is asked for, the cameras of the camera map that sit on it are found, the stretch of
 * road each one is on is excluded (the route server's `exclude_locations`) and the route is asked for
 * again, a few times, until nothing is on it or there is no way round. What can't be avoided (a camera on
 * the only bridge, one at the very start or end) stays, and the route says how many are left.
 *
 * Pure Kotlin (the questions to the route server are passed in), so it is unit-tested on the JVM.
 */
object CameraAvoid {

    /** A camera this close to the route's line is on it. */
    const val ON_ROUTE_M = 25.0
    /** Cameras this close to where you start, stop or finish can't be driven round. */
    const val ENDS_M = 120.0
    /** The route server accepts 50 excluded places in a request. */
    const val MAX_EXCLUDES = 48
    /** Questions to the route server for one plan (the first one included). */
    const val MAX_REQUESTS = 8
    /** Cameras this close along the route share one excluded place. */
    private const val MERGE_M = 40.0
    /** A camera still on the route after its road was excluded: try the road either side of it, this far on each try. */
    private const val NUDGE_M = 25.0
    private const val MAX_TRIES = 3

    fun kinds(o: Valhalla.Options): Set<KnownCamera.Kind> = buildSet {
        if (o.avoidSpeedCameras) add(KnownCamera.Kind.SPEED)
        if (o.avoidPlateReaders) add(KnownCamera.Kind.ALPR)
    }

    /** A camera on a route: where on the line it is nearest, and how far along. */
    class Hit(val camera: KnownCamera, val lat: Double, val lon: Double, val alongM: Double, val distM: Double)

    /** One place to exclude, for the cameras (OpenStreetMap ids) on that stretch. */
    class Spot(val lat: Double, val lon: Double, val keys: Set<String>, val hits: List<Hit>)

    // ---- finding the cameras on a route ----------------------------------------------------------

    /** The [kinds] of cameras within [radiusM] of the route's line, in the order you meet them. */
    fun hits(route: Route, cameras: List<KnownCamera>, kinds: Set<KnownCamera.Kind>, radiusM: Double = ON_ROUTE_M): List<Hit> {
        if (kinds.isEmpty() || cameras.isEmpty() || route.size < 2) return emptyList()
        var s = Double.MAX_VALUE; var n = -Double.MAX_VALUE; var w = Double.MAX_VALUE; var e = -Double.MAX_VALUE
        for (i in 0 until route.size) { s = min(s, route.lats[i]); n = max(n, route.lats[i]); w = min(w, route.lons[i]); e = max(e, route.lons[i]) }
        val pad = 0.001
        val candidates = cameras.filter { it.type in kinds && it.lat in (s - pad)..(n + pad) && it.lon in (w - pad)..(e + pad) }
        if (candidates.isEmpty()) return emptyList()

        val frame = RoadMatch.Frame((s + n) / 2, (w + e) / 2)
        val xs = DoubleArray(route.size); val ys = DoubleArray(route.size)
        for (i in 0 until route.size) { val (x, y) = frame.toXY(route.lats[i], route.lons[i]); xs[i] = x; ys[i] = y }
        // The segments, on a grid of cells each registered in every cell its box (grown by the radius) touches.
        val cell = 100.0
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for (i in 0 until route.size - 1) {
            val x0 = (min(xs[i], xs[i + 1]) - radiusM); val x1 = (max(xs[i], xs[i + 1]) + radiusM)
            val y0 = (min(ys[i], ys[i + 1]) - radiusM); val y1 = (max(ys[i], ys[i + 1]) + radiusM)
            for (cx in Math.floor(x0 / cell).toInt()..Math.floor(x1 / cell).toInt())
                for (cy in Math.floor(y0 / cell).toInt()..Math.floor(y1 / cell).toInt())
                    grid.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
        }
        val out = ArrayList<Hit>()
        for (c in candidates) {
            val (px, py) = frame.toXY(c.lat, c.lon)
            val segs = grid[key(Math.floor(px / cell).toInt(), Math.floor(py / cell).toInt())] ?: continue
            var best = Double.MAX_VALUE; var bestAlong = 0.0; var bx = 0.0; var by = 0.0
            for (i in segs) {
                val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-9) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = xs[i] + t * dx; val qy = ys[i] + t * dy
                val d = hypot(px - qx, py - qy)
                if (d < best) { best = d; bx = qx; by = qy; bestAlong = route.along[i] + t * (route.along[i + 1] - route.along[i]) }
            }
            if (best <= radiusM) { val (la, lo) = frame.toLatLon(bx, by); out += Hit(c, la, lo, bestAlong, best) }
        }
        return out.sortedBy { it.alongM }
    }

    /** The point [alongM] metres along the route (clamped to its ends). */
    fun pointAt(route: Route, alongM: Double): Pair<Double, Double> {
        val a = alongM.coerceIn(0.0, route.lengthM)
        val i = route.along.indexOfFirst { it >= a }.let { if (it <= 0) return route.lats[0] to route.lons[0] else it }
        val t = (a - route.along[i - 1]) / (route.along[i] - route.along[i - 1]).coerceAtLeast(1e-9)
        return (route.lats[i - 1] + t * (route.lats[i] - route.lats[i - 1])) to (route.lons[i - 1] + t * (route.lons[i] - route.lons[i - 1]))
    }

    /** Hits that sit within [ENDS_M] of where you start, stop or finish: nothing can be done about them. */
    private fun nearEnds(h: Hit, ends: List<Pair<Double, Double>>) =
        ends.any { DeviceRegistry.metersBetween(it.first, it.second, h.camera.lat, h.camera.lon) < ENDS_M }

    /** Cameras close along the route share one place to exclude. */
    internal fun spots(hits: List<Hit>): List<Spot> {
        val out = ArrayList<Spot>()
        var group = ArrayList<Hit>()
        fun flush() {
            if (group.isEmpty()) return
            out += Spot(group[0].lat, group[0].lon, group.map { it.camera.osmId }.toSet(), group)
            group = ArrayList()
        }
        for (h in hits) {
            if (group.isNotEmpty() && h.alongM - group.last().alongM > MERGE_M) flush()
            group += h
        }
        flush()
        return out
    }

    // ---- the plan ----------------------------------------------------------------------------------

    /**
     * Routes for the trip around the [kinds] of cameras in [cameras]. [ask] puts a question to the route
     * server: the route with those places excluded (it throws [Valhalla.NoRouteException] when there is
     * none); [ends] are where the trip starts, stops and finishes. The first route is the one with the
     * fewest cameras, then the ones the server offered first, each saying what is on it ([Route.cameraLine]).
     *
     * [covered] is asked once the first route is known: it can fetch the camera map along it, and says whether
     * the camera map covers the route (the route's line then says so when it doesn't).
     */
    suspend fun plan(
        kinds: Set<KnownCamera.Kind>,
        cameras: () -> List<KnownCamera>,
        ends: List<Pair<Double, Double>>,
        alternatives: Int,
        covered: suspend (Route) -> Boolean = { true },
        now: () -> Long = { System.currentTimeMillis() },
        budgetMs: Long = 45_000L,
        ask: suspend (exclude: List<Pair<Double, Double>>, alternatives: Int) -> List<Route>
    ): List<Route> {
        val first = ask(emptyList(), alternatives)
        val started = now()
        val complete = runCatching { covered(first.first()) }.getOrDefault(false)
        val known = cameras()
        var current = first.first()
        var requests = 1

        val sent = ArrayList<Pair<Double, Double>>()
        val tries = HashMap<String, Int>()
        val blocked = HashSet<String>()

        /** One place to ask the server to exclude, and the cameras it is for. */
        class Item(val lat: Double, val lon: Double, val spot: Spot)

        var offline = false
        while (requests < MAX_REQUESTS && now() - started < budgetMs && !offline) {
            val onRoute = hits(current, known, kinds)
            val items = ArrayList<Item>()
            for (spot in spots(onRoute.filter { !nearEnds(it, ends) })) {
                if (spot.keys.all { it in blocked }) continue
                val attempt = spot.keys.maxOf { tries[it] ?: 0 }
                if (attempt >= MAX_TRIES) { blocked += spot.keys; continue }
                if (attempt == 0) items += Item(spot.lat, spot.lon, spot)
                else {
                    // Still on the route after its road was excluded: the road just before and after it.
                    val a = spot.hits.first().alongM
                    for (d in listOf(-NUDGE_M * attempt, NUDGE_M * attempt)) {
                        val (la, lo) = pointAt(current, a + d)
                        items += Item(la, lo, spot)
                    }
                }
            }
            var batch = items.take((MAX_EXCLUDES - sent.size).coerceAtLeast(0))
            if (batch.isEmpty()) break
            while (batch.isNotEmpty() && requests < MAX_REQUESTS && now() - started < budgetMs) {
                requests++
                try {
                    val places = batch.map { it.lat to it.lon }
                    val r = ask(sent + places, 0)
                    sent += places
                    for (sp in batch.map { it.spot }.distinct()) for (k in sp.keys) tries[k] = (tries[k] ?: 0) + 1
                    current = r.first()
                    batch = emptyList()
                } catch (e: Valhalla.NoRouteException) {
                    // No way round with all of these: try fewer; a lone one that cuts the trip off stays as it is.
                    if (batch.size == 1) { blocked += batch[0].spot.keys; batch = emptyList() }
                    else batch = batch.take((batch.size + 1) / 2)
                } catch (e: java.io.IOException) {
                    offline = true; batch = emptyList() // no connection: keep what there is
                }
            }
        }

        val ordered = (listOf(current) + first.filter { it !== current }).take(3)
        return ordered.map { r -> r.copy(cameraLine = describe(hits(r, known, kinds), kinds, complete)) }
    }

    /** What is on a route, in a few words: "no plate readers", "2 plate readers, 1 speed camera". */
    fun describe(hits: List<Hit>, kinds: Set<KnownCamera.Kind>, complete: Boolean = true): String {
        fun noun(k: KnownCamera.Kind, n: Int) = when (k) {
            KnownCamera.Kind.ALPR -> if (n == 1) "plate reader" else "plate readers"
            KnownCamera.Kind.SPEED -> if (n == 1) "speed camera" else "speed cameras"
            KnownCamera.Kind.RED_LIGHT -> if (n == 1) "red-light camera" else "red-light cameras"
        }
        val order = listOf(KnownCamera.Kind.ALPR, KnownCamera.Kind.SPEED, KnownCamera.Kind.RED_LIGHT).filter { it in kinds }
        val counts = order.map { k -> k to hits.count { it.camera.type == k } }.filter { it.second > 0 }
        val text = if (counts.isEmpty()) "no " + (if (order.size > 1) "cameras" else noun(order.first(), 2))
            else counts.joinToString(", ") { (k, n) -> "$n ${noun(k, n)}" }
        return if (complete) text else "$text known (camera map incomplete here)"
    }

    // ---- the camera map along a route ------------------------------------------------------------

    /**
     * Boxes (south, west, north, east) that cover the route, none wider than [maxSpanDeg] (the most one
     * camera download takes), at most [maxBoxes] of them, each a little larger than the road it holds.
     */
    fun boxes(route: Route, maxSpanDeg: Double = 1.6, maxBoxes: Int = 4, pad: Double = 0.02): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var s = 0.0; var w = 0.0; var n = 0.0; var e = 0.0; var open = false
        for (i in 0 until route.size step 5) {
            val la = route.lats[i]; val lo = route.lons[i]
            if (!open) { s = la; n = la; w = lo; e = lo; open = true; continue }
            if (max(n, la) - min(s, la) > maxSpanDeg || max(e, lo) - min(w, lo) > maxSpanDeg) {
                out += doubleArrayOf(s - pad, w - pad, n + pad, e + pad)
                if (out.size >= maxBoxes) return out
                s = la; n = la; w = lo; e = lo
            } else { s = min(s, la); n = max(n, la); w = min(w, lo); e = max(e, lo) }
        }
        if (open && out.size < maxBoxes) out += doubleArrayOf(s - pad, w - pad, n + pad, e + pad)
        return out
    }
}
