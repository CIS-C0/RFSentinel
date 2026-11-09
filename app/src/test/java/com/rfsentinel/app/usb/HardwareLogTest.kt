package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The exportable hardware log: status lines once per change, no server names or IP addresses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HardwareLogTest {
    private fun lines(who: String) = UsbWifi.logText().lines().filter { who in it }

    @Test
    fun statusLogsOncePerChangeNotPerCount() {
        val who = "TestRadio${System.nanoTime()}"
        UsbWifi.logStatus(who, "")
        UsbWifi.logStatus(who, "$who: connecting…")
        UsbWifi.logStatus(who, "$who · live · 1 node heard")
        UsbWifi.logStatus(who, "$who · live · 12 node heard")
        UsbWifi.logStatus(who, "$who: out of reach")
        UsbWifi.logStatus(who, "")
        UsbWifi.logStatus(who, "")
        val l = lines(who)
        assertEquals(l.joinToString(" | "), 4, l.size)
        listOf("connecting", "live · 1 node", "out of reach", "$who: stopped").forEachIndexed { i, s -> assertTrue(l[i], s in l[i]) }
    }

    @Test
    fun redactHidesTheBrokerAndIps() {
        val r = UsbWifi.redact("Can't reach mqtt.example.org - failed to connect to mqtt.example.org/203.0.113.7 (port 8883), retrying", "mqtt.example.org")
        assertFalse(r, "example" in r || "203.0" in r)
        assertTrue(r, "port 8883" in r)
        val v6 = UsbWifi.redact("failed to connect to /[2001:db8::1] after 10000ms", null)
        assertFalse(v6, "2001" in v6)
        assertTrue(v6, "10000ms" in v6)
    }
}
