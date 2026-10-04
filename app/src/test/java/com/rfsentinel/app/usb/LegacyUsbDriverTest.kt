package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** RTL8187 (ALFA AWUS036H) and RT3070 (ALFA AWUS036NH / NEH) drivers: USB IDs, RX parsing, firmware. */
class LegacyUsbDriverTest {

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    private fun beacon(bssid: String, ssid: String, channel: Int): ByteArray {
        val hdr = ByteArray(24)
        hdr[0] = 0x80.toByte()
        System.arraycopy(mac("ff:ff:ff:ff:ff:ff"), 0, hdr, 4, 6)
        System.arraycopy(mac(bssid), 0, hdr, 10, 6)
        System.arraycopy(mac(bssid), 0, hdr, 16, 6)
        val ies = byteArrayOf(0, ssid.length.toByte()) + ssid.toByteArray() + byteArrayOf(3, 1, channel.toByte())
        return hdr + ByteArray(12) + ies
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    @Test
    fun adaptersRouteToTheirDrivers() {
        assertEquals(UsbWifi.Driver.RTL8187, UsbWifi.chipOf(0x0bda, 0x8187)?.driver) // ALFA AWUS036H
        assertEquals("RTL8187L", UsbWifi.chipOf(0x0bda, 0x8187)?.name)
        assertEquals("RTL8187B", UsbWifi.chipOf(0x0846, 0x4260)?.name) // Netgear WG111v3
        assertEquals(UsbWifi.Driver.RT3070, UsbWifi.chipOf(0x148f, 0x3070)?.driver) // ALFA AWUS036NH / NEH
        assertEquals(UsbWifi.Driver.RTL88X2BU, UsbWifi.chipOf(0x0bda, 0xb812)?.driver)
        assertNull(UsbWifi.chipOf(0x148f, 0x5370)) // RT5370: different radio, not supported
    }

    // --- RTL8187: | 802.11 frame | FCS | RX descriptor (16 B, or 20 B on the 8187B) | ---

    private fun rtl8187Transfer(frame: ByteArray, agc: Int, b: Boolean, crcError: Boolean = false): ByteArray {
        val len = frame.size + 4
        val flags = len or (if (crcError) 1 shl 13 else 0)
        val desc = ByteArray(if (b) 20 else 16)
        le32(flags).copyInto(desc, 0)
        desc[if (b) 14 else 6] = agc.toByte()
        return frame + byteArrayOf(1, 2, 3, 4) + desc
    }

    @Test
    fun rtl8187FrameEndsBeforeTheChecksum() {
        val f = beacon("00:c0:ca:11:22:33", "Unit-7", 6)
        val t = rtl8187Transfer(f, agc = 64, b = false)
        val rx = Rtl8187Monitor.parseRx(t, t.size, is8187b = false)
        assertNotNull(rx)
        assertEquals(0, rx!!.start)
        assertEquals(f.size, rx.end)
        assertEquals(-4 - ((27 * 64) shr 6), rx.rssi) // kernel scaling: -31

        val frames = MonitorFrames()
        frames.frame(t, rx.start, rx.end, 6, rx.rssi, now = 1000)
        val out = frames.drain(1000, 5000)
        assertEquals("Unit-7", out.single().ssid)
        assertEquals(2437, out.single().frequencyMhz)
    }

    @Test
    fun rtl8187bUsesItsLongerDescriptor() {
        val f = beacon("00:c0:ca:11:22:33", "Unit-7", 1)
        val t = rtl8187Transfer(f, agc = 100, b = true)
        val rx = Rtl8187Monitor.parseRx(t, t.size, is8187b = true)!!
        assertEquals(f.size, rx.end)
        assertEquals(14 - 100 / 2, rx.rssi)
    }

    @Test
    fun rtl8187DropsBadChecksumsAndRunts() {
        val f = beacon("00:c0:ca:11:22:33", "Unit-7", 1)
        val bad = rtl8187Transfer(f, agc = 64, b = false, crcError = true)
        assertNull(Rtl8187Monitor.parseRx(bad, bad.size, is8187b = false))
        assertNull(Rtl8187Monitor.parseRx(ByteArray(20), 20, is8187b = false))
        val t = rtl8187Transfer(f, agc = 64, b = false)
        t[t.size - 16] = 0x7f; t[t.size - 15] = 0x0f // length past the transfer
        assertNull(Rtl8187Monitor.parseRx(t, t.size, is8187b = false))
    }

    // --- RT3070: | RXINFO (4) | RXWI (16) | 802.11 frame, padded to 4 | RXD (4) | ---

    private fun rt3070Transfer(frame: ByteArray, rssiRaw: Int, crcError: Boolean = false): ByteArray {
        val padded = frame + ByteArray((4 - frame.size % 4) % 4)
        val rxwi = ByteArray(16)
        le32(frame.size shl 16).copyInto(rxwi, 0)
        rxwi[8] = rssiRaw.toByte()
        val pktLen = 16 + padded.size
        return le32(pktLen) + rxwi + padded + le32(if (crcError) 0x100 else 0)
    }

    private fun rt3070Frames(t: ByteArray, rssiOffset: Int = 0, lna: Int = 0): List<Triple<Int, Int, Int?>> {
        val out = mutableListOf<Triple<Int, Int, Int?>>()
        Rt3070Monitor.forEachFrame(t, t.size, rssiOffset, lna) { s, e, r -> out += Triple(s, e, r) }
        return out
    }

    @Test
    fun rt3070FrameAndSignal() {
        val f = beacon("00:c0:ca:6d:00:01", "Fleet-3", 11)
        val t = rt3070Transfer(f, rssiRaw = 50)
        val got = rt3070Frames(t, rssiOffset = 2, lna = 3).single()
        assertEquals(20, got.first)
        assertEquals(20 + f.size, got.second)
        assertEquals(-12 - 2 - 3 - 50, got.third) // rt2800_agc_to_rssi

        val frames = MonitorFrames()
        frames.frame(t, got.first, got.second, 11, got.third, now = 1000)
        assertEquals("Fleet-3", frames.drain(1000, 5000).single().ssid)
    }

    @Test
    fun rt3070SkipsBadChecksumsAndReadsEveryFrameOfATransfer() {
        val a = beacon("00:c0:ca:6d:00:01", "A", 1)
        val b = beacon("00:c0:ca:6d:00:02", "Bb", 1)
        val t = rt3070Transfer(a, 40, crcError = true) + rt3070Transfer(b, 0)
        val got = rt3070Frames(t)
        assertEquals(1, got.size)
        assertNull(got[0].third) // no signal reading
        assertEquals(b.size, got[0].second - got[0].first)
    }

    @Test
    fun rt3070CountsWhatItSkips() {
        val bad = mutableListOf<Boolean>()
        val a = rt3070Transfer(beacon("00:c0:ca:6d:00:01", "A", 1), 40, crcError = true)
        val b = rt3070Transfer(beacon("00:c0:ca:6d:00:02", "B", 1), 40)
        Rt3070Monitor.forEachFrame(a + b + ByteArray(4), a.size + b.size + 4, 0, 0, onBad = { bad += it }) { _, _, _ -> }
        assertEquals(listOf(true), bad) // the bad checksum; trailing USB padding isn't an error
        bad.clear()
        Rt3070Monitor.forEachFrame(ByteArray(64), 64, 0, 0, onBad = { bad += it }) { _, _, _ -> }
        assertEquals(listOf(false), bad) // nothing parseable in the transfer
    }

    @Test
    fun rt3070IgnoresTruncatedTransfers() {
        val t = rt3070Transfer(beacon("00:c0:ca:6d:00:01", "A", 1), 40)
        assertEquals(0, rt3070Frames(t.copyOf(t.size - 6)).size)
        assertEquals(0, rt3070Frames(ByteArray(8)).size)
    }

    @Test
    fun ralinkFirmwareIsTheLinuxFirmwareImage() {
        val fw = File("src/main/assets/usbwifi/rt2870.bin").readBytes()
        assertEquals(8192, fw.size)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(fw).joinToString("") { "%02x".format(it) }
        assertEquals("83f6e2ce95b10df1cf3e06928761fa57133ef164", sha1)
        assert(File("src/main/assets/usbwifi/LICENCE.ralink-firmware.txt").readText().contains("Ralink Technology"))
    }
}
