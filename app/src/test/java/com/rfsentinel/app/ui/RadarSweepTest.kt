package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarSweepTest {

    private fun angle(sinceScan: Long, scanMs: Long) = LiveWindow.sweepAngle(1_000_000L + sinceScan, 1_000_000L, scanMs)

    @Test
    fun passesTheTopAtEachScan() {
        for (ms in listOf(5_000L, 7_000L, 12_000L, 30_000L, 47_000L)) assertEquals(-90f, angle(0, ms), 0.01f)
        assertEquals(-90f, angle(30_000, 30_000), 0.01f)
    }

    @Test
    fun turnsTakeAboutFiveSeconds() {
        assertEquals(90f, angle(2_500, 5_000), 0.01f)
        assertEquals(90f, angle(2_500, 30_000), 0.01f) // six turns per scan
        assertEquals(-90f, angle(5_000, 30_000), 0.01f)
        assertEquals(90f, angle(3_500, 7_000), 0.01f)  // one turn: 7 s can't split
        assertEquals(90f, angle(3_000, 12_000), 0.01f) // two 6 s turns
    }

    @Test
    fun aClockSetBackStaysInRange() {
        assertTrue(angle(-1_000, 30_000) in -90f..270f)
    }
}
