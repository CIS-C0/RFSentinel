package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P25HistoryTest {

    @Test
    fun lineRoundTripsAndSummaryCounts() {
        val day = 24 * 60 * 60_000L
        val now = 10 * day
        val a = P25History.Call(now - 1_000, 1234, null, 5001, 0x1A2, 851_012_500L, emergency = true, encrypted = false, site = "1A3-1-4")
        val b = P25History.Call(now - 3 * day, 1234, null, 5002, null, null, emergency = false, encrypted = true, site = null)
        val p = P25History.Call(now - 2_000, null, 777, 5003, 0x10, null, emergency = false, encrypted = false, site = null)
        assertEquals(a, P25History.parse(P25History.line(a)))
        assertEquals(b, P25History.parse(P25History.line(b)))
        assertNull(P25History.parse(P25History.HEADER))

        val s = P25History.summarize(listOf(b, p, a), now)
        val tg = s.first { it.tg == 1234 }
        assertEquals(1, tg.lastDay); assertEquals(2, tg.lastWeek); assertEquals(2, tg.all)
        assertEquals(2, tg.radios)
        assertTrue(tg.emergency && tg.encrypted)
        assertEquals(777, s.first { it.tg == null }.target)
        assertEquals(1234, s.first().tg) // most recent call first
    }
}
