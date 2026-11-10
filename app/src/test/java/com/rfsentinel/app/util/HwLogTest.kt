package com.rfsentinel.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each piece of hardware's own log: status lines once per change, its own file, no server names or IP addresses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HwLogTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun statusLogsOncePerChangeNotPerCount() {
        val log = HwLog("Test radio", "test-status.log")
        log.logStatus("Radio", "")
        log.logStatus("Radio", "Radio: connecting…")
        log.logStatus("Radio", "Radio · live · 1 node heard")
        log.logStatus("Radio", "Radio · live · 12 nodes heard")
        log.logStatus("Radio", "Radio: out of reach")
        log.logStatus("Radio", "")
        log.logStatus("Radio", "")
        val l = log.text().lines()
        assertEquals(l.joinToString(" | "), 4, l.size)
        listOf("connecting", "live · 1 node", "out of reach", "Radio: stopped").forEachIndexed { i, s -> assertTrue(l[i], s in l[i]) }
    }

    @Test
    fun eachLogKeepsItsOwnFile() {
        HwLog.init(ctx)
        val a = HwLog("A", "test-a.log")
        val b = HwLog("B", "test-b.log")
        assertTrue(a.isEmpty(ctx))
        a.log("only in A")
        assertFalse(a.isEmpty(ctx))
        assertTrue(b.isEmpty(ctx))
        val report = a.report(ctx, "A · live", "USB devices plugged in now:\n  none\n")
        assertTrue(report, "RF Sentinel - A log" in report && "Status: A · live" in report && "only in A" in report && "none" in report)
        assertFalse("only in A" in b.report(ctx, ""))
        assertEquals(10, HwLogs.all.map { it.file }.toSet().size)
    }

    @Test
    fun redactHidesTheBrokerAndIps() {
        val r = HwLog.redact("Can't reach mqtt.example.org - failed to connect to mqtt.example.org/203.0.113.7 (port 8883), retrying", "mqtt.example.org")
        assertFalse(r, "example" in r || "203.0" in r)
        assertTrue(r, "port 8883" in r)
        val v6 = HwLog.redact("failed to connect to /[2001:db8::1] after 10000ms", null)
        assertFalse(v6, "2001" in v6)
        assertTrue(v6, "10000ms" in v6)
    }
}
