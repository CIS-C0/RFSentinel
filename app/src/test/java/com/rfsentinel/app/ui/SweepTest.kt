package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SweepTest {

    @Test
    fun fullTurnFindsTheStrongestSide() {
        val s = Sweep()
        // A slow turn: -80 dBm with your back to it, -66 facing east (about 90°).
        for (h in 0 until 360 step 10) s.add(1_000L + h * 10, h.toFloat(), if (h in 90..110) -66 else -80)
        assertEquals(105f, s.direction(5_000L)!!, 0.1f)
        assertEquals("E", Sweep.compassPoint(105f))
    }

    @Test
    fun noDirectionUntilTurnedOrWhenFlat() {
        val half = Sweep()
        for (h in 0 until 180 step 10) half.add(1_000L, h.toFloat(), if (h == 90) -60 else -80)
        assertNull(half.direction(1_000L)) // only half a turn
        val flat = Sweep()
        for (h in 0 until 360 step 10) flat.add(1_000L, h.toFloat(), -70 - (h / 10) % 2)
        assertNull(flat.direction(1_000L)) // no side stands out
        // Old readings drop out.
        for (h in 0 until 360 step 10) flat.add(1_000L, h.toFloat(), if (h == 90) -50 else -80)
        assertEquals(0, flat.covered(1_000L + Sweep.WINDOW_MS + 1))
    }

    @Test
    fun turnIsShortestWay() {
        assertEquals(30, Sweep.turn(10f, 40f))
        assertEquals(-20, Sweep.turn(10f, 350f))
        assertEquals(180, Sweep.turn(0f, 180f).let { kotlin.math.abs(it) })
    }
}
