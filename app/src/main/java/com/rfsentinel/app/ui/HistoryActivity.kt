package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.databinding.ActivityHistoryBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.history.History
import com.rfsentinel.app.history.HistoryEvent
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Where and when flagged equipment showed up: a heatmap (or points) of past
 * encounters on the map, plus hour-of-day and day-of-week patterns. Reads the
 * match log and recorded traces; everything stays on this phone.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var engine: MapEngine? = null
    private var map: MapEngine? = null
    private var events: List<HistoryEvent> = emptyList()
    /** The encounters drawn as points, to find what a tap hit. */
    private var shown: List<HistoryEvent> = emptyList()
    private var fitted = false
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "History map & timeline"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Edge-to-edge: keep the filter row below the action bar and the panel above the nav bar.
        binding.root.applySystemBarInsets()

        // The map chosen in Settings > Map (OpenFreeMap or Mapbox).
        val e = MapEngine.create(this).also { engine = it }
        binding.map.addView(e.view, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        binding.mapCredit.text = e.credit
        e.onCreate(savedInstanceState)
        e.whenReady {
            if (isDestroyed) return@whenReady
            map = e
            e.setMinZoom(2.0)
            e.jumpTo(30.0, 0.0, 3.0)
            e.onClick = { x, y -> onTap(e, x, y) }
            val dark = Prefs.mapDark(this) ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
            if (dark) { binding.mapCredit.setBackgroundColor(0x99000000.toInt()); binding.mapCredit.setTextColor(0xFFDDDDDD.toInt()) }
            // Mapbox's logo and info button, top left of the map.
            binding.mapCredit.post { if (!isDestroyed) e.placeBranding(0) }
            e.setStyle(dark) { fresh ->
                if (fresh) runCatching { addLayers(e) }
                render()
            }
        }

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

    /**
     * The heatmap: every encounter adds heat, coloured blue -> cyan -> green -> yellow -> red as it
     * builds up (as before); points: one dot per encounter in its category's colour.
     */
    private fun addLayers(m: MapEngine) {
        m.addSource(HEAT)
        m.addSource(POINTS)
        m.addLayer(JSONObject("""{"id":"$HEAT","type":"heatmap","source":"$HEAT","paint":{
            "heatmap-radius":38,"heatmap-weight":0.35,"heatmap-opacity":0.9,
            "heatmap-color":["interpolate",["linear"],["heatmap-density"],
                0,"rgba(0,0,0,0)",0.1,"rgba(32,32,160,0.13)",0.3,"rgba(0,229,255,0.44)",
                0.55,"rgba(0,208,96,0.56)",0.8,"rgba(255,224,0,0.69)",1,"rgba(255,32,32,0.88)"]}}"""))
        m.addLayer(JSONObject("""{"id":"$POINTS","type":"circle","source":"$POINTS","paint":{
            "circle-color":["to-color",["get","color"]],"circle-radius":6,
            "circle-stroke-color":"#FFFFFF","circle-stroke-width":1.5,"circle-pitch-alignment":"viewport"}}"""))
    }

    private fun render() {
        val positioned = events.filter { it.positioned }
        val showHeat = binding.heatChip.isChecked
        val m = map
        m?.takeIf { it.styleLoaded }?.let { mm ->
            runCatching {
                mm.setData(HEAT, GeoJson.collection(
                    if (showHeat) positioned.map { GeoJson.feature(GeoJson.point(it.lat!!, it.lon!!)) } else emptyList()))
                shown = if (showHeat) emptyList() else positioned.take(3000)
                mm.setData(POINTS, GeoJson.collection(shown.withIndex().map { (i, e) ->
                    GeoJson.feature(GeoJson.point(e.lat!!, e.lon!!), "i" to i.toString(),
                        "color" to String.format("#%06X",
                            ThemeManager.ink(this@HistoryActivity, Category.parse(e.category)?.colorArgb ?: 0xFF607D8B.toInt()) and 0xFFFFFF))
                }))
            }
        }

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

        if (!fitted && positioned.isNotEmpty() && m != null && m.styleLoaded) {
            fitted = true
            binding.map.post {
                runCatching {
                    if (positioned.size == 1) m.jumpTo(positioned[0].lat!!, positioned[0].lon!!, 14.0)
                    else m.fit(positioned.map { it.lat!! to it.lon!! }, (48 * resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    private fun top(): String = History.topLabels(events).joinToString(", ") { "${it.first} (${it.second})" }

    /** A tapped point: when it was, and the device. */
    private fun onTap(m: MapEngine, x: Float, y: Float) {
        val r = 18f * resources.displayMetrics.density
        m.query(android.graphics.RectF(x - r, y - r, x + r, y + r), listOf(POINTS to "i")) { found ->
            if (isFinishing || isDestroyed) return@query
            val e = found.firstOrNull()?.firstOrNull()?.toIntOrNull()?.let { shown.getOrNull(it) } ?: return@query
            AlertDialog.Builder(this)
                .setTitle(e.label)
                .setMessage("${dateFmt.format(Date(e.time))}\n${e.mac}")
                .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this, e.mac) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    override fun onStart() { super.onStart(); engine?.onStart() }
    override fun onResume() { super.onResume(); engine?.onResume() }
    override fun onPause() { engine?.onPause(); super.onPause() }
    override fun onStop() { engine?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); engine?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); engine?.onSaveInstanceState(outState) }
    override fun onDestroy() { engine?.onDestroy(); engine = null; map = null; super.onDestroy() }

    private companion object {
        const val HEAT = "history-heat"
        const val POINTS = "history-points"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
