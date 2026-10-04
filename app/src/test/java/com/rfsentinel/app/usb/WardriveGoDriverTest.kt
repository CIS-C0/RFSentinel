package com.rfsentinel.app.usb

import com.rfsentinel.app.esp.FreeWiliReports
import com.rfsentinel.app.esp.SerialPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** RTL8814AU, MT7612U and AR9271 drivers and the FREE-WiLi 2 reader (ported from Wardrive Go). */
class WardriveGoDriverTest {

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun beacon(bssid: String, ssid: String, channel: Int): ByteArray {
        val hdr = ByteArray(24)
        hdr[0] = 0x80.toByte()
        System.arraycopy(mac("ff:ff:ff:ff:ff:ff"), 0, hdr, 4, 6)
        System.arraycopy(mac(bssid), 0, hdr, 10, 6)
        System.arraycopy(mac(bssid), 0, hdr, 16, 6)
        return hdr + ByteArray(12) + byteArrayOf(0, ssid.length.toByte()) + ssid.toByteArray() + byteArrayOf(3, 1, channel.toByte())
    }

    private fun ssidOf(b: ByteArray, s: Int, e: Int, ch: Int): String? {
        val t = MonitorFrames()
        t.frame(b, s, e, ch, -60, now = 1000)
        return t.drain(1000, 5000).singleOrNull()?.ssid
    }

    @Test
    fun adaptersRouteToTheirDrivers() {
        assertEquals(UsbWifi.Driver.RTL8814AU, UsbWifi.chipOf(0x0bda, 0x8813)?.driver) // ALFA AWUS1900
        assertEquals(UsbWifi.Driver.MT7612U, UsbWifi.chipOf(0x0e8d, 0x7612)?.driver)    // ALFA AWUS036ACM
        assertEquals(UsbWifi.Driver.AR9271, UsbWifi.chipOf(0x0cf3, 0x9271)?.driver)     // ALFA AWUS036NHA
        assertEquals(UsbWifi.Driver.MT7612U, UsbWifi.chipOf(0x0846, 0x9053)?.driver)    // Netgear A6210
        assertEquals(UsbWifi.Driver.RTL88X2BU, UsbWifi.chipOf(0x0846, 0x9055)?.driver)  // Netgear A6150 stays 88x2bu
        assertNull(UsbWifi.chipOf(0x0cf3, 0x7010)) // AR7010: other firmware, not supported
        assertEquals(SerialPort.Chip.FREEWILI, SerialPort.chipOf(0x093C, 0x205A))
    }

    // --- RTL8814AU: | RX descriptor (24) | PHY status (drv * 8) | frame + FCS |, padded to 8 ---

    private fun rtlTransfer(frame: ByteArray, crc: Boolean = false, rate: Int = 0x0c, power: Int = 0): ByteArray {
        val pkt = frame.size + 4
        val desc = ByteArray(24)
        le32(pkt or (if (crc) 1 shl 14 else 0) or (1 shl 16)).copyInto(desc, 0) // 1 x 8 bytes of PHY status
        desc[12] = rate.toByte()
        val phy = ByteArray(8); phy[4] = power.toByte()
        val body = desc + phy + frame + byteArrayOf(9, 9, 9, 9)
        return body + ByteArray((8 - body.size % 8) % 8)
    }

    @Test
    fun rtl8814auFramesWithoutFcsAndOfdmSignal() {
        val a = beacon("00:c0:ca:00:00:01", "Unit-1", 36)
        val b = beacon("00:c0:ca:00:00:02", "Unit-2", 149)
        val t = rtlTransfer(a, power = 100) + rtlTransfer(b, crc = true)
        val got = mutableListOf<Triple<Int, Int, Int?>>(); var bad = 0
        Rtl8814auMonitor.forEachFrame(t, t.size, onBad = { if (it) bad++ }) { s, e, r -> got += Triple(s, e, r) }
        assertEquals(1, got.size); assertEquals(1, bad)
        assertEquals(32, got[0].first)
        assertEquals(32 + a.size, got[0].second)
        assertEquals(((100 shr 1) and 0x7f) - 110, got[0].third)
        assertEquals("Unit-1", ssidOf(t, got[0].first, got[0].second, 36))
    }

    // --- MT7612U: | DMA header (4) | RXWI (32) | 802.11 header | L2 pad | body | ---

    private fun mtTransfer(frame: ByteArray, l2pad: Boolean = false, crc: Boolean = false, rssi: Int = -55): ByteArray {
        val rxwi = ByteArray(32)
        le32((if (l2pad) 1 shl 14 else 0) or (if (crc) 1 shl 8 else 0)).copyInto(rxwi, 0)
        le32(frame.size shl 16).copyInto(rxwi, 4)
        rxwi[12] = rssi.toByte()
        val payload = if (l2pad) frame.copyOfRange(0, 24) + byteArrayOf(0, 0) + frame.copyOfRange(24, frame.size) else frame
        val body = rxwi + payload
        val padded = body + ByteArray((4 - body.size % 4) % 4)
        return le16(padded.size) + byteArrayOf(0, 0) + padded + ByteArray(4)
    }

