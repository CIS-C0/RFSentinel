package com.rfsentinel.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeBookTest {

    @Test
    fun recordsNetworksDevicesAndEncounters() {
        val b = ProbeBook()
        b.record("PD-MDT", "aa:bb:cc:00:00:01", -70, 0)
        b.record("pd-mdt", "AA:BB:CC:00:00:02", -60, 1_000)            // same name, any case
        b.record("PD-MDT", "aa:bb:cc:00:00:01", -65, 2_000)            // same device again
        b.record("PD-MDT", "aa:bb:cc:00:00:03", -80, 2_000 + ProbeBook.GAP_MS + 1) // a new encounter
        val n = b.all().single()
        assertEquals("PD-MDT", n.ssid)
        assertEquals(3, n.devices.size)
        assertEquals("AA:BB:CC:00:00:03", n.devices.last())
        assertEquals(2, n.encounters)
        assertEquals(-80, n.lastRssi)
        assertEquals(0L, n.firstSeen)
    }

    @Test
    fun keepsTheNewestNamesAndDevices() {
        val b = ProbeBook(maxNetworks = 3, maxDevices = 2)
        for (i in 1..5) b.record("net$i", "02:00:00:00:00:0$i", -50, i * 1000L)
        assertEquals(listOf("net5", "net4", "net3"), b.all().map { it.ssid })
        for (i in 1..4) b.record("net5", "02:00:00:00:01:0$i", -50, 10_000L + i)
        assertEquals(listOf("02:00:00:00:01:03", "02:00:00:00:01:04"), b.all().first().devices)
    }

    @Test
    fun restoresSavedData() {
        val b = ProbeBook()
        b.record("Home", "02:00:00:00:00:01", -50, 5)
        val saved = b.all()
        val c = ProbeBook()
        c.restore(saved)
        assertEquals(saved, c.all())
        c.remove("HOME")
        assertTrue(c.all().isEmpty())
    }
}
