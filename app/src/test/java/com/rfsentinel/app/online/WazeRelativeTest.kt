package com.rfsentinel.app.online

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WazeRelativeTest {
    private fun me(bearing: Float? = null, speed: Float? = null) = Location("t").apply {
        latitude = 10.0; longitude = 10.0
        bearing?.let { this.bearing = it }
        speed?.let { this.speed = it }
    }

    @Test
    fun distanceIsExactMetresThenKm() {
        assertEquals("412 m", WazePolice.preciseDistance(412.7))
        assertEquals("1.25 km", WazePolice.preciseDistance(1250.0))
    }

    @Test
    fun directionIsCompassAndAheadWhenDriving() {
        // About 0.9 km due north of the phone.
        val north = WazePolice.relative(me(), 10.008, 10.0)
        assertTrue(north, north.endsWith(" N"))
        assertTrue(north, north.startsWith("8") || north.startsWith("9"))
        // Driving north: it is ahead. Driving south: behind.
        assertTrue(WazePolice.relative(me(0f, 20f), 10.008, 10.0).endsWith("N · ahead"))
        assertTrue(WazePolice.relative(me(180f, 20f), 10.008, 10.0).endsWith("N · behind you"))
        // Driving east: due north is on the left.
        assertTrue(WazePolice.relative(me(90f, 20f), 10.008, 10.0).endsWith("to your left"))
    }
}