    @Test
    fun mt7612uFrameAndL2PadRemoval() {
        val f = beacon("00:c0:ca:00:00:03", "Patrol", 6)
        val plain = mtTransfer(f)
        val p = Mt7612uMonitor.parseRx(plain, plain.size)!!
        assertEquals(36, p.start); assertEquals(36 + f.size, p.end); assertEquals(-55, p.rawRssi)
        assertEquals("Patrol", ssidOf(plain, p.start, p.end, 6))

        val padded = mtTransfer(f, l2pad = true)
        val q = Mt7612uMonitor.parseRx(padded, padded.size)!!
        assertEquals(q.start + f.size, q.end)
        assertEquals("Patrol", ssidOf(padded, q.start, q.end, 6)) // the header moved up over the pad
    }

    @Test
    fun mt7612uDropsBadChecksums() {
        val t = mtTransfer(beacon("00:c0:ca:00:00:03", "X", 6), crc = true)
        var crc = false
        assertNull(Mt7612uMonitor.parseRx(t, t.size, onBad = { crc = it }))
        assertTrue(crc)
    }

    // --- AR9271: stream of | len | 0x4e00 | HTC header (8) | RX status (40) | frame + FCS |, padded to 4 ---

    private fun athPacket(frame: ByteArray, status: Int = 0, rssi: Int = 30): ByteArray {
        val dlen = frame.size + 4
        val rs = ByteArray(40)
        rs[8] = (dlen ushr 8).toByte(); rs[9] = dlen.toByte(); rs[10] = status.toByte(); rs[12] = rssi.toByte()
        val payload = rs + frame + byteArrayOf(1, 2, 3, 4)
        val htc = byteArrayOf(3, 0, (payload.size ushr 8).toByte(), payload.size.toByte(), 0, 0, 0, 0)
        val pkt = htc + payload
        return le16(pkt.size) + le16(0x4e00) + pkt + ByteArray((4 - pkt.size % 4) % 4)
    }

    @Test
    fun ar9271SignalIsRelativeToTheNoiseFloor() {
        val f = beacon("00:c0:ca:00:00:04", "Cam-9", 11)
        val s = athPacket(f, rssi = 30) + athPacket(f, status = 1)
        val got = mutableListOf<Triple<Int, Int, Int?>>(); var crc = 0
        val rest = Ar9271Monitor.forEachFrame(s, s.size, -95, onBad = { if (it) crc++ }) { a, b, r -> got += Triple(a, b, r) }
        assertNull(rest)
        assertEquals(1, got.size); assertEquals(1, crc)
        assertEquals(-95 + 30, got[0].third)
        assertEquals(got[0].first + f.size, got[0].second) // FCS removed
        assertEquals("Cam-9", ssidOf(s, got[0].first, got[0].second, 11))
    }

    @Test
    fun ar9271PacketsSpanningTwoTransfersAreReassembled() {
        val f = beacon("00:c0:ca:00:00:05", "Split", 1)
        val stream = athPacket(f) + athPacket(f)
        val cut = stream.size - 30
        val first = stream.copyOfRange(0, cut); val second = stream.copyOfRange(cut, stream.size)
        var count = 0
        val carry = Ar9271Monitor.forEachFrame(first, first.size, -95) { _, _, _ -> count++ }
        assertEquals(1, count)
        assertNotNull(carry)
        val joined = carry!! + second
        assertNull(Ar9271Monitor.forEachFrame(joined, joined.size, -95) { _, _, _ -> count++ })
        assertEquals(2, count)
    }

    // --- FREE-WiLi 2 ---

    @Test
    fun freeWiliScanLines() {
        val out = FreeWiliReports.parseScan(
            "\u001B[32m*wifiscan 1 WPA2 aa:bb:cc:dd:ee:ff -61 6 20 0 Coffee Shop x]\r\n" +
            "*wifiscan 2 OPEN 11:22:33:44:55:66 -80 149 20 0 x]\r\n" +
            "Scan for Access Points\r\n")
        assertEquals(2, out.size)
        val a = out.first { it.mac == "AA:BB:CC:DD:EE:FF" }
        assertEquals("Coffee Shop", a.name); assertEquals(-61, a.rssi); assertEquals(2437, a.frequencyMhz)
        val b = out.first { it.mac == "11:22:33:44:55:66" }
        assertNull(b.name); assertEquals(5745, b.frequencyMhz)
        assertTrue(FreeWiliReports.isWifiMenu("1) Wifi Functions"))
    }

    // --- firmware: the official linux-firmware images, unmodified ---

    private fun sha1(path: String) = MessageDigest.getInstance("SHA-1").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun firmwareImagesAreTheLinuxFirmwareOnes() {
        val dir = "src/main/assets/usbwifi"
        assertEquals("a28c063e6e488444d67607254f65b3ac4ab14717", sha1("$dir/mt7662.bin"))
        assertEquals("71b764a6911af6e092bcd2c98e663888fd5d71ea", sha1("$dir/mt7662_rom_patch.bin"))
        assertEquals("62686323432b63ec8091234f9b1b9ee6d746eec4", sha1("$dir/htc_9271-1.4.0.fw"))
        assertTrue(File("$dir/LICENCE.ralink_a_mediatek_company_firmware").readText().contains("MediaTek"))
        assertTrue(File("$dir/LICENCE.open-ath9k-htc-firmware").readText().contains("Qualcomm Atheros"))
    }
}
