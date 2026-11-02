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

    @Test
    fun unusualNeedsABaselineAndAClearRise() {
        val day = 24 * 60 * 60_000L
        val now = 20 * day
        fun call(t: Long, tg: Int) = P25History.Call(t, tg, null, 1, null, null, emergency = false, encrypted = false, site = null)
        // Talkgroup 100: 2 calls in this hour on each of the 4 days before, 12 now. Talkgroup 200: as busy as ever.
        val past = (1..4).flatMap { d -> listOf(call(now - d * day - 60_000, 100), call(now - d * day - 120_000, 100)) +
            (1..6).map { call(now - d * day - it * 60_000L, 200) } }
        val nowCalls = (1..12).map { call(now - it * 60_000L, 100) } + (1..6).map { call(now - it * 60_000L, 200) } +
            (1..5).map { call(now - it * 60_000L, 300) }
        val u = P25History.unusual(past + nowCalls, now)
        assertEquals(listOf(100, 300), u.map { it.tg })
        assertEquals(2.0, u[0].usual, 0.001)
        assertTrue(!u[0].new && u[1].new)
        // Under 3 days of log: no baseline yet.
        assertTrue(P25History.unusual(nowCalls + call(now - 2 * day, 100), now).isEmpty())
    }
}
