package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

class DetectionTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            // Unit tests run with the module directory as working dir.
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    private fun ble(
        mac: String = "C0:11:22:33:44:55",
        name: String? = null,
        mfg: Map<Int, ByteArray> = emptyMap(),
        uuids: List<UUID> = emptyList(),
        data: Map<UUID, ByteArray> = emptyMap(),
        raw: ByteArray? = null
    ) = Advert(mac, Advert.Source.BLE, -60, name, mfg, uuids, data, raw)

    private fun wifi(mac: String, ssid: String?) =
        Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()))

    private fun best(a: Advert) = SignatureEngine.classify(a).firstOrNull()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---- Body cams ----------------------------------------------------------

    @Test
    fun axonTagMatchesInEitherByteOrder() {
        // On the wire the tag appears little-endian-reversed ("AXJANUSBWCDEVICE" reversed).
        val reversed = "AXJANUSBWCDEVICE".reversed().toByteArray()
        val hit = best(ble(data = mapOf(Advert.uuid16(0xFE6B) to reversed)))
        assertEquals(Category.BODY_CAM, hit?.category)
        assertEquals(90, hit?.confidence)
        // The spaced display form must NOT match anything.
        assertNull(best(ble(data = mapOf(Advert.uuid16(0x1234) to "BWC DEVICE".toByteArray()))))
    }

    @Test
    fun axonSigIdentifiers() {
        assertEquals(Category.BODY_CAM, best(ble(mfg = mapOf(0x034D to bytes(1, 2))))?.category)
        assertEquals(Category.BODY_CAM, best(ble(uuids = listOf(Advert.uuid16(0xFC81))))?.category)
    }

    @Test
    fun motorolaIsWeak() {
        val hit = best(ble(mfg = mapOf(0x04EC to bytes(0))))
        assertEquals(Category.PUBLIC_SAFETY, hit?.category)
        assertEquals(Tier.WEAK, hit?.tier)
    }

    // ---- Ticket printers -----------------------------------------------------

    @Test
    fun zebraPrinterNeedsSerialNameToBeProbable() {
        val fleet = best(ble(name = "XXRBJ000000001", uuids = listOf(Advert.uuid16(0xFE79))))
        assertEquals(Category.PUBLIC_SAFETY, fleet?.category)
        assertEquals(Tier.MEDIUM, fleet?.tier)
        val renamed = best(ble(name = "Warehouse 3", uuids = listOf(Advert.uuid16(0xFE79))))
        assertEquals(Tier.WEAK, renamed?.tier)
        assertNull(best(ble(name = "XXRBJ000000001")))
    }

    // ---- Flock ---------------------------------------------------------------

    @Test
    fun cameraBlocksFromCommunityLists() {
        // Hikvision, Ring and Uniview blocks added from Flock-You-Android / OUI-Spy / Fieldwatch.
        for ((mac, vendor) in listOf("B4:A3:82:11:22:33" to "Hikvision", "18:7F:88:11:22:33" to "Ring (Amazon)",
            "48:EA:63:11:22:33" to "Uniview")) {
            val h = best(wifi(mac, "cam"))
            assertEquals(Category.NETWORK_CAMERA, h?.category)
            assertEquals("$vendor camera, hub or recorder", h?.label)
        }
    }

    @Test
    fun flockSignatures() {
        assertEquals(88, best(wifi("B4:1E:52:00:00:01", "Flock-3F2A1B"))?.confidence)
        assertEquals(80, best(ble(name = "FS Ext Battery"))?.confidence)
        assertEquals(70, best(ble(name = "Penguin-1234567890"))?.confidence)
        assertEquals(80, best(ble(name = "Penguin-1234567890", mfg = mapOf(0x09C8 to bytes(0))))?.confidence)
        assertEquals(70, best(ble(name = "FS-BEC46A"))?.confidence)
        // Near misses must not match.
        assertNull(best(ble(name = "Penguin-abc")))
        assertNull(best(ble(name = "My penguin speaker")))
        assertNull(best(wifi("12:00:00:00:00:01", "Atlanta-Falcons")))
    }

    @Test
    fun ravenServiceUuid() {
        val hit = best(ble(uuids = listOf(Advert.uuid16(0x3100), Advert.uuid16(0x3400))))
        assertEquals(Category.AUDIO_SENSOR, hit?.category)
        assertTrue(hit!!.evidence.contains("0x3100"))
    }

    // ---- Trackers ------------------------------------------------------------

    @Test
    fun appleFindMyOnlyWhenSeparated() {
        val separated = ByteArray(27).also { it[0] = 0x12; it[1] = 0x19 }
        val nearOwner = bytes(0x12, 0x02, 0x00, 0x00)
        assertEquals(Category.TRACKER, best(ble(mfg = mapOf(0x004C to separated)))?.category)
        assertNull(best(ble(mfg = mapOf(0x004C to nearOwner))))
    }

    @Test
    fun findHubOnlyFrame0x41() {
        assertEquals(Category.TRACKER, best(ble(data = mapOf(Advert.uuid16(0xFEAA) to bytes(0x41, 1, 2))))?.category)
        assertNull(best(ble(data = mapOf(Advert.uuid16(0xFEAA) to bytes(0x40, 1, 2)))))
        assertNull(best(ble(data = mapOf(Advert.uuid16(0xFEAA) to bytes(0x10, 1, 2))))) // Eddystone-URL
    }

    // ---- Glasses ---------------------------------------------------------------

    @Test
    fun heyCyanNeedsFull128BitMatch() {
        val heyCyan = UUID.fromString("7905FFF0-B5CE-4E99-A40F-4B1E122D00D0")
        val ancs = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0") // Apple Notification Center Service
        assertEquals(Category.GLASSES, best(ble(uuids = listOf(heyCyan)))?.category)
        assertNull(best(ble(uuids = listOf(ancs))))
    }

    @Test
    fun glassesCompanyIds() {
        assertEquals(70, best(ble(mfg = mapOf(0x0D53 to bytes(0))))?.confidence)
        assertEquals(Tier.WEAK, best(ble(mfg = mapOf(0x01AB to bytes(0))))?.tier)
        // Meta Technologies ID alone (shared with Quest) is gated off...
        assertNull(best(ble(mfg = mapOf(0x058E to bytes(1, 2, 3)))))
        // ...unless the glasses token is present.
        assertEquals(72, best(ble(mfg = mapOf(0x058E to "xxMETA_RB_GLASSxx".toByteArray())))?.confidence)
    }

    // ---- Drones ----------------------------------------------------------------

    @Test
    fun droneMakerPrefixesRespectBlockSize() {
        assertEquals(Category.DRONE, best(ble(mac = "60:60:1F:12:34:56"))?.category) // DJI MA-L
        assertEquals(Category.DRONE, best(ble(mac = "EC:5B:CD:E1:23:45"))?.category) // Autel MA-M EC:5B:CD:E
        assertNull(best(ble(mac = "EC:5B:CD:01:23:45")))                            // same 24 bits, other /28 block
        assertNull(best(ble(mac = "62:60:1F:12:34:56")))                            // locally administered
    }

    @Test
    fun remoteIdBasicAndLocation() {
        val basic = ByteArray(2 + 25)
        basic[0] = 0x0D; basic[1] = 7
        basic[2] = 0x02                          // type 0 (Basic ID), version 2
        basic[3] = ((1 shl 4) or 2).toByte()     // serial number, multirotor
        "1581F5FJD123456789".toByteArray().copyInto(basic, 4)
        var info = RemoteId.decodeBle(basic, null)
        assertEquals("1581F5FJD123456789", info?.uasId)
        assertEquals("Helicopter / multirotor", info?.uaType)

        val loc = ByteArray(2 + 25)
        loc[0] = 0x0D; loc[1] = 8
        val m = 2
        loc[m] = 0x12                            // type 1 (Location), version 2
        loc[m + 1] = (2 shl 4).toByte()          // airborne, speed mult 0, EW 0
        loc[m + 2] = 90                          // heading 90
        loc[m + 3] = 40                          // 40 * 0.25 = 10 m/s
        fun i32(v: Int, at: Int) { for (k in 0..3) loc[at + k] = (v ushr (8 * k)).toByte() }
        i32(105_000_000, m + 5)                  // 10.5
        i32(-205_000_000, m + 9)                 // -20.5
        val alt = ((120.0 + 1000) / 0.5).toInt()
        loc[m + 15] = alt.toByte(); loc[m + 16] = (alt ushr 8).toByte()
        info = RemoteId.decodeBle(loc, info)
        assertEquals("1581F5FJD123456789", info?.uasId) // merged, not replaced
        assertEquals(10.5, info!!.latitude!!, 1e-6)
        assertEquals(-20.5, info.longitude!!, 1e-6)
        assertEquals(10.0, info.speedMs!!, 1e-9)
        assertEquals(90, info.directionDeg)
        assertEquals(120.0, info.altitudeGeoM!!, 1e-9)
        assertEquals("Airborne", info.status)

        assertEquals(Category.DRONE, best(ble(data = mapOf(Advert.uuid16(0xFFFA) to basic)))?.category)
    }

    // ---- Device intelligence -----------------------------------------------------

    @Test
    fun addressTypes() {
        assertEquals(AddressType.RANDOM_STATIC, AddressType.ofRandom("C3:11:22:33:44:55"))
        assertEquals(AddressType.RESOLVABLE_PRIVATE, AddressType.ofRandom("5A:11:22:33:44:55"))
        assertEquals(AddressType.NON_RESOLVABLE, AddressType.ofRandom("1A:11:22:33:44:55"))
        assertEquals(AddressType.WIFI_LOCAL, AddressType.ofWifi("DA:A1:19:00:00:01"))
        assertEquals(AddressType.WIFI_GLOBAL, AddressType.ofWifi("00:25:DF:00:00:01"))
    }

    @Test
    fun adStructureParsing() {
        val raw = bytes(0x02, 0x01, 0x06, 0x05, 0x09, 'T'.code, 'e'.code, 's'.code, 't'.code, 0x03, 0x19, 0xC1, 0x00, 0x00, 0x00)
        val ads = AdStructure.parse(raw)
        assertEquals(listOf(0x01, 0x09, 0x19), ads.map { it.type })
        assertEquals("Test", String(ads[1].data))
        // Truncated structure is dropped, not crashed on.
        assertEquals(1, AdStructure.parse(bytes(0x02, 0x01, 0x06, 0x09, 0x09, 0x41)).size)
    }

    @Test
    fun identifiesAppleAndBeaconsAndAppearance() {
        val airpods = bytes(0x07, 0x19, 0x01, 0x0E, 0x20) + ByteArray(22)
        assertEquals("AirPods Pro", DeviceIntel.identify(ble(mfg = mapOf(0x004C to airpods))).type)
        val unknownModel = bytes(0x07, 0x19, 0x01, 0x7E, 0x7E) + ByteArray(22)
        assertEquals("AirPods / Beats", DeviceIntel.identify(ble(mfg = mapOf(0x004C to unknownModel))).type)

        val ibeacon = bytes(0x02, 0x15) + ByteArray(16) { 0x11 } + bytes(0x00, 0x01, 0x00, 0x02, 0xC5)
        val id = DeviceIntel.identify(ble(mfg = mapOf(0x004C to ibeacon)))
        assertEquals("iBeacon", id.type)
        assertTrue(id.facts.any { it.second.contains("major 1, minor 2") })

        // Appearance 0x00C1 = category Watch (3), subcategory Sports Watch (1).
        val raw = bytes(0x03, 0x19, 0xC1, 0x00)
        assertTrue(DeviceIntel.identify(ble(raw = raw)).type.startsWith("Watch"))
    }

    @Test
    fun vendorLookups() {
        assertTrue(VendorDb.macVendor("00:25:DF:11:22:33")!!.contains("Axon"))
        assertTrue(VendorDb.macVendor("B4:1E:52:11:22:33")!!.contains("Flock"))
        assertTrue(VendorDb.macVendor("EC:5B:CD:E1:22:33")!!.contains("Autel", ignoreCase = true))
        assertNull(VendorDb.macVendor("DA:A1:19:00:00:01")) // randomized
        assertEquals("Apple, Inc.", VendorDb.company(0x004C))
        assertNotNull(VendorDb.uuid16(0xFFFA))
    }

    @Test
    fun wifiSecurityAndChannels() {
        assertEquals("WPA3", DeviceIntel.security("[RSN-SAE-CCMP][ESS]"))
        assertEquals("Open (no password)", DeviceIntel.security("[ESS]"))
        assertEquals(6, DeviceIntel.channelOf(2437))
        assertEquals(36, DeviceIntel.channelOf(5180))
        assertFalse(DeviceIntel.distanceMeters(ble()).isNaN())
    }
}
