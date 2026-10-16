package com.rfsentinel.app.detect

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Device distance estimates in metres or in feet and miles (Settings > General > Device distances). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceDistanceUnitsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun reset() { Prefs.setUnits(ctx, "auto"); DeviceIntel.imperial = false }

    @Test
    fun feetAndMiles() {
        assertEquals("< 3 ft", DeviceIntel.formatDistance(0.5, true))
        assertEquals("~16 ft", DeviceIntel.formatDistance(5.0, true))
        assertEquals("~160 ft", DeviceIntel.formatDistance(50.0, true))
        assertEquals("~980 ft", DeviceIntel.formatDistance(300.0, true)); assertEquals("~0.2 mi", DeviceIntel.formatDistance(400.0, true))
        assertEquals("~50 m", DeviceIntel.formatDistance(50.0, false))
    }

    @Test
    fun theSettingDecidesAndAutomaticFollowsTheRegion() {
        Prefs.setUnits(ctx, "ft"); DeviceIntel.applyUnits(ctx, Locale.FRANCE); assertTrue(DeviceIntel.imperial)
        assertEquals("~30 ft", DeviceIntel.formatDistance(10.0))
        Prefs.setUnits(ctx, "m"); DeviceIntel.applyUnits(ctx, Locale.US); assertFalse(DeviceIntel.imperial)
        Prefs.setUnits(ctx, "auto")
        DeviceIntel.applyUnits(ctx, Locale.US); assertTrue(DeviceIntel.imperial)
        DeviceIntel.applyUnits(ctx, Locale.GERMANY); assertFalse(DeviceIntel.imperial)
    }

    @Test
    fun pinpointAndDroneFiguresFollowTheSettingToo() {
        assertEquals("40 m", DeviceIntel.length(40.0, false)); assertEquals("131 ft", DeviceIntel.length(40.0, true))
        assertEquals("0.6 mi", DeviceIntel.length(1000.0, true))
        assertEquals("36 km/h", DeviceIntel.speed(10.0, false)); assertEquals("22 mph", DeviceIntel.speed(10.0, true))
        DeviceIntel.imperial = true
        assertEquals("±39 ft from 18 spots you passed", Locator.describe(Locator.Estimate(0.0, 0.0, 12.0, 18, 40, 100.0, 0L)))
    }

    @Test
    fun theWholeAppFollowsOneSetting() {
        val U = com.rfsentinel.app.util.Units
        // Navigation's old own choice carries over until the app-wide one is set.
        ctx.getSharedPreferences("rf_sentinel_prefs", Context.MODE_PRIVATE).edit().remove("device_units").putString("nav_units", "mi").commit()
        assertEquals("ft", Prefs.units(ctx))
        assertTrue(com.rfsentinel.app.nav.NavFormat.miles(ctx, Locale.GERMANY))
        Prefs.setUnits(ctx, "m"); assertFalse(com.rfsentinel.app.nav.NavFormat.miles(ctx, Locale.US))
        assertEquals("0.26 mi", U.precise(412.2, true)); assertEquals("328 ft", U.precise(100.0, true)); assertEquals("412 m", U.precise(412.2, false))
        assertEquals("16 mi", U.km(25, true)); assertEquals("25 km", U.km(25, false))
        val (m, label) = com.rfsentinel.app.ui.ScaleBarView.niceImperial(200.0)
        assertEquals("500 ft", label); assertEquals(152.4, m, 0.01)
        assertEquals("1 mi", com.rfsentinel.app.ui.ScaleBarView.niceImperial(2000.0).second)
    }
}
