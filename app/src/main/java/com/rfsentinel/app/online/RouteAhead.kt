package com.rfsentinel.app.online

import com.rfsentinel.app.nav.Route
import kotlin.math.cos

/**
 * While you are being guided, "ahead of me" is the route itself: a Waze report counts when it lies on the
 * stretch of the route still in front of you. The route knows the turns (a report round the next corner
 * is on your way, one on the street beside it is not) and it does not need the GPS to have a heading,
 * which a phone standing at a light or crawling in traffic does not have.
 */
internal object RouteAhead {

    /** A report is on the route when within this of its line: Waze puts it on the road, the line is the road's centre. */
    const val CORRIDOR_M = 45.0
    /** A report just behind your position still counts as here (GPS error, the report's own rounding). */
    const val BEHIND_SLACK_M = 25.0

    /**
     * Where the report at ([lat], [lon]) is against the route, for you [alongM] along it: on your way within
     * [reachM] ahead, behind you, or not on the route (or too far on it to matter yet). A route that passes
     * the report twice (out and back) counts the first pass still in front of you.
     */
    fun verdict(route: Route, alongM: Double, lat: Double, lon: Double, reachM: Double): RoadMatch.Verdict {
        val n = route.size
        if (n < 2) return RoadMatch.Verdict.UNKNOWN
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_540.0
        var nextAhead = Double.NaN
        var anyBehind = false
        for (i in 0 until n - 1) {
            val x0 = (route.lons[i] - lon) * kx; val y0 = (route.lats[i] - lat) * ky
            val dx = (route.lons[i + 1] - route.lons[i]) * kx; val dy = (route.lats[i + 1] - route.lats[i]) * ky
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9) 0.0 else ((-x0 * dx - y0 * dy) / l2).coerceIn(0.0, 1.0)
            val px = x0 + t * dx; val py = y0 + t * dy
            if (px * px + py * py > CORRIDOR_M * CORRIDOR_M) continue
            val a = route.along[i] + t * (route.along[i + 1] - route.along[i])
            if (a >= alongM - BEHIND_SLACK_M) { if (nextAhead.isNaN() || a < nextAhead) nextAhead = a } else anyBehind = true
        }
        return when {
            !nextAhead.isNaN() -> if (nextAhead - alongM <= reachM) RoadMatch.Verdict.ON_MY_ROAD else RoadMatch.Verdict.OTHER_ROAD
            anyBehind -> RoadMatch.Verdict.BEHIND
            else -> RoadMatch.Verdict.OTHER_ROAD
        }
    }
}
