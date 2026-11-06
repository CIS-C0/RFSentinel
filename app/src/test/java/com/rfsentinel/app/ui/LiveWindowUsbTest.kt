package com.rfsentinel.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveWindowUsbTest {
    @After fun reset() { LiveWindow.usbQuickClear = true }

    @Test
    fun aUsbHeardDeviceEndsSoonerOnlyWithTheSwitchOn() {
        LiveWindow.wifiScanMs = 30_000L
        assertEquals(LiveWindow.USB_GONE_MS, LiveWindow.wifiWindowMs(usbHeard = true))
        assertEquals(40_000L, LiveWindow.wifiWindowMs(usbHeard = false))
        LiveWindow.usbQuickClear = false
        assertEquals(40_000L, LiveWindow.wifiWindowMs(usbHeard = true))
    }
}
