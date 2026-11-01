package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class SweepTest {

    @Test
    fun fullTurnFindsTheStrongestSide() {
        val s = Sweep()
        // A slow turn: -80 dBm with your back to it, -66 facing east (about 90°).
        for (h in 0 until 360 step 10) s.add(1_000L + h * 10, h.toFloat(), if (h in 90..110) -66 else -80)
        assertEquals(105f, s.estimate(5_000L)!!.bearing, 0.1f)
        assertEquals("E", Sweep.compassPoint(105f))
    }

    @Test
    fun bodyShadowWithFadingLocksOnTheRightSide() {
        val s = Sweep()
        // Facing it (200°) 10 dB stronger than turned away, plus fading of up to ±3 dB.
        for (h in 0 until 360 step 5) {
            val fade = (h / 5 * 3 % 7) - 3
            s.add(1_000L + h * 50, h.toFloat(), (-75 + 5 * cos(Math.toRadians(h - 200.0)) + fade).toInt())
        }
        val e = s.estimate(20_000L)!!
        assertEquals(200f, e.bearing, 15f)
        assertTrue(e.toString(), Sweep.locks(e))
    }

    @Test
    fun noDirectionUntilTurnedOrWhenFlat() {
        val half = Sweep()
        for (h in 0 until 180 step 10) half.add(1_000L, h.toFloat(), if (h == 90) -60 else -80)
        assertNull(half.estimate(1_000L)) // only half a turn
        val flat = Sweep()
        for (h in 0 until 360 step 10) flat.add(1_000L, h.toFloat(), -70 - (h / 10) % 2)
        assertFalse(Sweep.locks(flat.estimate(1_000L)!!)) // no side stands out
        // Old readings drop out.
        assertEquals(0, flat.covered(1_000L + Sweep.WINDOW_MS + 1))
    }

    @Test
    fun locksOnlyOnceItHolds() {
        val s = Sweep()
        for (h in 0 until 360 step 5) s.add(1_000L + h * 50, h.toFloat(), (-75 + 5 * cos(Math.toRadians(h - 120.0))).toInt())
        assertNull(s.settled(19_000L)) // just became clear
        s.add(19_500L, 120f, -70)
        assertNull(s.settled(19_500L))
        s.add(21_000L, 125f, -70)
        assertEquals(120f, s.settled(21_000L)!!.bearing, 15f)
    }

    @Test
    fun headingAtWhenHeardGoesTheShortWay() {
        assertEquals(360f, Sweep.headingAt(5L, 0L, 350f, 10L, 10f), 0.01f)
        assertEquals(15f, Sweep.headingAt(50L, 0L, 10f, 100L, 20f), 0.01f)
        assertEquals(20f, Sweep.headingAt(500L, 0L, 10f, 100L, 20f), 0.01f)
    }

    @Test
    fun turnIsShortestWay() {
        assertEquals(30, Sweep.turn(10f, 40f))
        assertEquals(-20, Sweep.turn(10f, 350f))
        assertEquals(180, Sweep.turn(0f, 180f).let { kotlin.math.abs(it) })
    }
}
