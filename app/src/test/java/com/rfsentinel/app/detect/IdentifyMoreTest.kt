package com.rfsentinel.app.detect

import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.DeviceRegistry.GeoSample
import com.rfsentinel.app.ui.DeviceFilter
import com.rfsentinel.app.ui.DeviceOrder
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
    fun kindFiltersPickTheirDevices() {
        DeviceRegistry.clear(); DeviceRegistry.startSession()
        val kinds = mapOf(
            "BELL123" to DeviceFilter.ROUTERS, "xfinitywifi" to DeviceFilter.HOTSPOTS,
            "70mai_D06_1234" to DeviceFilter.CAMERAS, "TELLO-5A1B2C" to DeviceFilter.DRONES,
            "DIRECT-1F-[TV] Samsung Q60" to DeviceFilter.TVS, "ESP_1A2B3C" to DeviceFilter.SMART_HOME
        )
        kinds.keys.forEachIndexed { i, ssid ->
            val a = Advert("02:00:00:00:00:1$i", Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()))
            DeviceRegistry.report(a, emptyList(), DeviceIntel.identify(a), null, null, null)
        }
        val buds = ble(data = mapOf(Advert.uuid16(0xFE2C) to bytes(0xCD, 0x82, 0x56)))
        DeviceRegistry.report(buds, emptyList(), DeviceIntel.identify(buds), null, null, null)
        val snaps = DeviceRegistry.snapshot()
        kinds.forEach { (ssid, f) -> assertEquals(ssid, listOf(ssid), snaps.filter { f.matches(it) }.map { it.name }) }
        assertEquals(listOf(buds.mac), snaps.filter { DeviceFilter.AUDIO.matches(it) }.map { it.mac })
        assertTrue(snaps.none { DeviceFilter.VEHICLES.matches(it) || DeviceFilter.PHONES.matches(it) })
        DeviceRegistry.clear()
    }

    @Test
    fun savedDevicesUseTheSameFilters() {
        fun saved(mac: String, type: String?, source: String = "WIFI", category: String? = null, label: String = type ?: mac, vendor: String? = null) =
            com.rfsentinel.app.data.TripDeviceEntity(1, mac, label, null, vendor, type, source, category, if (category != null) 60 else 0,
                null, 0, 0, -60, null, null)
        val router = saved("02:00:00:00:00:21", "Home router - Bell")
        val tile = saved("D0:00:00:00:00:22", "Tile", "BLE", "TRACKER", "Tile tracker")
        val buds = saved("C1:22:33:44:55:23", "Bose NC 700", "BLE")
        val all = listOf(router, tile, buds)
        assertEquals(listOf(router), all.filter { DeviceFilter.ROUTERS.matches(it) })
        assertEquals(listOf(tile), all.filter { DeviceFilter.TRACKERS.matches(it) })
        assertEquals(listOf(tile), all.filter { DeviceFilter.FLAGGED.matches(it) })
        assertEquals(listOf(buds), all.filter { DeviceFilter.AUDIO.matches(it) })
        assertEquals(listOf(tile, buds), all.filter { DeviceFilter.BLE.matches(it) })
        // Filters that only mean something live aren't offered, and match nothing saved.
        assertTrue(DeviceFilter.SAVED_ORDER.none { it.liveOnly } && DeviceFilter.KINDS.all { it in DeviceFilter.SAVED_ORDER })
        assertTrue(all.none { DeviceFilter.NEW.matches(it) })
    }

    @Test
    fun kindChipsSortTheCommonCases() {
        fun seen(type: String, name: String? = null, vararg cats: Category) = DeviceFilter.Seen("02:00:00:00:00:31", type, name,
            cats.map { Hit(it, "x", 60, "e", "s") }, setOf(Advert.Source.BLE), cats.isNotEmpty())
        fun kinds(s: DeviceFilter.Seen) = DeviceFilter.KINDS.filter { it.matches(s) }
        assertEquals(listOf(DeviceFilter.AUDIO), kinds(seen("Wearable Audio Device - Earbud")))
        assertEquals(listOf(DeviceFilter.AUDIO), kinds(seen("Hearing aid")))
        assertEquals(listOf(DeviceFilter.TVS), kinds(seen("Display Equipment - Television")))
        assertEquals(listOf(DeviceFilter.VEHICLES), kinds(seen("Motorized Vehicle - Car")))
        assertEquals(listOf(DeviceFilter.VEHICLES), kinds(seen("Sensor - Vehicle Tire Pressure Sensor")))
        assertEquals(listOf(DeviceFilter.SMART_HOME), kinds(seen("HVAC - Thermostat")))
        assertEquals(listOf(DeviceFilter.ROUTERS), kinds(seen("WiFi access point - NETGEAR")))
        assertEquals(listOf(DeviceFilter.CAMERAS), kinds(seen("Body cam", null, Category.BODY_CAM)))
        assertEquals(listOf(DeviceFilter.PHONES), kinds(seen("Phone")))
        assertTrue(DeviceFilter.TRACKERS.matches(seen("Tag")))
        // A watch rides with its owner (tracker checks) but isn't on the Phones chip; Galaxy Buds aren't a phone.
        assertTrue(DeviceIntel.isPhone("Apple Watch", null) && !DeviceFilter.PHONES.matches(seen("Apple Watch")))
        assertTrue(!DeviceIntel.isPhone("Bluetooth device", "Galaxy Buds2 Pro") && DeviceIntel.isPhone("Bluetooth device", "Galaxy S24"))
        // A gunshot sensor isn't headphones.
        assertTrue(!DeviceFilter.AUDIO.matches(seen("Audio / gunshot sensor", null, Category.AUDIO_SENSOR)))
        // Names: "Audio" isn't an Audi; a doorbell is a camera.
        assertEquals("Speaker", DeviceIntel.identify(Advert("C1:22:33:44:55:32", Advert.Source.BLE, -60, "Audio Pro speaker")).type)
        assertEquals("Camera", DeviceIntel.identify(Advert("C1:22:33:44:55:33", Advert.Source.BLE, -60, "Ring Doorbell")).type)
        assertEquals("Vehicle", DeviceIntel.identify(Advert("C1:22:33:44:55:34", Advert.Source.BLE, -60, "Uconnect 4C")).type)
        mapOf("NETGEAR47" to "Home router - NETGEAR", "TP-Link_3F2A" to "Home router - TP-Link", "Starbucks WiFi" to "Public WiFi hotspot",
            "_Free_WiFi" to "Public WiFi hotspot", "FRITZ!Box 7590 XY" to "Home router - FRITZ!Box"
        ).forEach { (ssid, type) -> assertEquals(ssid, type, DeviceIntel.identify(wifi(ssid)).type) }
    }

    @Test
    fun detectionMakersAndLookalikes() {
        fun hits(mac: String, name: String? = null, source: Advert.Source = Advert.Source.WIFI, mfg: Map<Int, ByteArray> = emptyMap()) =
            SignatureEngine.classify(Advert(mac, source, -60, name, mfg,
                wifi = if (source == Advert.Source.WIFI) Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()) else null))
        // More drone makers' blocks; DJI gimbals and mics aren't drones.
        assertTrue(hits("98:AA:FC:71:22:33", "x", Advert.Source.BLE).any { it.category == Category.DRONE && it.label.startsWith("Hubsan") })
        assertTrue(hits("60:60:1F:11:22:33", "RS 4 Pro-1A2B", Advert.Source.BLE).none { it.category == Category.DRONE })
        assertTrue(hits("60:60:1F:11:22:33", "DJI MIC 2", Advert.Source.BLE).none { it.category == Category.DRONE })
        assertTrue(hits("60:60:1F:11:22:33", "Mavic 3", Advert.Source.BLE).any { it.category == Category.DRONE })
        // Any block of a camera maker, by its registrant.
        assertEquals("Tiandy camera, hub or recorder", hits("3C:DA:6D:11:22:33", "cam").first { it.category == Category.NETWORK_CAMERA }.label)
        // A Quest headset isn't camera glasses; Brother's mobile printers say so.
        assertTrue(hits("C1:22:33:44:55:41", "Quest 3", Advert.Source.BLE, mapOf(0x01AB to bytes(1, 2))).none { it.category == Category.GLASSES })
        assertTrue(hits("C1:22:33:44:55:42", null, Advert.Source.BLE, mapOf(0x01AB to bytes(1, 2))).any { it.category == Category.GLASSES })
        assertEquals(35, hits("C1:22:33:44:55:43", "RJ-4250WB_1234", Advert.Source.BLE, mapOf(0x0755 to bytes(1, 2)))
            .first { it.label.startsWith("Brother") }.confidence)
    }

    @Test
    fun advertHashIsStable() {
        assertEquals(AdvertFingerprint.hash("n=Buds|u=|d=|m=|t=|c="), AdvertFingerprint.hash("n=Buds|u=|d=|m=|t=|c="))
        assertEquals(8, AdvertFingerprint.hash("x").length)
    }

    @Test
    fun plainNamedNetworksSortedFromTheBeacon() {
        fun net(mac: String, ssid: String?, caps: String = "[WPA2-PSK-CCMP]", ies: List<Pair<Int, ByteArray>> = emptyList()) =
            DeviceIntel.identify(Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, caps, null, ies)),
                VendorDb.macVendor(mac)).type
        fun tlv(type: Int, value: String) = byteArrayOf((type shr 8).toByte(), type.toByte(), 0, value.length.toByte()) + value.toByteArray()
        fun wps(vararg attrs: ByteArray) = 221 to (byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + attrs.fold(ByteArray(0)) { a, b -> a + b })

        // A provider's router by its WPS model, even renamed or hidden.
        assertEquals("Home router - Orange Livebox (Sagemcom Livebox 5)",
            net("06:00:00:00:00:41", "Smith house", ies = listOf(wps(tlv(0x1021, "Sagemcom"), tlv(0x1023, "Livebox 5")))))
        // By maker: a provider's router, a store-bought one, a business access point.
        assertEquals("Home router (internet provider's) - Sagemcom", net("00:0E:59:00:00:42", "Smith house"))
        assertEquals("Home router (internet provider's) - ZTE", net("00:15:EB:00:00:43", null))
        assertEquals("Home router - eero", net("00:AB:48:00:00:44", "Smith house"))
        assertEquals("Business access point - Ruckus", net("06:00:00:00:00:45", "Guest", ies = listOf(221 to byteArrayOf(0x00, 0x13, 0x92.toByte(), 0x01))))
        // By security: account sign-in, Passpoint, open.
        assertEquals("Business WiFi (account sign-in) - Cisco Meraki", net("00:18:0A:00:00:46", "corp", "[WPA2-EAP-CCMP]"))
        val hs20 = 221 to byteArrayOf(0x50, 0x6F, 0x9A.toByte(), 0x10, 0x00)
        assertEquals("Public WiFi hotspot (Passpoint)", net("06:00:00:00:00:47", "plain", "[WPA2-EAP-CCMP]", listOf(hs20, 107 to byteArrayOf(0x12))))
        assertEquals("Public WiFi hotspot", net("06:00:00:00:00:4C", "plain", "[ESS]", listOf(107 to byteArrayOf(0x03))))
        // A home router's Interworking element ("private network") or a stray HS2.0 tag on a password network: still a router.
        assertEquals("Home router (internet provider's) - Sagemcom", net("00:0E:59:00:00:4D", "Smith house", ies = listOf(107 to byteArrayOf(0x10))))
        assertEquals("Home router (internet provider's) - Sagemcom", net("00:0E:59:00:00:4E", "Smith house", ies = listOf(hs20, 107 to byteArrayOf(0x03))))
        assertEquals("WiFi access point", net("06:00:00:00:00:4F", "plain", ies = listOf(107 to byteArrayOf(0x00))))
        assertEquals("Open WiFi (public or guest)", net("06:00:00:00:00:48", "plain", "[ESS]"))
        // WPS "router" kind with no known maker.
        assertEquals("Home router", net("06:00:00:00:00:49", "plain",
            ies = listOf(wps(byteArrayOf(0x10, 0x54, 0, 8, 0, 6, 0x00, 0x50, 0xF2.toByte(), 0x04, 0, 2)))))
        // A recognised name still wins; nothing to go on stays generic.
        assertEquals("WiFi access point", net("06:00:00:00:00:4A", "plain"))

        fun kinds(type: String) = DeviceFilter.KINDS.filter { it.matches(DeviceFilter.Seen("02:00:00:00:00:4B", type, null, emptyList(), emptySet(), false)) }
        assertEquals(listOf(DeviceFilter.ROUTERS), kinds("Home router (internet provider's) - Sagemcom"))
        assertEquals(listOf(DeviceFilter.HOTSPOTS), kinds("Open WiFi (public or guest)"))
        assertEquals(listOf(DeviceFilter.HOTSPOTS), kinds("Public WiFi hotspot (Passpoint)"))
        assertEquals(listOf(DeviceFilter.ACCESS_POINTS), kinds("Business access point - Ruckus"))
        assertEquals(listOf(DeviceFilter.ACCESS_POINTS), kinds("Business WiFi (account sign-in)"))
        assertEquals(listOf(DeviceFilter.ACCESS_POINTS), kinds("Business access point - Aruba (hidden network)"))
    }

    @Test
    fun listSortAndGroupKeepAlertsOnTop() {
        fun item(id: String, type: String, rssi: Int, vendor: String?, flagged: Boolean = false, src: Advert.Source = Advert.Source.BLE,
                 name: String? = null, seen: Long = 0L) =
            DeviceOrder.Item(id, DeviceFilter.Seen("02:00:00:00:00:$id", type, name, emptyList(), setOf(src), flagged),
                name ?: type, vendor, rssi, seen, seen)
        val items = listOf(
            item("01", "Phone", -50, "Samsung Electronics Co.,Ltd", name = "Zed"),
            item("02", "AirTag (Find My)", -85, "Apple, Inc.", flagged = true),
            item("03", "Home router - eero", -70, "eero inc.", src = Advert.Source.WIFI, name = "Alpha", seen = 5L),
            item("04", "Phone", -90, null)
        )
        val none = Comparator<DeviceOrder.Item<String>> { _, _ -> 0 }
        fun ids(sort: DeviceOrder.Sort, group: DeviceOrder.Group) =
            DeviceOrder.arrange(items, sort, group, none).map { (it.header ?: "") to it.items }

        // The flagged tracker stays first; then the chosen order.
        assertEquals(listOf("" to listOf("02", "01", "03", "04")), ids(DeviceOrder.Sort.SIGNAL, DeviceOrder.Group.NONE))
        assertEquals(listOf("" to listOf("02", "03", "01", "04")), ids(DeviceOrder.Sort.NAME, DeviceOrder.Group.NONE))
        assertEquals(listOf("" to listOf("02", "03", "01", "04")), ids(DeviceOrder.Sort.RECENT, DeviceOrder.Group.NONE))
        // Grouped: the alerts in their own group first, then the groups in order.
        assertEquals(listOf("Alerts" to listOf("02"), "Phones" to listOf("01", "04"), "Routers" to listOf("03")),
            ids(DeviceOrder.Sort.SIGNAL, DeviceOrder.Group.CATEGORY))
        assertEquals(listOf("Alerts" to listOf("02"), "WiFi" to listOf("03"), "Bluetooth" to listOf("01", "04")),
            ids(DeviceOrder.Sort.SIGNAL, DeviceOrder.Group.RADIO))
        assertEquals(listOf("Alerts" to listOf("02"), "Near" to listOf("01"), "Nearby" to listOf("03"), "Far" to listOf("04")),
            ids(DeviceOrder.Sort.SIGNAL, DeviceOrder.Group.DISTANCE))
        assertEquals(listOf("Alerts", "eero", "Samsung", "Unknown maker"),
            ids(DeviceOrder.Sort.SIGNAL, DeviceOrder.Group.MAKER).map { it.first }.map { if (it.startsWith("Samsung")) "Samsung" else it })
    }

    @Test
    fun tvsAndGameGear() {
        fun wifiType(ssid: String, mac: String = "06:00:00:00:00:61") = DeviceIntel.identify(
            Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList())),
            VendorDb.macVendor(mac)).type
        fun bleType(name: String) = DeviceIntel.identify(Advert("C1:22:33:44:55:61", Advert.Source.BLE, -60, name)).type
        listOf("DIRECT-4F-Philips TV", "[LG] webOS TV OLED55C1", "Samsung Smart TV", "Fire TV Stick-1A2B", "Roku Ultra 4K",
            "AndroidTV-4F2A", "Google TV 3B", "SHIELD-TV", "MiBox_4S").forEach { assertEquals(it, "Smart TV / streaming device", wifiType(it)) }
        listOf("PS5-123456", "steamdeck", "ROG Ally").forEach { assertEquals(it, "Game console", wifiType(it)) }
        assertEquals("VR headset", wifiType("Meta Quest 3"))
        // By maker, whatever the network is called: a Roku and a Nintendo console.
        assertEquals("Smart TV / streaming device (WiFi)", wifiType("Living room", "D8:31:34:00:00:62"))
        assertEquals("Game console (WiFi)", wifiType("plain", "00:09:BF:00:00:63"))

        assertEquals("Game controller", bleType("Xbox Wireless Controller"))
        assertEquals("Game controller", bleType("DualSense Wireless Controller"))
        assertEquals("Game console", bleType("Steam Deck"))
        assertEquals("VR headset", bleType("Meta Quest 3"))
        assertEquals("TV / streaming device", bleType("[TV] Samsung Q70 Series"))
        assertEquals("TV / streaming device", bleType("LG webOS TV OLED65"))

        fun kinds(type: String) = DeviceFilter.KINDS.filter { it.matches(DeviceFilter.Seen("02:00:00:00:00:64", type, null, emptyList(), emptySet(), false)) }
        listOf("Game controller", "VR headset", "Game console (WiFi)", "Smart TV / streaming device (WiFi)", "Gamepad")
            .forEach { assertEquals(it, listOf(DeviceFilter.TVS), kinds(it)) }
    }

    @Test
    fun hiddenNetworkOfARouterIsARouter() {
        DeviceRegistry.clear(); DeviceRegistry.startSession()
        fun report(mac: String, ssid: String?) {
            val a = Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()))
            DeviceRegistry.report(a, emptyList(), DeviceIntel.identify(a, VendorDb.macVendor(mac)), null, null, null)
        }
        report("00:0E:59:12:34:50", "Smith house")
        report("02:0E:59:12:34:52", null) // the same router's hidden network (virtual BSSID)
        report("06:AA:BB:CC:DD:10", null) // a hidden network of no known router
        val now = System.currentTimeMillis()
        val byMac = DeviceRegistry.snapshot(now).associateBy { it.mac }
        assertEquals("Home router (internet provider's) - Sagemcom (hidden network)", byMac.getValue("02:0E:59:12:34:52").deviceType)
        assertTrue(DeviceFilter.ROUTERS.matches(byMac.getValue("02:0E:59:12:34:52")))
        assertEquals("Hidden WiFi network", byMac.getValue("06:AA:BB:CC:DD:10").deviceType)
        DeviceRegistry.clear()
    }

    @Test
    fun appleAndComputers() {
        fun bleType(name: String) = DeviceIntel.identify(Advert("C1:22:33:44:55:71", Advert.Source.BLE, -60, name)).type
        assertEquals("Computer", bleType("Jane's MacBook Pro"))
        assertEquals("Computer", bleType("DESKTOP-4F2A9QK"))
        assertEquals("Computer", bleType("ThinkPad X1"))
        assertEquals("Phone / computer", bleType("Jane's iPhone"))

        fun kinds(type: String, name: String? = null, vendor: String? = null) = DeviceFilter.KINDS.filter {
            it.matches(DeviceFilter.Seen("02:00:00:00:00:72", type, name, emptyList(), emptySet(), false, vendor = vendor))
        }
        assertEquals(listOf(DeviceFilter.COMPUTERS, DeviceFilter.APPLE), kinds("MacBook Pro"))
        assertEquals(listOf(DeviceFilter.COMPUTERS), kinds("Computer"))
        assertEquals(listOf(DeviceFilter.COMPUTERS), kinds("Windows PC (Connected Devices beacon)"))
        assertTrue(DeviceFilter.COMPUTERS in kinds("WiFi device (laptop, phone or camera on a network)", "LAPTOP-1A2B3C4D"))
        assertEquals(listOf(DeviceFilter.PHONES, DeviceFilter.APPLE), kinds("iPhone or cellular iPad"))
        assertTrue(DeviceFilter.APPLE in kinds("AirPods Pro"))
        assertTrue(DeviceFilter.APPLE in kinds("Apple TV / AirPlay speaker"))
        assertEquals(listOf(DeviceFilter.APPLE), kinds("Bluetooth device", vendor = "Apple, Inc."))
        // Not computers: a guess that may be a phone, a plain WiFi client, a machine-like word.
        assertTrue(DeviceFilter.COMPUTERS !in kinds("Apple device (iPhone, iPad, Mac or Watch)"))
        assertEquals(emptyList<DeviceFilter>(), kinds("WiFi device (laptop, phone or camera on a network)"))
        val client = DeviceFilter.Seen("02:00:00:00:00:73", "WiFi device (laptop, phone or camera on a network)", null, emptyList(), emptySet(), false)
        assertTrue(DeviceFilter.OTHER.matches(client))
        assertTrue(!DeviceFilter.OTHER.matches(DeviceFilter.Seen("02:00:00:00:00:74", "Computer", null, emptyList(), emptySet(), false)))
        assertTrue(DeviceFilter.COMPUTERS !in kinds("Machine sensor"))
    }

    @Test
    fun categoriesFromEveryClue() {
        fun tlv(type: Int, value: String) = byteArrayOf((type shr 8).toByte(), type.toByte(), 0, value.length.toByte()) + value.toByteArray()
        fun wps(vararg attrs: ByteArray) = 221 to (byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + attrs.fold(ByteArray(0)) { a, b -> a + b })
        val rokuTag = 221 to byteArrayOf(0xD8.toByte(), 0x31, 0x34, 0x01, 0x02)
        fun hidden(mac: String, ies: List<Pair<Int, ByteArray>>) = DeviceIntel.identify(
            Advert(mac, Advert.Source.WIFI, -60, null, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, ies)), VendorDb.macVendor(mac))

        // A TV's hidden network (random address) named by its WPS device name, or by the maker's tag alone.
        val tv = hidden("06:00:00:00:00:81", listOf(wps(tlv(0x1011, "65\" TCL Roku TV")), rokuTag))
        assertEquals("Smart TV / streaming device (WiFi)", tv.type)
        assertTrue(tv.facts.contains("Network name" to "Hidden (not broadcast)"))
        assertEquals("Smart TV / streaming device (WiFi)", hidden("06:00:00:00:00:82", listOf(rokuTag)).type)
        // A router's WPS name stays a router.
        val router = DeviceIntel.identify(Advert("02:11:22:33:44:83", Advert.Source.WIFI, -60, null,
            wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, listOf(wps(tlv(0x1021, "NETGEAR, Inc."), tlv(0x1023, "R7000"), tlv(0x1011, "Nighthawk"))))))
        assertEquals("Home router - NETGEAR R7000", router.type)

        // The chips read every clue, not only the type that won.
        fun kinds(type: String, facts: List<Pair<String, String>> = emptyList(), name: String? = null, vendor: String? = null,
                  sources: Set<Advert.Source> = setOf(Advert.Source.WIFI)) =
            DeviceFilter.KINDS.filter { it.matches(DeviceFilter.Seen("06:00:00:00:00:84", type, name, emptyList(), sources, false, facts, vendor = vendor)) }
        assertEquals(listOf(DeviceFilter.TVS), kinds("Hidden WiFi network", listOf("AP device name (WPS)" to "65\" TCL Roku TV")))
        assertEquals(listOf(DeviceFilter.TVS), kinds("Hidden WiFi network", listOf("Equipment maker (vendor IEs)" to "Roku")))
        assertTrue(DeviceFilter.TVS in kinds("WiFi access point", listOf("Device kind (WPS)" to "Gaming device")))
        assertTrue(DeviceFilter.AUDIO in kinds("Bluetooth device", listOf("Google Fast Pair" to "Ready to pair, model ID 0x123456 (Pixel Buds Pro)"),
            sources = setOf(Advert.Source.BLE)))
        assertTrue(DeviceFilter.COMPUTERS in kinds("Bluetooth device", name = "Jane's MacBook Air", sources = setOf(Advert.Source.BLE)))
        assertTrue(DeviceFilter.TVS in kinds("Bluetooth device", vendor = "Roku, Inc.", sources = setOf(Advert.Source.BLE)))
        // A network's name already decided its type: it isn't read again (a router called "Galaxy" isn't a phone).
        assertTrue(DeviceFilter.PHONES !in kinds("Home router - NETGEAR", name = "Galaxy"))
        // A provider's hotspot on a home gateway stays off Routers.
        assertEquals(listOf(DeviceFilter.HOTSPOTS), kinds("Public WiFi hotspot (Passpoint)", listOf("Device kind (WPS)" to "Router")))
    }

    @Test
    fun carWifiIsAVehicleNotARouter() {
        fun wifi(mac: String, ssid: String) = DeviceIntel.identify(
            Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList())), VendorDb.macVendor(mac)).type
        listOf("myChevrolet", "myBuick", "CHEVROLET 4G", "myGMC", "Kia Hotspot", "FordPass 1234").forEach {
            assertEquals(it, "Vehicle WiFi", wifi("06:00:00:00:00:91", it))
        }
        fun kinds(type: String) = DeviceFilter.KINDS.filter { it.matches(DeviceFilter.Seen("06:00:00:00:00:92", type, null, emptyList(), setOf(Advert.Source.WIFI), false)) }
        assertEquals(listOf(DeviceFilter.VEHICLES), kinds("Vehicle WiFi"))
        assertEquals(listOf(DeviceFilter.VEHICLES), kinds("Vehicle / cellular router (WiFi)"))
        assertEquals(listOf(DeviceFilter.ROUTERS), kinds("WiFi access point"))
    }

    @Test
    fun ledControllersAlarmPanelsAndIntelPcs() {
        assertEquals("Smart light / LED controller", DeviceIntel.identify(Advert("C1:22:33:44:55:A1", Advert.Source.BLE, -60, "ELK-BLEDOM04")).type)
        fun wifi(mac: String, client: Boolean) = DeviceIntel.identify(Advert(mac, Advert.Source.WIFI, -60, if (client) null else "Home",
            wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList(), client = client)), VendorDb.macVendor(mac))
        val panel = wifi("18:C1:E2:00:00:A2", client = false)
        assertEquals("Alarm / security panel (WiFi)", panel.type)
        fun kinds(type: String, vendor: String?) = DeviceFilter.KINDS.filter {
            it.matches(DeviceFilter.Seen("02:00:00:00:00:A3", type, null, emptyList(), setOf(Advert.Source.WIFI), false, vendor = vendor))
        }
        assertEquals(listOf(DeviceFilter.SMART_HOME), kinds(panel.type, "Qolsys Inc."))
        // A plain WiFi client is in no category, an Intel one is a PC's.
        assertEquals(listOf(DeviceFilter.COMPUTERS), kinds(DeviceIntel.WIFI_CLIENT_TYPE, VendorDb.macVendor("00:1B:21:00:00:A4")))
    }

    @Test
    fun partsMakersModulesInATvArentAVehicle() {
        // LG Innotek makes LG TVs' Bluetooth: an LG TV named by its advert stays a TV, never a vehicle.
        val tv = DeviceFilter.Seen("02:00:00:00:00:B1", "TV / streaming device", "[LG] webOS TV", emptyList(), setOf(Advert.Source.BLE), false, vendor = "LG Innotek")
        assertEquals(listOf(DeviceFilter.TVS), DeviceFilter.KINDS.filter { it.matches(tv) })
        // A Denso Wave barcode scanner isn't a car part either.
        assertEquals(null, DeviceIntel.automotiveMaker("DENSO WAVE INC."))
        assertEquals("Denso", DeviceIntel.automotiveMaker("DENSO Co.,Ltd"))
    }

    @Test
    fun theMatchHistoryKeepsTheRadioSide() {
        val ap = Advert("02:00:00:00:00:C1", Advert.Source.WIFI, -60, "Cafe",
            wifi = Advert.WifiInfo(5180, "[WPA2-PSK-CCMP][ESS]", "802.11ax", emptyList()))
        assertEquals("5 GHz · channel 36 · WPA2 · 802.11ax", DeviceIntel.radioSummary(ap))
        val six = Advert("02:00:00:00:00:C2", Advert.Source.WIFI, -60, "x", wifi = Advert.WifiInfo(5975, "[SAE]", null, emptyList()))
        assertEquals("6 GHz · channel 5 · WPA3", DeviceIntel.radioSummary(six))
        val tag = Advert("C2:00:00:00:00:C3", Advert.Source.BLE, -60, addressType = AddressType.RANDOM_STATIC,
            connectable = true, phy = "LE 1M", txPower = 4)
        assertEquals("BLE · Random static · connectable · LE 1M · TX 4 dBm", DeviceIntel.radioSummary(tag))
        assertNull(DeviceIntel.radioSummary(Advert("C2:00:00:00:00:C4", Advert.Source.BLE, -60)))
    }
}
