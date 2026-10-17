package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

/** Ideas from Wardrive Go (facts only): EMS gear, IP-camera setup networks, train WiFi, mesh radio nodes. */
class WardriveGoIdeasTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    @Before fun reset() = HackerWatch.reset()

    private fun ble(mac: String, name: String? = null, uuids: List<UUID> = emptyList()) =
        Advert(mac = mac, source = Advert.Source.BLE, rssi = -60, name = name, serviceUuids = uuids, timestamp = 0L)

    private fun ap(mac: String, ssid: String) =
        Advert(mac = mac, source = Advert.Source.WIFI, rssi = -60, name = ssid,
            wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList()), timestamp = 0L)

    @Test
    fun ambulanceGear() {
        val zoll = SignatureEngine.classify(ble("5a:11:22:33:44:55", uuids = listOf(Advert.uuid16(0xFE22))))
        assertEquals(Category.PUBLIC_SAFETY, zoll.first().category)
        assertTrue(zoll.first().label.startsWith("ZOLL"))
        val lifepak = SignatureEngine.classify(ap("40:BD:9E:12:34:56", "LP15-1234"))
        assertNotNull(lifepak.firstOrNull { it.label.startsWith("Physio-Control (LIFEPAK)") })
    }

    @Test
    fun ipCameraSetupNetwork() {
        val hit = SignatureEngine.classify(ap("02:12:34:56:78:9a", "IPC-8F3A21")).firstOrNull { it.category == Category.OTHER_CAMERA }
        assertNotNull(hit)
        assertEquals(50, hit!!.confidence)
        assertNull("an ordinary name", SignatureEngine.classify(ap("02:12:34:56:78:9a", "IPCC Office")).firstOrNull { it.category == Category.OTHER_CAMERA })
    }

    @Test
    fun trainWifiIsNotAPoliceCar() {
        val router = SignatureEngine.policeVehicleSsid("IBR900-3ab")!!
        val onTrain = ap("00:30:44:12:34:56", "AmtrakConnect")
        assertTrue(DeviceIntel.dropTrainRouter(listOf(router), onTrain).isEmpty())
        assertEquals(1, DeviceIntel.dropTrainRouter(listOf(router), ap("00:30:44:12:34:56", "IBR900-3ab")).size)
        assertEquals("Train WiFi (passenger rail)", DeviceIntel.identify(ap("02:00:00:00:00:01", "_SNCF_WIFI_INOUI"), null).type)
        assertEquals("Train WiFi (passenger rail)", DeviceIntel.identify(ap("02:00:00:00:00:01", "WIFIonICE"), null).type)
    }

    @Test
    fun meshRadioNodes() {
        assertTrue(DeviceIntel.identify(ble("5a:00:00:00:00:01", "Meshtastic_1a2b"), null).type.startsWith("Mesh radio node"))
        assertTrue(DeviceIntel.identify(ble("5a:00:00:00:00:02", "MeshCore-ab12cd"), null).type.startsWith("Mesh radio node"))
        val byService = ble("5a:00:00:00:00:03", uuids = listOf(UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")))
        assertTrue(DeviceIntel.identify(byService, null).type.startsWith("Mesh radio node"))
    }
}
