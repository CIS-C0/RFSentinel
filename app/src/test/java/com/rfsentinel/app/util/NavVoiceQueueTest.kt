package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Driving directions share the voice with alerts but are not alerts: never merged, never "a repeat", gone once stale. */
class NavVoiceQueueTest {

    @Test
    fun theSameDirectionTwiceIsTwoTurns() {
        val q = VoiceQueue()
        assertTrue(q.addNav("Turn left.", 0))
        assertEquals("Turn left.", q.next(1_000)?.text)
        // An alert's repeat gap would drop this; a second left turn a few seconds later is real.
        assertTrue(q.addNav("Turn left.", 5_000))
        assertEquals("Turn left.", q.next(5_100)?.text)
    }

    @Test
    fun theSameWordsAlreadyWaitingAreNotQueuedTwice() {
        val q = VoiceQueue()
        assertTrue(q.addNav("Turn left.", 0))
        assertFalse(q.addNav("turn left.", 100))
        assertEquals(1, q.size)
    }

    @Test
    fun directionsAreNeverMergedIntoAlerts() {
        val q = VoiceQueue()
        q.add("Axon body camera nearby", VoiceQueue.WEAK, 0)
        q.add("Flock camera nearby", VoiceQueue.WEAK, 0)
        q.add("Drone overhead", VoiceQueue.WEAK, 0)
        q.addNav("In 500 meters, keep right.", 0)
        // The direction first (it ties with a strong alert and is older than nothing), the alerts merged after it.
        val first = q.next(100)!!
        assertTrue(first.text, first.nav && first.text.startsWith("In 500 meters"))
        val second = q.next(200)!!
        assertTrue(second.text, second.text.startsWith("3 alerts:"))
        assertFalse(second.nav)
        assertNull(q.next(300))
    }

    @Test
    fun aDirectionGoesStaleSoonerThanAnAlert() {
        val q = VoiceQueue()
        q.addNav("Turn right.", 0)
        q.add("Axon body camera nearby", VoiceQueue.WEAK, 0)
        // 8 s later: the turn is behind you; the alert is still worth saying.
        val next = q.next(8_000)!!
        assertEquals("Axon body camera nearby", next.text)
        assertNull(q.next(8_100))
    }

    @Test
    fun aFollowingAlertStillGoesFirst() {
        val q = VoiceQueue()
        q.addNav("Turn right.", 0)
        q.add("Something is following you", VoiceQueue.FOLLOWING, 10)
        assertEquals("Something is following you", q.next(100)?.text)
        assertEquals("Turn right.", q.next(200)?.text)
    }

    @Test
    fun aDirectionIsNotRememberedAsSaidSoAnAlertOfTheSameWordsStillSpeaks() {
        val q = VoiceQueue()
        q.addNav("Drone overhead", 0)
        q.next(100)
        assertTrue(q.add("Drone overhead", VoiceQueue.WEAK, 1_000))
    }
}
