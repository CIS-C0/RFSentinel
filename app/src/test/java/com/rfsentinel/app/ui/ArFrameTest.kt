package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArFrameTest {

    @Test
    fun walkingAheadInTheCameraWorldIsWalkingTheCompassWay() {
        val f = ArFrame()
        val here = Motion.Pos(10.0, 5.0)
        // Facing AR north, the compass says east.
        repeat(ArFrame.ALIGN_FRAMES - 1) { assertNull(f.place(0f, 0f, 0f, 90f, here)) }
        val p0 = f.place(0f, 0f, 0f, 90f, here)!!
        assertEquals(10.0, p0.x, 1e-6); assertEquals(5.0, p0.y, 1e-6)
        // Two metres ahead (AR -z) is two metres east.
        val p = f.place(0f, -2f, 0f, null, null)!!
        assertEquals(12.0, p.x, 1e-6); assertEquals(5.0, p.y, 1e-6)
        assertEquals(90f, f.heading(0f)!!, 1e-3f)
        assertEquals(180f, f.heading(ArFrame.arHeading(1f, 0f))!!, 1e-3f)
    }

    @Test
    fun alignsOnlyWhileHeldLevelAndPlaced() {
        val f = ArFrame()
        repeat(100) { f.place(0f, 0f, 0f, null, Motion.Pos(0.0, 0.0)) }
        assertNull(f.heading(0f))
        f.reset()
        repeat(ArFrame.ALIGN_FRAMES) { f.place(0f, 0f, 350f, 10f, Motion.Pos(0.0, 0.0)) }
        // The turn averages the short way round through north.
        assertEquals(10f, f.heading(350f)!!, 1e-3f)
    }
}
