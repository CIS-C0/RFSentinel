package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TripUnitsTest {
    @Test
    fun tripLengthFollowsTheUnits() {
        assertEquals("0.00 km", Units.trip(0.0, feet = false))
        assertEquals("12.35 km", Units.trip(12_345.0, feet = false))
        assertEquals("0.00 mi", Units.trip(0.0, feet = true))
        assertEquals("1.00 mi", Units.trip(1609.344, feet = true))
    }

    @Test
    fun aircraftAltitudeFollowsTheUnits() {
        assertEquals("1,200 ft", Units.altitude(1200, feet = true))
        assertEquals("365 m", Units.altitude(1200, feet = false))
    }
}
