package com.rfsentinel.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.databinding.ActivityHistoryBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.history.History
import com.rfsentinel.app.history.HistoryEvent
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Where and when flagged equipment showed up: a heatmap (or points) of past
 * encounters on the map, plus hour-of-day and day-of-week patterns. Reads the
 * match log and recorded traces; everything stays on this phone.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val heat by lazy { HeatmapOverlay(radiusDp = 38f) }
    private val points = FolderOverlay()
    private var events: List<HistoryEvent> = emptyList()
    private var fitted = false
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "History map & timeline"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Edge-to-edge: keep the filter row below the action bar and the panel above the nav bar.
        binding.root.applySystemBarInsets()

        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.controller.setZoom(4.0)
        map.overlays.add(heat)
        map.overlays.add(points)
        map.overlays.add(CopyrightOverlay(this)) // "© OpenStreetMap contributors"

        var lastRange = binding.rangeChips.checkedChipId
        binding.rangeChips.setOnCheckedStateChangeListener { group, ids ->
            // Tapping the selected period again would leave none selected: keep it selected.
            if (ids.isEmpty()) { group.check(lastRange); return@setOnCheckedStateChangeListener }
            lastRange = ids.first()
            fitted = false; load()
        }
        binding.heatChip.setOnCheckedChangeListener { _, _ -> render() }
        load()
    }

    private fun sinceMillis(): Long {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        return when (binding.rangeChips.checkedChipId) {
            R.id.range24h -> now - day
            R.id.range7d -> now - 7 * day
            R.id.range30d -> now - 30 * day
            else -> 0L
        }
    }

    private fun load() {
        val since = sinceMillis()
        lifecycleScope.launch {
            events = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@HistoryActivity)
                val logged = db.detectionDao().since(since).map {
                    HistoryEvent(it.timestamp, it.latitude, it.longitude, it.category, it.label, it.mac)
                }
                val traced = db.tripDao().flaggedDevicesSince(since).map {
                    HistoryEvent(it.firstSeen, it.lat, it.lon, it.category ?: "CUSTOM", it.label, it.mac)
                }
                History.encounters(logged + traced)
            }
            render()
        }
    }

    private fun render() {
        val positioned = events.filter { it.positioned }
        val geo = positioned.map { GeoPoint(it.lat!!, it.lon!!) }
        val showHeat = binding.heatChip.isChecked
        heat.points = if (showHeat) geo else emptyList()
        points.items.clear()
        if (!showHeat) drawPoints(positioned)

        binding.summaryText.text = when {
            events.isEmpty() -> "No flagged equipment logged in this period"
            else -> "${events.size} encounter${if (events.size == 1) "" else "s"} · " +
                (History.busiest(events) ?: "")
        }
        binding.detailText.text = when {
            events.isEmpty() -> "Scan with alerts on, or record traces, to build your history."
            positioned.isEmpty() -> "Top: " + top() +
                "\nNot on the map: turn on Settings > Location > Save GPS position with logged matches, or record traces."
            positioned.size < events.size -> "Top: " + top() + "\n${positioned.size} with a position shown on the map."
            else -> "Top: " + top()
        }
        binding.hourChart.set(History.byHour(events), List(24) { h -> if (h % 6 == 0) "${h}h" else null })
        binding.dayChart.set(History.byWeekday(events), listOf("M", "T", "W", "T", "F", "S", "S"))

        if (!fitted && geo.isNotEmpty()) {
            fitted = true
            binding.map.post {
                if (geo.size == 1) { binding.map.controller.setZoom(15.0); binding.map.controller.setCenter(geo[0]) }
                else binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.4f), false)
            }
        }
        binding.map.invalidate()
    }

    private fun top(): String = History.topLabels(events).joinToString(", ") { "${it.first} (${it.second})" }

    private fun drawPoints(list: List<HistoryEvent>) {
        val dp = resources.displayMetrics.density
        list.take(3000).forEach { e ->
            val color = ThemeManager.ink(this, Category.parse(e.category)?.colorArgb ?: 0xFF607D8B.toInt())
            points.add(Marker(binding.map).apply {
                position = GeoPoint(e.lat!!, e.lon!!)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke((1.5f * dp).toInt(), Color.WHITE)
                    setSize((12 * dp).toInt(), (12 * dp).toInt())
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ ->
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle(e.label)
                        .setMessage("${dateFmt.format(Date(e.time))}\n${e.mac}")
                        .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this@HistoryActivity, e.mac) }
                        .setNegativeButton("Close", null)
                        .show()
                    true
                }
            })
        }
    }

    override fun onResume() { super.onResume(); binding.map.onResume() }
    override fun onPause() { binding.map.onPause(); super.onPause() }
    override fun onDestroy() { binding.map.onDetach(); super.onDestroy() }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
