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
}
