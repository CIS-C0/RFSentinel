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

    /** The corridor along your heading, used when the map's roads can't say which road a report is on. */
    private fun where(distanceM: Double, relDeg: Float?) =
        WazePolice.Where(distanceM, "N", null, relDeg)

    @Test
    fun theCorridorWidensWithDistanceButNeverWithoutLimit() {
        assertEquals(30.0, WazePolice.corridorM(0.0), 0.01)
        assertEquals(42.0, WazePolice.corridorM(150.0), 0.01)
        assertEquals(110.0, WazePolice.corridorM(1_000.0), 0.01)
        assertEquals("capped", 250.0, WazePolice.corridorM(50_000.0), 0.01)
    }

    @Test
    fun sidewaysOffsetIsWhatCounts() {
        // 150 m away, 20 degrees off: about 51 m to the side, so another road, not the one you are on.
        assertEquals(51.3, where(150.0, 20f).lateralM!!, 0.5)
        assertTrue(where(150.0, 5f).maybeOnMyWay())   // 13 m: your road
        assertTrue(!where(150.0, 20f).maybeOnMyWay()) // 51 m: a parallel or crossing road
        // The same angle a kilometre out is still within the corridor: the road may bend that way.
        assertTrue(where(1_000.0, 5f).maybeOnMyWay())
        assertTrue(!where(1_000.0, 20f).maybeOnMyWay()) // 342 m: too far to the side at any distance
    }

    @Test
    fun besideAndBehindYouAreNeverOnYourWay() {
        assertTrue(!where(150.0, 95f).maybeOnMyWay())
        assertTrue(!where(150.0, 180f).maybeOnMyWay())
    }

    @Test
    fun withNoHeadingNothingIsRuledOut() {
        assertTrue(where(150.0, null).maybeOnMyWay())
        assertEquals(null, where(150.0, null).lateralM)
    }
}
