package com.rfsentinel.app.ui

import com.rfsentinel.app.online.PoliceAircraft
import com.rfsentinel.app.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AircraftTextTest {
    private fun plane(ft: Int?) = PoliceAircraft.Plane("abc123", "TEST1", null, null, null, 0.0, 0.0, ft, null, null, false)

    @Test
    fun altitudeSaysAltAndTheGround() {
        Units.imperial = false
        assertEquals("alt 1,310 m", AircraftActivity.altitude(plane(4300)))
        assertEquals("on the ground", AircraftActivity.altitude(plane(0)))
        assertEquals("on the ground", AircraftActivity.altitude(plane(-175)))
        Units.imperial = true
        assertEquals("alt 4,300 ft", AircraftActivity.altitude(plane(4300)))
        Units.imperial = false
    }

    @Test
    fun aircraftAreNotDevices() {
        assertFalse(DeviceFilter.AIRCRAFT in com.rfsentinel.app.car.DeviceListScreen.CAR_FILTERS)
        assertEquals(DeviceFilter.AIRCRAFT, DeviceFilter.parse("AIRCRAFT"))
    }
}
