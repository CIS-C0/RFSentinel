package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

class PinSpreadTest {

    private fun px(a: Pair<Double, Double>, b: Pair<Double, Double>, mPerPx: Double): Double {
        val dLat = (b.first - a.first) * 111_320.0
        val dLon = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
        return sqrt(dLat * dLat + dLon * dLon) / mPerPx
    }

    @Test
    fun stackedDotsAllBecomeSeparate() {
        val spot = 39.0 to -98.0
        val out = PinSpread.spread(List(16) { spot }, metersPerPx = 0.3, spacingPx = 24.0)
        assertEquals(spot, out[0]) // the first keeps the real spot
        for (i in out.indices) for (j in i + 1 until out.size) {
            assertTrue("dots $i and $j overlap", px(out[i], out[j], 0.3) >= 23.0)
        }
    }

    @Test
    fun farApartDotsStayPut() {
        val pts = listOf(39.0 to -98.0, 39.01 to -98.0, 39.0 to -98.01)
        assertEquals(pts, PinSpread.spread(pts, metersPerPx = 0.3, spacingPx = 24.0))
    }

    @Test
    fun zoomedOutNeighboursAreNotDraggedAround() {
        // ~100 m apart: at 20 m/px they'd overlap on screen, but they're different spots.
        val pts = listOf(39.0 to -98.0, 39.0009 to -98.0)
        assertEquals(pts, PinSpread.spread(pts, metersPerPx = 20.0, spacingPx = 24.0))
    }
}
