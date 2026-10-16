package com.rfsentinel.app.nav

import kotlin.math.atan2
import kotlin.math.cos

/**
 * While guided, the arrow sits on the route, as navigation apps do: a phone's GPS is often 5 - 15 m off in town
 * (buildings, trees), which put the arrow on the other side of the road or on the pavement. A fix close enough to
 * the route, going its way, is drawn at its point on the route and turned along it; one further off (you really
 * left the route, or took a turn it doesn't) is drawn where it is. Pure; unit-tested.
 */
object RouteSnap {

    /** Within this of the route a fix is put on it... */
    const val SNAP_M = 25.0
    /** ...or within this when the fix itself says it is that rough (never further). */
    const val SNAP_MAX_M = 40.0
    /** Heading this far against the route: not on it (the other carriageway, a U-turn). */
    private const val AGAINST_DEG = 60.0

    data class Snapped(val lat: Double, val lon: Double, val bearingDeg: Double, val state: RouteProgress.State)

    /** The fix at ([lat], [lon]) put on [route], or null when it should be drawn where it is. */
    fun snap(route: Route, lat: Double, lon: Double, bearingDeg: Double?, accuracyM: Double?, last: RouteProgress.State?): Snapped? {
        if (route.size < 2) return null
        val s = RouteProgress.locate(route, lat, lon, bearingDeg, last)
        val limit = maxOf(SNAP_M, minOf((accuracyM ?: 0.0) * 1.5, SNAP_MAX_M))
        if (s.offM > limit) return null
        if (bearingDeg != null && (s.headingOffDeg ?: 0.0) > AGAINST_DEG) return null
        val (la, lo) = RouteProgress.pointAt(route, s.alongM)
        val ahead = RouteProgress.pointAt(route, (s.alongM + 5.0).coerceAtMost(route.lengthM))
        val behind = if (ahead == la to lo) RouteProgress.pointAt(route, (s.alongM - 5.0).coerceAtLeast(0.0)) else la to lo
        val to = if (ahead == la to lo) la to lo else ahead
        val dy = to.first - behind.first
        val dx = (to.second - behind.second) * cos(Math.toRadians(la))
        val bearing = if (dx == 0.0 && dy == 0.0) (bearingDeg ?: 0.0) else (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        return Snapped(la, lo, bearing, s)
    }
}
