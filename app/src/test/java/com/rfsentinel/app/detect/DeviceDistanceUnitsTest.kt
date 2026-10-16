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

    @After fun reset() { Prefs.setDeviceUnits(ctx, "auto"); DeviceIntel.imperial = false }

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
        Prefs.setDeviceUnits(ctx, "ft"); DeviceIntel.applyUnits(ctx, Locale.FRANCE); assertTrue(DeviceIntel.imperial)
        assertEquals("~30 ft", DeviceIntel.formatDistance(10.0))
        Prefs.setDeviceUnits(ctx, "m"); DeviceIntel.applyUnits(ctx, Locale.US); assertFalse(DeviceIntel.imperial)
        Prefs.setDeviceUnits(ctx, "auto")
        DeviceIntel.applyUnits(ctx, Locale.US); assertTrue(DeviceIntel.imperial)
        DeviceIntel.applyUnits(ctx, Locale.GERMANY); assertFalse(DeviceIntel.imperial)
    }
}
