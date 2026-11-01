package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class RadarTrackTest {

    private val ble = RadarTrack.Radio(-59.0, 2.5)

    /** What a phone at x/y facing [heading] hears from a device at dx/dy: path loss plus the body's shadow. */
    private fun heard(x: Double, y: Double, heading: Float, dx: Double, dy: Double, time: Long): RadarTrack.Reading {
        val d = hypot(dx - x, dy - y).coerceAtLeast(1.0)
        val r = Math.toRadians(heading.toDouble())
        val facing = ((dx - x) * sin(r) + (dy - y) * cos(r)) / d
        return RadarTrack.Reading(x, y, heading, (-59 - 25 * log10(d) - 8 * (1 - facing) / 2).toInt(), time)
    }

    @Test
    fun twoTurnsCrossAtTheDevice() {
        // Device 50 m east and 50 m north: NE from the start, NW from 100 m further east.
        val p = RadarTrack.solve(listOf(RadarTrack.Line(0.0, 0.0, 45f, 1f, 0L), RadarTrack.Line(100.0, 0.0, 315f, 1f, 0L)), emptyList(), ble)!!
        assertEquals(50.0, p.x, 2.0)
        assertEquals(50.0, p.y, 2.0)
    }

    @Test
    fun oneTurnThenWalkingGivesTheRange() {
        // Turned at the start and found it east; then walked 60 m north past it. Device 40 m east.
        val readings = (0..60 step 2).map { heard(0.0, it - 30.0, 0f, 40.0, 0.0, it * 1_000L) }
        val p = RadarTrack.solve(listOf(RadarTrack.Line(0.0, -30.0, 53f, 1f, 0L)), readings, ble)!!
        assertTrue("$p", hypot(p.x - 40.0, p.y) < 10.0)
    }

    @Test
    fun oneTurnAloneStaysOnItsLine() {
        val readings = listOf(heard(0.0, 0.0, 90f, 20.0, 0.0, 0L))
        val p = RadarTrack.solve(listOf(RadarTrack.Line(0.0, 0.0, 90f, 1f, 0L)), readings, ble)!!
        assertTrue("${p.x}", p.x > 3)
        assertEquals(0.0, p.y, p.x * 0.2)
        assertNull(RadarTrack.solve(emptyList(), readings, ble))
    }
}
