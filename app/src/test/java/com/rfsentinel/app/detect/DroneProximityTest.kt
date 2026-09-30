package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneProximityTest {

    // Synthetic test position (not a real place of interest).
    private val me = 10.5 to -20.5

    private fun drone(dLat: Double, height: Double? = 60.0) =
        RemoteId.Info(latitude = me.first + dLat, longitude = me.second, heightM = height, speedMs = 5.0, uasId = "TEST123")

    @Test
    fun droneWithin200mIsOverhead() {
        val hit = DroneProximity.overheadHit(drone(0.001), me.first, me.second) // ~111 m north
        assertNotNull(hit)
        assertEquals(Category.DRONE, hit!!.category)
        assertEquals(95, hit.confidence)
        assertTrue(hit.evidence.contains("~111 m from you"))
        assertTrue(hit.evidence.contains("60 m up"))
    }

    @Test
    fun farOrUnpositionedDronesAreNot() {
        assertNull(DroneProximity.overheadHit(drone(0.003), me.first, me.second)) // ~333 m
        assertNull(DroneProximity.overheadHit(RemoteId.Info(uasId = "NOPOS"), me.first, me.second))
    }
}
