package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbPickTest {

    @Test
    fun theSavedAdapterOrNothingAutoTakesTheFirst() {
        val plugged = listOf("0e8d:7961", "0bda:8771") // a combo WiFi adapter and a Bluetooth dongle
        assertEquals("0e8d:7961", UsbPick.choose(plugged, null) { it })
        assertEquals("0bda:8771", UsbPick.choose(plugged, "0bda:8771") { it })
        // The picked one is unplugged: wait for it, never fall back to another.
        assertNull(UsbPick.choose(plugged, "2357:0138") { it })
    }
}
