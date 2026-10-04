package com.rfsentinel.app.esp

import com.rfsentinel.app.detect.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines in the formats OUI-Spy and GhostESP print (made-up addresses and names). */
class EspReportsTest {

    @Test
    fun ouiSpyFlockYouWildcardProbe() {
        val s = OuiSpyReports.parse("""{"event":"detection","detection_method":"wifi_wildcard_probe","detection_tier":3,"protocol":"wifi_2_4ghz","mac_address":"70:c9:4e:11:22:33","oui":"70:c9:4e","device_name":"","rssi":-71,"channel":6,"frequency":2437,"ssid":""}""")!!
        assertEquals("70:C9:4E:11:22:33", s.mac)
        assertFalse(s.ble)
        assertEquals(2437, s.frequencyMhz)
        assertEquals(Category.ALPR, s.hits.single().category)
        assertEquals(80, s.hits.single().confidence)
    }

    @Test
    fun ouiSpyFlockYouBleTierAndDetectorTarget() {
        val ble = OuiSpyReports.parse("""{"event":"detection","detection_method":"ble_mfg","detection_tier":2,"protocol":"ble","mac_address":"aa:bb:cc:00:11:22","oui":"aa:bb:cc","device_name":"Penguin-1","rssi":-60,"channel":255,"frequency":0,"ssid":""}""")!!
        assertTrue(ble.ble)
        assertEquals("Penguin-1", ble.name)
        assertEquals(55, ble.hits.single().confidence)

        val target = OuiSpyReports.parse("""{"event":"detection","protocol":"ble","detection_method":"ble_oui","mac_address":"00:25:df:aa:bb:cc","addr_type":"public","rssi":-55,"rssi_min":-70,"rssi_max":-50,"company_id":845,"service_uuid":null,"local_name":"","device_name":"","match_method":"oui","matched_signature":"00:25:DF","first_seen_ms":1}""")!!
        assertEquals(845, target.companyId)
        assertNull(target.serviceUuid16)
        assertEquals("OUI-Spy target: 00:25:DF", target.hits.single().label)
    }

    @Test
    fun ouiSpySkySpyDrone() {
        val d = OuiSpyReports.parse("""{"mac":"60:60:1f:01:02:03","rssi":-80,"drone_lat":10.501000,"drone_long":-20.502000,"drone_altitude":120,"pilot_lat":10.500000,"pilot_long":-20.500000,"basic_id":"1581F5FJD123"}""")!!
        assertEquals("1581F5FJD123", d.remoteId?.uasId)
        assertEquals(10.501, d.remoteId!!.latitude!!, 1e-9)
        assertEquals(Category.DRONE, d.hits.single().category)
    }

    @Test
    fun ouiSpyIgnoresOtherLines() {
        assertNull(OuiSpyReports.parse("""{"status":"scanning"}"""))
        assertNull(OuiSpyReports.parse("[flockyou] DETECT-OUI mac=70:c9:4e:11:22:33 rssi=-70"))
        assertNull(OuiSpyReports.parse("{not json"))
        assertTrue(OuiSpyReports.recognises("boot...\n{\"status\":\"scanning\"}\n"))
        assertFalse(OuiSpyReports.recognises("Ghost ESP Commands:"))
    }

    @Test
    fun ghostEspNetworkList() {
        val text = "\u001B[0;32m[0] SSID: Example Net, BSSID: 34:53:D2:C4:5D:E6, RSSI: -55, Company: Unknown\u001B[0m\n" +
            "[1] SSID: (Hidden), BSSID: aa:bb:cc:dd:ee:ff, RSSI: -80, Channel: 11, Company: X\n" +
            "some other log line\n"
        val list = GhostEspReports.parseList(text)
        assertEquals(2, list.size)
        assertEquals("Example Net", list[0].name)
        assertEquals(-55, list[0].rssi)
        assertNull(list[1].name)
        assertEquals("AA:BB:CC:DD:EE:FF", list[1].mac)
        assertEquals(2462, list[1].frequencyMhz)
        assertTrue(GhostEspReports.recognises("Ghost ESP Commands:"))
    }

    @Test
    fun espChipsAreRecognised() {
        assertEquals(SerialPort.Chip.NATIVE_USB, SerialPort.chipOf(0x303A, 0x1001))
        assertEquals(SerialPort.Chip.CP210X, SerialPort.chipOf(0x10C4, 0xEA60))
        assertEquals(SerialPort.Chip.CH34X, SerialPort.chipOf(0x1A86, 0x7523))
        assertNull(SerialPort.chipOf(0x0BDA, 0x0811)) // not an ESP32 serial board
    }

    @Test
    fun marauderBeaconAndProbeLines() {
        val ap = MarauderReports.parse("-55 Ch: 36 34:53:d2:c4:5d:e6 ESSID: Example Net " + 13.toChar())!!
        assertEquals("34:53:D2:C4:5D:E6", ap.mac)
        assertEquals(-55, ap.rssi)
        assertEquals(5180, ap.frequencyMhz)
        assertEquals("Example Net", ap.name)
        assertFalse(ap.client)
        val hidden = MarauderReports.parse("-80 Ch: 6 aa:bb:cc:dd:ee:ff ESSID: ")!!
        assertNull(hidden.name)
        assertEquals(2437, hidden.frequencyMhz)
        val probe = MarauderReports.parse("-60 Ch: 11 Client: 02:11:22:33:44:55 Requesting: Home")!!
        assertTrue(probe.client)
        assertNull(probe.name)
        assertEquals(2462, probe.frequencyMhz)
        assertEquals(listOf("Home"), probe.probedSsids)
        assertTrue(MarauderReports.parse("-60 Ch: 1 Client: 02:11:22:33:44:55 Requesting: ")!!.probedSsids.isEmpty()) // wildcard
        assertNull(MarauderReports.parse("Beacon sniff"))
        assertTrue(MarauderReports.recognises("============ Commands ============\nchannel [-s <channel>]"))
        assertTrue(MarauderReports.isFlipperCli("Welcome to Flipper Zero Command Line Interface!\n>: "))
        assertFalse(MarauderReports.isFlipperCli("-55 Ch: 1 aa:bb:cc:dd:ee:ff ESSID: x"))
    }

    @Test
    fun flipperZeroIsASerialBoard() {
        assertEquals(SerialPort.Chip.NATIVE_USB, SerialPort.chipOf(0x0483, 0x5740))
    }
}
