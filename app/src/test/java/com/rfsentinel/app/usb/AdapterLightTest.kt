package com.rfsentinel.app.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RTL8811AU / 8821AU adapter's light: blinks with traffic, steady when quiet, dark when switched off. */
class AdapterLightTest {

    @Test
    fun blinksWhileFramesArriveAndStaysOnWhenQuiet() {
        assertFalse(Rtl8821auMonitor.nextLight(true, framesArrived = true, on = true))
        assertTrue(Rtl8821auMonitor.nextLight(true, framesArrived = true, on = false))
        assertTrue(Rtl8821auMonitor.nextLight(true, framesArrived = false, on = false))
        assertTrue(Rtl8821auMonitor.nextLight(true, framesArrived = false, on = true))
    }

    @Test
    fun staysDarkWhenSwitchedOff() {
        for (arrived in listOf(true, false)) for (on in listOf(true, false))
            assertFalse(Rtl8821auMonitor.nextLight(false, arrived, on))
    }
}
