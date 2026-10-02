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
}
