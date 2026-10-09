package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ESP32 scanner bursts (OUI-SPY survey engine) don't count as new client devices. */
class ScannerProbeTest {

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** OUI-SPY's wildcard probe: broadcast, random source, empty SSID, rates 1 / 2 / 5.5 / 11. */
    private fun scannerProbe(src: String) = ByteArray(24).also {
        it[0] = 0x40; mac("ff:ff:ff:ff:ff:ff").copyInto(it, 4); mac(src).copyInto(it, 10); mac("ff:ff:ff:ff:ff:ff").copyInto(it, 16)
    } + byteArrayOf(0, 0, 1, 4, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte())

    @Test
    fun randomScannerBurstsAreIgnoredButPhonesAreNot() {
        val t = MonitorFrames()
        repeat(20) { i ->
            val f = scannerProbe("%02x:11:22:33:44:%02x".format(0x02 or (i shl 2), i))
            t.frame(f, 0, f.size, 6, -50, i.toLong())
            val withFcs = f + byteArrayOf(1, 2, 3, 4)
            t.frame(withFcs, 0, withFcs.size, 6, -50, i.toLong())
        }
        assertEquals(0, t.drain(20, 5000).size)
        // A phone's probe: same start, plus more elements (extended rates, HT capabilities...).
        val phone = scannerProbe("3a:11:22:33:44:55") + byteArrayOf(0x32, 4, 0x0c, 0x12, 0x18, 0x24)
        t.frame(phone, 0, phone.size, 6, -50, 30)
        assertTrue(t.drain(30, 5000).any { it.mac.equals("3A:11:22:33:44:55", ignoreCase = true) })
    }
}
