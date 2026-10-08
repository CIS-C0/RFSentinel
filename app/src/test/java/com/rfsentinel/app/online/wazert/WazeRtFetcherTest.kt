package com.rfsentinel.app.online.wazert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** What position Waze is told, and that the rounding never costs the query its coverage. */
class WazeRtFetcherTest {

    private fun metresLat(deg: Double) = Math.abs(deg) * WazeConstants.M_PER_DEG_LAT

    @Test
    fun everyPointInACellSnapsToTheSameCentre() {
        for (lat in listOf(0.0, 10.0, 45.0, 70.0)) {
            val c = WazeRtFetcher.snapToGrid(lat + 0.0003, 10.0003)
            val east = 400.0 / WazeConstants.mPerDegLon(c[0])
            val north = 400.0 / WazeConstants.M_PER_DEG_LAT
            // 400 m either way from the centre is still inside the ~1 km cell.
            for (dLat in listOf(-north, 0.0, north)) for (dLon in listOf(-east, 0.0, east)) {
                val s = WazeRtFetcher.snapToGrid(c[0] + dLat, c[1] + dLon)
                assertEquals(c[0], s[0], 1e-9)
                assertEquals(c[1], s[1], 1e-9)
            }
        }
    }

    @Test
    fun theRoundingErrorStaysUnderAboutFiveHundredMetres() {
        val rnd = Random(7)
        repeat(2000) {
            val lat = rnd.nextDouble() * 140 - 70
            val lon = rnd.nextDouble() * 340 - 170
            val s = WazeRtFetcher.snapToGrid(lat, lon)
            assertTrue(metresLat(s[0] - lat) <= 500.5)
            assertTrue(Math.abs(s[1] - lon) * WazeConstants.mPerDegLon(s[0]) <= 500.5)
        }
    }

    @Test
    fun theSmallestBoxStillHoldsTheTruePosition() {
        val rnd = Random(11)
        for (radius in listOf(8000.0, 9600.0, 16000.0, 24000.0)) {
            val steps = WazeRtFetcher.boxSteps(radius)
            repeat(500) {
                val lat = rnd.nextDouble() * 120 - 60
                val lon = rnd.nextDouble() * 340 - 170
                val s = WazeRtFetcher.snapToGrid(lat, lon)
                // The query boxes are centred on the rounded point, and each is shrunk to 0.75 before it is sent.
                val box = GeoBoxes.shrinkingBoxes(s[1], s[0], radius, steps).last().let { GeoBoxes.shrink(it, 0.75) }
                assertTrue("lat $lat lon $lon r $radius", lat in box[1]..box[3] && lon in box[0]..box[2])
            }
        }
    }

    @Test
    fun boxCountStopsBeforeTheBoxesGetTooNarrow() {
        assertEquals(1, WazeRtFetcher.boxSteps(1000.0))
        assertEquals(2, WazeRtFetcher.boxSteps(3000.0))
        assertEquals(4, WazeRtFetcher.boxSteps(8000.0))
        assertEquals(5, WazeRtFetcher.boxSteps(24000.0))
    }
}
