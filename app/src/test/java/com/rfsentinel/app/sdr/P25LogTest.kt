package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Test

class P25LogTest {

    @Test
    fun callsStartOnceAndKeepTheirDetails() {
        val log = P25Log()
        log.add(P25Tsbk.Iden(1, 851_006_250L, 6_250, 1), 0)
        // A grant starts a call; its updates keep it going; after the gap it is a new call.
        assertEquals(listOf(0x300), log.add(P25Tsbk.Grant(0x1002, 0x300, null, 1001, emergency = false, encrypted = true), 1_000))
        assertEquals(emptyList<Int>(), log.add(P25Tsbk.GrantUpdate(listOf(0x1002 to 0x300)), 5_000))
        // One update can start two calls: both are reported.
        assertEquals(listOf(0x301, 0x302), log.add(P25Tsbk.GrantUpdate(listOf(0x1003 to 0x301, 0x1004 to 0x302)), 6_000))
        assertEquals(listOf(0x300), log.add(P25Tsbk.Grant(0x1002, 0x300, null, 1002, false, false), 30_000))
        log.add(P25Tsbk.Affiliation(1003, 0x300), 31_000)
        log.add(P25Tsbk.Grant(0x1005, null, 77, 1001, false, false), 32_000)

        val rows = log.rows()
        assertEquals(listOf(null, 0x300), rows.take(2).map { it.tg })
        assertEquals(setOf(0x301, 0x302), rows.drop(2).map { it.tg }.toSet()) // same moment: either order
        assertEquals(77, rows[0].target)
        val tg = rows[1]
        assertEquals(2, tg.calls)
        assertEquals(3, tg.radios)
        assertEquals(851_006_250L + 2 * 6_250, tg.lastHz)
        assertEquals(false, tg.encrypted)
    }

    @Test
    fun siteFillsInAndClearForgets() {
        val log = P25Log()
        log.add(P25Tsbk.Site(0xBEE00, 0x3A1, null, null, 0x1040), 0)
        log.add(P25Tsbk.Site(null, 0x3A1, 1, 7, 0x1040), 0)
        assertEquals(P25Tsbk.Site(0xBEE00, 0x3A1, 1, 7, 0x1040), log.summary().site)
        log.clear(10)
        assertEquals(null, log.summary().site)
        assertEquals(emptyList<P25Log.Row>(), log.rows())
    }

    @Test
    fun watchedTalkgroups() {
        assertEquals(mapOf(1234 to "Sheriff dispatch", 2001 to "", 9 to "Fire"),
            P25Log.watched("1234 Sheriff dispatch\n2001\n  9 - Fire\nnot a number\n70000 too big"))
    }
}
