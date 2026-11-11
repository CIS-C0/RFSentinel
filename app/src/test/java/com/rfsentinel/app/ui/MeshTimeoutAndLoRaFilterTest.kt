package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.esp.HeardBy
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.online.PoliceAircraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTimeoutAndLoRaFilterTest {
    @After fun reset() { HeardBy.peerMesh.clear(); HeardBy.phone.clear(); HeardBy.esp.clear() }

    private fun snap(mac: String, name: String?, lastSeen: Long) = DeviceRegistry.Snapshot(
        mac, Advert.Source.BLE, setOf(Advert.Source.BLE), name, null, "", emptyList(), emptyList(), -70, -70, 0.0,
        AddressType.entries.first(), lastSeen, lastSeen, 1, null, false, null, null, emptyList(), emptyList(), null
    )

    @Test
    fun meshDeviceLeavesAfterTheChosenTimeUnlessHeardHereToo() {
        val now = 10_000_000L
        val mac = "AA:BB:CC:00:00:01"
        HeardBy.peerMesh.mark(mac, now - 60_000)
        val s = snap(mac, null, now - 60_000)
        assertTrue("default: held until repeated", LiveWindow.keep(s, now, 30_000, 30_000))
        assertTrue(LiveWindow.keep(s, now, 30_000, 30_000, meshMs = 90_000))
        assertFalse("30 s chosen", LiveWindow.keep(s, now, 30_000, 30_000, meshMs = 30_000))
        HeardBy.phone.mark(mac, now - 5_000)
        assertTrue("this phone hears it too", LiveWindow.keep(s.copy(lastSeen = now - 5_000), now, 30_000, 30_000, meshMs = 30_000))
    }

    @Test
    fun loraChipShowsOnlyMeshNodes() {
        assertTrue(DeviceFilter.LORA.matches(snap("06:4D:A1:B2:C3:D4", "Meshtastic: Hilltop", 0)))
        assertTrue(DeviceFilter.LORA.matches(snap("06:43:01:02:03:04", "MeshCore: Repeater", 0)))
        assertFalse(DeviceFilter.LORA.matches(snap("AA:BB:CC:00:00:01", "Phone", 0)))
    }

    @Test
    fun externalLeavesOutTheLoRaNodesTheMeshNodeReports() {
        // A phone whose only hardware is a Meshtastic node: its LoRa nodes were counted as External too.
        HeardBy.esp.mark("06:4D:A1:B2:C3:D4")
        HeardBy.esp.mark("AA:BB:CC:00:00:02")
        assertFalse(DeviceFilter.EXTERNAL.matches(snap("06:4D:A1:B2:C3:D4", "Meshtastic: Hilltop", 0)))
        assertTrue(DeviceFilter.LORA.matches(snap("06:4D:A1:B2:C3:D4", "Meshtastic: Hilltop", 0)))
        assertTrue("an ESP32 board's device", DeviceFilter.EXTERNAL.matches(snap("AA:BB:CC:00:00:02", "Cam", 0)))
    }

    @Test
    fun allCountsEveryRowTheAllListShows() {
        fun plane(hex: String) = PoliceAircraft.Plane(hex, null, null, null, null, 1.0, 2.0, null, null, null, false)
        val hit = com.rfsentinel.app.detect.Hit(com.rfsentinel.app.detect.Category.AIRCRAFT, "Police", 85, "", "")
        // Bluetooth 3 + WiFi 3, Aircraft 5 with one flagged, 2 cells: All used to say 6.
        val aircraft = listOf(PoliceAircraft.Seen(plane("a1"), hit)) + (2..5).map { PoliceAircraft.Seen(plane("a$it"), null) }
        assertEquals(6 + 1 + 2, DeviceFilter.allCount(6, aircraft, cells = 2, waze = 0, radio = 0, talkgroups = 0))
        assertEquals(5, DeviceFilter.allCount(1, aircraft.take(1), cells = 1, waze = 1, radio = 1, talkgroups = 0))
    }
}
