package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3D driving view's camera: close and flatter in town, wider and tilted further at speed, arrow low on the screen. */
class DriveCameraTest {

    @Test
    fun zoomAndTiltFollowTheSpeed() {
        assertEquals(17.0, MapFragment.zoomFor(0f), 1e-9)
        assertEquals(17.0, MapFragment.zoomFor(30f), 1e-9)
        assertEquals(15.4, MapFragment.zoomFor(130f), 1e-9)
        assertEquals(50.0, MapFragment.tiltFor(10f), 1e-9)
        assertEquals(60.0, MapFragment.tiltFor(120f), 1e-9)
        // Smooth in between: never a jump.
        var z = MapFragment.zoomFor(30f); var t = MapFragment.tiltFor(30f)
        for (kmh in 31..110) {
            val nz = MapFragment.zoomFor(kmh.toFloat()); val nt = MapFragment.tiltFor(kmh.toFloat())
            assertTrue(nz <= z && z - nz < 0.05); assertTrue(nt >= t && nt - t < 0.2)
            z = nz; t = nt
        }
    }

    @Test
    fun theDefaultZoomSetsTheWholeCurve() {
        assertEquals(16.0, MapFragment.zoomFor(0f, 16.0), 1e-9)
        assertEquals(14.4, MapFragment.zoomFor(130f, 16.0), 1e-9)
        assertEquals(MapFragment.zoomFor(50f), MapFragment.zoomFor(50f, 17.0), 1e-9)
    }

    @Test
    fun theAngleIsSetByTheUserAndNeverPastTheEnginesLimit() {
        assertEquals(40.0, MapFragment.tiltFor(0f, 40.0), 1e-9)
        assertEquals(50.0, MapFragment.tiltFor(130f, 40.0), 1e-9)
        assertEquals(60.0, MapFragment.tiltFor(130f, 58.0), 1e-9)
    }

    @Test
    fun theArrowSitsLowOnTheScreen() {
        // The camera's focus is the middle of the area below the top padding.
        val h = 2000
        val pad = MapFragment.topPaddingFor(h)
        assertEquals(MapFragment.ARROW_HEIGHT * h, (pad + h) / 2, 1e-6)
    }
}
