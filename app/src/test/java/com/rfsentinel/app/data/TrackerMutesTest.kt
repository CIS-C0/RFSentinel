package com.rfsentinel.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMutesTest {

    private val airtag = "Apple Find My tracker away from its owner (AirTag or compatible)"
    private val t0 = 1_000_000_000L

    @Test
    fun todayOnlyExpiresAfterADay() {
        val s = TrackerMutes.Store()
        s.mute("AA:00:00:00:00:01", airtag, -50, t0, follow = false)
        assertTrue(s.isMuted("AA:00:00:00:00:01", t0 + 3600_000))
        assertFalse(s.isMuted("AA:00:00:00:00:01", t0 + TrackerMutes.DAY_MS + 1))
        // Not followed to a new address.
        assertFalse(s.onSeen("AA:00:00:00:00:02", airtag, -50, t0 + 60_000))
    }

    @Test
    fun mineFollowsTheDailyAddressChange() {
        val s = TrackerMutes.Store()
        s.mute("AA:00:00:00:00:01", airtag, -50, t0, follow = true)
        // Old address silent for 30 s, a new one appears at a similar strength: same tag.
        assertTrue(s.onSeen("AA:00:00:00:00:02", airtag, -55, t0 + 30_000))
        assertTrue(s.isMuted("AA:00:00:00:00:02", t0 + 31_000))
        assertFalse(s.isMuted("AA:00:00:00:00:01", t0 + 31_000))
        // ...and again the next day.
        assertTrue(s.onSeen("AA:00:00:00:00:02", airtag, -52, t0 + 2 * 3600_000))
        assertTrue(s.onSeen("AA:00:00:00:00:03", airtag, -50, t0 + 2 * 3600_000 + 40_000))
        assertEquals(1, s.mutes.size)
    }

    @Test
    fun aDifferentTagIsNotSilenced() {
        val s = TrackerMutes.Store()
        s.mute("AA:00:00:00:00:01", airtag, -50, t0, follow = true)
        // Much weaker: likely another tag, not yours.
        assertFalse(s.onSeen("BB:00:00:00:00:01", airtag, -85, t0 + 30_000))
        // Yours still around (not silent): a second tag doesn't steal the mute.
        assertTrue(s.onSeen("AA:00:00:00:00:01", airtag, -50, t0 + 40_000))
        assertFalse(s.onSeen("BB:00:00:00:00:02", airtag, -50, t0 + 45_000))
        // Too long after it went quiet.
        assertFalse(s.onSeen("BB:00:00:00:00:03", airtag, -50, t0 + 40_000 + TrackerMutes.CARRY_WINDOW_MS + 1))
        // Another kind of tracker.
        assertFalse(s.onSeen("BB:00:00:00:00:04", "Tile tracker", -50, t0 + 70_000))
    }

    @Test
    fun yourTagStillAroundKeepsItsMute() {
        val s = TrackerMutes.Store()
        s.mute("AA:00:00:00:00:01", airtag, -50, t0, follow = true)
        // Your tag keeps advertising every few seconds for a minute...
        var t = t0
        while (t < t0 + 60_000) { t += 5_000; assertTrue(s.onSeen("AA:00:00:00:00:01", airtag, -50, t)) }
        // ...so a stranger's tag of the same kind showing up now doesn't take its mute.
        assertFalse(s.onSeen("BB:00:00:00:00:01", airtag, -52, t + 2_000))
        assertTrue(s.isMuted("AA:00:00:00:00:01", t + 2_000))
    }

    @Test
    fun linkedAddressInheritsRightAway() {
        val s = TrackerMutes.Store()
        s.mute("AA:00:00:00:00:01", airtag, -50, t0, follow = true)
        // The scanner linked the new address to the old one (same advert fingerprint):
        // carried over at once, even at a different strength.
        assertTrue(s.onSeen("AA:00:00:00:00:02", airtag, -75, t0 + 3_000, linkedFrom = "AA:00:00:00:00:01"))
        assertTrue(s.isMuted("AA:00:00:00:00:02", t0 + 4_000))
        // A "today only" mute is never carried, linked or not.
        s.mute("CC:00:00:00:00:01", airtag, -50, t0, follow = false)
        assertFalse(s.onSeen("CC:00:00:00:00:02", airtag, -50, t0 + 3_000, linkedFrom = "CC:00:00:00:00:01"))
    }
}
