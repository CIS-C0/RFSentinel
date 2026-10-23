package com.rfsentinel.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDevicesTest {

    private val random = { mac: String -> (mac.take(2).toInt(16) and 0x02) != 0 }

    @Test
    fun recordsWhatEachDeviceAskedForAndSaid() {
        val b = ProbeBook()
        assertTrue(b.recordDevice("02:00:00:00:00:01", listOf("PD-MDT"), -70, 0))
        assertTrue(b.recordDevice("02:00:00:00:00:01", listOf("Home", "pd-mdt"), -60, 1_000,
            wpsName = "UNIT12", wpsProduct = "Panasonic CF-33", fingerprint = "abcd1234"))
        // A wildcard probe (no names) only adds to a known device.
        assertTrue(b.recordDevice("02:00:00:00:00:01", emptyList(), -55, 2_000 + ProbeBook.GAP_MS + 1))
        assertFalse(b.recordDevice("02:00:00:00:00:09", emptyList(), -55, 3_000, fingerprint = "ffff0000"))
        val d = b.devices().single()
        assertEquals(listOf("Home", "pd-mdt"), d.ssids)
        assertEquals("UNIT12", d.wpsName)
        assertEquals("Panasonic CF-33", d.wpsProduct)
        assertEquals("abcd1234", d.fingerprint)
        assertEquals(2, d.encounters)
        assertEquals(-55, d.lastRssi)
        assertEquals(1, b.sharing("abcd1234"))
    }

    @Test
    fun randomAddressesWithARareFingerprintAndACommonNameAreOneDevice() {
        val b = ProbeBook()
        b.recordDevice("02:00:00:00:00:01", listOf("PD-MDT", "Home"), -70, 0, fingerprint = "f1")
        b.recordDevice("06:00:00:00:00:02", listOf("pd-mdt"), -70, 1_000, fingerprint = "f1")
        b.recordDevice("0A:00:00:00:00:03", listOf("Cafe"), -70, 2_000, fingerprint = "f1") // nothing in common
        val groups = b.groups(random)
        assertEquals(2, groups.size)
        val merged = groups.single { it.likelySame }
        assertEquals(setOf("02:00:00:00:00:01", "06:00:00:00:00:02"), merged.devices.map { it.mac }.toSet())
        assertEquals(listOf("pd-mdt", "Home"), merged.ssids)
    }

    @Test
    fun fixedAddressesAndCommonFingerprintsStandAlone() {
        val b = ProbeBook()
        // Fixed (maker) addresses with the same fingerprint and name: two devices of one model.
        b.recordDevice("00:11:22:00:00:01", listOf("Office"), -70, 0, fingerprint = "f2")
        b.recordDevice("00:11:22:00:00:02", listOf("Office"), -70, 0, fingerprint = "f2")
        // A fingerprint shared by more addresses than rareMax says nothing.
        for (i in 1..4) b.recordDevice("02:00:00:00:01:0$i", listOf("Starbucks"), -70, i.toLong(), fingerprint = "common")
        val groups = b.groups(random, rareMax = 3)
        assertEquals(6, groups.size)
        assertTrue(groups.none { it.likelySame })
    }

    @Test
    fun removingANameDropsDevicesThatAskedForNothingElse() {
        val b = ProbeBook()
        b.record("PD-MDT", "02:00:00:00:00:01", -70, 0)
        b.recordDevice("02:00:00:00:00:01", listOf("PD-MDT"), -70, 0)
        b.record("Home", "02:00:00:00:00:02", -70, 0)
        b.recordDevice("02:00:00:00:00:02", listOf("PD-MDT", "Home"), -70, 0)
        b.remove("pd-mdt")
        assertEquals(listOf("02:00:00:00:00:02"), b.devices().map { it.mac })
        assertEquals(listOf("Home"), b.devices().single().ssids)
        b.clear()
        assertTrue(b.devices().isEmpty())
    }

    @Test
    fun restoredDevicesSurviveMissingFields() {
        val b = ProbeBook()
        b.restoreDevices(listOf(ProbeBook.Device("aa:bb:cc:dd:ee:ff", 1, 2, 1, -50, mutableListOf("X"))))
        assertEquals("AA:BB:CC:DD:EE:FF", b.devices().single().mac)
    }

    @Test
    fun aListSavedBeforeDevicesWereKeptStillShowsWhoAsked() {
        val b = ProbeBook()
        b.restore(listOf(
            ProbeBook.Network("Home", 100, 200, 1, -60, mutableListOf("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02")),
            ProbeBook.Network("Office", 50, 300, 2, -70, mutableListOf("aa:aa:aa:aa:aa:01"))
        ))
        assertTrue(b.backfillDevices())
        val first = b.devices().first { it.mac == "AA:AA:AA:AA:AA:01" }
        assertEquals(listOf("Home", "Office"), first.ssids)
        assertEquals(50L, first.firstSeen)
        assertEquals(300L, first.lastSeen)
        assertEquals(2, b.devices().size)
        // Run again: nothing new.
        assertFalse(b.backfillDevices())
    }
}
