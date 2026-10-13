package com.rfsentinel.app.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Where you are on a [Route] (pure; unit-tested): the nearest point of the line to your fix - looked
 * for just ahead of where you were first, so a road that comes back on itself or runs beside itself
 * can't make you jump - how far along and how far off it that is, the step you're on, and what's left.
 */
object RouteProgress {

    /** Where you are along the route. */
    data class State(
        /** The stretch of line you're on: from point [segment] to [segment] + 1. */
        val segment: Int,
        val alongM: Double,
        /** How far off the line your fix is. */
        val offM: Double,
        /** Your heading against the line's direction there (0..180), when you're moving. */
        val headingOffDeg: Double?,
        val stepIndex: Int,
        /** To the end of this step: the next manoeuvre. */
        val toManeuverM: Double,
        val remainingM: Double,
        val remainingS: Double
    )

    /** Looked for ahead of the last match first, this far along the line. */
    private const val WINDOW_AHEAD_M = 1_500.0
    private const val WINDOW_BEHIND_M = 60.0
    /** Within the window, a match this close is taken without looking at the whole route. */
    private const val WINDOW_OK_M = 50.0
    /** How many metres a direction against the line counts for, at 90 degrees off. */
    private const val HEADING_WEIGHT_M = 20.0

    /**
     * Your place on [route] from a fix at ([lat], [lon]) going [bearing] (null when not moving), with
     * [last] the previous match (null on a new route). [advanceLimitM] is the most you could have moved along
     * the route since then (a second at 30 m/s: about 100 m): a stretch of the route further on that merely
     * passes close by - a loop, an out-and-back road beside itself - is not where you are.
     */
    fun locate(route: Route, lat: Double, lon: Double, bearing: Double?, last: State?, advanceLimitM: Double = WINDOW_AHEAD_M): State {
        val n = route.size
        if (n < 2) return State(0, 0.0, 0.0, null, 0, 0.0, 0.0, 0.0)
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_540.0
        fun x(i: Int) = (route.lons[i] - lon) * kx
        fun y(i: Int) = (route.lats[i] - lat) * ky

        class Best(var seg: Int = 0, var cost: Double = Double.MAX_VALUE, var off: Double = 0.0, var t: Double = 0.0, var dir: Double = 0.0)
        fun scan(from: Int, to: Int, lastAlong: Double?): Best {
            val b = Best()
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(n - 1)) {
                val x0 = x(i); val y0 = y(i); val dx = x(i + 1) - x0; val dy = y(i + 1) - y0
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (-(x0 * dx + y0 * dy) / l2).coerceIn(0.0, 1.0)
                val off = hypot(x0 + t * dx, y0 + t * dy)
                val dir = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                var cost = off
                if (bearing != null && l2 > 0) cost += HEADING_WEIGHT_M * angleDiff(bearing, dir) / 90.0
                // Going back along the route is unlikely: you'd have to turn round.
                if (lastAlong != null) {
                    val a = route.along[i] + t * (route.along[i + 1] - route.along[i])
                    if (a < lastAlong - WINDOW_BEHIND_M) cost += 40.0
                    else if (a > lastAlong + advanceLimitM) cost += 60.0
                }
                if (cost < b.cost) { b.seg = i; b.cost = cost; b.off = off; b.t = t; b.dir = dir }
            }
            return b
        }

        var best: Best? = null
        if (last != null) {
            val lastAlong = last.alongM
            var from = last.segment
            while (from > 0 && route.along[from] > lastAlong - WINDOW_BEHIND_M) from--
            var to = last.segment + 1
            while (to < n - 1 && route.along[to] < lastAlong + WINDOW_AHEAD_M) to++
            best = scan(from, to + 1, lastAlong).takeIf { it.off <= WINDOW_OK_M }
        }
        val b = best ?: scan(0, n, last?.alongM)
        val along = route.along[b.seg] + b.t * (route.along[b.seg + 1] - route.along[b.seg])
        val headingOff = bearing?.let { angleDiff(it, b.dir) }

        val steps = route.steps
        var si = steps.indexOfLast { it.startM <= along + 0.5 && !it.isArrival }.coerceAtLeast(0)
        if (steps.isEmpty()) si = 0
        val step = steps.getOrNull(si)
        val toManeuver = ((step?.endM ?: route.lengthM) - along).coerceAtLeast(0.0)
        val remaining = (route.lengthM - along).coerceAtLeast(0.0)
        val remainingS = if (step == null) 0.0 else {
            val stepLen = (step.endM - step.startM).coerceAtLeast(1.0)
            step.durationS * (toManeuver / stepLen).coerceIn(0.0, 1.0) + steps.drop(si + 1).sumOf { it.durationS }
        }
        return State(b.seg, along, b.off, headingOff, si, toManeuver, remaining, remainingS)
    }

    /** Seconds the route plans to take to reach [alongM] from its start. */
    fun plannedSecondsAt(route: Route, alongM: Double): Double {
        var t = 0.0
        for (s in route.steps) {
            if (s.endM <= alongM) { t += s.durationS; continue }
            if (s.startM >= alongM) break
            t += s.durationS * ((alongM - s.startM) / (s.endM - s.startM).coerceAtLeast(1.0))
            break
        }
        return t
    }

    /**
     * How much slower (above 1) or faster (below 1) than the route's plan you are driving: the time
     * you took from [fromAlongM] to [toAlongM] ([elapsedS], stops at lights included) against the time
     * the route plans for that stretch. Null until there's enough to go on (600 m and 90 s); it counts
     * for more the further you've driven (fully after 4 km), and stays between 0.6 and 2.
     */
    fun pace(route: Route, fromAlongM: Double, toAlongM: Double, elapsedS: Double): Double? {
        val driven = toAlongM - fromAlongM
        if (driven < PACE_MIN_M || elapsedS < PACE_MIN_S) return null
        val planned = plannedSecondsAt(route, toAlongM) - plannedSecondsAt(route, fromAlongM)
        if (planned < 30.0) return null
        val raw = (elapsedS / planned).coerceIn(0.6, 2.0)
        val weight = (driven / PACE_FULL_M).coerceIn(0.0, 1.0)
        return 1.0 + (raw - 1.0) * weight
    }

    private const val PACE_MIN_M = 600.0
    private const val PACE_MIN_S = 90.0
    private const val PACE_FULL_M = 4_000.0

    /** The point [m] along the route (clamped to its ends). */
    fun pointAt(route: Route, m: Double): Pair<Double, Double> {
        val a = route.along
        if (m <= 0) return route.lats[0] to route.lons[0]
        if (m >= a.last()) return route.end
        var lo = 0; var hi = a.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (a[mid] <= m) lo = mid else hi = mid }
        val t = (m - a[lo]) / (a[hi] - a[lo]).coerceAtLeast(1e-9)
        return (route.lats[lo] + t * (route.lats[hi] - route.lats[lo])) to (route.lons[lo] + t * (route.lons[hi] - route.lons[lo]))
    }

    /**
     * How far along [route] a point at ([lat], [lon]) lies, and how far off the line - for things on or
     * beside the road (a camera, a Waze report). The whole route is searched.
     */
    fun project(route: Route, lat: Double, lon: Double): Pair<Double, Double> {
        val s = locate(route, lat, lon, null, null)
        return s.alongM to s.offM
    }

    internal fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)
}
