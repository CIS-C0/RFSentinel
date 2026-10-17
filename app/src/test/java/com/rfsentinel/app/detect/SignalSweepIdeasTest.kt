package com.rfsentinel.app.detect

import com.rfsentinel.app.usb.DeauthWatch
import com.rfsentinel.app.usb.KarmaWatch
import com.rfsentinel.app.usb.MonitorFrames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Ideas from SignalSweep (GPL-3.0, facts only): the Flock camera's wildcard probe, newer Flock module
 * prefixes, Axon pairing networks, Karma access points, the second Flock battery service, the Penguin
 * serial, a SmartTag in setup, fake-tracker floods, the STM32WB hint, Apple activity and a deauth alarm
 * that a normal network's burst can't trip.
 */
class SignalSweepIdeasTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    @Before
    fun setUp() { HackerWatch.reset(); KarmaWatch.clear(); KarmaWatch.onAttack = null; DeauthWatch.clear(); DeauthWatch.onAttack = null }

    @After
    fun tearDown() = setUp()

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** A management frame: [fc0] subtype byte, addresses, [fixed] fixed-field bytes, then [ies]. */
    private fun mgmt(fc0: Int, a1: String, a2: String, a3: String, fixed: Int, ies: ByteArray): ByteArray {
        val h = ByteArray(24); h[0] = fc0.toByte()
        mac(a1).copyInto(h, 4); mac(a2).copyInto(h, 10); mac(a3).copyInto(h, 16)
        return h + ByteArray(fixed) + ies
    }

    private fun ssidIe(name: String) = byteArrayOf(0, name.length.toByte()) + name.toByteArray()
    private val rates = byteArrayOf(1, 8, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24)
    private val flockIe = byteArrayOf(221.toByte(), 7, 0x50, 0x6F, 0x9A.toByte(), 0x16, 0x03, 0x01, 0x03)
    private val everyone = "ff:ff:ff:ff:ff:ff"

    private fun client(mac: String, wildcard: Boolean, ie: Boolean) = Advert(mac, Advert.Source.WIFI, -70,
        wifi = Advert.WifiInfo(2437, "", null, emptyList(), client = true, wildcardProbe = wildcard, flockProbeIe = ie))

    private fun ap(mac: String, ssid: String) = Advert(mac, Advert.Source.WIFI, -60, name = ssid,
        wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList()))

    private fun alpr(a: Advert) = SignatureEngine.classify(a).firstOrNull { it.category == Category.ALPR }

    // ---- Flock wildcard probe

    @Test
    fun theAdapterRecordsAWildcardProbeAndTheFlockElement() {
        val t = MonitorFrames()
        val cam = "14:b5:cd:12:34:56"
        val probe = mgmt(0x40, everyone, cam, everyone, 0, ssidIe("") + rates + flockIe)
        t.frame(probe, 0, probe.size, 6, -70, 0)
        val s = t.drain(0, 5000).single()
        assertFalse(s.isAccessPoint)
        assertTrue(s.wildcardProbe)
        assertTrue(s.flockProbeIe)
        // A laptop asking for its home network by name: neither.
        val t2 = MonitorFrames()
        val named = mgmt(0x40, everyone, "3c:22:fb:00:00:02", everyone, 0, ssidIe("HomeNet") + rates)
        t2.frame(named, 0, named.size, 6, -70, 0)
        val l = t2.drain(0, 5000).single()
        assertFalse(l.wildcardProbe); assertFalse(l.flockProbeIe)
    }

    @Test
    fun aWildcardProbeFromAFlockModuleIsAFlockCamera() {
        val bare = alpr(client("14:B5:CD:12:34:56", wildcard = true, ie = false))
        assertEquals("Possible Flock camera (probe request)", bare?.label)
        assertEquals(60, bare?.confidence)
        val withIe = alpr(client("E0:0A:F6:12:34:56", wildcard = true, ie = true))
        assertEquals("Flock Safety camera (probe request)", withIe?.label)
        assertEquals(75, withIe?.confidence)
    }

    @Test
    fun aWildcardProbeAloneOrFromAGenericChipIsNothing() {
        assertNull(alpr(client("3C:22:FB:12:34:56", wildcard = true, ie = true)))  // not a Flock module
        assertNull(alpr(client("A4:CF:12:12:34:56", wildcard = true, ie = true)))  // Espressif: in every gadget
        assertNull(alpr(client("82:6B:F2:12:34:56", wildcard = true, ie = true)))  // a randomized address
        assertNull(alpr(client("14:B5:CD:12:34:56", wildcard = false, ie = false))) // asked for networks by name
    }

    @Test
    fun theNewFlockPrefixesCountLikeTheOldOnes() {
        for (p in listOf("14:B5:CD", "04:0D:84", "1C:34:F1", "38:5B:44", "94:34:69", "B4:E3:F9", "F0:82:C0", "E0:0A:F6")) {
            assertEquals(p, "Possible Flock camera (hidden network)", alpr(ap("$p:00:00:01", ""))?.label)
            assertEquals(p, "Flock Safety camera", alpr(ap("$p:00:00:01", "Falcon_12"))?.label)
        }
    }

    @Test
    fun theDoubtfulFlockPrefixesAreVeryWeakAndNeverAlert() {
        for (p in listOf("A4:CF:12", "F8:A2:D6", "82:6B:F2")) {
            for (ssid in listOf("", "Falcon_12")) {
                val h = alpr(ap("$p:00:00:01", ssid))
                assertEquals("$p '$ssid'", "Very weak Flock signature", h?.label)
                assertEquals(Tier.VERY_WEAK_MAX, h?.confidence)
                assertTrue(h!!.listOnly)
            }
            assertNull(alpr(ap("$p:00:00:01", "HomeNet")))
        }
        // The other module prefixes keep their scores.
        assertEquals(35, alpr(ap("70:C9:4E:00:00:01", ""))?.confidence)
        assertFalse(alpr(ap("70:C9:4E:00:00:01", ""))!!.listOnly)
        // Their watchlist entries are very weak too.
        val preset = com.google.gson.Gson().fromJson(File("src/main/assets/oui_presets/global.json").readText(),
            Array<com.rfsentinel.app.oui.OuiEntry>::class.java)
        val scores = preset.associate { it.prefix to it.effectiveScore }
        assertEquals(Tier.VERY_WEAK_MAX, scores["A4:CF:12"])
        assertEquals(Tier.VERY_WEAK_MAX, scores["F8:A2:D6"])
    }

    // ---- Axon pairing networks

    @Test
    fun axonBodyCamerasPairingNetworks() {
        val ab3 = SignatureEngine.classify(ap("00:11:22:33:44:55", "AB3-X12345")).first()
        assertEquals(Category.BODY_CAM, ab3.category)
        assertEquals("Axon Body 3 camera (pairing network)", ab3.label)
        assertEquals(80, ab3.confidence)
        assertEquals("Axon Body 4 camera (pairing network)", SignatureEngine.classify(ap("00:11:22:33:44:56", "ab4-1")).first().label)
        assertEquals("Axon device hotspot (body camera or in-car)", SignatureEngine.classify(ap("00:11:22:33:44:57", "AXON-7F2A")).first().label)
        // Not these: an Axon Fleet in-car network has its own rule; other "AB" names are nothing.
        assertNull(SignatureEngine.axonPairingSsid("Axon12-5g"))
        assertNull(SignatureEngine.axonPairingSsid("AB5-Home"))
        assertNull(SignatureEngine.axonPairingSsid("CAB3-x"))
    }

    // ---- Flock battery

    private fun ble(mac: String, name: String? = null, mfr: Map<Int, ByteArray> = emptyMap(), uuids: List<UUID> = emptyList(),
                    data: Map<UUID, ByteArray> = emptyMap(), rssi: Int = -60) =
        Advert(mac, Advert.Source.BLE, rssi, name = name, manufacturerData = mfr, serviceUuids = uuids, serviceData = data)

    @Test
    fun theFlockBatterysSecondService() {
        val h = alpr(ble("C0:11:22:33:44:55", uuids = listOf(UUID.fromString("20c944c1-add2-42d7-a638-967ee9a26ff6"))))
        assertEquals("Flock Safety device", h?.label)
        assertEquals(80, h?.confidence)
    }

    @Test
    fun aPenguinBatteryByTheSerialInItsData() {
        val serial = byteArrayOf(1, 2) + "TN1234567".toByteArray()
        val h = alpr(ble("C0:11:22:33:44:56", mfr = mapOf(0x09C8 to serial)))
        assertEquals("Flock Safety device", h?.label)
        assertEquals(80, h?.confidence)
        // The module alone stays the weak shared-silicon hint.
        val bare = alpr(ble("C0:11:22:33:44:57", mfr = mapOf(0x09C8 to "TNX".toByteArray())))
        assertEquals(45, bare?.confidence)
    }

    // ---- Trackers

    @Test
    fun aSmartTagBeingSetUp() {
        val h = SignatureEngine.classify(ble("C0:11:22:33:44:58", data = mapOf(Advert.uuid16(0xFD59) to byteArrayOf(1)))).first()
        assertEquals(Category.TRACKER, h.category)
        assertEquals("Samsung SmartTag (not set up yet)", h.label)
        assertTrue(h.confidence < 50) // below the default alert threshold
        // A registered one is still the plain SmartTag.
        assertEquals("Samsung SmartTag", SignatureEngine.classify(ble("C0:11:22:33:44:59",
            data = mapOf(Advert.uuid16(0xFD5A) to byteArrayOf(1)))).first().label)
    }

    private fun airTag(mac: String, rssi: Int = -60) =
        ble(mac, mfr = mapOf(0x004C to byteArrayOf(0x12, 0x19) + ByteArray(25)), rssi = rssi)

    /** [n] fake tags on throwaway addresses, each heard twice a second apart, a few seconds after one another. */
    private fun fakes(n: Int, from: Long, rssi: Int = -60, prefix: String = "D0"): Long {
        var t = from
        repeat(n) { i ->
            val m = "$prefix:00:00:00:00:%02X".format(i)
            assertNull(HackerWatch.tagFlood(airTag(m, rssi), t)); t += 1_000
            HackerWatch.tagFlood(airTag(m, rssi), t); t += 2_000
        }
        return t
    }

    @Test
    fun aFloodOfFakeTrackersIsOneAlarm() {
        var t = fakes(HackerWatch.FLOOD_EVIDENCE, 0L)
        // The last fakes count once they've gone quiet: the next tracker advert notices.
        t += HackerWatch.FLOOD_QUIET_MS
        val f = HackerWatch.tagFlood(airTag("D1:00:00:00:00:01"), t)
        assertNotNull(f)
        assertEquals("Apple Find My", f!!.network)
        assertEquals(HackerWatch.FLOOD_EVIDENCE, f.addresses)
        // Still going: not again for a while.
        t = fakes(HackerWatch.FLOOD_EVIDENCE, t + 1_000, prefix = "D2") + HackerWatch.FLOOD_QUIET_MS
        assertNull(HackerWatch.tagFlood(airTag("D1:00:00:00:00:01"), t))
    }

    @Test
    fun realTagsAndFarAwayOnesAreNoFlood() {
        // Real tags: each keeps its address and is heard again and again.
        var t = 0L
        repeat(HackerWatch.FLOOD_EVIDENCE * 2) { i ->
            repeat(10) { assertNull(HackerWatch.tagFlood(airTag("E0:00:00:00:00:%02X".format(i)), t)); t += 2_000 }
        }
        assertNull(HackerWatch.tagFlood(airTag("E1:00:00:00:00:01"), t + HackerWatch.FLOOD_QUIET_MS))
        // Short-lived but weak: tags in passing cars.
        HackerWatch.reset()
        t = fakes(HackerWatch.FLOOD_EVIDENCE * 2, 0L, rssi = -90) + HackerWatch.FLOOD_QUIET_MS
        assertNull(HackerWatch.tagFlood(airTag("E1:00:00:00:00:01"), t))
        // Not a tracker at all: an iPhone's short Find My status.
        assertNull(HackerWatch.trackerNetwork(ble("E2:00:00:00:00:01", mfr = mapOf(0x004C to byteArrayOf(0x12, 0x02, 0, 2)))))
    }

    @Test
    fun theFindingNetworks() {
        assertEquals("Google Find Hub", HackerWatch.trackerNetwork(ble("E3:00:00:00:00:01", data = mapOf(Advert.uuid16(0xFEAA) to byteArrayOf(0x41)))))
        assertNull(HackerWatch.trackerNetwork(ble("E3:00:00:00:00:02", data = mapOf(Advert.uuid16(0xFEAA) to byteArrayOf(0x10))))) // Eddystone-URL
        assertEquals("Samsung SmartTag", HackerWatch.trackerNetwork(ble("E3:00:00:00:00:03", data = mapOf(Advert.uuid16(0xFD5A) to byteArrayOf(1)))))
        assertEquals("Tile", HackerWatch.trackerNetwork(ble("E3:00:00:00:00:04", data = mapOf(Advert.uuid16(0xFEEC) to byteArrayOf(1)))))
    }

    // ---- Flipper hint

    @Test
    fun anStm32wbAddressIsOnlyAWeakFlipperHint() {
        val h = SignatureEngine.classify(ble("80:E1:26:00:00:01")).first { it.category == Category.HACKER }
        assertEquals("Possible Flipper Zero (STM32WB chip)", h.label)
        assertTrue(h.confidence < 50)
    }

    // ---- Apple activity

    @Test
    fun whatAnAppleDeviceIsDoing() {
        val driving = ble("40:00:00:00:00:01", mfr = mapOf(0x004C to byteArrayOf(0x10, 0x05, 0x3D, 0x18, 0, 0, 0)))
        assertEquals("Driving", DeviceIntel.identify(driving).facts.toMap()["Apple activity"])
        val call = ble("40:00:00:00:00:02", mfr = mapOf(0x004C to byteArrayOf(0x10, 0x05, 0x1E, 0x18, 0, 0, 0)))
        assertEquals("On a call", DeviceIntel.identify(call).facts.toMap()["Apple activity"])
    }

    // ---- Karma

    @Test
    fun oneAccessPointAnsweringUnderManyNamesIsKarma() {
        val heard = ArrayList<KarmaWatch.Attack>()
        KarmaWatch.onAttack = { heard += it }
        val t = MonitorFrames()
        val pineapple = "00:13:37:00:00:01"
        val names = listOf("HomeNet", "Starbucks WiFi", "attwifi", "eduroam")
        names.forEachIndexed { i, n ->
            val resp = mgmt(0x50, "3c:22:fb:00:00:02", pineapple, pineapple, 12, ssidIe(n))
            t.frame(resp, 0, resp.size, 6, -55, i * 1_000L)
        }
        assertEquals(1, heard.size)
        assertEquals(pineapple, heard[0].transmitter)
        assertEquals(names.reversed(), heard[0].names)
    }

    @Test
    fun ordinaryAccessPointsAreNotKarma() {
        var t = 0L
        // A router answering under its own name, again and again.
        repeat(100) { assertNull(KarmaWatch.frame("00:30:44:00:00:01", "HomeNet", 6, t)); t += 500 }
        // A router with a guest network: a second address for it.
        assertNull(KarmaWatch.frame("00:30:44:00:00:02", "HomeNet-Guest", 6, t))
        // Renamed twice in a minute: still under the threshold.
        assertNull(KarmaWatch.frame("00:30:44:00:00:01", "HomeNet2", 6, t))
        assertNull(KarmaWatch.frame("00:30:44:00:00:01", "HomeNet3", 6, t))
        // Names heard more than a minute apart don't add up.
        assertNull(KarmaWatch.frame("00:30:44:00:00:01", "HomeNet4", 6, t + KarmaWatch.WINDOW_MS + 1))
    }

    // ---- Deauth

    @Test
    fun aNormalNetworksBurstDoesNotAlarm() {
        // An access point restarting drops its 24 devices at once, then nothing.
        var t = 0L
        repeat(24) { assertNull(DeauthWatch.frame("02:00:00:00:00:BB", broadcast = false, channel = 6, now = t)); t += 100 }
        // ...and a couple more devices leave later on.
        t += 7_000; assertNull(DeauthWatch.frame("02:00:00:00:00:BB", false, 6, t))
        t += 7_000; assertNull(DeauthWatch.frame("02:00:00:00:00:BB", false, 6, t))
    }

    @Test
    fun aStreamHeardInPiecesStillAlarms() {
        // The adapter is on this channel for a moment every few seconds: a few frames each time.
        var t = 0L
        var alarm: DeauthWatch.Attack? = null
        repeat(10) {
            repeat(5) { DeauthWatch.frame("02:00:00:00:00:CC", false, 1, t)?.let { alarm = it }; t += 50 }
            t += 3_000
        }
        assertNotNull(alarm)
        assertFalse(alarm!!.broadcast)
    }
}
