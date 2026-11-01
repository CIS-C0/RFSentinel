package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MotionTest {

    @Test
    fun stepsWalkTheWayYouFaceAndGpsPullsBack() {
        val f = Motion.Fusion()
        f.gps(0.0, 0.0, 5.0) // ties the frame at 0 / 0
        repeat(10) { f.step(0f) } // 7 m north
        assertEquals(7.0, f.y, 0.01)
        assertEquals(0.0, f.x, 0.01)
        // A fix 20 m east, 5 m sure: pulled part way, not all the way.
        f.gps(7.0 / 110_540.0, 20.0 / 111_320.0, 5.0)
        assertTrue("${f.x}", f.x > 0.5 && f.x < 15)
        // After a pause, a fix snaps you there.
        f.gps(0.0, 0.0, 5.0, snap = true)
        assertEquals(0.0, f.x, 0.01)
        assertEquals(0.0, f.y, 0.01)
    }

    @Test
    fun turningOnTheSpotBarelyMoves() {
        val f = Motion.Fusion()
        for (h in 0 until 360 step 45) f.step(h.toFloat())
        assertTrue("${f.x},${f.y}", kotlin.math.hypot(f.x, f.y) < 1.5)
    }

    @Test
    fun countsWalkingSteps() {
        val s = Motion.Steps()
        // 10 s at 50 Hz: walking (2 steps a second, ±3 m/s²), then holding still.
        val walking = (0 until 500).count { s.add(it * 20L, 9.81 + 3 * sin(it * 20 / 1000.0 * 2 * Math.PI * 2)) }
        assertEquals(20.0, walking.toDouble(), 2.0)
        val still = (500 until 1000).count { s.add(it * 20L, 9.81 + 0.05 * sin(it * 1.3)) }
        assertEquals(0, still)
    }
}
