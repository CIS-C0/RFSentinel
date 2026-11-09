package com.rfsentinel.app.ui

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.online.WazePolice
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Appearance › List size: what each size shows, alerting rows staying Regular, and sizes switching back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ListSizeTest {
    private lateinit var activity: AppCompatActivity
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_RFSentinel)
        parent = FrameLayout(activity)
    }

    private fun row(highlight: Int? = null, tag: String? = "BODY CAM · strong 90%") = DeviceRow(
        mac = "00:25:DF:12:34:56", title = "Axon body camera", subtitle = "00:25:DF:12:34:56 · Axon", meta = "-62 dBm · 12 s ago",
        tag = tag, tagColor = 0xFF1E88E5.toInt(), highlight = highlight, flashing = false, bold = false, heardByPhone = true
    )

    private fun bind(adapter: DeviceAdapter, r: DeviceRow, holder: DeviceAdapter.VH? = null): DeviceAdapter.VH {
        adapter.submitList(listOf(r))
        val h = holder ?: adapter.onCreateViewHolder(parent, 0) as DeviceAdapter.VH
        adapter.onBindViewHolder(h, 0)
        return h
    }

    @Test
    fun regularKeepsEveryLine() {
        val h = bind(DeviceAdapter({}, {}), row())
        assertEquals(View.VISIBLE, h.binding.tagText.visibility)
        assertEquals(View.VISIBLE, h.binding.macText.visibility)
        assertEquals("Axon body camera", h.binding.titleText.text.toString())
        assertEquals(Int.MAX_VALUE, h.binding.metaText.maxLines)
    }

    @Test
    fun compactPutsTheTagInTheTitle() {
        val a = DeviceAdapter({}, {}).apply { size = Prefs.ListSize.COMPACT }
        val h = bind(a, row())
        assertEquals(View.GONE, h.binding.tagText.visibility)
        assertTrue(h.binding.titleText.text.toString().contains("BODY CAM · strong 90%"))
        assertTrue(h.binding.titleText.text.toString().endsWith("Axon body camera"))
        assertEquals(View.VISIBLE, h.binding.macText.visibility)
        assertEquals(1, h.binding.titleText.maxLines)
    }

    @Test
    fun denseMergesAddressAndDetails() {
        val a = DeviceAdapter({}, {}).apply { size = Prefs.ListSize.DENSE }
        val h = bind(a, row())
        assertEquals(View.GONE, h.binding.macText.visibility)
        val meta = h.binding.metaText.text.toString()
        assertTrue(meta, meta.startsWith("00:25:DF:12:34:56 · Axon") && meta.contains("-62 dBm") && meta.contains("INTERNAL"))
        assertEquals(1, h.binding.metaText.maxLines)
        assertTrue(h.binding.rowBox.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
    }

    @Test
    fun alertingRowStaysRegular() {
        val a = DeviceAdapter({}, {}).apply { size = Prefs.ListSize.DENSE }
        val h = bind(a, row(highlight = 0xFFD0503C.toInt()))
        assertEquals(View.VISIBLE, h.binding.tagText.visibility)
        assertEquals(View.VISIBLE, h.binding.macText.visibility)
        assertEquals("Axon body camera", h.binding.titleText.text.toString())
    }

    @Test
    fun recycledRowGoesBackToRegular() {
        val a = DeviceAdapter({}, {}).apply { size = Prefs.ListSize.DENSE }
        val h = bind(a, row())
        a.size = Prefs.ListSize.REGULAR
        bind(a, row(), h)
        assertEquals(View.VISIBLE, h.binding.macText.visibility)
        assertEquals(View.VISIBLE, h.binding.tagText.visibility)
        assertEquals(Int.MAX_VALUE, h.binding.titleText.maxLines)
        assertEquals(activity.resources.getDimension(R.dimen.text_subtitle), h.binding.titleText.textSize, 0.01f)
    }

    @Test
    fun historyDenseMergesTheLines() {
        val a = DetectionAdapter({}).apply { size = Prefs.ListSize.DENSE }
        a.submitList(listOf(DetectionEntity(mac = "00:25:DF:12:34:56", label = "Axon body camera", category = "BODY_CAM",
            confidence = 90, rssi = -62, timestamp = 1_700_000_000_000L, source = "BLE")))
        val h = a.onCreateViewHolder(parent, 0)
        a.onBindViewHolder(h, 0)
        assertEquals(View.GONE, h.binding.macText.visibility)
        assertTrue(h.binding.metaText.text.toString().startsWith("00:25:DF:12:34:56"))
        a.size = Prefs.ListSize.REGULAR
        a.onBindViewHolder(h, 0)
        assertEquals(View.VISIBLE, h.binding.macText.visibility)
        assertFalse(h.binding.metaText.text.toString().startsWith("00:25:DF:12:34:56"))
    }

    @Test
    fun flashIsOneBeatFromTheClock() {
        val low = 0x26D0503C; val high = 0xE6D0503C.toInt()
        assertEquals(low, DeviceAdapter.flashColor(low, high, 0))
        assertEquals(high, DeviceAdapter.flashColor(low, high, DeviceAdapter.FLASH_MS))
        assertEquals(low, DeviceAdapter.flashColor(low, high, 2 * DeviceAdapter.FLASH_MS))
        // Rising and falling meet at the same shade; any row at the same moment gets the same one.
        assertEquals(DeviceAdapter.flashColor(low, high, 200), DeviceAdapter.flashColor(low, high, 2 * DeviceAdapter.FLASH_MS - 200))
        assertEquals(DeviceAdapter.flashColor(low, high, 1_700_000_000_123L), DeviceAdapter.flashColor(low, high, 1_700_000_000_123L))
        val mid = DeviceAdapter.flashColor(low, high, DeviceAdapter.FLASH_MS / 2)
        assertTrue(mid != low && mid != high)
    }

    @Test
    fun wazeRowSaysHowLongAgoAndHowManyReports() {
        val now = 1_700_000_000_000L
        fun r(published: Long?, thumbs: Int) = WazePolice.Report("a", 0.0, 0.0, published, null, null, thumbs, null)
        assertEquals("12 min ago · 3 reports", WazePolice.reportsText(r(now - 12 * 60_000, 2), now))
        assertEquals("just now · 1 report", WazePolice.reportsText(r(now - 20_000, 0), now))
        assertEquals("1 h 5 min ago · 1 report", WazePolice.reportsText(r(now - 65 * 60_000, 0), now))
        // No time from Waze: left out rather than a wrong "just now".
        assertEquals("2 reports", WazePolice.reportsText(r(null, 1), now))
        // A clock a little ahead of the phone's.
        assertEquals("just now · 1 report", WazePolice.reportsText(r(now + 30_000, 0), now))
    }
}
