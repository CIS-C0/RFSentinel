package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.databinding.ActivityHistoryBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.history.History
import com.rfsentinel.app.history.HistoryEvent
import com.rfsentinel.app.service.DeviceRegistry
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
 *
 * Opened for one device ([EXTRA_MAC], its page's Sightings map): every spot it was heard with a position (match
 * log, recorded traces, this session's scanning) as a dot, the ones of one encounter linked in time order.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var engine: MapEngine? = null
    private var map: MapEngine? = null
    private var events: List<HistoryEvent> = emptyList()
    /** One device's map: the device, and every sighting of it with a position, oldest first. */
    private var deviceMac: String? = null
    private var trail: List<HistoryEvent> = emptyList()
    /** The encounters drawn as points, to find what a tap hit. */
    private var shown: List<HistoryEvent> = emptyList()
    private var fitted = false
    /** The map's start-up is marked on disk until it has drawn the encounters for a few seconds. */
    private var safetyArmed = false
    /** The encounters have been read at least once (the map has had something to draw). */
    private var loaded = false
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        deviceMac = intent.getStringExtra(EXTRA_MAC)
        title = if (deviceMac != null) "Sightings map" else "History map & timeline"
        deviceMac?.let { mac ->
            supportActionBar?.subtitle = DeviceNames.of(mac) ?: mac
            // Its own spots, linked, rather than a heat cloud; over all the time there is.
            if (savedInstanceState == null) { binding.rangeChips.check(R.id.rangeAll); binding.heatChip.isChecked = false }
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Edge-to-edge: keep the filter row below the action bar and the panel above the nav bar.
        binding.root.applySystemBarInsets()

        // The app closed while a map was starting last time: say so, and try with OpenGL (or ask before trying again).
        if (savedInstanceState == null) MapSafetyUi.tell(this, MapSafety.recover(this), onTryAgain = { addMap(null) })
        else addMap(savedInstanceState)

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

    /**
     * The map chosen in Settings > Map (OpenFreeMap or Mapbox). Its start-up is guarded like the main map's
     * ([MapSafety]): a phone whose graphics driver closes the app while drawing it (seen with the heatmap on
     * Vulkan) gets the OpenGL renderer at the next launch instead of closing every time.
     */
    private fun addMap(state: Bundle?) {
        if (isFinishing || isDestroyed || engine != null) return
        MapSafety.arm(this, MapEngine.chosen(this)); safetyArmed = true
        val e = MapEngine.create(this).also { engine = it }
        binding.map.addView(e.view, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        binding.mapCredit.text = e.credit
        e.onCreate(state)
        e.whenReady {
            if (isDestroyed) return@whenReady
            map = e
            e.setMinZoom(2.0)
            e.jumpTo(30.0, 0.0, 3.0)
            e.onClick = { x, y -> onTap(e, x, y) }
            val dark = Prefs.mapDark(this) ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
            if (dark) { binding.mapCredit.setBackgroundColor(0x40000000); binding.mapCredit.setTextColor(0xFFDDDDDD.toInt()) }
            // Mapbox's logo and info button, top left of the map.
            binding.mapCredit.post { if (!isDestroyed) e.placeBranding(0) }
            e.setStyle(dark) { fresh ->
                if (fresh) runCatching { addLayers(e) }
                render()
            }
        }
        // Started late (after the "map closed the app" question): catch up with the screen.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) e.onStart()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) e.onResume()
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
        deviceMac?.let { loadDevice(it, since); return }
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
            loaded = true
            render()
        }
    }

    /** One device's sightings: logged matches, recorded traces and where this session heard it. */
    private fun loadDevice(mac: String, since: Long) {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@HistoryActivity)
                val logged = db.detectionDao().forMacSince(mac, since)
                val category = logged.firstOrNull()?.category
                val label = logged.firstOrNull()?.label ?: DeviceRegistry.get(mac)?.let { it.best?.label ?: it.name } ?: mac
                val traced = db.tripDao().sightingsOf(mac, since)
                // This session: where your phone was each time it heard it (its newer address too, if it changed).
                val live = listOf(mac, DeviceRegistry.currentAddress(mac)).distinct()
                    .flatMap { DeviceRegistry.get(it)?.path.orEmpty() }
                    .filter { it.time >= since }
                    .map { HistoryEvent(it.time, it.lat, it.lon, category ?: "", label, mac) }
                logged.map { HistoryEvent(it.timestamp, it.latitude, it.longitude, it.category, it.label, mac) } +
                    traced.map { HistoryEvent(it.firstSeen, it.lat, it.lon, it.category ?: category ?: "", it.label, mac) } + live
            }
            trail = all.filter { it.positioned }.distinctBy { Triple(it.time / 1000, it.lat, it.lon) }.sortedBy { it.time }
            events = History.encounters(all)
            loaded = true
            render()
        }
    }

    /**
     * The heatmap: every encounter adds heat, coloured blue -> cyan -> green -> yellow -> red as it
     * builds up (as before); points: one dot per encounter in its category's colour.
     */
    private fun addLayers(m: MapEngine) {
        m.addSource(HEAT)
        m.addSource(TRAIL)
        m.addSource(POINTS)
        m.addLayer(JSONObject("""{"id":"$HEAT","type":"heatmap","source":"$HEAT","paint":{
            "heatmap-radius":38,"heatmap-weight":0.35,"heatmap-opacity":0.9,
            "heatmap-color":["interpolate",["linear"],["heatmap-density"],
                0,"rgba(0,0,0,0)",0.1,"rgba(32,32,160,0.13)",0.3,"rgba(0,229,255,0.44)",
                0.55,"rgba(0,208,96,0.56)",0.8,"rgba(255,224,0,0.69)",1,"rgba(255,32,32,0.88)"]}}"""))
        // One device's sightings of one encounter, linked in time order (under the dots).
        m.addLayer(JSONObject("""{"id":"$TRAIL","type":"line","source":"$TRAIL",
            "layout":{"line-join":"round","line-cap":"round"},
            "paint":{"line-color":["to-color",["get","color"]],"line-width":3,"line-opacity":0.75}}"""))
        // Mapbox's night look is lit: a dot that gives off no light of its own goes black on it, so there it glows.
        val glow = if (m.provider == MapEngine.Provider.MAPBOX) "\"circle-emissive-strength\":1," else ""
        m.addLayer(JSONObject("""{"id":"$POINTS","type":"circle","source":"$POINTS","paint":{
            $glow"circle-color":["to-color",["get","color"]],"circle-radius":6,
            "circle-stroke-color":"#FFFFFF","circle-stroke-width":1.5,"circle-pitch-alignment":"viewport"}}"""))
    }

    private fun render() {
        val device = deviceMac != null
        // On the map: the encounters, or every sighting of the one device.
        val positioned = if (device) trail else events.filter { it.positioned }
        val showHeat = binding.heatChip.isChecked
        val m = map
        fun color(e: HistoryEvent) = String.format("#%06X",
            ThemeManager.ink(this@HistoryActivity, Category.parse(e.category)?.colorArgb ?: 0xFF607D8B.toInt()) and 0xFFFFFF)
        m?.takeIf { it.styleLoaded }?.let { mm ->
            runCatching {
                mm.setData(HEAT, GeoJson.collection(
                    if (showHeat) positioned.map { GeoJson.feature(GeoJson.point(it.lat!!, it.lon!!)) } else emptyList()))
                shown = if (showHeat) emptyList() else if (device) positioned.takeLast(3000) else positioned.take(3000)
                mm.setData(TRAIL, GeoJson.collection(if (!device) emptyList() else
                    History.runs(shown, ENCOUNTER_GAP_MS).filter { it.size >= 2 }.map { run ->
                        GeoJson.feature(GeoJson.line(run.map { it.lat!! to it.lon!! }), "color" to color(run.first()))
                    }))
                mm.setData(POINTS, GeoJson.collection(shown.withIndex().map { (i, e) ->
                    GeoJson.feature(GeoJson.point(e.lat!!, e.lon!!), "i" to i.toString(), "color" to color(e))
                }))
            }
        }

        binding.summaryText.text = when {
            device && events.isEmpty() -> "Not heard in this period"
            device -> "${events.size} encounter${if (events.size == 1) "" else "s"} · ${positioned.size} spot${if (positioned.size == 1) "" else "s"} on the map"
            events.isEmpty() -> "No flagged equipment logged in this period"
            else -> "${events.size} encounter${if (events.size == 1) "" else "s"} · " +
                (History.busiest(events) ?: "")
        }
        binding.detailText.text = when {
            device && positioned.isEmpty() -> "No position saved for it in this period. Spots come from this session's scanning, " +
                "recorded traces and logged matches (Settings > Location > Save GPS position with logged matches)."
            device -> "Each dot is where your phone was when it heard it (not the device's own position). " +
                "Dots of one encounter are linked in time order; tap one for its time."
            events.isEmpty() -> "Scan with alerts on, or record traces, to build your history."
            positioned.isEmpty() -> "Top: " + top() +
                "\nNot on the map: turn on Settings > Location > Save GPS position with logged matches, or record traces."
            positioned.size < events.size -> "Top: " + top() + "\n${positioned.size} with a position shown on the map."
            else -> "Top: " + top()
        }
        binding.hourChart.set(History.byHour(events), List(24) { h -> if (h % 6 == 0) "${h}h" else null })
        binding.dayChart.set(History.byWeekday(events), listOf("M", "T", "W", "T", "F", "S", "S"))

        // Drawn with the encounters (the heatmap too): once that has lasted a few seconds the map works on this phone.
        if (safetyArmed && loaded && m != null && m.styleLoaded) binding.map.postDelayed({
            if (safetyArmed && !isDestroyed) { safetyArmed = false; MapSafety.worked(this) }
        }, MapSafety.SETTLE_MS)
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
            MaterialAlertDialogBuilder(this)
                .setTitle(e.label)
                .setMessage("${dateFmt.format(Date(e.time))}\n${e.mac}")
                .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this, e.mac) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    override fun onStart() { super.onStart(); engine?.onStart() }
    override fun onResume() { super.onResume(); engine?.onResume() }
    override fun onPause() {
        engine?.onPause()
        if (safetyArmed) { safetyArmed = false; MapSafety.disarm(this) } // leaving normally is not a crash
        super.onPause()
    }
    override fun onStop() { engine?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); engine?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); engine?.onSaveInstanceState(outState) }
    override fun onDestroy() { engine?.onDestroy(); engine = null; map = null; super.onDestroy() }

    companion object {
        /** Show one device's sightings only. */
        const val EXTRA_MAC = "mac"
        private const val HEAT = "history-heat"
        private const val TRAIL = "history-trail"
        private const val POINTS = "history-points"
        /** Sightings further apart than this are separate encounters (not linked), as in [History.encounters]. */
        private const val ENCOUNTER_GAP_MS = 10 * 60_000L
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
