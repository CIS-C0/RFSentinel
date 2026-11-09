package com.rfsentinel.app.esp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardByTest {

    @Test
    fun marksForAWhileThenForgets() {
        val r = RecentMacs()
        r.mark("70:c9:4e:11:22:33", now = 1_000)
        assertTrue(r.recent("70:C9:4E:11:22:33", now = 1_000 + HeardBy.WINDOW_MS - 1))
        assertFalse(r.recent("70:C9:4E:11:22:33", now = 1_000 + HeardBy.WINDOW_MS))
        assertFalse(r.recent("00:11:22:33:44:55", now = 1_000))
    }

    @Test
    fun boardAndPhoneAreTrackedSeparately() {
        HeardBy.esp.clear(); HeardBy.phone.clear()
        HeardBy.esp.mark("AA:BB:CC:00:00:01", now = 5_000)
        assertTrue(HeardBy.esp.recent("AA:BB:CC:00:00:01", now = 5_000))
        assertFalse(HeardBy.phone.recent("AA:BB:CC:00:00:01", now = 5_000))
    }

    @Test
    fun meshDevicesStayUntilTheSenderRepeatsThem() {
        HeardBy.peerMesh.clear()
        HeardBy.peerMesh.mark("AA:BB:CC:00:00:02", now = 0)
        // Ordinary devices go out again every 15 minutes: still listed just before the next one.
        assertTrue(HeardBy.peerMesh.recent("AA:BB:CC:00:00:02", now = com.rfsentinel.app.link.MeshOutbox.repeatMs(0)))
        assertFalse(HeardBy.peerMesh.recent("AA:BB:CC:00:00:02", now = HeardBy.MESH_HOLD_MS))
    }

    @Test
    fun aDeviceFromTheMeshKeepsItsViaBadgeAsLongAsItStaysListed() {
        val p = PeerMacs()
        p.mark("AA:BB:CC:00:00:01", "phone b", 0L, HeardBy.MESH_HOLD_MS)
        // A later Bluetooth report doesn't cut the mesh hold short.
        p.mark("AA:BB:CC:00:00:01", "phone b", 60_000L)
        assertTrue(p.recent("AA:BB:CC:00:00:01", 10 * 60_000L))  // past the 5-minute Bluetooth window
        assertFalse(p.recent("AA:BB:CC:00:00:01", HeardBy.MESH_HOLD_MS))
        p.mark("AA:BB:CC:00:00:02", "phone b", 0L)
        assertFalse(p.recent("AA:BB:CC:00:00:02", HeardBy.WINDOW_MS))
    }
}
