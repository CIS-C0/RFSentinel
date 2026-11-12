package com.rfsentinel.app.link

import com.rfsentinel.app.detect.Advert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentinel Link › Auto send pace ([MeshPace]). */
class MeshPaceTest {
    @Test
    fun quietChannelGoesFastestBusyOneBacksOff() {
        assertEquals(MeshPace.FASTEST_MS, MeshPace.intervalMs(3f, 1f, 10, waiting = true))
        assertEquals("nothing waiting", MeshPace.IDLE_MS, MeshPace.intervalMs(3f, 1f, 10, waiting = false))
        assertEquals("no telemetry yet", MeshPace.UNKNOWN_MS, MeshPace.intervalMs(null, null, -1, waiting = true))
        assertEquals("25 % channel use: half the pace", 30_000L, MeshPace.intervalMs(25f, 1f, 10, waiting = true))
        assertEquals("own air time near the duty cycle", 30_000L, MeshPace.intervalMs(5f, 8f, 10, waiting = true))
        assertEquals("queue nearly full", 30_000L, MeshPace.intervalMs(3f, 1f, 1, waiting = true))
        assertEquals("never slower than the slowest", MeshPace.SLOWEST_MS, MeshPace.intervalMs(90f, 40f, 0, waiting = true))
    }

    @Test
    fun waitingFollowsWhatIsDue() {
        val box = MeshOutbox()
        assertFalse(box.waiting(1_000))
        box.add(Advert("00:00:00:00:00:01", Advert.Source.WIFI, -60, timestamp = 0), emptyList(), 1_000)
        assertTrue(box.waiting(1_000))
        assertFalse("heard too long ago", box.waiting(1_000 + MeshOutbox.FRESH_MS + 1))
    }
}
