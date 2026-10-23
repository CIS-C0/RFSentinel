package com.rfsentinel.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSpotsTest {

    private val here = 10.0 to 20.0
    private val nextDoor = 10.0005 to 20.0          // ~55 m away
    private val farAway = 10.01 to 20.0             // ~1.1 km away

    @Test
    fun nearbyRequestsInOneEncounterAreOnePlaceAtTheStrongest() {
        val b = ProbeBook()
        b.record("Home", "02:00:00:00:00:01", -80, 0, here)
        b.record("Home", "02:00:00:00:00:02", -60, 1_000, nextDoor)
        b.record("Home", "02:00:00:00:00:01", -90, 2_000, here)
        val s = b.network("home")!!.spots.single()
        assertEquals(nextDoor.first, s.lat, 0.0)
        assertEquals(-60, s.rssi)
        assertEquals("02:00:00:00:00:02", s.mac)
        assertEquals(2_000L, s.at)
    }

    @Test
    fun anotherPlaceOrALaterEncounterIsANewSpot() {
        val b = ProbeBook()
        b.record("Home", "02:00:00:00:00:01", -70, 0, here)
        b.record("Home", "02:00:00:00:00:01", -70, 1_000, farAway)
        b.record("Home", "02:00:00:00:00:01", -70, 2_000 + ProbeBook.GAP_MS + 1, farAway)
        assertEquals(3, b.network("Home")!!.spots.size)
    }

    @Test
    fun noFixNoPlaceAndOldListsLoad() {
        val b = ProbeBook()
        b.record("Cafe", "02:00:00:00:00:01", -70, 0)
        assertTrue(b.network("Cafe")!!.spots.isEmpty())
        // A list saved before places were kept: Gson leaves the field null.
        val old = ProbeBook.Network("Office", 1, 2, 1, -50, mutableListOf("AA:AA:AA:AA:AA:01"))
        val f = ProbeBook.Network::class.java.getDeclaredField("spots").apply { isAccessible = true }
        f.set(old, null)
        b.restore(listOf(old))
        assertTrue(b.network("office")!!.spots.isEmpty())
        assertNull(b.network("nothing"))
    }

    @Test
    fun placesAreCapped() {
        val b = ProbeBook()
        for (i in 0 until ProbeBook.MAX_SPOTS + 5) b.record("Home", "02:00:00:00:00:01", -70, i * (ProbeBook.GAP_MS + 1), here)
        assertEquals(ProbeBook.MAX_SPOTS, b.network("Home")!!.spots.size)
    }
}
