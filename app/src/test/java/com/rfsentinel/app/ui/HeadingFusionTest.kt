package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingFusionTest {

    @Test
    fun compassAloneOrNorthUp() {
        val h = HeadingFusion()
        assertNull(h.heading(0L)) // no compass, not moving: north-up
        h.compass(123f)
        assertEquals(123f, h.heading(0L)!!, 0.01f)
    }

    @Test
    fun withoutCompassTheCourseCountsWhileMoving() {
        val h = HeadingFusion()
        h.fix(0L, 200f, 1.2f)
        assertNull(h.heading(0L)) // below walking speed
        h.fix(1_000L, 200f, 1.6f)
        assertEquals(200f, h.heading(1_000L)!!, 0.01f)
        h.fix(2_000L, 210f, 1.2f)
        assertEquals(210f, h.heading(2_000L)!!, 0.01f) // still moving: no flipping at the edge
        h.fix(3_000L, 210f, 0.8f)
        assertNull(h.heading(3_000L))
    }

    @Test
    fun drivingSetsTheCompassRightAndTurnsShowAtOnce() {
        val h = HeadingFusion()
        h.compass(80f) // the car's metal pulls it 20° off
        h.fix(0L, 100f, 12f)
        assertTrue(h.gpsSet(0L))
        assertEquals(100f, h.heading(0L)!!, 0.01f)
        h.compass(110f) // turning, between two fixes
        assertEquals(130f, h.heading(500L)!!, 0.01f)
        // Across north: 350° + 20° is 10°, not 370°.
        h.compass(350f); h.fix(1_000L, 10f, 12f)
        assertEquals(10f, h.heading(1_000L)!!, 0.5f)
    }

    @Test
    fun stoppedTheCorrectionHoldsUntilThePhoneIsMovedOrItIsOld() {
        val h = HeadingFusion()
        h.compass(80f); h.fix(0L, 100f, 12f)
        h.fix(1_000L, null, 0f) // a red light
        h.compass(90f)
        assertEquals(110f, h.heading(60_000L)!!, 0.01f)
        assertFalse(h.gpsSet(HeadingFusion.HOLD_MS + 1_000L))
        assertEquals(90f, h.heading(HeadingFusion.HOLD_MS + 1_000L)!!, 0.01f)
        val moved = HeadingFusion()
        moved.compass(80f); moved.fix(0L, 100f, 12f); moved.fix(1_000L, null, 0f)
        moved.compass(200f) // taken out of the mount
        assertEquals(200f, moved.heading(2_000L)!!, 0.01f)
    }
}
