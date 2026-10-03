package com.rfsentinel.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAreaTest {

    // Synthetic boxes (not real places).
    private val day = 24 * 3600_000L
    private val area = CameraArea(10.0, -21.0, 11.0, -20.0, time = 0L)

    @Test
    fun coveredOnlyInsideAFreshArea() {
        assertFalse(CameraArea.needsDownload(listOf(area), 10.2, -20.8, 10.8, -20.2, now = day, maxAgeMs = 7 * day))
        assertTrue(CameraArea.needsDownload(listOf(area), 10.2, -20.8, 11.2, -20.2, now = day, maxAgeMs = 7 * day)) // sticks out
        assertTrue(CameraArea.needsDownload(listOf(area), 10.2, -20.8, 10.8, -20.2, now = 8 * day, maxAgeMs = 7 * day)) // stale
        assertTrue(CameraArea.needsDownload(emptyList(), 10.2, -20.8, 10.8, -20.2, now = day, maxAgeMs = 7 * day))
    }

    @Test
    fun tilesTogetherCoverABox() {
        val tiles = CameraArea.tilesAround(10.5, -20.5, radiusKm = 100.0, maxTileDeg = 1.0)
        assertEquals(4, tiles.size)
        tiles.forEach { assertTrue(it[2] - it[0] <= 1.0 + 1e-9 && it[3] - it[1] <= 1.0 + 1e-9) }
        val areas = tiles.map { CameraArea(it[0], it[1], it[2], it[3], time = 0L) }
        // A view straddling all four tiles is covered by them together...
        assertFalse(CameraArea.needsDownload(areas, 10.3, -20.7, 10.7, -20.3, now = day, maxAgeMs = 7 * day))
        // ...but one reaching past them is not.
        assertTrue(CameraArea.needsDownload(areas, 10.3, -20.7, 11.9, -20.3, now = day, maxAgeMs = 7 * day))
    }

    @Test
    fun largeRadiusIsSplitIntoSmallTiles() {
        // 200 km at 45 degrees: about 3.6 x 5.1 degrees, more than the old 2x2 tiles could hold.
        val tiles = CameraArea.tilesAround(45.0, 10.0, radiusKm = 200.0, maxTileDeg = 1.0)
        assertTrue(tiles.size > 4)
        tiles.forEach { assertTrue(it[2] - it[0] <= 1.0 + 1e-9 && it[3] - it[1] <= 1.0 + 1e-9) }
        val areas = tiles.map { CameraArea(it[0], it[1], it[2], it[3], time = 0L) }
        // The whole radius is covered: a box reaching 190 km north and east of the point.
        assertFalse(CameraArea.needsDownload(areas, 45.0, 10.0, 45.0 + 190 / 111.0, 10.0 + 190 / (111.0 * Math.cos(Math.toRadians(45.0))), now = day, maxAgeMs = 7 * day))
    }

    @Test
    fun expandDoublesWithinLimits() {
        // A small zoomed-in box grows to the minimum span.
        val (s, w, n, e) = CameraArea.expand(10.0, -20.05, 10.1, -19.95, maxSpan = 2.0)
        assertEquals(CameraArea.MIN_SPAN_DEG, n - s, 1e-9)
        assertEquals(CameraArea.MIN_SPAN_DEG, e - w, 1e-9)
        assertEquals(10.05, (s + n) / 2, 1e-9) // still centred
        // A large box doubles but never exceeds the maximum.
        val big = CameraArea.expand(10.0, -21.0, 11.5, -19.5, maxSpan = 2.0)
        assertEquals(2.0, big[2] - big[0], 1e-9)
        // A box already at the maximum is left as is.
        val max = CameraArea.expand(10.0, -21.0, 12.0, -19.0, maxSpan = 2.0)
        assertEquals(listOf(10.0, -21.0, 12.0, -19.0), max)
    }
}
