package com.rfsentinel.app.usb

import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › WiFi bands › Mostly 2.4 GHz ([UsbWifi.mostlyLow]). */
class UsbWifiMostlyLowTest {
    private val g24 = UsbWifi.HOP_24.toList()
    private val g5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 149, 153, 157, 161, 165)

    @After fun reset() { UsbWifi.band = Prefs.WifiBand.ALL }

    @Test
    fun mostTimeOnTwoPointFourAndEveryChannelStillVisited() {
        UsbWifi.band = Prefs.WifiBand.MOSTLY_LOW
        val plan = UsbWifi.hops((g24 + g5).toIntArray()).toList()
        assertEquals("every channel visited", (g24 + g5).toSet(), plan.toSet())
        assertEquals("each 5 GHz channel once a round", g5, plan.filter { it > 14 })
        assertEquals("three 2.4 GHz hops per 5 GHz hop", 3 * g5.size, plan.count { it <= 14 })
        assertTrue("never two 5 GHz hops in a row", plan.zipWithNext().none { (a, b) -> a > 14 && b > 14 })
    }

    @Test
    fun fewHighChannelsStillVisitEveryTwoPointFourChannel() {
        UsbWifi.band = Prefs.WifiBand.MOSTLY_LOW
        val plan = UsbWifi.mostlyLow(g24 + listOf(36, 149)) { it <= 14 }
        assertEquals((g24 + listOf(36, 149)).toSet(), plan.toSet())
    }

    @Test
    fun otherSettingsAndSingleBandAdaptersAreUnchanged() {
        val dual = g24 + g5
        assertEquals(dual, UsbWifi.mostlyLow(dual) { it <= 14 })
        UsbWifi.band = Prefs.WifiBand.MOSTLY_LOW
        assertEquals(g24, UsbWifi.mostlyLow(g24) { it <= 14 })
        assertTrue(UsbWifi.inBand(true) && UsbWifi.inBand(false))
        UsbWifi.band = Prefs.WifiBand.LOW
        assertTrue(!UsbWifi.inBand(true) && UsbWifi.inBand(false))
    }

    @Test
    fun mt7921RoundsAreWovenToo() {
        UsbWifi.band = Prefs.WifiBand.MOSTLY_LOW
        val plan = Mt7921uMonitor.roundPlan(0, emptySet())
        val low = plan.count { it.first == 0 }; val high = plan.size - low
        assertEquals(3 * high, low)
        assertEquals(Mt7921uMonitor.HOP.filter { it.first == 0 }.toSet(), plan.filter { it.first == 0 }.toSet())
    }
}
