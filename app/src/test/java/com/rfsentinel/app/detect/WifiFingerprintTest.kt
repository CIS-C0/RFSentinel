package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class WifiFingerprintTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    private fun tlv(type: Int, value: ByteArray) =
        byteArrayOf((type shr 8).toByte(), type.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + value

    private fun wps(vararg attrs: ByteArray) =
        221 to (byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + attrs.fold(ByteArray(0)) { a, b -> a + b })

    private val routerType = tlv(0x1054, byteArrayOf(0, 6, 0x00, 0x50, 0xF2.toByte(), 0x04, 0, 2))

    private fun wifi(mac: String, ssid: String?, ies: List<Pair<Int, ByteArray>>) =
        Advert(mac, Advert.Source.WIFI, -60, ssid, wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", "WiFi 6 (802.11ax)", ies))

    @Test
    fun wpsGivesMakeAndModel() {
        val ies = listOf(wps(
            tlv(0x1021, "NETGEAR, Inc.".toByteArray()),
            tlv(0x1023, "R7000".toByteArray()),
            tlv(0x1024, "R7000".toByteArray()),
            tlv(0x1011, "Nighthawk".toByteArray()),
            routerType
        ))
        val info = WifiFingerprint.parse(ies)
        assertEquals("NETGEAR R7000", info.model)
        assertEquals("Router", info.deviceKind)
        assertEquals("Nighthawk", info.deviceName)

        val id = DeviceIntel.identify(wifi("02:11:22:33:44:55", null, ies))
        assertEquals("Hidden WiFi network - NETGEAR R7000", id.type)
        assertTrue(id.facts.contains("Identified by" to "WPS model in the beacon"))
    }

    @Test
    fun wpsSplitOverTwoElementsAndPlaceholdersIgnored() {
        val whole = tlv(0x1021, "TP-Link".toByteArray()) + tlv(0x1023, "Archer AX55".toByteArray())
        val ies = listOf(
            221 to (byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + whole.copyOfRange(0, 6)),
            221 to (byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) + whole.copyOfRange(6, whole.size))
        )
        assertEquals("TP-Link Archer AX55", WifiFingerprint.parse(ies).model)
        val junk = listOf(wps(tlv(0x1021, "0000".toByteArray()), tlv(0x1023, "Wireless Router".toByteArray())))
        assertNull(WifiFingerprint.parse(junk).model)
    }

    @Test
    fun ciscoApNameAndVendorElements() {
        val cisco = 133 to (ByteArray(10) + "LOBBY-AP-02".toByteArray() + ByteArray(5) + ByteArray(4))
        val broadcom = 221 to byteArrayOf(0x00, 0x10, 0x18, 0x02, 0x00)
        val apple = 221 to byteArrayOf(0x00, 0x17, 0xF2.toByte(), 0x0A, 0x00)
        val wmm = 221 to byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x02, 0x01)
        val info = WifiFingerprint.parse(listOf(cisco, broadcom, apple, wmm))
        assertEquals("LOBBY-AP-02", info.ciscoApName)
        assertTrue(info.chipsetVendors.single().contains("Broadcom", true))
        assertTrue(info.equipmentVendors.single().contains("Apple", true))

        // Locally administered BSSID (no registrant): the equipment vendor IE names the maker.
        val id = DeviceIntel.identify(wifi("06:11:22:33:44:55", null, listOf(broadcom, apple)))
        assertEquals("Hidden WiFi network - Apple", id.type)
    }

    @Test
    fun hiddenNetworkFallsBackToBssidRegistrant() {
        val mac = "00:30:44:12:34:56" // Cradlepoint: a vehicle router kind wins
        val id = DeviceIntel.identify(wifi(mac, null, emptyList()), VendorDb.macVendor(mac))
        assertEquals("Vehicle / cellular router (WiFi)", id.type)
        assertEquals("NETGEAR", DeviceIntel.shortVendor("NETGEAR, Inc."))
        assertEquals("Cisco", DeviceIntel.shortVendor("Cisco Systems, Inc"))
        assertEquals("Samsung Electronics Co.,Ltd".let { DeviceIntel.shortVendor(it) }, "Samsung")
    }

    @Test
    fun sameAccessPointHeuristic() {
        assertTrue(WifiFingerprint.sameAccessPoint("A4:2B:B0:11:22:30", "A6:2B:B0:11:22:31"))  // virtual BSSID
        assertTrue(WifiFingerprint.sameAccessPoint("A4:2B:B0:11:22:30", "A4:2B:B0:11:22:38"))  // other band
        assertFalse(WifiFingerprint.sameAccessPoint("A4:2B:B0:11:22:30", "A4:2B:B0:11:23:30")) // other device
        assertFalse(WifiFingerprint.sameAccessPoint("A4:2B:B0:11:22:30", "B8:2B:B0:11:22:30")) // different maker
        assertFalse(WifiFingerprint.sameAccessPoint("A4:2B:B0:11:22:30", "A4:2B:B0:11:22:30"))
    }
}
