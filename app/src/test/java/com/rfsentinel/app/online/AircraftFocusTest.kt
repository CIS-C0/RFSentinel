package com.rfsentinel.app.online

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.ui.MapActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A police aircraft alert or log row opens the map on the aircraft: where it is now, else its last known position. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AircraftFocusTest {

    private fun plane(hex: String, lat: Double) =
        PoliceAircraft.Seen(PoliceAircraft.Plane(hex, "POL1", null, null, null, lat, -70.0, 1500, 90.0, 45.0, false), null)

    @After
    fun clear() { PoliceAircraft.latest = emptyList(); PoliceAircraft.latestAt = 0L }

    @Test
    fun theLatestPositionWhileTheFeedIsFresh() {
        PoliceAircraft.latest = listOf(plane("a1b2c3", 45.1), plane("ffffff", 46.0))
        PoliceAircraft.latestAt = 1_000_000L
        assertEquals(45.1, PoliceAircraft.current("A1B2C3", now = 1_000_000L + 60_000L)!!.plane.lat, 1e-9)
        assertNull("gone from the feed", PoliceAircraft.current("000001", now = 1_000_000L))
        assertNull("feed older than 3 minutes", PoliceAircraft.current("a1b2c3", now = 1_000_000L + 4 * 60_000L))
    }

    @Test
    fun theMapIntentCarriesTheAircraftAndItsLastPosition() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val i = MapActivity.onlineIntent(ctx, "air:a1b2c3", 45.5, -73.5)!!
        assertEquals("a1b2c3", i.getStringExtra(MapActivity.EXTRA_AIRCRAFT))
        assertEquals(45.5, i.getDoubleExtra(MapActivity.EXTRA_CENTER_LAT, 0.0), 1e-9)
        assertEquals(-73.5, i.getDoubleExtra(MapActivity.EXTRA_CENTER_LON, 0.0), 1e-9)
        // No saved position: the map then needs the live feed.
        assertFalse(MapActivity.onlineIntent(ctx, "air:a1b2c3", null, null)!!.hasExtra(MapActivity.EXTRA_CENTER_LAT))
    }

    @Test
    fun wazeReportsOpenTheMapOnTheirSpot() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // Waze ids are case-sensitive: kept as they are.
        val i = MapActivity.onlineIntent(ctx, "waze:Ab12-Cd", 45.6, -73.6)!!
        assertEquals("Ab12-Cd", i.getStringExtra(MapActivity.EXTRA_WAZE))
        assertNull(i.getStringExtra(MapActivity.EXTRA_AIRCRAFT))
        assertEquals(45.6, i.getDoubleExtra(MapActivity.EXTRA_CENTER_LAT, 0.0), 1e-9)
        assertNull("other alerts keep their usual screen", MapActivity.onlineIntent(ctx, "deauth:aa", 1.0, 2.0))
        assertFalse("not in the current reports", MapActivity.onlineIsLive("waze:Ab12-Cd"))
    }
}
