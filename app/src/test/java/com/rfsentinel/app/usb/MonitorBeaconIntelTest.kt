package com.rfsentinel.app.usb

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.WifiFingerprint
import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorBeaconIntelTest {

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()
    private fun ie(id: Int, vararg v: Int) = byteArrayOf(id.toByte(), v.size.toByte()) + v.map { it.toByte() }.toByteArray()

    private val ap = "00:30:44:11:22:33"
    private val laptop = "3c:22:fb:00:00:02"
    private val hours3 = 3L * 3600 * 1_000_000

    /** A beacon: clock [tsf] (µs), capability [cap], then [elements]. */
    private fun beacon(tsf: Long, cap: Int, elements: ByteArray): ByteArray {
        val h = ByteArray(24); h[0] = 0x80.toByte()
        mac("ff:ff:ff:ff:ff:ff").copyInto(h, 4); mac(ap).copyInto(h, 10); mac(ap).copyInto(h, 16)
        val fixed = ByteArray(12)
        for (k in 0 until 8) fixed[k] = (tsf shr (8 * k)).toByte()
        fixed[8] = 100; fixed[10] = cap.toByte()
        return h + fixed + ie(0, *"IBR900-233".map { it.code }.toIntArray()) + elements
    }

    private fun data(toDs: Boolean, a1: String, a2: String, size: Int): ByteArray {
        val h = ByteArray(size); h[0] = 0x08; h[1] = if (toDs) 0x01 else 0x02
        mac(a1).copyInto(h, 4); mac(a2).copyInto(h, 10); mac(ap).copyInto(h, 16)
        return h
    }

    // RSN: version 1, group CCMP, 1 pairwise CCMP, 2 key managements PSK + SAE.
    private val rsnPskSae = ie(48, 1, 0, 0, 0x0F, 0xAC, 4, 1, 0, 0, 0x0F, 0xAC, 4, 2, 0, 0, 0x0F, 0xAC, 2, 0, 0x0F, 0xAC, 8)
    // HT capabilities: info, A-MPDU, MCS set with 2 streams.
    private val ht2 = ie(45, *IntArray(26).also { it[3] = 0xFF; it[4] = 0xFF })
    // VHT capabilities: info, then RX MCS map 0xFFFA (streams 1-2).
    private val vht2 = ie(191, 0, 0, 0, 0, 0xFA, 0xFF, 0, 0, 0xFA, 0xFF, 0, 0)

    @Test
    fun beaconGivesUptimeSecurityGenerationStreamsAndCountry() {
        val t = MonitorFrames()
        val now = 10_000_000L
        val b = beacon(hours3, 0x11, rsnPskSae + ht2 + vht2 + ie(7, 'U'.code, 'S'.code, 0x20))
        t.frame(b, 0, b.size, 36, -60, now)
        val s = t.drain(now, 5000).single()
        assertEquals(now - hours3 / 1000, s.upSince)
        assertEquals("[RSN-PSK+SAE][ESS]", s.capabilities)
        assertEquals("WPA3", DeviceIntel.security(s.capabilities))
        assertEquals("WiFi 5 (802.11ac)", s.standard)
        val info = WifiFingerprint.parse(s.infoElements)
        assertEquals(2, info.streams)
        assertEquals("US", info.country)
    }

    @Test
    fun securityTextMatchesThePhoneScan() {
        val eap = ie(48, 1, 0, 0, 0x0F, 0xAC, 4, 1, 0, 0, 0x0F, 0xAC, 4, 1, 0, 0, 0x0F, 0xAC, 1).let { MonitorFrames.elements(it, 0, it.size) }
        assertTrue(MonitorFrames.capabilities(eap, true).contains("EAP"))
        assertEquals("WEP (insecure)", DeviceIntel.security(MonitorFrames.capabilities(emptyList(), true)))
        assertEquals("Open (no password)", DeviceIntel.security(MonitorFrames.capabilities(emptyList(), false)))
        // A broken element still reads as protected, never as open.
        assertEquals("WPA2", DeviceIntel.security(MonitorFrames.capabilities(listOf(48 to byteArrayOf(1, 0)), true)))
    }

    @Test
    fun clientsAreLinkedAndTheRoutersFramesDontLendThemItsSignal() {
        val t = MonitorFrames()
        val b = beacon(hours3, 0x11, rsnPskSae)
        t.frame(b, 0, b.size, 36, -40, 0)
        val up = data(true, ap, laptop, 300)
        t.frame(up, 0, up.size, 36, -85, 10)
        val down = data(false, laptop, ap, 1500)
        t.frame(down, 0, down.size, 36, -40, 20)
        val byMac = t.drain(20, 5000).associateBy { it.mac }
        val client = byMac.getValue(laptop)
        assertEquals(ap, client.bssid)
        assertEquals(-85, client.rssi) // not the router's -40
        assertEquals(300L, client.dataBytes)
        assertEquals(1500L, byMac.getValue(ap).dataBytes)
    }

    @Test
    fun devicePageShowsTheLinkBothWaysAndTheUptime() {
        DeviceRegistry.startSession()
        try {
            val now = System.currentTimeMillis()
            val apMac = "00:30:44:11:22:33"; val clientMac = "3C:22:FB:00:00:02"
            DeviceRegistry.report(Advert(apMac, Advert.Source.WIFI, -60, "IBR900-233",
                wifi = Advert.WifiInfo(5180, "[RSN-PSK][ESS]", null, emptyList(), upSince = now - 3 * 3_600_000L, dataBytes = 2_500_000)),
                emptyList(), DeviceIntel.Identity("Vehicle / cellular router", emptyList()), "CradlePoint, Inc", null, null, now)
            DeviceRegistry.report(Advert(clientMac, Advert.Source.WIFI, -70, null,
                wifi = Advert.WifiInfo(5180, "", null, emptyList(), client = true, bssid = apMac)),
                emptyList(), DeviceIntel.Identity("Laptop", emptyList()), "Panasonic", null, null, now)
            // The phone's own scan of the router (no USB fields) doesn't wipe them.
            DeviceRegistry.report(Advert(apMac, Advert.Source.WIFI, -60, "IBR900-233",
                wifi = Advert.WifiInfo(5180, "[RSN-PSK][ESS]", null, emptyList())),
                emptyList(), DeviceIntel.Identity("Vehicle / cellular router", emptyList()), "CradlePoint, Inc", null, null, now)
            val router = DeviceRegistry.get(apMac)!!.facts.toMap()
            assertTrue(router["WiFi on for"]!!.startsWith("3 h 0 min"))
            assertEquals("1: Laptop - Panasonic ($clientMac)", router["Devices connected"])
            assertEquals("2.5 MB", router["Data heard by USB adapter"])
            assertEquals("\"IBR900-233\" ($apMac)", DeviceRegistry.get(clientMac)!!.facts.toMap()["Connected to"])
        } finally {
            DeviceRegistry.clear()
        }
    }
}
