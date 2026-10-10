package com.rfsentinel.app

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > General > Upside down: the app asks for reverse portrait, and goes back to the default when it's off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpsideDownTest {

    @Test
    fun theSwitchTurnsTheScreenAndBack() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val app = RFSentinelApp()
        Prefs.setRotation(activity, Prefs.Rotation.UPSIDE_DOWN)
        app.applyOrientation(activity)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
        Prefs.setRotation(activity, Prefs.Rotation.ALL_WAYS)
        app.applyOrientation(activity)
        assertEquals("auto-rotate in all four directions, rotation lock respected",
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
        Prefs.setRotation(activity, Prefs.Rotation.NORMAL)
        app.applyOrientation(activity)
        assertEquals("normal: follows auto-rotate as before", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun theEarlierSwitchCarriesOver() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals("nothing set: normal", Prefs.Rotation.NORMAL, Prefs.rotation(activity))
        // Someone who turned on the earlier "Upside down" switch keeps it.
        activity.getSharedPreferences("rf_sentinel_prefs", 0).edit().putBoolean("upside_down", true).commit()
        assertEquals(Prefs.Rotation.UPSIDE_DOWN, Prefs.rotation(activity))
    }
}
