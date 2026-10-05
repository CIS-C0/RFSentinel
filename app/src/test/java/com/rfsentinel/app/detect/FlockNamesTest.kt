package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlockNamesTest {
    private fun ble(name: String, xuntong: Boolean) = Advert(
        mac = "AA:BB:CC:DD:EE:FF", source = Advert.Source.BLE, rssi = -60, name = name,
        manufacturerData = if (xuntong) mapOf(0x09C8 to ByteArray(0)) else emptyMap(), timestamp = 0L
    )

    @Test
    fun numericPenguinNeedsXuntong() {
        assertEquals(80, SignatureEngine.classify(ble("1234567890", xuntong = true)).first { it.category == Category.ALPR }.confidence)
        assertTrue(SignatureEngine.classify(ble("1234567890", xuntong = false)).none { it.category == Category.ALPR })
    }

    @Test
    fun pigvisionName() {
        assertTrue(SignatureEngine.classify(ble("Pigvision-01", xuntong = false)).any { it.label == "Possible Flock Safety device" })
    }

    private fun ap(mac: String, ssid: String) = Advert(
        mac = mac, source = Advert.Source.WIFI, rssi = -60, name = ssid,
        wifi = Advert.WifiInfo(2437, "", null, emptyList()), timestamp = 0L
    )

    @Test
    fun flockModuleNeedsProductNameOrHiddenNetwork() {
        assertEquals(85, SignatureEngine.classify(ap("70:C9:4E:11:22:33", "Falcon-1234")).first { it.category == Category.ALPR }.confidence)
        assertEquals(35, SignatureEngine.classify(ap("70:C9:4E:11:22:33", "")).first { it.category == Category.ALPR }.confidence)
        assertTrue(SignatureEngine.classify(ap("70:C9:4E:11:22:33", "HomeWifi")).none { it.category == Category.ALPR })
        assertTrue(SignatureEngine.classify(ap("11:22:33:44:55:66", "Falcon-1234")).none { it.category == Category.ALPR })
    }

    @Test
    fun flockGattRavenGpsAndTrafficCameraMakers() {
        val gatt = Advert(mac = "AA:BB:CC:DD:EE:01", source = Advert.Source.BLE, rssi = -60, name = null,
            serviceUuids = listOf(java.util.UUID.fromString("e8ccbb38-9532-46a8-9fe5-1814df172e6f")), timestamp = 0L)
        assertEquals(80, SignatureEngine.classify(gatt).first { it.category == Category.ALPR }.confidence)
        val raven = Advert(mac = "AA:BB:CC:DD:EE:02", source = Advert.Source.BLE, rssi = -60, name = null,
            serviceUuids = listOf(Advert.uuid16(0x3101)), timestamp = 0L)
        assertTrue(SignatureEngine.classify(raven).any { it.category == Category.AUDIO_SENSOR })
        val jenoptik = SignatureEngine.classify(ap("00:04:4C:11:22:33", "Office"))
        assertTrue(jenoptik.any { it.label == "Jenoptik traffic / plate-camera hardware" })
        assertEquals("Hikvision", SignatureEngine.cameraMaker("Hangzhou Hikvision Digital Technology Co.,Ltd."))
        assertEquals("Eufy (Anker)", SignatureEngine.cameraMaker("Fantasia Trading LLC"))
    }
}
