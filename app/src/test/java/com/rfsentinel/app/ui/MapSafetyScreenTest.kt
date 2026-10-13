package com.rfsentinel.app.ui

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** What the full screen map and the main screen say when the map closed the app while starting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapSafetyScreenTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("map_safety", Context.MODE_PRIVATE).edit().clear().commit()
        MapSafety.live = false
        Prefs.setViewPanes(ctx, setOf("map", "list"))
    }

    private fun titleOf(d: AlertDialog) = d.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    /** A second start-up crash in a row (OpenGL already tried). */
    private fun secondCrash() {
        ctx.getSharedPreferences("map_safety", Context.MODE_PRIVATE).edit()
            .putBoolean("opengl", true).putLong("armed_at", 1L).putString("armed_provider", "OPENFREEMAP").commit()
    }

    @Test
    fun theFullScreenMapAsksBeforeTryingAgainAndLeavesWhenNotNow() {
        secondCrash()
        val controller = Robolectric.buildActivity(MapActivity::class.java).create()
        val activity = controller.get()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(MapSafetyUi.OFF_TITLE, titleOf(dialog))
        assertEquals("Try the map again", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertNotNull(dialog.getButton(DialogInterface.BUTTON_NEGATIVE))
        assertTrue("the map is not started behind the question",
            activity.supportFragmentManager.findFragmentById(com.rfsentinel.app.R.id.mapHost) == null)
        assertEquals("the saved main screen view lost the map", setOf("list"), Prefs.viewPanes(ctx))

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("Not now closes the screen", activity.isFinishing)
    }

    @Test
    fun theMainScreenJustSaysSo() {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().get()
        activity.setTheme(com.rfsentinel.app.R.style.Theme_RFSentinel)
        MapSafetyUi.tell(activity, MapSafety.Recovery.MAP_OFF)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(MapSafetyUi.OFF_TITLE, titleOf(dialog))
        assertEquals("OK", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertFalse("nothing to try again from here", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility == android.view.View.VISIBLE)
    }

    @Test
    fun theOpenGlRetryIsATextNoteNotADialog() {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().get()
        activity.setTheme(com.rfsentinel.app.R.style.Theme_RFSentinel)
        var started = 0
        MapSafetyUi.tell(activity, MapSafety.Recovery.OPENGL, onTryAgain = { started++ })
        assertEquals("the map starts right away", 1, started)
        assertEquals(MapSafetyUi.OPENGL_NOTE, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }
}
