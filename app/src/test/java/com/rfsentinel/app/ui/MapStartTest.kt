package com.rfsentinel.app.ui

import android.Manifest
import android.app.Application
import android.location.Location
import android.net.ConnectivityManager
import android.os.Looper
import com.rfsentinel.app.R
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.MapStart.Fix
import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

/** The live map screen itself, opened in each situation. Tile downloads are switched off (no network in tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveMapOpensTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ScanForegroundService.setLastFixForTest(null)
    }

    private fun offlineWithLocationPermission(granted: Boolean) {
        shadowOf(ctx.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        if (granted) shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun openMap(): Pair<MapActivity, MapView> {
        val controller = Robolectric.buildActivity(MapActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        return activity to activity.findViewById(R.id.map)
    }

    private fun fix(lat: Double, lon: Double, ageMs: Long) = Location("test").apply {
        latitude = lat; longitude = lon; time = System.currentTimeMillis() - ageMs
    }

    @Test
    fun opensOnTheScansCurrentFixAtStreetZoom() {
        offlineWithLocationPermission(true)
        ScanForegroundService.setLastFixForTest(fix(10.5, 20.5, ageMs = 5_000))
        val (activity, map) = openMap()
        assertEquals(10.5, map.mapCenter.latitude, 1e-3)
        assertEquals(20.5, map.mapCenter.longitude, 1e-3)
        assertEquals(MapStart.DEFAULT_ZOOM, map.zoomLevelDouble, 0.0)
        activity.finish()
    }

    @Test
    fun opensOnAnOldFixToo() {
        offlineWithLocationPermission(true)
        ScanForegroundService.setLastFixForTest(fix(10.5, 20.5, ageMs = 5 * 3_600_000L))
        val (activity, map) = openMap()
        assertEquals(10.5, map.mapCenter.latitude, 1e-3)
        assertEquals(20.5, map.mapCenter.longitude, 1e-3)
        activity.finish()
    }

    @Test
    fun opensOnTheSavedViewWhenNothingElseIsKnown() {
        offlineWithLocationPermission(true)
        Prefs.setGpsTaggingEnabled(ctx, true)
        Prefs.saveMapView(ctx, 11.0, 22.0, 15.0)
        val (activity, map) = openMap()
        assertEquals(11.0, map.mapCenter.latitude, 1e-3)
        assertEquals(22.0, map.mapCenter.longitude, 1e-3)
        assertEquals(15.0, map.zoomLevelDouble, 0.0)
        activity.finish()
    }

    @Test
    fun opensOnAWorldViewWhenNothingAtAllIsKnown() {
        offlineWithLocationPermission(true)
        val (activity, map) = openMap()
        assertEquals(MapStart.WORLD_ZOOM, map.zoomLevelDouble, 0.0)
        activity.finish()
    }

    @Test
    fun withoutLocationPermissionItStillOpens() {
        offlineWithLocationPermission(false)
        val (activity, map) = openMap()
        assertNotNull(map)
        activity.finish()
    }

    @Test
    fun leavingRemembersTheZoomAlwaysAndThePlaceOnlyWithGpsTagging() {
        offlineWithLocationPermission(true)
        ScanForegroundService.setLastFixForTest(fix(10.5, 20.5, ageMs = 5_000))
        run {
            val controller = Robolectric.buildActivity(MapActivity::class.java).setup().visible()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            assertEquals(MapStart.DEFAULT_ZOOM, Prefs.mapZoom(ctx)!!, 0.0)
            assertNull("GPS tagging is off: no place is kept", Prefs.lastMapCenter(ctx))
            controller.destroy()
        }
        Prefs.setGpsTaggingEnabled(ctx, true)
        run {
            val controller = Robolectric.buildActivity(MapActivity::class.java).setup().visible()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            val saved = Prefs.lastMapCenter(ctx)
            assertNotNull(saved)
            assertEquals(10.5, saved!!.first, 1e-3)
            assertEquals(20.5, saved.second, 1e-3)
            controller.destroy()
        }
    }
}
