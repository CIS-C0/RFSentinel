package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueueTest {

    @Test
    fun mostUrgentFirstThenOldest() {
        val q = VoiceQueue()
        q.add("Drone overhead", VoiceQueue.PROBABLE, 0)
        q.add("Warning. Tracker may be following you.", VoiceQueue.FOLLOWING, 100)
        assertEquals("Warning. Tracker may be following you.", q.next(200)!!.text)
        assertEquals("Drone overhead", q.next(300)!!.text)
        assertNull(q.next(400))
    }

    @Test
    fun staleAlertsAreDropped() {
        val q = VoiceQueue()
        q.add("Flock camera nearby", VoiceQueue.STRONG, 0)
        assertNull(q.next(VoiceQueue.MAX_AGE_MS + 1))
    }

    @Test
    fun noRepeatWithin30Seconds() {
        val q = VoiceQueue()
        assertTrue(q.add("Axon body camera nearby", VoiceQueue.STRONG, 0))
        assertFalse(q.add("axon body camera nearby", VoiceQueue.STRONG, 10)) // already waiting
        q.next(20)
        assertFalse(q.add("Axon body camera nearby", VoiceQueue.STRONG, 10_000))
        assertTrue(q.add("Axon body camera nearby", VoiceQueue.STRONG, 40_000))
    }

    @Test
    fun sameWordsAboutAnotherDeviceAreSaid() {
        val q = VoiceQueue()
        assertTrue(q.add("Police car nearby", VoiceQueue.STRONG, 0, key = "AA:01"))
        assertFalse(q.add("Police car nearby", VoiceQueue.STRONG, 5, key = "aa:01")) // the same router again
        assertEquals("Police car nearby", q.next(10)!!.text)
        // A second patrol car on the same network name, 5 s later: said again.
        assertTrue(q.add("Police car nearby", VoiceQueue.STRONG, 5_000, key = "AA:02"))
        assertEquals("Police car nearby", q.next(5_010)!!.text)
        // Two more waiting together become one sentence, and neither repeats after it.
        assertTrue(q.add("Police car nearby", VoiceQueue.PROBABLE, 6_000, key = "AA:03"))
        assertTrue(q.add("Police car nearby", VoiceQueue.STRONG, 6_001, key = "AA:04"))
        val both = q.next(6_010)!!
        assertEquals("Police car nearby, 2 of them", both.text)
        assertEquals(VoiceQueue.STRONG, both.priority)
        assertNull(q.next(6_020))
        assertFalse(q.add("Police car nearby", VoiceQueue.STRONG, 7_000, key = "AA:03"))
        assertFalse(q.add("Police car nearby", VoiceQueue.STRONG, 7_000, key = "AA:04"))
    }

    @Test
    fun networkNameWords() {
        assertEquals(", network Fleet 12", Spoken.networkWords(" Fleet_12 ", "Vehicle router"))
        assertEquals("", Spoken.networkWords("  ", "Vehicle router"))
        assertEquals("", Spoken.networkWords("Fleet_12", "Watchlist: fleet_12")) // the label says it already
    }

    @Test
    fun pileUpBecomesOneSentence() {
        val q = VoiceQueue()
        q.add("Axon body camera nearby", VoiceQueue.STRONG, 0)
        q.add("Flock camera nearby", VoiceQueue.PROBABLE, 1)
        q.add("Drone overhead", VoiceQueue.PROBABLE, 2)
        q.add("Cell network warning", VoiceQueue.WEAK, 3)
        assertEquals(
            "4 alerts: Axon body camera nearby, Flock camera nearby and Drone overhead, and 1 more.",
            q.next(10)!!.text
        )
        assertEquals(0, q.size)
    }

    @Test
    fun onlyFollowingInterrupts() {
        val q = VoiceQueue()
        assertTrue(q.preempts(VoiceQueue.FOLLOWING, VoiceQueue.STRONG))
        assertFalse(q.preempts(VoiceQueue.STRONG, VoiceQueue.PROBABLE))
        assertFalse(q.preempts(VoiceQueue.FOLLOWING, VoiceQueue.FOLLOWING))
    }

    @Test
    fun alertsWaitForAPauseInRadioVoiceButNotForever() {
        val q = VoiceQueue()
        assertEquals(0L, q.radioWait(0, 0)) // nothing waiting
        q.add("Flock camera nearby", VoiceQueue.STRONG, 1_000)
        assertEquals(0L, q.radioWait(5_000, 1_000)) // radio quiet for 4 s: say it
        assertEquals(1_200L, q.radioWait(5_000, 5_000)) // radio playing: wait for 1.2 s of quiet
        assertEquals(1_000L, q.radioWait(10_000, 10_000)) // still playing: said 10 s after it was queued anyway
        assertEquals(0L, q.radioWait(11_000, 11_000))
        q.add("Vehicle following you", VoiceQueue.FOLLOWING, 11_000)
        assertEquals(0L, q.radioWait(11_000, 11_000)) // following you never waits
    }
}
