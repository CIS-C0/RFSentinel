package com.rfsentinel.app.detect

import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.usb.DeauthWatch
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

/** Three ideas from ESPsoup: the WiFi deauth alarm, Chipolo by its service ID, and Locate following an address rotation. */
class EspSoupIdeasTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    @Before
    fun setUp() { DeauthWatch.clear(); DeauthWatch.onAttack = null }

    @After
    fun tearDown() { DeauthWatch.clear(); DeauthWatch.onAttack = null }

    // ---- Deauth alarm

    private val ap = "02:00:00:00:00:AA"

    @Test
    fun aFloodRaisesOneAlarm() {
        val heard = ArrayList<DeauthWatch.Attack>()
        DeauthWatch.onAttack = { heard += it }
        var t = 1_000_000L
        repeat(DeauthWatch.MIN_FRAMES - 1) { assertNull(DeauthWatch.frame(ap, broadcast = true, channel = 6, now = t)); t += 100 }
        val a = DeauthWatch.frame(ap, broadcast = true, channel = 6, now = t)
        assertNotNull(a)
        assertEquals(ap, a!!.bssid); assertTrue(a.broadcast); assertEquals(6, a.channel)
        assertEquals(DeauthWatch.MIN_FRAMES, a.frames)
        // Still flooding: no new alarm for a while.
        repeat(100) { t += 100; assertNull(DeauthWatch.frame(ap, true, 6, t)) }
        assertEquals(1, heard.size)
        // Much later, still going: alarms again.
        t += DeauthWatch.REPEAT_MS
        repeat(DeauthWatch.MIN_FRAMES) { t += 100; DeauthWatch.frame(ap, true, 6, t) }
        assertEquals(2, heard.size)
    }

    @Test
    fun aNormalNetworkNeverAlarms() {
        // A client leaving now and then: a deauth every 10 s for an hour.
        var t = 0L
        repeat(360) { assertNull(DeauthWatch.frame(ap, broadcast = false, channel = 1, now = t)); t += 10_000 }
    }

    @Test
    fun separateNetworksAreCountedSeparately() {
        var t = 0L
        repeat(DeauthWatch.MIN_FRAMES - 1) {
            assertNull(DeauthWatch.frame("02:00:00:00:00:01", false, 1, t))
            assertNull(DeauthWatch.frame("02:00:00:00:00:02", false, 1, t)); t += 50
        }
    }

    /** A deauthentication frame for network [bssid] to [dest], optionally 802.11w-protected. */
    private fun deauth(bssid: Int, dest: ByteArray, protected: Boolean = false): ByteArray {
        val f = ByteArray(26)
        f[0] = 0xC0.toByte()                     // management, subtype 12
        f[1] = if (protected) 0x40 else 0
        System.arraycopy(dest, 0, f, 4, 6)
        for (i in 10..15) f[i] = 0x02; f[15] = bssid.toByte()   // transmitter = the AP it pretends to be
        for (i in 16..21) f[i] = 0x02; f[21] = bssid.toByte()   // BSSID
        f[24] = 7                                // reason code
        return f
    }

    private val everyone = ByteArray(6) { 0xFF.toByte() }

    @Test
    fun theAdaptersFramesFeedTheAlarm() {
        val heard = ArrayList<DeauthWatch.Attack>()
        DeauthWatch.onAttack = { heard += it }
        val frames = MonitorFrames()
        var t = 5_000_000L
        repeat(DeauthWatch.MIN_FRAMES) { val f = deauth(0x33, everyone); frames.frame(f, 0, f.size, 11, -50, t); t += 200 }
        assertEquals(1, heard.size)
        assertEquals("02:02:02:02:02:33", heard[0].bssid)
        assertTrue(heard[0].broadcast)
        assertEquals(11, heard[0].channel)
    }

    @Test
    fun protectedFramesAreNotCounted() {
        // 802.11w: an attacker can't forge these, so a stream of them is the network itself.
        val heard = ArrayList<DeauthWatch.Attack>()
        DeauthWatch.onAttack = { heard += it }
        val frames = MonitorFrames()
        var t = 0L
        repeat(DeauthWatch.MIN_FRAMES * 2) { val f = deauth(0x44, everyone, protected = true); frames.frame(f, 0, f.size, 6, -50, t); t += 100 }
        assertTrue(heard.isEmpty())
    }

    // ---- Chipolo

    @Test
    fun chipoloIsFoundByItsServiceId() {
        val byData = Advert("C1:22:33:44:55:66", Advert.Source.BLE, -60,
            serviceData = mapOf(Advert.uuid16(0xFE33) to byteArrayOf(1, 2, 3)))
        assertEquals("Chipolo tracker", SignatureEngine.classify(byData).firstOrNull { it.category == Category.TRACKER }?.label)
        val byUuid = Advert("C1:22:33:44:55:67", Advert.Source.BLE, -60, serviceUuids = listOf(Advert.uuid16(0xFE33)))
        assertEquals("Chipolo tracker", SignatureEngine.classify(byUuid).firstOrNull { it.category == Category.TRACKER }?.label)
        val other = Advert("C1:22:33:44:55:68", Advert.Source.BLE, -60, serviceUuids = listOf(Advert.uuid16(0x180F)))
        assertTrue(SignatureEngine.classify(other).none { it.label == "Chipolo tracker" })
    }

    // ---- Locate follows an address rotation

    @Test
    fun theCurrentAddressFollowsARotation() {
        DeviceRegistry.startSession(0)
        val id = DeviceIntel.Identity("x", emptyList())
        val tag = Hit(Category.TRACKER, "Tracker", 60, "e", "s")
        fun advert(mac: String, t: Long) = Advert(mac, Advert.Source.BLE, -60, null, mapOf(0x034D to ByteArray(6)), timestamp = t)
        DeviceRegistry.report(advert("5A:00:00:00:10:01", 1_000), listOf(tag), id, null, null, null, 1_000)
        assertEquals("5A:00:00:00:10:01", DeviceRegistry.currentAddress("5A:00:00:00:10:01"))
        // It goes quiet, then the same fingerprint appears under a new random address, then again.
        DeviceRegistry.report(advert("5A:00:00:00:10:02", 6_000), emptyList(), id, null, null, null, 6_000)
        assertEquals("5A:00:00:00:10:02", DeviceRegistry.currentAddress("5A:00:00:00:10:01"))
        // Heard under that address for a while, then it rotates again (the first address is long gone by then).
        DeviceRegistry.report(advert("5A:00:00:00:10:02", 38_000), emptyList(), id, null, null, null, 38_000)
        DeviceRegistry.report(advert("5A:00:00:00:10:03", 40_000), emptyList(), id, null, null, null, 40_000)
        assertEquals("5A:00:00:00:10:03", DeviceRegistry.currentAddress("5A:00:00:00:10:01"))
        assertEquals("5A:00:00:00:10:03", DeviceRegistry.currentAddress("5A:00:00:00:10:02"))
        // Something never seen keeps its own address.
        assertFalse(DeviceRegistry.currentAddress("5A:FF:FF:FF:FF:FF") != "5A:FF:FF:FF:FF:FF")
    }
}
