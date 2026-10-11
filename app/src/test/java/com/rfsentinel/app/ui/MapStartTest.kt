package com.rfsentinel.app.ui

import com.rfsentinel.app.ui.MapStart.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the live map opens: on your area at once, not on open sea until the first GPS fix. */
class MapStartPickTest {
    private val now = 1_000_000L

    @Test
    fun aFreshFixIsWhereYouAreAndIsUsedAsIs() {
        val v = MapStart.pick(listOf(Fix(10.5, 20.5, now - 5_000)), null, null, now)!!
        assertEquals(10.5, v.lat, 1e-9)
        assertEquals(20.5, v.lon, 1e-9)
        assertTrue(v.fresh)
        assertEquals(MapStart.DEFAULT_ZOOM, v.zoom, 0.0)
    }

    @Test
    fun anOldFixStillCentresTheMapButIsNotCurrent() {
        val v = MapStart.pick(listOf(Fix(10.5, 20.5, now - 3 * 3_600_000L)), null, null, now)!!
        assertEquals(10.5, v.lat, 1e-9)
        assertFalse(v.fresh)
    }

    @Test
    fun theNewestFixWinsAmongTheKnownOnes() {
        val old = Fix(1.0, 2.0, now - 600_000)
        val newer = Fix(3.0, 4.0, now - 10_000)
        val v = MapStart.pick(listOf(old, null, newer), null, null, now)!!
        assertEquals(3.0, v.lat, 1e-9)
    }

    @Test
    fun withoutAFixTheLastPlaceTheMapLookedIsUsedAndIsNotCurrent() {
        val v = MapStart.pick(listOf(null, null), 11.0 to 22.0, 15.0, now)!!
        assertEquals(11.0, v.lat, 1e-9)
        assertEquals(22.0, v.lon, 1e-9)
        assertEquals(15.0, v.zoom, 0.0)
        assertFalse(v.fresh)
    }

    @Test
    fun aFixBeatsTheSavedPlaceEvenWhenOld() {
        val v = MapStart.pick(listOf(Fix(5.0, 6.0, now - 86_400_000L)), 11.0 to 22.0, null, now)!!
        assertEquals(5.0, v.lat, 1e-9)
    }

    @Test
    fun nothingKnownMeansNoStartView() {
        assertNull(MapStart.pick(emptyList(), null, null, now))
        assertNull(MapStart.pick(listOf(null), null, 14.0, now))
    }

    @Test
    fun theOriginIsNeverARealStart() {
        assertNull(MapStart.pick(listOf(Fix(0.0, 0.0, now)), 0.0 to 0.0, null, now))
        val v = MapStart.pick(listOf(Fix(0.0, 0.0, now)), 11.0 to 22.0, null, now)!!
        assertEquals("a (0, 0) fix is skipped for the saved place", 11.0, v.lat, 1e-9)
    }

    @Test
    fun theSavedZoomIsKeptOnlyWhenItSuitsAStreetToCityView() {
        fun zoom(z: Double?) = MapStart.pick(listOf(Fix(10.0, 10.0, now)), null, z, now)!!.zoom
        assertEquals(14.0, zoom(14.0), 0.0)
        assertEquals(11.0, zoom(11.0), 0.0)
        assertEquals("zoomed out to look at a country: not worth reopening on", 16.0, zoom(6.0), 0.0)
        assertEquals(16.0, zoom(null), 0.0)
        assertEquals(16.0, zoom(20.0), 0.0)
    }
}

/**
 * Where the live map's camera opens (vector zoom levels), in each situation: the screen itself runs
 * the map engine's native code, which unit tests can't load, so the decision is checked here.
 */
class LiveMapOpensTest {
    private val now = 1_000_000L

    @Test
    fun opensOnTheScansCurrentFixAtStreetZoom() {
        val c = MapStart.camera(listOf(Fix(10.5, 20.5, now - 5_000)), null, null, now)
        assertEquals(10.5, c.lat, 1e-9); assertEquals(20.5, c.lon, 1e-9)
        assertEquals(MapStart.DEFAULT_ZOOM - MapStart.VECTOR_SHIFT, c.zoom, 0.0)
        assertTrue(c.fresh)
    }

    @Test
    fun opensOnAnOldFixToo() {
        val c = MapStart.camera(listOf(Fix(10.5, 20.5, now - 5 * 3_600_000L)), null, null, now)
        assertEquals(10.5, c.lat, 1e-9); assertFalse(c.fresh)
    }

    @Test
    fun opensOnTheSavedViewWhenNothingElseIsKnown() {
        val c = MapStart.camera(listOf(null), 11.0 to 22.0, 15.0, now)
        assertEquals(11.0, c.lat, 1e-9); assertEquals(22.0, c.lon, 1e-9)
        assertEquals("the same scale as the saved view", 15.0 - MapStart.VECTOR_SHIFT, c.zoom, 0.0)
    }

    @Test
    fun opensOnAWorldViewWhenNothingAtAllIsKnown() {
        val c = MapStart.camera(emptyList(), null, null, now)
        assertEquals(MapStart.WORLD_ZOOM - MapStart.VECTOR_SHIFT, c.zoom, 0.0)
        assertFalse(c.fresh)
    }

    @Test
    fun theZoomIsSavedOnTheClassicScale() {
        // Leaving at street zoom saves the classic street level, so reopening comes back the same.
        val saved = MapStart.savedZoom(MapStart.DEFAULT_ZOOM - MapStart.VECTOR_SHIFT)
        assertEquals(MapStart.DEFAULT_ZOOM, saved, 0.0)
        assertEquals(MapStart.DEFAULT_ZOOM - MapStart.VECTOR_SHIFT,
            MapStart.camera(listOf(Fix(1.0, 1.0, now)), null, saved, now).zoom, 0.0)
    }
}
