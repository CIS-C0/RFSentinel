package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RadarTrackTest {

    // Metres east / north of a neutral origin (0, 0) to lat/lon.
    private fun line(east: Double, north: Double, bearing: Float) =
        RadarTrack.Line(north / 110_540.0, east / 111_320.0, bearing, 1f, 0L)

    @Test
    fun twoTurnsCrossAtTheDevice() {
        // Device 50 m east and 50 m north: seen NE from the start, NW from 100 m further east.
        val (lat, lon) = RadarTrack.cross(listOf(line(0.0, 0.0, 45f), line(100.0, 0.0, 315f)))!!
        assertEquals(50.0, lat * 110_540.0, 1.0)
        assertEquals(50.0, lon * 111_320.0, 1.0)
        // A third, slightly off line still lands close.
        val (lat3, lon3) = RadarTrack.cross(listOf(line(0.0, 0.0, 45f), line(100.0, 0.0, 315f), line(50.0, 0.0, 5f)))!!
        assertEquals(50.0, lat3 * 110_540.0, 5.0)
        assertEquals(50.0, lon3 * 111_320.0, 5.0)
    }

    @Test
    fun noCrossingWhenParallelBehindOrAlone() {
        assertNull(RadarTrack.cross(listOf(line(0.0, 0.0, 45f))))
        assertNull(RadarTrack.cross(listOf(line(0.0, 0.0, 45f), line(100.0, 0.0, 50f)))) // nearly parallel
        assertNull(RadarTrack.cross(listOf(line(0.0, 0.0, 45f), line(100.0, 0.0, 135f)))) // behind the second spot
        assertNull(RadarTrack.cross(listOf(RadarTrack.Line(null, null, 45f, 1f, 0L), line(100.0, 0.0, 315f))))
        assertNotNull(RadarTrack.cross(listOf(line(0.0, 0.0, 45f), line(100.0, 0.0, 315f))))
    }
}
