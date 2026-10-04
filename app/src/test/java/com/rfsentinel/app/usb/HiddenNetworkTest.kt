package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenNetworkTest {

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** A management frame: [fc0] subtype byte, addresses, [fixed] fixed-field bytes, then an SSID element. */
    private fun mgmt(fc0: Int, a1: String, a2: String, a3: String, fixed: Int, ssid: String): ByteArray {
        val h = ByteArray(24); h[0] = fc0.toByte()
        mac(a1).copyInto(h, 4); mac(a2).copyInto(h, 10); mac(a3).copyInto(h, 16)
        return h + ByteArray(fixed) + byteArrayOf(0, ssid.length.toByte()) + ssid.toByteArray()
    }

    private val ap = "00:30:44:11:22:33"
    private val laptop = "3c:22:fb:00:00:02"

    @Test
    fun probeResponseRevealsAHiddenNetwork() {
        val t = MonitorFrames()
        val beacon = mgmt(0x80, "ff:ff:ff:ff:ff:ff", ap, ap, 12, "")
        t.frame(beacon, 0, beacon.size, 36, -60, 0)
        val first = t.drain(0, 5000).single()
        assertTrue(first.hiddenNetwork)
        assertNull(first.ssid)
        val resp = mgmt(0x50, laptop, ap, ap, 12, "PD-UNIT12-5g")
        t.frame(resp, 0, resp.size, 36, -60, 10)
        t.frame(beacon, 0, beacon.size, 36, -60, 20) // still blank in later beacons
        val s = t.drain(20, 5000).single()
        assertEquals("PD-UNIT12-5g", s.ssid)
        assertTrue(s.hiddenNetwork)
    }

    @Test
    fun associationRequestRevealsItEvenBeforeTheBeacon() {
        val t = MonitorFrames()
        val assoc = mgmt(0x00, ap, laptop, ap, 4, "Axon12-5g")
        t.frame(assoc, 0, assoc.size, 36, -60, 0)
        val beacon = mgmt(0x80, "ff:ff:ff:ff:ff:ff", ap, ap, 12, "")
        t.frame(beacon, 0, beacon.size, 36, -60, 10)
        val byMac = t.drain(10, 5000).associateBy { it.mac }
        assertEquals("Axon12-5g", byMac.getValue(ap).ssid)
        assertEquals(ap, byMac.getValue(laptop).bssid)
    }
}
