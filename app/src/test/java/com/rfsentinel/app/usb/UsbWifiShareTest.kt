package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several USB WiFi adapters share the channels between them ([UsbWifi.split]). */
class UsbWifiShareTest {
    private val g24 = UsbWifi.HOP_24.toList()
    private val g5 = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    private val dual = g24 + g5

    @Test
    fun fourIdenticalAdaptersVisitEveryChannelOnceBetweenThem() {
        val caps = List(4) { dual.toSet() }
        val parts = (0 until 4).map { UsbWifi.split(dual, it, caps) }
        assertEquals("no channel left out", dual.toSet(), parts.flatten().toSet())
        assertEquals("no channel visited by two", dual.size, parts.sumOf { it.size })
        assertTrue("about a quarter each", parts.all { it.size in dual.size / 4..dual.size / 4 + 1 })
    }

    @Test
    fun aTwoPointFourOnlyAdapterLeavesFiveGhzToTheOther() {
        val caps = listOf(g24.toSet(), dual.toSet())
        val small = UsbWifi.split(g24, 0, caps)
        val big = UsbWifi.split(dual, 1, caps)
        assertTrue("the dual-band one keeps all of 5 GHz", big.containsAll(g5))
        assertEquals("2.4 GHz is split, nothing twice", g24.toSet(), (small + big.filter { it <= 14 }).toSet())
        assertEquals(g24.size, small.size + big.count { it <= 14 })
    }

    @Test
    fun aloneOrWithNothingToTakeItKeepsEverything() {
        assertEquals(dual, UsbWifi.split(dual, 0, listOf(dual.toSet())))
        // nothing of its own left after the split: it hops its whole list rather than none
        assertEquals(listOf(1), UsbWifi.split(listOf(1), 1, listOf(setOf(1), setOf(1))))
    }

    @Test
    fun theOrderOfTheHopListIsKept() {
        val mine = UsbWifi.split(dual, 0, List(2) { dual.toSet() })
        assertEquals(dual.filter { it in mine }, mine)
    }

    @Test
    fun linkedPhonesSplitTheChannelsTheSameWayFromTheirOwnLists() {
        val ids = listOf(7L, 3L, 11L)
        // Each phone's adapter lists the channels in its own order, one with extra 5 GHz channels.
        val lists = mapOf(7L to dual, 3L to dual.reversed(), 11L to dual + listOf(52, 56))
        val parts = ids.associateWith { me -> UsbWifi.linkSplit(lists.getValue(me), me, ids.filter { it != me }.associateWith { true }) }
        assertEquals("no channel left out", dual.toSet(), parts.values.flatten().filter { it in dual }.toSet())
        assertEquals("no common channel visited twice", dual.size, parts.values.sumOf { p -> p.count { it in dual } })
    }

    @Test
    fun aTwoGhzOnlyPhoneGetsNoFiveGhz() {
        val dualPhone = UsbWifi.linkSplit(dual, 1L, mapOf(2L to false))
        val lowPhone = UsbWifi.linkSplit(g24, 2L, mapOf(1L to true))
        assertTrue("all of 5 GHz stays with the dual-band phone", dualPhone.containsAll(g5))
        assertEquals(g24.toSet(), (dualPhone.filter { it <= 14 } + lowPhone).toSet())
        assertEquals(g24.size, dualPhone.count { it <= 14 } + lowPhone.size)
    }

    @Test
    fun noPeersOrSixGhzKeepEverything() {
        assertEquals(dual, UsbWifi.linkSplit(dual, 1L, emptyMap()))
        assertTrue(5975 in UsbWifi.linkSplit(dual + 5975, 1L, mapOf(2L to true, 3L to true)))
    }
}
