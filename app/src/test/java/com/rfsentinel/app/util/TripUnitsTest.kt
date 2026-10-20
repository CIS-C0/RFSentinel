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
}
