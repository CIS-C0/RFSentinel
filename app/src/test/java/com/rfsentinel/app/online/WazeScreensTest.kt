package com.rfsentinel.app.online

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.settings.SettingsActivity
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceRow
import com.rfsentinel.app.ui.WazeStatusActivity
import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opens the screens that carry the Waze controls, so a layout or slider mistake fails here and not on the
 * phone: the Material slider throws when its value does not line up with its steps, and only when measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WazeScreensTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ScanForegroundService.isRunning = false
        WazePolice.running = false
        WazePolice.publish(emptyList(), 0L)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        return out
    }

    private fun wazeOn(direct: Boolean) {
        Prefs.setCategoryEnabled(ctx, Category.POLICE_REPORT, true)
        Prefs.setWazeAccepted(ctx, true)
        Prefs.setWazeDirectAccepted(ctx, true)
        Prefs.setWazeBackend(ctx, if (direct) Prefs.WAZE_DIRECT else Prefs.WAZE_NINJA)
    }

    @Test
    fun settingsBuildsEveryWazeControlForBothSources() {
        for (direct in listOf(false, true)) {
            wazeOn(direct)
            val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            val views = allViews(activity.window.decorView)
            val sliders = views.filterIsInstance<Slider>()
            val exactly = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            val atMost = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST)
            // Waze alone adds four: alert range, map range, and one interval slider per source.
            assertTrue("sliders: ${sliders.size}", sliders.size >= 4)
            sliders.forEach { it.measure(exactly, atMost) } // throws if a value does not fit its steps
            assertEquals("one level menu per kind of report", WazePolice.Type.entries.size, views.filterIsInstance<AppCompatSpinner>().size)
            val text = views.filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(text.contains("Alert when a report is within"))
            assertTrue(text.contains("Show reports on the map within"))
            assertTrue(text.contains("Check for new reports every"))
            assertTrue(text.contains("Only alert for reports ahead of me"))
            assertTrue(text.contains("What to alert on, and how loud"))
            activity.finish()
        }
    }

    @Test
    fun theStatusScreenSaysWhatIsSentAndWhen() {
        wazeOn(direct = true)
        ScanForegroundService.isRunning = true
        WazePolice.running = true
        WazeStatus.baseS = 60
        WazeStatus.everyS = 240
        WazeStatus.motion = MotionTracker.State.PARKED
        val activity = Robolectric.buildActivity(WazeStatusActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val text = allViews(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertTrue(text, text.contains("State: Checking"))
        assertTrue(text, text.contains("Checks every: 4 min (set to 60 s, adapted: you are parked)"))
        assertTrue(text, text.contains("Source: Waze direct"))
        assertTrue(text, text.contains("never your exact position") || text.contains("Sent to Waze: nothing yet"))
        assertTrue(text, text.contains("Pause Waze checks"))
        assertTrue(text, text.contains("Forget Waze account"))
        activity.finish()
    }

    @Test
    fun theMainListPinsAnUrgentWazeReportAboveEverythingElse() {
        wazeOn(direct = true)
        ScanForegroundService.isRunning = true
        val report = WazePolice.Report("r1", 10.0, 10.0, null, null, null, 0, null)
        val hit = WazePolice.hit(report, 10.0, 10.0, 0L)!!.first
        WazePolice.publish(
            listOf(WazePolice.Shown(report, hit, WazePolice.Where(120.0, "N", WazePolice.Side.AHEAD, 5f), WazeTrend.Trend.CLOSING)),
            1L
        )
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val list = activity.findViewById<RecyclerView>(com.rfsentinel.app.R.id.recyclerView)
        @Suppress("UNCHECKED_CAST")
        val rows = (list.adapter as ListAdapter<DeviceRow, *>).currentList
        val first = rows.first()
        assertEquals("Police reported on Waze", first.title)
        assertEquals("120 m N · ahead · closing in", first.subtitle)
        assertTrue("flashing: it is above the alert threshold", first.flashing)
        activity.finish()
    }
}
