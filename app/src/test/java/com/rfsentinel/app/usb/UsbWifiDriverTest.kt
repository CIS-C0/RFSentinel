package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbWifiDriverTest {

    // --- 802.11 frames ---

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    private fun beacon(bssid: String, ssid: String, vararg ies: Pair<Int, ByteArray>): ByteArray {
        val hdr = ByteArray(24)
        hdr[0] = 0x80.toByte() // management, beacon
        System.arraycopy(mac("ff:ff:ff:ff:ff:ff"), 0, hdr, 4, 6)
        System.arraycopy(mac(bssid), 0, hdr, 10, 6)
        System.arraycopy(mac(bssid), 0, hdr, 16, 6)
        val fixed = ByteArray(12)
        val body = mutableListOf<Byte>()
        body += 0.toByte(); body += ssid.length.toByte(); body += ssid.toByteArray().toList()
        for ((tag, v) in ies) { body += tag.toByte(); body += v.size.toByte(); body += v.toList() }
        return hdr + fixed + body.toByteArray()
    }

    private fun probeRequest(sa: String): ByteArray {
        val f = ByteArray(24 + 2)
        f[0] = 0x40 // management, probe request
        System.arraycopy(mac("ff:ff:ff:ff:ff:ff"), 0, f, 4, 6)
        System.arraycopy(mac(sa), 0, f, 10, 6)
        System.arraycopy(mac("ff:ff:ff:ff:ff:ff"), 0, f, 16, 6)
        return f
    }

    private fun dataToAp(sa: String, bssid: String): ByteArray {
        val f = ByteArray(24 + 8)
        f[0] = 0x08; f[1] = 0x01 // data, to-DS
        System.arraycopy(mac(bssid), 0, f, 4, 6)
        System.arraycopy(mac(sa), 0, f, 10, 6)
        System.arraycopy(mac("01:00:5e:00:00:fb"), 0, f, 16, 6)
        return f
    }

    @Test
    fun beaconsUseTheirOwnChannelOverTheTunedOne() {
        val t = MonitorFrames()
        val b = beacon("00:30:44:11:22:33", "Patrol-12", 3 to byteArrayOf(6))
        t.frame(b, 0, b.size, channel = 7, rssi = -70, now = 1000)
        val out = t.drain(1000, 5000)
        assertEquals(1, out.size)
        val s = out[0]
        assertEquals("00:30:44:11:22:33", s.mac)
        assertEquals("Patrol-12", s.ssid)
        assertTrue(s.isAccessPoint)
        assertEquals(2437, s.frequencyMhz)
        assertEquals(-70, s.rssi)
    }

    @Test
    fun fiveGhzBeaconTakesTheHtPrimaryChannel() {
        val t = MonitorFrames()
        val b = beacon("00:30:44:aa:bb:cc", "", 61 to ByteArray(22).also { it[0] = 44 })
        t.frame(b, 0, b.size, channel = 40, rssi = -80, now = 0)
        assertEquals(5220, t.drain(0, 5000).single().frequencyMhz)
    }

    @Test
    fun clientsAreTrackedWithTheirAccessPoint() {
        val t = MonitorFrames()
        val p = probeRequest("da:a1:19:00:00:01")
        t.frame(p, 0, p.size, channel = 1, rssi = -60, now = 0)
        val d = dataToAp("3c:22:fb:00:00:02", "00:30:44:11:22:33")
        t.frame(d, 0, d.size, channel = 36, rssi = -65, now = 0)
        val out = t.drain(0, 5000).associateBy { it.mac }
        assertFalse(out.getValue("da:a1:19:00:00:01").isAccessPoint)
        assertEquals(2412, out.getValue("da:a1:19:00:00:01").frequencyMhz)
        assertEquals("00:30:44:11:22:33", out.getValue("3c:22:fb:00:00:02").bssid)
        assertEquals(5180, out.getValue("3c:22:fb:00:00:02").frequencyMhz)
        assertEquals(0 to 2, t.live(0, 1000))
    }

    @Test
    fun clientCaptureCanBeTurnedOff() {
        val t = MonitorFrames().apply { captureClients = false }
        val p = probeRequest("da:a1:19:00:00:01")
        t.frame(p, 0, p.size, channel = 1, rssi = -60, now = 0)
        assertTrue(t.drain(0, 5000).isEmpty())
    }

    @Test
    fun groupAddressesAndShortFramesAreIgnored() {
        val t = MonitorFrames()
        val b = beacon("01:00:5e:00:00:01", "x")
        t.frame(b, 0, b.size, 1, -50, 0)
        val zero = beacon("00:00:00:00:00:00", "x")
        t.frame(zero, 0, zero.size, 1, -50, 0)
        t.frame(ByteArray(10), 0, 10, 1, -50, 0)
        assertEquals(0, t.tracked)
    }

    @Test
    fun signalFollowsTheLastFewSecondsAndOldDevicesArePruned() {
        val t = MonitorFrames()
        val b = beacon("00:30:44:11:22:33", "a")
        t.frame(b, 0, b.size, 1, -40, now = 0)
        t.frame(b, 0, b.size, 1, -70, now = 500)
        assertEquals(-40, t.drain(500, 5000).single().rssi) // strongest of the recent burst
        t.frame(b, 0, b.size, 1, -75, now = 5000)
        assertEquals(-75, t.drain(5000, 5000).single().rssi) // the car moved away
        assertTrue(t.drain(5000 + MonitorFrames.PRUNE_MS + 1, 5000).isEmpty())
        assertEquals(0, t.tracked)
    }

    @Test
    fun framesWithoutASignalReadingKeepTheLastRealOne() {
        val t = MonitorFrames()
        val b = beacon("c0:94:35:fc:65:48", "Alaindion", 3 to byteArrayOf(1))
        t.frame(b, 0, b.size, 1, rssi = null, now = 0)
        assertEquals(MonitorFrames.NOMINAL_RSSI, t.drain(0, 5000).single().rssi) // nothing measured yet
        t.frame(b, 0, b.size, 1, rssi = -82, now = 100)                            // the first real reading wins at once
        t.frame(b, 0, b.size, 1, rssi = null, now = 200)
        assertEquals(-82, t.drain(200, 5000).single().rssi)
    }

    @Test
    fun channelFrequencies() {
        assertEquals(2412, MonitorFrames.frequencyOf(1))
        assertEquals(2472, MonitorFrames.frequencyOf(13))
        assertEquals(2484, MonitorFrames.frequencyOf(14))
        assertEquals(5180, MonitorFrames.frequencyOf(36))
        assertEquals(5825, MonitorFrames.frequencyOf(165))
        assertEquals(0, MonitorFrames.frequencyOf(0))
    }

    // --- 8822B RX path ---

    private fun le32(b: ByteArray, at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v ushr (8 * k)).toByte() }

    /** One RX descriptor + optional 32-byte PHY status + frame (+ FCS), padded to 8 bytes. */
    private fun rxUnit(frame: ByteArray, crcErr: Boolean = false, c2h: Boolean = false, pwdb: Int? = null, rate: Int = 4): ByteArray {
        val drv = if (pwdb != null) 32 else 0
        val pkt = frame.size + 4
        val len = (24 + drv + pkt + 7) and 7.inv()
        val u = ByteArray(len)
        var d0 = pkt or ((drv / 8) shl 16)
        if (crcErr) d0 = d0 or (1 shl 14)
        if (pwdb != null) d0 = d0 or (1 shl 26)
        le32(u, 0, d0)
        if (c2h) u[11] = 0x10
        u[12] = rate.toByte()
        if (pwdb != null) { u[24] = (if (rate <= 3) 0 else 1).toByte(); u[24 + 1] = pwdb.toByte(); u[24 + 2] = (pwdb - 6).toByte() }
        System.arraycopy(frame, 0, u, 24 + drv, frame.size)
        return u
    }

    @Test
    fun aggregatedRxBufferIsWalkedAndBadFramesSkipped() {
        val good = beacon("00:30:44:11:22:33", "Good", 3 to byteArrayOf(11))
        val bad = beacon("00:30:44:44:55:66", "Bad")
        val c2h = beacon("00:30:44:77:88:99", "Firmware")
        val buf = rxUnit(good, pwdb = 110 - 58) + rxUnit(bad, crcErr = true) + rxUnit(c2h, c2h = true) +
            rxUnit(probeRequest("da:a1:19:00:00:01"))
        val t = MonitorFrames()
        Rtl8822buMonitor.parseRxBuf(buf, buf.size, ch = 11, twoPaths = true, sink = t)
        val out = t.drain(System.currentTimeMillis(), 60_000).associateBy { it.mac }
        assertEquals(setOf("00:30:44:11:22:33", "da:a1:19:00:00:01"), out.keys)
        assertEquals(-58, out.getValue("00:30:44:11:22:33").rssi) // strongest path from the PHY status
        assertEquals(-60, out.getValue("da:a1:19:00:00:01").rssi) // no PHY status: nominal
    }

    @Test
    fun singleAntennaOnPathBIsDetected() {
        // Wise Tiger 8812BU: one antenna, on path B; path A hears 25-35 dB less (measured).
        val s = Rtl8822buMonitor.PathSense()
        repeat(39) { s.add(-70, -40) }
        assertNull(s.verdict()) // not enough frames yet
        s.add(-70, -40)
        assertEquals(1, s.verdict())
        // Noise-floor frames don't vote.
        val quiet = Rtl8822buMonitor.PathSense()
        repeat(100) { quiet.add(-110, -100) }
        assertNull(quiet.verdict())
    }

    @Test
    fun twoAntennaAdaptersKeepBothPaths() {
        val s = Rtl8822buMonitor.PathSense()
        val r = java.util.Random(7)
        // Both paths connected: multipath fades swing either way, rarely by 10 dB.
        repeat(2000) { val a = -60 + r.nextInt(17) - 8; s.add(a, -60 + r.nextInt(17) - 8) }
        repeat(30) { s.add(-50, -62) }; repeat(30) { s.add(-62, -50) }
        assertNull(s.verdict())
    }

    @Test
    fun ofdmFramesFeedThePathVote() {
        val ofdm = rxUnit(beacon("00:30:44:11:22:33", "x"), pwdb = 40, rate = 4) // path A 40, B 34 (helper)
        ofdm[24 + 1] = 40; ofdm[24 + 2] = 72                                       // A -70 dBm, B -38 dBm
        val s = Rtl8822buMonitor.PathSense()
        val t = MonitorFrames()
        repeat(40) { Rtl8822buMonitor.parseRxBuf(ofdm, ofdm.size, ch = 36, twoPaths = true, sink = t, sense = s) }
        assertEquals(1, s.verdict())
        assertEquals(-38, t.drain(System.currentTimeMillis(), 60_000).single().rssi)
    }

    @Test
    fun truncatedRxBufferStopsCleanly() {
        val u = rxUnit(beacon("00:30:44:11:22:33", "x"))
        val t = MonitorFrames()
        Rtl8822buMonitor.parseRxBuf(u, u.size - 12, ch = 1, twoPaths = false, sink = t)
        assertEquals(0, t.tracked)
    }

    @Test
    fun rxDescriptorFields() {
        val u = rxUnit(beacon("00:30:44:11:22:33", "x"), pwdb = 50, rate = 2)
        val d = Rtl8822buMonitor.rxDesc(u, 0)
        assertEquals(32, d.drvInfo)
        assertTrue(d.physt)
        assertEquals(2, d.rate)
        assertEquals(24 + 32, d.frameOffset)
        assertEquals(u.size, d.next)
        assertEquals(listOf(-60), Rtl8822buMonitor.phyPaths(u, 24, paths = 2)!!.toList()) // page 0: one CCK value
        val ofdm = ByteArray(32).also { it[0] = 1; it[1] = 70; it[2] = 0 }
        assertEquals(listOf(-40, Rtl8822buMonitor.NO_SIGNAL), Rtl8822buMonitor.phyPaths(ofdm, 0, paths = 2)!!.toList())
        // A corrupt report (seen on CCK frames after moving CCK to path B) isn't a +141 dBm signal.
        val bad = ByteArray(32).also { it[1] = 251.toByte() }
        assertEquals(listOf(Rtl8822buMonitor.NO_SIGNAL), Rtl8822buMonitor.phyPaths(bad, 0, paths = 1)!!.toList())
    }

    @Test
    fun txDescriptorChecksumMatchesTheLinuxDriver() {
        // The kernel's H2C GENERAL_INFO descriptor: TXPKTSIZE 32, QSEL 0x13 -> checksum 0x1320.
        val d = ByteArray(48)
        Rtl8822buMonitor.setDesc(d, 0x00, 0, 16, 32)
        Rtl8822buMonitor.setDesc(d, 0x04, 8, 5, 0x13)
        Rtl8822buMonitor.txDescChecksum(d)
        assertEquals(0x00000020, Rtl8822bTables.le32(d, 0))
        assertEquals(0x00001300, Rtl8822bTables.le32(d, 4))
        assertEquals(0x1320, Rtl8822bTables.le32(d, 0x1C) and 0xffff)
    }

    // --- channels ---

    @Test
    fun hopPlanCoversEveryChannelAndAllRadarChannelsInTurn() {
        val first = Rtl8822buMonitor.hopCycle(0)
        assertEquals(24, first.size)
        assertEquals(1, first[0])
        assertTrue(first.all { MonitorFrames.validChannel(it) })
        val dfsSeen = (0 until 8).flatMap { Rtl8822buMonitor.hopCycle(it).toList() }.toSet()
        assertTrue(Rtl8822buMonitor.HOP_DFS.all { it in dfsSeen })
        assertTrue(Rtl8822buMonitor.HOP_5.all { it in first } && Rtl8822buMonitor.HOP_24.all { it in first })
    }

    @Test
    fun rf18SetsTheBandExplicitly() {
        // 2.4 -> 5 GHz: the band bits come from the channel, not from what the radio was left on.
        assertEquals(0x10d24, Rtl8822buMonitor.rf18For(0x00c01, 36))  // the vendor's 5 GHz value
        assertEquals(0x00c09, Rtl8822buMonitor.rf18For(0x10d24, 9))   // and its clean 2.4 GHz one
        assertEquals(0x30d64, Rtl8822buMonitor.rf18For(0x00c01, 100)) // >= ch 80: sub-band bit 17
        assertEquals(0x50d95, Rtl8822buMonitor.rf18For(0x00c01, 149)) // > ch 144: bit 18
        assertEquals(0x07c06, Rtl8822buMonitor.rf18For(0x0f201, 6) and 0xfffff) // other bits kept, BW forced to 20 MHz
        assertEquals(0x00c01, Rtl8822buMonitor.rf18For(0x18c01, 1))   // bring-up read-back: 5 GHz band + LCK bits dropped
    }

    @Test
    fun statsWindowCountsFramesAndResets() {
        val t = MonitorFrames()
        val b = beacon("00:30:44:11:22:33", "Patrol-12", 3 to byteArrayOf(6))
        t.frame(b, 0, b.size, 6, -70, 0); t.frame(b, 0, b.size, 6, -60, 100)
        val w = t.window()
        assertEquals(1, w.size)
        assertTrue(w[0], w[0].contains("2 fr max  -60 avg  -65 AP"))
        assertTrue(t.window().isEmpty())
    }

    @Test
    fun channelConstantsMatchThePhydmTables() {
        assertEquals(0, Rtl8822buMonitor.agcBucket(6))
        assertEquals(1, Rtl8822buMonitor.agcBucket(36))
        assertEquals(2, Rtl8822buMonitor.agcBucket(120))
        assertEquals(3, Rtl8822buMonitor.agcBucket(165))
        assertEquals(0x96a, Rtl8822buMonitor.fcFor(1))
        assertEquals(0x494, Rtl8822buMonitor.fcFor(44))
        assertEquals(0x453, Rtl8822buMonitor.fcFor(60))
        assertEquals(0x452, Rtl8822buMonitor.fcFor(112))
        assertEquals(0x412, Rtl8822buMonitor.fcFor(149))
        assertEquals(0x0, Rtl8822buMonitor.rfBeFor(11))
        assertEquals(0x7, Rtl8822buMonitor.rfBeFor(36))
        assertEquals(0x6, Rtl8822buMonitor.rfBeFor(100))
        assertEquals(0x5, Rtl8822buMonitor.rfBeFor(149))
        assertEquals(0x0, Rtl8822buMonitor.rfBeFor(165))
    }

    // --- tables and firmware ---

    @Test
    fun tableWalkerPicksTheBlockForThisRfeAndCut() {
        val t = intArrayOf(
            0x111, 1,
            0x80000000.toInt(), 0, 0x40000000, 0,   // IF rfe 0
            0x222, 2,
            0x90000003.toInt(), 0, 0x40000000, 0,   // ELSE IF rfe 3
            0x333, 3,
            0xA0000000.toInt(), 0,                  // ELSE
            0x444, 4,
            0xB0000000.toInt(), 0,                  // ENDIF
            0x555, 5,
            0x8A000000.toInt(), 0, 0x40000000, 0,   // IF cut 10 (any rfe 0)
            0x666, 6,
            0xB0000000.toInt(), 0
        )
        fun run(cut: Int, rfe: Int) = mutableListOf<Int>().also { out -> Rtl8822bTables.walk(t, cut, rfe) { a, _ -> out += a } }
        assertEquals(listOf(0x111, 0x222, 0x555), run(cut = 2, rfe = 0))
        assertEquals(listOf(0x111, 0x333, 0x555), run(cut = 2, rfe = 3))
        assertEquals(listOf(0x111, 0x444, 0x555), run(cut = 2, rfe = 5))
        assertEquals(listOf(0x111, 0x222, 0x555, 0x666), run(cut = 10, rfe = 0))
    }

    private fun asset(name: String) = File("src/main/assets/usbwifi/rtl8822b_$name.bin").readBytes()
    private fun words(name: String) = asset(name).let { b -> IntArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) } }

    @Test
    fun bundledFirmwareIsComplete() {
        val fw = asset("fw")
        Rtl8822bTables.checkFirmware(fw)
        assertEquals(0x200000, Rtl8822bTables.dmemAddr(fw))
        assertEquals(0, Rtl8822bTables.imemAddr(fw))
        try {
            Rtl8822bTables.checkFirmware(fw.copyOf(fw.size - 1)); throw AssertionError("damaged image accepted")
        } catch (_: IOException) {}
    }

    @Test
    fun bundledTablesWalkToPlainRegisterWrites() {
        for (name in listOf("phy_reg", "agc_tab", "radioa", "radiob")) {
            val tab = words(name)
            assertEquals(0, tab.size % 2)
            for (rfe in listOf(0, 1, 3, 5)) {
                var n = 0
                Rtl8822bTables.walk(tab, cut = 2, rfe = rfe) { a, _ ->
                    assertEquals("$name rfe $rfe: condition word leaked as a write", 0, a and 0xC0000000.toInt())
                    n++
                }
                assertTrue("$name rfe $rfe applies nothing", n > 100)
            }
        }
        // The AGC table differs per antenna front-end; the walker must actually choose.
        val agc = words("agc_tab")
        fun count(rfe: Int) = mutableListOf<Long>().also { l -> Rtl8822bTables.walk(agc, 2, rfe) { a, v -> l += (a.toLong() shl 32) or (v.toLong() and 0xffffffffL) } }
        assertFalse(count(0) == count(5))
    }

    // --- adapter list ---

    @Test
    fun adaptersMapToTheirDrivers() {
        assertEquals(UsbWifi.Driver.RTL88X2BU, UsbWifi.chipOf(0x0bda, 0xb812)?.driver)
        assertEquals("RTL8812BU", UsbWifi.chipOf(0x0bda, 0xb812)?.name)
        assertEquals(UsbWifi.Driver.RTL88X2BU, UsbWifi.chipOf(0x2357, 0x012d)?.driver) // TP-Link Archer T3U
        assertEquals(UsbWifi.Driver.RTL88XXAU, UsbWifi.chipOf(0x0bda, 0x0811)?.driver) // ALFA AWUS036ACS
        assertNull(UsbWifi.chipOf(0x0bda, 0x8812))
    }
}
