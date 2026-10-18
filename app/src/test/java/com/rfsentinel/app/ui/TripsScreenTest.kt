package com.rfsentinel.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.TripPointEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Recorded traces in both styles: the list rows, and the visual cards with the route's outline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TripsScreenTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        return out
    }

    /** A made-up trace: a small square around 0,0 with one flagged device on it. */
    private fun seed() = runBlocking {
        val dao = AppDatabase.getInstance(ctx).tripDao()
        // The database outlives a single test here: start from none.
        dao.allFlow().first().forEach { dao.delete(it.id) }
        val start = 1_700_000_000_000L
        val id = dao.insertTrip(TripEntity(name = "Test trace", startTime = start, endTime = start + 754_000L,
            distanceM = 2_400.0, pointCount = 5, deviceCount = 12, flaggedCount = 1))
        dao.insertPoints(listOf(0.0 to 0.0, 0.0 to 0.005, 0.005 to 0.005, 0.005 to 0.0, 0.0 to 0.0).mapIndexed { i, (lat, lon) ->
            TripPointEntity(tripId = id, time = start + i * 1000L, lat = lat, lon = lon)
        })
        dao.upsertDevices(listOf(TripDeviceEntity(tripId = id, mac = "AA:BB:CC:00:00:01", label = "Test camera", name = null,
            vendor = null, deviceType = null, source = "BLE", category = "BODY_CAM", confidence = 90, evidence = null,
            firstSeen = start, lastSeen = start, bestRssi = -60, lat = 0.005, lon = 0.0025)))
    }

    private fun open(visual: Boolean): List<View> {
        VisualMode.set(ctx, VisualMode.Screen.TRIPS, visual)
        val activity = Robolectric.buildActivity(TripsActivity::class.java).setup().get()
        // The list and the route load off the main thread.
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            val views = allViews(activity.window.decorView)
            val ready = if (visual) views.filterIsInstance<RouteOutlineView>().any { it.contentDescription?.startsWith("Route") == true }
                else views.filterIsInstance<TextView>().any { it.text.contains("Test trace") }
            if (ready) return views
            Thread.sleep(50)
        }
        return allViews(activity.window.decorView)
    }

    @Test
    fun theListStyleKeepsTheTextRow() {
        seed()
        val text = open(visual = false).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertTrue(text, text.contains("Test trace"))
        assertTrue(text, text.contains("12 devices · 1 flagged · 5 GPS points"))
    }

    @Test
    fun theVisualStyleDrawsTheRouteAndItsNumbers() {
        seed()
        val views = open(visual = true)
        val route = views.filterIsInstance<RouteOutlineView>()
        assertEquals(1, route.size)
        assertEquals("Route, 1 flagged along it", route[0].contentDescription)
        val text = views.filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertTrue(text, text.contains("Test trace"))
        assertTrue(text, text.contains("2.40 km"))
        assertTrue(text, text.contains("0:12"))
        assertTrue(text, text.contains("12 devices"))
        assertTrue(text, text.contains("1 flagged"))
    }
}
