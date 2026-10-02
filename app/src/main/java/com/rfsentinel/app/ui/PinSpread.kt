package com.rfsentinel.app.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Devices are placed where the phone heard them loudest, so everything heard
 * from one spot shares the same coordinates and the dots stack on top of each
 * other. This fans dots that would overlap on screen out into rings around
 * their shared spot, so each one can be tapped. Pure (unit-tested).
 */
object PinSpread {

    /** Dots further apart than this in reality are never regrouped. */
    const val MAX_GROUP_M = 30.0
    /** Below this zoom the map shows dots where they are, overlapping or not. */
    const val MIN_ZOOM = 15.0

    /**
     * Returns a display position for every input position (same order).
     * [metersPerPx] is the map's current scale and [spacingPx] the on-screen gap
     * wanted between dot centres.
     */
    fun spread(
        points: List<Pair<Double, Double>>, metersPerPx: Double, spacingPx: Double,
        maxGroupM: Double = MAX_GROUP_M
    ): List<Pair<Double, Double>> {
        if (points.size < 2 || metersPerPx <= 0.0) return points
        val spacingM = spacingPx * metersPerPx
        // Only dots heard from (nearly) the same spot are fanned out - never ones that just look close when zoomed out.
        val joinM = minOf(spacingM, maxGroupM)
        // Greedy grouping: a dot joins the first group whose centre is close enough.
        val groups = ArrayList<MutableList<Int>>()
        for (i in points.indices) {
            val g = groups.firstOrNull { metersBetween(points[it.first()], points[i]) < joinM }
            if (g != null) g += i else groups += mutableListOf(i)
        }
        val out = points.toMutableList()
        for (g in groups) {
            if (g.size < 2) continue
            val (lat0, lon0) = points[g.first()]
            val mPerDegLat = 111_320.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
            // The first dot keeps the spot; the rest go on rings of 6, 12, 18... one spacing apart.
            var ring = 1
            var slot = 0
            for (k in 1 until g.size) {
                val perRing = 6 * ring
                val angle = 2 * PI * slot / perRing - PI / 2
                val r = spacingM * ring
                out[g[k]] = (lat0 + r * sin(-angle) / mPerDegLat) to (lon0 + r * cos(angle) / mPerDegLon)
                if (++slot == perRing) { ring++; slot = 0 }
            }
        }
        return out
    }

    private fun metersBetween(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = (b.first - a.first) * 111_320.0
        val dLon = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
