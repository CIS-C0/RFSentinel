package com.rfsentinel.app.detect

import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.DeviceRegistry.GeoSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Fast Pair models, Apple device kinds, network-name types, companions and advert look-alikes. */
class IdentifyMoreTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    private fun ble(mfg: Map<Int, ByteArray> = emptyMap(), data: Map<java.util.UUID, ByteArray> = emptyMap()) =
        Advert("C1:22:33:44:55:66", Advert.Source.BLE, -60, null, mfg, emptyList(), data)

    private fun wifi(ssid: String) = Advert("02:00:00:00:00:01", Advert.Source.WIFI, -60, ssid,
        wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()))

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun fastPairModelIdNamesTheProduct() {
        val id = DeviceIntel.identify(ble(data = mapOf(Advert.uuid16(0xFE2C) to bytes(0xCD, 0x82, 0x56))))
        assertEquals("Bose NC 700", id.type)
        assertTrue(id.facts.contains("Identified by" to "Fast Pair model ID"))
        // Not in pairing mode: an account-key filter, no model.
        val paired = DeviceIntel.identify(ble(data = mapOf(Advert.uuid16(0xFE2C) to bytes(0x00, 0x40, 0x11, 0x22, 0x33, 0x44))))
        assertTrue(paired.facts.contains("Google Fast Pair" to "Pairing / account-key broadcast"))
    }

    @Test
    fun fastPairListHasNoTestOrSwappedEntries() {
        assertNull(VendorDb.fastPairModel(0x00000A))
        assertNull(VendorDb.fastPairModel(0xF00000))
        assertEquals("Bose QuietComfort 35 II", VendorDb.fastPairModel(0x0000F0))
    }

    @Test
    fun appleWatchAndIphoneAreToldApart() {
        // Nearby Info (0x10), status byte low nibble 0x0A = on a wrist, unlocked.
        assertEquals("Apple Watch", DeviceIntel.identify(ble(mapOf(0x004C to bytes(0x10, 0x05, 0x0A, 0x1C, 0x00, 0x00, 0x00)))).type)
        assertEquals("Apple Watch", DeviceIntel.identify(ble(mapOf(0x004C to bytes(0x0B, 0x02, 0x00, 0x00)))).type)
        assertEquals("iPhone", DeviceIntel.identify(ble(mapOf(0x004C to bytes(0x10, 0x05, 0x0D, 0x1C, 0x00, 0x00, 0x00)))).type)
        assertEquals("Apple device (iPhone, iPad, Mac or Watch)",
            DeviceIntel.identify(ble(mapOf(0x004C to bytes(0x10, 0x05, 0x03, 0x1C, 0x00, 0x00, 0x00)))).type)
    }

    @Test
    fun networkNamesSayWhatTheNetworkIs() {
        mapOf(
            "BELL123" to "Home router - Bell", "Livebox-1A2B" to "Home router - Orange", "BT-ABC123" to "Home router - BT",
            "ATT4b7Xq2" to "Home router - AT&T", "Fios-AB12C" to "Home router - Verizon", "SKY1A2B3" to "Home router - Sky",
            "xfinitywifi" to "Public WiFi hotspot - Xfinity", "Verizon-MiFi8800L-1A2B" to "Mobile hotspot box",
            "70mai_D06_1234" to "Dashcam", "TELLO-5A1B2C" to "Drone / controller", "GP25123456" to "Action camera",
            "DIRECT-1F-[TV] Samsung Q60" to "Smart TV / streaming device", "ESP_1A2B3C" to "IoT board (ESP / smart-home firmware)",
            "TP-LINK_Smart Plug_1A2B" to "Smart plug / bulb waiting to be set up", "Ring Setup 12" to "Security camera / doorbell"
        ).forEach { (ssid, type) -> assertEquals(ssid, type, DeviceIntel.identify(wifi(ssid)).type) }
        // Ordinary names stay ordinary.
        listOf("attention", "Skyline", "Franklin Family", "Bellevue Cafe", "My Home").forEach {
            assertNull(it, DeviceIntel.ssidType(it))
        }
    }

    @Test
    fun companionsAreDevicesHeardWithItAtSeveralPlaces() {
        // Your way north, ~550 m per sample, one a minute.
        val way = (0 until 5).map { GeoSample(it * 60_000L, 45.0 + it * 0.005, -70.0) }
        val withIt = way.map { GeoSample(it.time + 20_000L, it.lat + 0.0005, it.lon) }
        assertEquals(5, DeviceRegistry.sharedPlaces(way, withIt))
        // Heard only at the start: one place, not a companion.
        val parked = way.take(1) + way.take(1).map { it.copy(time = it.time + 30_000L) } + way.take(1).map { it.copy(time = it.time + 50_000L) }
        assertEquals(0, DeviceRegistry.sharedPlaces(way, parked))
        // Heard along the way but long after: not together.
        assertEquals(0, DeviceRegistry.sharedPlaces(way, way.map { it.copy(time = it.time + 3_600_000L) }))
    }

    @Test
    fun aTagThatNeverLeavesAPhoneIsItsOwners() {
        DeviceRegistry.clear(); DeviceRegistry.startSession()
        val tile = Advert("D0:00:00:00:00:01", Advert.Source.BLE, -60, null,
            serviceData = mapOf(Advert.uuid16(0xFEED) to bytes(0x02, 0x00)))
        val tileHit = Hit(Category.TRACKER, "Tile tracker", 55, "e", "test")
        val phone = Advert("D0:00:00:00:00:02", Advert.Source.BLE, -60, "Galaxy S24")
        val watch = Advert("D0:00:00:00:00:03", Advert.Source.BLE, -60, "Fitbit")
        // Five places ~550 m apart, a minute each; the phone is there at all of them, the watch at the first three.
        (0 until 5).forEach { i ->
            val at = GeoSample(i * 60_000L, 45.0 + i * 0.005, -70.0)
            DeviceRegistry.report(tile, listOf(tileHit), DeviceIntel.Identity("Tile", emptyList()), null, null, at, at.time)
            DeviceRegistry.report(phone, emptyList(), DeviceIntel.Identity("Samsung device", emptyList()), null, null, at, at.time + 5_000)
            if (i < 3) DeviceRegistry.report(watch, emptyList(), DeviceIntel.Identity("Wearable", emptyList()), null, null, at, at.time + 5_000)
        }
        assertEquals("Galaxy S24", DeviceRegistry.ownerPhone(tile.mac))
        // Without the phone, nothing: a watch there all the way isn't a phone.
        DeviceRegistry.clear(); DeviceRegistry.startSession()
        (0 until 5).forEach { i ->
            val at = GeoSample(i * 60_000L, 45.0 + i * 0.005, -70.0)
            DeviceRegistry.report(tile, listOf(tileHit), DeviceIntel.Identity("Tile", emptyList()), null, null, at, at.time)
            DeviceRegistry.report(watch, emptyList(), DeviceIntel.Identity("Wearable", emptyList()), null, null, at, at.time + 5_000)
        }
        assertNull(DeviceRegistry.ownerPhone(tile.mac))
        // AirTags and Find Hub tags say it themselves; the rest need this.
        assertTrue(SignatureEngine.reportsOwnerState(Hit(Category.TRACKER, SignatureEngine.APPLE_FIND_MY_LABEL, 70, "e", "s")))
        assertTrue(!SignatureEngine.reportsOwnerState(tileHit))
        DeviceRegistry.clear()
    }

    @Test
    fun advertHashIsStable() {
        assertEquals(AdvertFingerprint.hash("n=Buds|u=|d=|m=|t=|c="), AdvertFingerprint.hash("n=Buds|u=|d=|m=|t=|c="))
        assertEquals(8, AdvertFingerprint.hash("x").length)
    }
}
