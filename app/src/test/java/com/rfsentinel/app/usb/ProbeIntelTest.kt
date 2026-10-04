package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeIntelTest {

    private fun attr(type: Int, v: String) = byteArrayOf((type shr 8).toByte(), type.toByte(), 0, v.length.toByte()) + v.toByteArray()

    /** A WPS vendor element (221, 00:50:F2:04) with maker / model / number / device name. */
    private fun wpsIe(vararg attrs: ByteArray): ByteArray {
        val body = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + attrs.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(221.toByte(), body.size.toByte()) + body
    }

    private fun ie(tag: Int, vararg v: Int) = byteArrayOf(tag.toByte(), v.size.toByte()) + v.map { it.toByte() }.toByteArray()
    private fun ssid(s: String) = byteArrayOf(0, s.length.toByte()) + s.toByteArray()

    /** A probe request's elements from one laptop: name, rates, channel, HT, ext caps, a vendor element. */
    private fun laptop(name: String, channel: Int, ht0: Int = 0xEF) =
        ssid(name) + ie(1, 0x82, 0x84, 0x8b, 0x96) + ie(3, channel) + ie(45, ht0, 0x09, 0x17, 0xff) +
            ie(127, 0x04, 0x00, 0x0a, 0x82) + ie(221, 0x00, 0x50, 0xf2, 0x08, 0x00)

    @Test
    fun wpsBlockGivesMakerModelAndName() {
        val ie = wpsIe(attr(0x1021, "Panasonic"), attr(0x1023, "Toughbook"), attr(0x1024, "CF-33"), attr(0x1011, "PD-UNIT12"))
        val w = ProbeIntel.parseWps(ie, 6, ie.size)!!
        assertEquals("PD-UNIT12", w.label)
        assertEquals("Panasonic Toughbook CF-33", w.product)
        assertNull(ProbeIntel.parseWps(wpsIe(attr(0x1021, "0")), 6, 12)) // placeholder only
    }

    @Test
    fun fingerprintIgnoresWhatAndWhereItAsks() {
        val a = laptop("PD-MDT", 1); val b = laptop("Home", 11)
        assertEquals(ProbeIntel.fingerprint(a, 0, a.size), ProbeIntel.fingerprint(b, 0, b.size))
        val other = laptop("PD-MDT", 1, ht0 = 0x6F) // a different radio
        assertNotEquals(ProbeIntel.fingerprint(a, 0, a.size), ProbeIntel.fingerprint(other, 0, other.size))
        val bare = ssid("")
        assertNull(ProbeIntel.fingerprint(bare, 0, bare.size))
    }

    @Test
    fun probeRequestsCarryWpsAndFingerprintToTheApp() {
        val hdr = ByteArray(24).also { it[0] = 0x40; for (k in 0 until 6) { it[4 + k] = -1; it[16 + k] = -1 } }
        byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55).copyInto(hdr, 10)
        val frame = hdr + laptop("PD-MDT", 6) + wpsIe(attr(0x1021, "Panasonic"), attr(0x1023, "Toughbook"))
        val t = MonitorFrames()
        t.frame(frame, 0, frame.size, 6, -60, 0)
        val s = t.drain(0, 5000).single()
        assertEquals("Panasonic Toughbook", s.wps?.label)
        assertEquals(listOf("PD-MDT"), s.probedSsids)
        assertEquals(8, s.fingerprint?.length)
    }
}
