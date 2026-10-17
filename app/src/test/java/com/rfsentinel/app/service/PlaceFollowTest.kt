package com.rfsentinel.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFollowTest {

    private val min = 60_000L
    /** About 1.1 km of latitude. */
    private val step = 0.01

    @Test
    fun turningUpAtThreePlacesWithGapsIsFollowing() {
        val p = PlaceFollow()
        p.heard("aa", null, 0, 10.5, -20.5)
        p.heard("aa", null, 6 * min, 10.5 + step, -20.5)   // gone in between: a new track each time
        assertFalse(p.follower("aa", 6 * min, 10 * min, 800.0))
        p.heard("aa", null, 14 * min, 10.5 + 2 * step, -20.5)
        assertEquals(3, p.places("aa", 10 * min, 800.0))
        assertTrue(p.follower("aa", 14 * min, 10 * min, 800.0))
        p.heard("aa", null, 30 * min, 10.5 + 3 * step, -20.5)
        assertFalse("once per window", p.follower("aa", 30 * min, 10 * min, 800.0))
    }

    @Test
    fun aFixedCameraPassedRepeatedlyIsOnePlace() {
        val p = PlaceFollow()
        for (i in 0 until 6) p.heard("cam", null, i * 10 * min, 10.5 + (i % 2) * 0.001, -20.5)
        assertEquals(0, p.places("cam", 10 * min, 800.0))
    }

    @Test
    fun tooQuickOrTooCloseIsNot() {
        val p = PlaceFollow()
        p.heard("bb", null, 0, 10.5, -20.5)
        p.heard("bb", null, 2 * min, 10.504, -20.5)
        p.heard("bb", null, 4 * min, 10.508, -20.5)
        assertEquals("under 10 min", 0, p.places("bb", 10 * min, 800.0))
        assertEquals("under 1500 m apart", 0, p.places("bb", 0, 1500.0))
    }

    @Test
    fun anAddressRotationKeepsItsPlacesAndItsAlert() {
        val p = PlaceFollow()
        p.heard("old", null, 0, 10.5, -20.5)
        p.heard("old", null, 6 * min, 10.5 + step, -20.5)
        p.heard("new", "old", 14 * min, 10.5 + 2 * step, -20.5)
        assertTrue(p.follower("new", 14 * min, 10 * min, 800.0))
        p.alertedNow("x", 0)
        p.heard("y", "x", 1 * min, 10.5, -20.5)
        p.heard("y", null, 7 * min, 10.5 + step, -20.5)
        p.heard("y", null, 15 * min, 10.5 + 2 * step, -20.5)
        assertFalse("alert carried with the rotation", p.follower("y", 15 * min, 10 * min, 800.0))
    }
}
