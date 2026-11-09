package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.esp.HeardBy
import com.rfsentinel.app.service.DeviceRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTimeoutAndLoRaFilterTest {
    @After fun reset() { HeardBy.peerMesh.clear(); HeardBy.phone.clear() }

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
}
