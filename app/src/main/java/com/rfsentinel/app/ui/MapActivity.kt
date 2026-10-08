package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenStreetMap view of a scan. Live mode shows your position, the trace being
 * recorded and nearby devices; trip mode shows a saved trace. Devices are drawn
 * where YOUR phone was when their signal was strongest - an approximation.
 *
 * Network use: map tiles only (OpenStreetMap), cached in the app's cache dir.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        /** Open the map centred here (e.g. a cell tower), without following your position. */
        const val EXTRA_CENTER_LAT = "center_lat"
        const val EXTRA_CENTER_LON = "center_lon"
        /** Turn the cell tower layer on. */
        const val EXTRA_SHOW_TOWERS = "show_towers"
        /** A failed camera download stays in the status line this long (the retry runs after a minute). */
        private const val CAMERA_FAIL_SHOWN_MS = 90_000L
        private const val TRACE_COLOR = 0xFFE0622D.toInt()
        private const val PAST_TRACE_COLOR = 0xFF1F5FBF.toInt()
        private const val DRONE_COLOR = 0xFF1F5FBF.toInt()
    }

    /** One device to draw, from the live registry or a saved trip. */
    private data class Pin(
        val mac: String, val lat: Double, val lon: Double, val label: String, val flagged: Boolean,
        val color: Int, val details: String
    )

    private lateinit var binding: ActivityMapBinding
    private var tripId: Long? = null
    private var trip: TripEntity? = null
    /** Which devices the map shows (live: every filter; a saved trace: all or flagged). */
    private var filter = DeviceFilter.ALL
    private val filterChips = HashMap<DeviceFilter, com.google.android.material.chip.Chip>()
    private var centeredOnce = false
    /** The live map opened on a world view because nothing was known about where you are: zoom in with the first fix. */
    private var startedOnWorld = false
    /** The map keeps your position centred until you pan it yourself. */
    private var following = false

    private val trace = Polyline()
    private val pins by lazy { PointsOverlay<Pin>(resources.displayMetrics.density) { showPins(it) } }
    /** Live drones from Remote ID: aircraft, operator and the line between them. */
    private val drones = FolderOverlay()
    /** Aircraft from the ADS-B feeds (Settings > What to detect > Police / government aircraft). */
    private val aircraft = FolderOverlay()
    /** Waze reports (Settings > What to detect > Waze): shown on the All and Waze chips. */
    private val wazeLayer = FolderOverlay()
    private var planeZoomStep = -1
    /** Plate-reader cameras mapped in OpenStreetMap (downloaded on request). */
    private val knownAlpr by lazy {
        PointsOverlay<com.rfsentinel.app.alpr.KnownCamera>(resources.displayMetrics.density) { showKnownCamera(it.first()) }
    }
    private var knownAlprDrawnFor: BoundingBox? = null
    /** CCTV cameras mapped in OpenStreetMap (map menu, off by default): icons over their view cones. */
    private val cctv by lazy {
        PointsOverlay<com.rfsentinel.app.alpr.CctvCamera>(resources.displayMetrics.density) { showCctv(it.first()) }
    }
    private val cctvCones by lazy { CctvConeOverlay(resources.displayMetrics.density) }
    /** Cell towers seen while scanning, at their estimated (strongest-signal) spot. */
    private val towers by lazy {
        PointsOverlay<com.rfsentinel.app.service.CellTowerStore.Tower>(resources.displayMetrics.density) { showTower(it.first()) }
    }
    private val towerIcon by lazy { MapIcons.towerIcon(resources.displayMetrics.density) }
    private lateinit var myLocation: MyLocationNewOverlay
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid: identify ourselves to the tile server (OSM tile policy) and keep
        // the tile cache inside the app's private cache.
        MapIcons.configureOsm(this)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1).takeIf { it > 0 }
        title = if (tripId == null) "Scan map" else "Recorded trace"

        // Edge-to-edge: keep the controls above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controls.updatePadding(bottom = bars.bottom + (6 * resources.displayMetrics.density).toInt())
            binding.statusBox.translationY = bars.top.toFloat()
            insets
        }

        if (intent.getBooleanExtra(EXTRA_SHOW_TOWERS, false)) com.rfsentinel.app.util.Prefs.setShowCellTowers(this, true)
        setupMap()
        binding.map.post { drawKnownAlpr() }
        // Every device heard around you by default (same colours as the list and radar).
        setupFilterChips()
        styleFollowButton()
        // Outlined "Traces" in the theme highlight (the stock dark teal was unreadable on dark themes).
        ChipStyle.accent(this).first.let { accent ->
            binding.tracesButton.setTextColor(accent)
            binding.tracesButton.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        }
        binding.centerButton.setOnClickListener {
            if (tripId == null) setFollow(true) else centerOnMe()
        }
        binding.tracesButton.setOnClickListener { startActivity(Intent(this, TripsActivity::class.java)) }

        if (tripId == null) {
            observeBulk()
            binding.recordButton.setOnClickListener { toggleRecording() }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // A new or cleared Waze report is drawn at once, not at the next 3 s tick.
                    val stopWaze = com.rfsentinel.app.online.WazePolice.addListener { runOnUiThread { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) renderLive() } }
                    try {
                        while (isActive) {
                            renderLive()
                            delay(3_000)
                        }
                    } finally {
                        stopWaze()
                    }
                }
            }
        } else {
            binding.recordButton.visibility = View.GONE
            loadTrip()
        }
    }

    private fun setupMap() {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        // Open where you are, not on (0, 0): the live map used to sit on open sea until the first GPS fix.
        val liveHere = tripId == null && !intent.hasExtra(EXTRA_CENTER_LAT)
        val knownFix = if (liveHere) knownFix() else null
        val start = if (liveHere) MapStart.pick(
            listOf(knownFix?.let { MapStart.Fix(it.latitude, it.longitude, it.time) }),
            com.rfsentinel.app.util.Prefs.lastMapCenter(this), com.rfsentinel.app.util.Prefs.mapZoom(this), System.currentTimeMillis()
        ) else null
        map.controller.setZoom(start?.zoom ?: if (liveHere) MapStart.WORLD_ZOOM else MapStart.DEFAULT_ZOOM)
        startedOnWorld = liveHere && start == null
        if (start != null) {
            map.controller.setCenter(GeoPoint(start.lat, start.lon))
            if (start.fresh) centeredOnce = true // already where you are: the first GPS fix need not move the map
        }
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        trace.outlinePaint.apply {
            color = if (tripId == null) TRACE_COLOR else PAST_TRACE_COLOR
            strokeWidth = 7f * resources.displayMetrics.density / 2
        }
        map.overlays.add(cctvCones)
        map.overlays.add(cctv)
        map.overlays.add(knownAlpr)
        map.overlays.add(towers)
        map.overlays.add(trace)
        map.overlays.add(pins)
        map.overlays.add(drones)
        map.overlays.add(wazeLayer)
        map.overlays.add(aircraft)
        // Redraw the camera layer for the visible area after panning / zooming.
        map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = false.also {
                drawKnownAlprSoon()
                // osmdroid stops following when you drag the map: show that on the button.
                if (following && !myLocation.isFollowLocationEnabled) setFollow(false)
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?) = false.also {
                drawKnownAlprSoon()
                // Plane icons grow as you zoom in (and shrink as you zoom out).
                val step = kotlin.math.round(binding.map.zoomLevelDouble * 2).toInt()
                if (step != planeZoomStep) { planeZoomStep = step; drawAircraft() }
            }
        })

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        if (Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            myLocation.enableMyLocation()
            if (intent.hasExtra(EXTRA_CENTER_LAT)) {
                // Opened on a place (a cell tower): look there instead of following you.
                map.controller.setZoom(15.0)
                map.controller.setCenter(GeoPoint(intent.getDoubleExtra(EXTRA_CENTER_LAT, 0.0), intent.getDoubleExtra(EXTRA_CENTER_LON, 0.0)))
                centeredOnce = true
            } else if (tripId == null) {
                // Live map: keep your position centred from the first fix until you pan away.
                setFollow(true, quiet = true)
                myLocation.runOnFirstFix {
                    runOnUiThread {
                        if (!centeredOnce) {
                            centeredOnce = true
                            if (following) {
                                if (startedOnWorld) { startedOnWorld = false; binding.map.controller.setZoom(MapStart.DEFAULT_ZOOM) }
                                centerOnMe()
                            }
                        }
                    }
                }
                // A current position puts the arrow on the map straight away instead of after the first GPS update.
                if (start?.fresh == true) knownFix?.let { myLocation.onLocationChanged(it, null) }
            }
        }
        map.overlays.add(myLocation)
        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(false); setScaleBarOffset(20, 180) })
        map.overlays.add(CopyrightOverlay(this)) // "© OpenStreetMap contributors" (required attribution)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        // The location overlay's GPS runs only while the map is on screen.
        if (Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            myLocation.enableMyLocation()
            if (following) myLocation.enableFollowLocation()
        }
        // Precise GPS while the map is on screen, so devices land where they were heard.
        if (tripId == null) ScanForegroundService.mapShown(this)
        // Back from Settings > Map: show what's switched on now (and nothing that was switched off).
        drawTowers()
        drawKnownAlpr()
        if (com.rfsentinel.app.util.Prefs.showCctv(this)) lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rfsentinel.app.alpr.CctvStore.load(this@MapActivity) }
            drawCctv()
            maybeDownloadCctv()
        } else drawCctv()
    }

    override fun onPause() {
        saveView()
        if (tripId == null) ScanForegroundService.mapHidden(this)
        myLocation.disableMyLocation()
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopObservingBulk?.invoke()
        com.rfsentinel.app.alpr.CctvStore.onChanged = null
        myLocation.disableMyLocation()
        binding.map.removeCallbacks(autoDownloadCameras)
        binding.map.removeCallbacks(redrawKnownAlpr)
        // These two were left pending: one firing after onDetach() below touches detached overlays and crashes.
        binding.map.removeCallbacks(redrawCctv)
        binding.map.removeCallbacks(downloadCctv)
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun setupFilterChips() {
        val options = if (tripId == null) DeviceFilter.entries else listOf(DeviceFilter.ALL, DeviceFilter.FLAGGED)
        filter = DeviceFilter.parse(com.rfsentinel.app.util.Prefs.mapFilter(this)).takeIf { it in options } ?: DeviceFilter.ALL
        val group = binding.mapFilterChips
        for (f in options) {
            val chip = com.google.android.material.chip.Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = f.label
                isCheckable = true
                isCheckedIconVisible = false
                ChipStyle.apply(this, overMap = true)
            }
            filterChips[f] = chip
            group.addView(chip)
            if (f == filter) chip.isChecked = true
        }
        group.setOnCheckedStateChangeListener { _, ids ->
            val picked = filterChips.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            filter = picked
            com.rfsentinel.app.util.Prefs.setMapFilter(this, picked.name)
            if (tripId != null) loadTrip() else renderLive()
            drawTowers()
        }
    }

    /** Chip labels with how many devices each filter would show ("Trackers 2"). */
    private fun updateFilterCounts(devices: List<DeviceRegistry.Snapshot>) {
        val placed = devices.filter { it.bestPosition != null }
        for ((f, chip) in filterChips) {
            val n = when (f) {
                DeviceFilter.CELLS -> towerCount
                DeviceFilter.WAZE -> currentWaze().size
                else -> placed.count { f.matches(it) }
            }
            chip.text = if (f == DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
        }
    }

    /** Turns following on (re-centres now and keeps you centred) or off (after you pan). */
    private fun setFollow(on: Boolean, quiet: Boolean = false) {
        following = on
        if (on) {
            myLocation.enableFollowLocation()
            centerOnMe(quiet)
        } else {
            myLocation.disableFollowLocation()
        }
        styleFollowButton()
    }

    /** Filled with the theme highlight while following, plain otherwise. */
    private fun styleFollowButton() {
        val on = following
        val (accent, onAccent) = ChipStyle.accent(this)
        val plain = 0xFFFFFFFF.toInt()
        binding.centerButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) accent else plain)
        binding.centerButton.imageTintList = android.content.res.ColorStateList.valueOf(if (on) onAccent else 0xFF0B5C63.toInt())
        binding.centerButton.contentDescription = if (on) "Following your position" else "Follow my position"
    }

    private fun centerOnMe(quiet: Boolean = false) {
        val fix = myLocation.myLocation
            ?: TripRecorder.livePoints().lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
        if (fix != null) goTo(fix)
        else if (!quiet) Toast.makeText(this, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
    }

    /** Glides to a nearby place; jumps when it is far (an old position, or the first fix after a long trip). */
    private fun goTo(p: GeoPoint) {
        val map = binding.map
        val here = map.mapCenter
        val far = !map.isLayoutOccurred ||
            DeviceRegistry.metersBetween(here.latitude, here.longitude, p.latitude, p.longitude) > 3_000
        if (far) map.controller.setCenter(p) else map.controller.animateTo(p)
    }

    /** The best position known before this screen's own GPS answers: the running scan's fix, else the system's last known. */
    private fun knownFix(): android.location.Location? =
        listOfNotNull(ScanForegroundService.lastFix, systemLastKnown()).maxByOrNull { it.time }

    @android.annotation.SuppressLint("MissingPermission")
    private fun systemLastKnown(): android.location.Location? {
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        return runCatching {
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    /** Remembers where the live map is looking, so it can open there next time. */
    private fun saveView() {
        if (tripId != null || intent.hasExtra(EXTRA_CENTER_LAT)) return
        val c = binding.map.mapCenter
        if (kotlin.math.abs(c.latitude) < 1e-4 && kotlin.math.abs(c.longitude) < 1e-4) return // it never looked anywhere
        com.rfsentinel.app.util.Prefs.saveMapView(this, c.latitude, c.longitude, binding.map.zoomLevelDouble)
    }

    // ---- Live mode ----------------------------------------------------------------

    private fun renderLive() {
        val recording = TripRecorder.isRecording
        binding.recordButton.text = if (recording) "Stop recording" else "Record trace"
        val (accent, onAccent) = ChipStyle.accent(this)
        binding.recordButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (recording) 0xFFB3261E.toInt() else accent)
        val recordFg = if (recording) 0xFFFFFFFF.toInt() else onAccent
        binding.recordButton.setTextColor(recordFg)
        binding.recordButton.iconTint = android.content.res.ColorStateList.valueOf(recordFg)

        // Only the trace being recorded right now; a stopped one is under Traces.
        trace.setPoints(if (recording) TripRecorder.livePoints().map { GeoPoint(it.lat, it.lon) } else emptyList())

        // Includes devices from a scan that just stopped: the registry drops them after 3 minutes.
        val devices = DeviceRegistry.snapshot()
        val drawn = devices.mapNotNull { s ->
            val pos = s.bestPosition ?: return@mapNotNull null
            val flagged = DeviceColors.isFlagged(s)
            if (!filter.matches(s)) return@mapNotNull null
            val best = s.best
            Pin(
                s.mac, pos.lat, pos.lon,
                best?.label ?: s.name ?: s.deviceType,
                flagged,
                DeviceColors.forDevice(this, s),
                buildString {
                    append(s.mac)
                    s.vendor?.let { append("\n").append(it) }
                    if (best != null) append("\n${best.category.title} · ${best.tier.label} ${best.confidence}%\n${best.evidence}")
                    append("\nStrongest signal here: ${s.bestRssi} dBm")
                }
            )
        }
        drawPins(drawn)
        updateFilterCounts(devices)
        drawDrones(devices.filter { it.remoteId?.hasPosition == true })
        drawAircraft()
        drawWaze()

        val positioned = devices.count { it.bestPosition != null }
        binding.statusText.text = when {
            bulkStatus() != null -> bulkStatus() // a camera download's progress wins
            System.currentTimeMillis() - com.rfsentinel.app.alpr.AlprStore.lastAutoFailureAt < CAMERA_FAIL_SHOWN_MS ->
                "Couldn't download the cameras for this area (map server busy or offline) - retrying"
            !ScanForegroundService.isRunning -> "Not scanning - start scanning, then Record trace to save your route"
            recording -> {
                val mins = (System.currentTimeMillis() - TripRecorder.startedAt) / 60000
                String.format(
                    Locale.US, "● Recording %d:%02d · %.2f km · %s (%d flagged)",
                    mins / 60, mins % 60, TripRecorder.distanceM / 1000, devicesLabel(TripRecorder.deviceCount()), TripRecorder.flaggedCount()
                )
            }
            positioned == 0 -> "Scanning · waiting for GPS to place devices"
            else -> "Scanning · ${drawn.size} on map" + if (filter != DeviceFilter.ALL) " (${filter.label} only)" else ""
        } + (cctvLine?.let { "\n$it" } ?: "")
        binding.statusText.background.mutate().setTint(if (recording) 0xFFB3261E.toInt() else 0xCC0B5C63.toInt())
        binding.map.invalidate()
    }

    private fun toggleRecording() {
        if (TripRecorder.isRecording) {
            lifecycleScope.launch {
                val id = TripRecorder.activeTripId
                TripRecorder.stop(this@MapActivity)
                refreshServiceLocation()
                val saved = id?.let { AppDatabase.getInstance(this@MapActivity).tripDao().get(it) }
                if (saved != null) {
                    AlertDialog.Builder(this@MapActivity)
                        .setTitle("Trace saved")
                        .setMessage(String.format(Locale.US, "%s\n%.2f km · %s (%d flagged)",
                            saved.name, saved.distanceM / 1000, devicesLabel(saved.deviceCount), saved.flaggedCount))
                        .setPositiveButton("View") { _, _ -> openTrip(saved.id) }
                        .setNeutralButton("Export") { _, _ -> Exporter.showTripExport(this@MapActivity, saved.id) }
                        .setNegativeButton("Close", null)
                        .show()
                } else {
                    Toast.makeText(this@MapActivity, "Nothing recorded (no GPS fix)", Toast.LENGTH_LONG).show()
                }
                renderLive()
            }
            return
        }
        if (Permissions.missingRequired(this).isNotEmpty()) {
            Toast.makeText(this, "Grant location and nearby-devices first (Start scanning on the main screen)", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            if (!ScanForegroundService.isRunning) {
                runCatching { ScanForegroundService.start(this@MapActivity) }
                delay(600)
            }
            TripRecorder.start(this@MapActivity)
            refreshServiceLocation()
            Toast.makeText(this@MapActivity, "Recording your trace - keeps going with the screen off", Toast.LENGTH_SHORT).show()
            renderLive()
        }
    }

    /** Tells the running service to switch location updates to trace mode (or back). */
    private fun refreshServiceLocation() {
        // Only a running scanner needs refreshing; sending it otherwise would start scanning.
        if (!ScanForegroundService.isRunning) return
        runCatching {
            startService(Intent(this, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_REFRESH_LOCATION))
        }
    }

    private fun openTrip(id: Long) {
        startActivity(Intent(this, MapActivity::class.java).putExtra(EXTRA_TRIP_ID, id))
    }

    // ---- Trip mode ------------------------------------------------------------------

    private fun loadTrip() {
        val id = tripId ?: return
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@MapActivity).tripDao()
            val t = dao.get(id) ?: run { finish(); return@launch }
            trip = t
            val points = dao.points(id).map { GeoPoint(it.lat, it.lon) }
            val devices = dao.devices(id)
            trace.setPoints(points)
            drawPins(devices.filter { it.lat != null && (it.flagged || filter == DeviceFilter.ALL) }.map { it.toPin() })
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            binding.statusText.text = String.format(
                Locale.US, "%s\n%s · %d:%02d · %.2f km · %s (%d flagged)",
                t.name, dateFmt.format(Date(t.startTime)), mins / 60, mins % 60, t.distanceM / 1000,
                devicesLabel(t.deviceCount), t.flaggedCount
            )
            binding.statusText.background.mutate().setTint(0xCC1F5FBF.toInt())
            val all = points + devices.filter { it.lat != null }.map { GeoPoint(it.lat!!, it.lon!!) }
            if (all.isNotEmpty()) {
                binding.map.post {
                    if (all.size == 1) binding.map.controller.setCenter(all[0])
                    else binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.3f), false)
                }
            }
            binding.map.invalidate()
        }
    }

    private fun TripDeviceEntity.toPin(): Pin {
        val cat = Category.parse(category)
        return Pin(
            mac, lat!!, lon!!, label, flagged,
            DeviceColors.forMatch(this@MapActivity, if (flagged) cat else null, confidence),
            buildString {
                append(mac)
                vendor?.let { append("\n").append(it) }
                if (cat != null) append("\n${cat.title} · ${Tier.of(confidence).label} $confidence%")
                evidence?.let { append("\n").append(it) }
                probedList.takeIf { it.isNotEmpty() }?.let { append("\nAsked for networks: ").append(it.joinToString(", ")) }
                append("\nStrongest signal here: $bestRssi dBm")
                append("\nHeard ${dateFmt.format(Date(firstSeen))}")
            }
        )
    }

    // ---- Drawing ----------------------------------------------------------------------

    /**
     * Each device sits exactly where it was heard best. Devices heard from the same
     * spot overlap; tapping there lists them all.
     */
    private fun drawPins(list: List<Pin>) {
        // Ordinary devices first so flagged ones are drawn on top.
        pins.points = list.sortedBy { it.flagged }.map { p ->
            PointsOverlay.Point(p.lat, p.lon, p, color = p.color, sizeDp = if (p.flagged) 24f else 16f)
        }
    }

    /**
     * Drones place themselves: Remote ID broadcasts the aircraft's own GPS fix
     * (and usually the operator's / take-off point), so these markers are real
     * positions, unlike RSSI-based device pins.
     */
    private fun drawDrones(list: List<DeviceRegistry.Snapshot>) {
        drones.items.clear()
        val dp = resources.displayMetrics.density
        for (s in list) {
            val r = s.remoteId ?: continue
            val aircraft = GeoPoint(r.latitude!!, r.longitude!!)
            val details = droneDetails(s, r)
            if (r.hasOperatorPosition) {
                val operator = GeoPoint(r.operatorLatitude!!, r.operatorLongitude!!)
                drones.add(Polyline().apply {
                    setPoints(listOf(operator, aircraft))
                    outlinePaint.apply {
                        color = DRONE_COLOR
                        strokeWidth = 2.5f * dp
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * dp, 6 * dp), 0f)
                    }
                })
                drones.add(Marker(binding.map).apply {
                    position = operator
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xFFF5E100.toInt())
                        setStroke((2 * dp).toInt(), DRONE_COLOR)
                        setSize((14 * dp).toInt(), (14 * dp).toInt())
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Drone operator / take-off point"
                    setOnMarkerClickListener { _, _ -> showDrone(s, "Operator of this drone (or its take-off point)\n\n$details"); true }
                })
            }
            drones.add(Marker(binding.map).apply {
                position = aircraft
                icon = android.graphics.drawable.BitmapDrawable(resources, droneArrow(dp))
                rotation = -(r.directionDeg ?: 0).toFloat() // osmdroid rotates counter-clockwise
                isFlat = true
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Drone"
                setOnMarkerClickListener { _, _ -> showDrone(s, details); true }
            })
        }
    }

    /** The Waze reports from the checker, which clears them when it stops. */
    private fun currentWaze(): List<com.rfsentinel.app.online.WazePolice.Shown> = com.rfsentinel.app.online.WazePolice.latest

    /**
     * Waze reports as coloured dots (blue police, red accident, orange hazard, brown closure,
     * purple jam) with a white outline. Tap one for what it is, how far, how old, how many confirmed.
     */
    private fun drawWaze() {
        wazeLayer.items.clear()
        val show = tripId == null && (filter == DeviceFilter.ALL || filter == DeviceFilter.WAZE)
        if (show) {
            val dp = resources.displayMetrics.density
            for (w in currentWaze()) {
                wazeLayer.add(Marker(binding.map).apply {
                    position = GeoPoint(w.report.lat, w.report.lon)
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(w.report.type.color)
                        setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
                        setSize((20 * dp).toInt(), (20 * dp).toInt())
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = w.hit.label
                    setOnMarkerClickListener { _, _ -> showWaze(w); true }
                })
            }
        }
        binding.map.invalidate()
    }

    private fun showWaze(w: com.rfsentinel.app.online.WazePolice.Shown) {
        AlertDialog.Builder(this)
            .setTitle(w.hit.label)
            .setMessage("${w.hit.tier.label} ${w.hit.confidence}%\n${com.rfsentinel.app.online.WazePolice.relativeText(w.where, w.trend)}\n${w.hit.evidence}")
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Aircraft from the latest ADS-B poll: police / government / circling ones in bold
     * purple with their agency and altitude, every other aircraft small and grey, each
     * pointing along its track. Tap one for its details.
     */
    private fun drawAircraft() {
        aircraft.items.clear()
        binding.map.invalidate()
        val list = com.rfsentinel.app.online.PoliceAircraft.latest
        if (list.isEmpty() || System.currentTimeMillis() - com.rfsentinel.app.online.PoliceAircraft.latestAt > 3 * 60_000L) return
        val dp = resources.displayMetrics.density
        // Sized to the zoom: 0.5x at a city-wide view (zoom 10), 1x at zoom 13, 2.2x at street level (17+).
        val scale = (0.5 + (binding.map.zoomLevelDouble - 10.0) * (1.7 / 7.0)).coerceIn(0.5, 2.2).toFloat()
        val icons = HashMap<Boolean, android.graphics.drawable.BitmapDrawable>()
        // Ordinary traffic first, so flagged aircraft are drawn on top.
        for ((p, hit) in list.sortedBy { it.hit != null }) {
            val flagged = hit != null
            val at = GeoPoint(p.lat, p.lon)
            aircraft.add(Marker(binding.map).apply {
                position = at
                icon = icons.getOrPut(flagged) { android.graphics.drawable.BitmapDrawable(resources, MapIcons.planeIcon(dp, flagged, scale)) }
                rotation = -(p.trackDeg ?: 0.0).toFloat() // osmdroid rotates counter-clockwise
                isFlat = true
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> showAircraft(p, hit); true }
            })
            if (flagged) {
                val label = listOfNotNull(
                    hit!!.label.substringAfter(": ", hit.label).take(28),
                    p.altitudeFt?.takeIf { it > 0 }?.let { "$it ft" }
                ).joinToString(" · ")
                aircraft.add(Marker(binding.map).apply {
                    position = at
                    icon = android.graphics.drawable.BitmapDrawable(resources, aircraftLabel(dp, label))
                    setAnchor(Marker.ANCHOR_CENTER, -0.9f) // just below the plane, never rotated
                    setOnMarkerClickListener { _, _ -> showAircraft(p, hit); true }
                })
            }
        }
    }

    private fun aircraftLabel(dp: Float, text: String): android.graphics.Bitmap {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * dp
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pad = 4 * dp
        val w = (p.measureText(text) + 2 * pad).toInt()
        val h = (p.textSize + 2 * pad).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = MapIcons.POLICE_AIRCRAFT_COLOR }
        c.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), 4 * dp, 4 * dp, bg)
        c.drawText(text, pad, h - pad - p.descent() / 2, p)
        return bmp
    }

    private fun showAircraft(p: com.rfsentinel.app.online.PoliceAircraft.Plane, hit: com.rfsentinel.app.detect.Hit?) {
        val me = myLocation.myLocation
        val text = buildString {
            hit?.let { append(it.evidence).append("\n\n") }
            p.registration?.let { append("Registration: $it\n") }
            p.callsign?.let { append("Callsign: $it\n") }
            p.type?.let { append("Type: $it\n") }
            p.owner?.let { append("Owner / operator: $it\n") }
            p.altitudeFt?.let { append(if (it <= 0) "On the ground\n" else "Altitude: $it ft\n") }
            p.speedKt?.let { append(String.format(Locale.US, "Speed: %.0f kt (%.0f km/h)\n", it, it * 1.852)) }
            p.trackDeg?.let { append(String.format(Locale.US, "Heading: %.0f°\n", it)) }
            me?.let { append(String.format(Locale.US, "Distance: %.1f km\n",
                DeviceRegistry.metersBetween(it.latitude, it.longitude, p.lat, p.lon) / 1000)) }
            append("ICAO address: ${p.hex.uppercase()}\nSource: community ADS-B (adsb.fi / adsb.lol)")
        }
        AlertDialog.Builder(this)
            .setTitle(hit?.label ?: (p.registration ?: p.callsign ?: "Aircraft"))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun droneDetails(s: DeviceRegistry.Snapshot, r: com.rfsentinel.app.detect.RemoteId.Info) = buildString {
        r.uasId?.let { append("ID: $it\n") }
        r.uaType?.let { append("Type: $it\n") }
        r.status?.let { append("Status: $it\n") }
        r.heightM?.let { append(String.format(Locale.US, "Height: %.0f m\n", it)) }
        r.altitudeGeoM?.let { append(String.format(Locale.US, "Altitude (GPS): %.0f m\n", it)) }
        r.speedMs?.let { append(String.format(Locale.US, "Speed: %.0f km/h\n", it * 3.6)) }
        r.directionDeg?.let { append("Heading: $it°\n") }
        r.operatorId?.let { append("Operator ID: $it\n") }
        r.selfIdDescription?.let { append("Description: $it\n") }
        append("Radio: ${s.mac} · ${s.rssi} dBm")
    }

    private fun showDrone(s: DeviceRegistry.Snapshot, text: String) {
        AlertDialog.Builder(this)
            .setTitle("Drone (Remote ID)")
            .setMessage(text + "\n\nPositions are broadcast by the drone itself (ASTM F3411 Remote ID).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this, s.mac) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** A blue arrow pointing north; the marker rotation turns it to the drone's heading. */
    private fun droneArrow(dp: Float): android.graphics.Bitmap {
        val size = (30 * dp).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(size / 2f, 2 * dp); lineTo(size - 5 * dp, size - 4 * dp)
            lineTo(size / 2f, size * 0.68f); lineTo(5 * dp, size - 4 * dp); close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawPath(path, paint.apply { color = DRONE_COLOR; style = android.graphics.Paint.Style.FILL })
        c.drawPath(path, paint.apply { color = Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * dp })
        return bmp
    }

    // ---- Known plate, speed and red-light cameras (OpenStreetMap) --------------------------------------------

    private val redrawKnownAlpr = Runnable { drawKnownAlpr() }

    private fun drawKnownAlprSoon() {
        binding.map.removeCallbacks(redrawKnownAlpr)
        binding.map.postDelayed(redrawKnownAlpr, 250)
        binding.map.removeCallbacks(redrawCctv)
        binding.map.postDelayed(redrawCctv, 250)
    }

    // ---- CCTV cameras (OpenStreetMap) ---------------------------------------------------------------

    private val redrawCctv = Runnable { drawCctv() }
    private fun drawCctvSoon() {
        binding.map.removeCallbacks(redrawCctv)
        binding.map.postDelayed(redrawCctv, 300)
    }
    private val downloadCctv = Runnable { maybeDownloadCctv() }

    /** Below this zoom the layer is hidden (cities hold thousands of cameras); view cones from [cctvConeZoom]. */
    private val cctvMinZoom = 12.0
    private val cctvConeZoom = 15.0

    /** What the CCTV layer is doing, added to the status line (null while the layer is off). */
    @Volatile private var cctvLine: String? = null

    private fun setCctvLine(text: String?) {
        if (text == cctvLine) return
        cctvLine = text
        if (tripId == null) renderLive()
    }

    private fun drawCctv() {
        com.rfsentinel.app.alpr.CctvStore.onChanged = { if (!isFinishing && !isDestroyed) drawCctvSoon() }
        val map = binding.map
        val on = com.rfsentinel.app.util.Prefs.showCctv(this) && tripId == null
        if (!on || map.zoomLevelDouble < cctvMinZoom) {
            if (cctv.points.isNotEmpty() || cctvCones.cameras.isNotEmpty()) {
                cctv.points = emptyList(); cctvCones.cameras = emptyList(); map.invalidate()
            }
            setCctvLine(if (!on) null else com.rfsentinel.app.alpr.CctvStore.progress?.let { "CCTV: downloading around you, $it" }
                ?: "CCTV: zoom in to see the cameras")
            return
        }
        map.removeCallbacks(downloadCctv)
        map.postDelayed(downloadCctv, 1_200)
        val private = com.rfsentinel.app.util.Prefs.cctvPrivate(this)
        val box = map.boundingBox.increaseByScale(1.5f)
        val center = map.mapCenter
        val inView = com.rfsentinel.app.alpr.CctvStore.cameras.filter {
            (private || !it.isPrivate) && it.lat in box.latSouth..box.latNorth && it.lon in box.lonWest..box.lonEast
        }.let { all ->
            // Zoomed out over a city: the ones nearest the centre of the screen.
            if (all.size <= 3000) all
            else all.sortedBy { (it.lat - center.latitude).let { d -> d * d } + (it.lon - center.longitude).let { d -> d * d } }.take(3000)
        }
        val dp = resources.displayMetrics.density
        val publicIcon = MapIcons.cctvIcon(dp, false); val privateIcon = MapIcons.cctvIcon(dp, true)
        cctvCones.cameras = if (map.zoomLevelDouble >= cctvConeZoom) inView else emptyList()
        cctv.points = inView.map { PointsOverlay.Point(it.lat, it.lon, it, icon = if (it.isPrivate) privateIcon else publicIcon) }
        map.invalidate()
        val store = com.rfsentinel.app.alpr.CctvStore
        val view = map.boundingBox
        val hiddenPrivate = if (private) 0 else store.cameras.count {
            it.isPrivate && it.lat in view.latSouth..view.latNorth && it.lon in view.lonWest..view.lonEast
        }
        val shown = inView.count { it.lat in view.latSouth..view.latNorth && it.lon in view.lonWest..view.lonEast }
        setCctvLine(when {
            store.progress != null -> "CCTV: downloading around you, ${store.progress}"
            store.isBusy -> "CCTV: downloading..."
            System.currentTimeMillis() - store.lastFailureAt < 60_000 -> "CCTV: map server busy - retrying"
            store.missingAreas > 0 && shown > 0 -> "CCTV: $shown in view - ${store.missingAreas} area(s) around you still missing (servers busy), retried next time"
            shown == 0 && store.needsDownload(view.latSouth, view.lonWest, view.latNorth, view.lonEast) -> "CCTV: downloading..."
            shown == 0 && hiddenPrivate > 0 -> "CCTV: only private ones here ($hiddenPrivate) - Settings > Known cameras > Include private"
            shown == 0 -> "CCTV: none mapped here"
            else -> "CCTV: $shown in view" + if (hiddenPrivate > 0) " (+$hiddenPrivate private hidden)" else ""
        })
    }

    /**
     * Fetches the CCTV cameras within the download radius around you (once, in the
     * background), and the area on screen when you look somewhere outside it.
     */
    private fun maybeDownloadCctv() {
        val map = binding.map
        if (!com.rfsentinel.app.util.Prefs.showCctv(this) || tripId != null) return
        val store = com.rfsentinel.app.alpr.CctvStore
        if (map.isAnimating || store.isBusy) { map.postDelayed(downloadCctv, 2_000); return }
        val here = myLocation.myLocation ?: ScanForegroundService.lastFix?.let { GeoPoint(it.latitude, it.longitude) }
        if (here != null) {
            val radius = com.rfsentinel.app.util.Prefs.cameraRadiusKm(this).toDouble()
            store.ensureAround(this, here.latitude, here.longitude, radius)
            if (store.isBusy) return
        }
        if (map.zoomLevelDouble < cctvMinZoom) return
        val box = map.boundingBox
        lifecycleScope.launch {
            com.rfsentinel.app.alpr.CctvStore.autoDownload(this@MapActivity, box.latSouth, box.lonWest, box.latNorth, box.lonEast)
            // Redraw either way: new cameras, or the status line (none mapped / server busy).
            drawCctv()
            // After a failure, try again once the back-off has passed.
            if (System.currentTimeMillis() - com.rfsentinel.app.alpr.CctvStore.lastFailureAt < 60_000)
                binding.map.postDelayed(downloadCctv, 61_000)
        }
    }

    private fun showCctv(c: com.rfsentinel.app.alpr.CctvCamera) {
        val text = buildString {
            append(when (c.zone) {
                "public" -> "Watches a public space (street, square, station...).\n"
                "outdoor" -> "A private camera that watches outside (an entrance, a car park...).\n"
                "indoor" -> "Inside a building or shop.\n"
                else -> "Whether it watches public space isn't mapped.\n"
            })
            c.area?.let { append("Watches: $it\n") }
            c.operator?.let { append("Operator: $it\n") }
            c.mount?.let { append("Mounted on: $it\n") }
            c.height?.let { append("Height: ${"%.0f".format(it)} m\n") }
            c.direction?.let { append("Faces: $it°" + (c.angle?.let { a -> ", $a° wide" } ?: "") + "\n") }
            append("OSM: ${c.osmId}\n\n")
            append("Mapped by OpenStreetMap volunteers. Most of these cameras are wired and give off no Bluetooth or WiFi " +
                "signal, so the scan can't detect them - this layer shows the ones that have been mapped. " +
                "The shaded area is an estimate of what it sees.\n\nMap data © OpenStreetMap contributors (ODbL).")
        }
        AlertDialog.Builder(this)
            .setTitle(com.rfsentinel.app.alpr.Cctv.title(c))
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Open in OpenStreetMap") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/${c.osmId}"))) }
            }
            .show()
    }

    private val autoDownloadCameras = Runnable { maybeAutoDownloadCameras() }

    /**
     * Fetches the cameras for the area on screen when it isn't cached yet, so
     * they're always on the map without a manual download. Runs once the map
     * has stopped moving for a moment.
     */
    private fun maybeAutoDownloadCameras() {
        val prefs = com.rfsentinel.app.util.Prefs
        if (!prefs.autoCameras(this) || !prefs.showKnownAlpr(this) || tripId != null) return
        // Still gliding to your position (or another target): wait until it stops.
        if (binding.map.isAnimating) { binding.map.postDelayed(autoDownloadCameras, 1_000); return }
        val box = binding.map.boundingBox
        val span = com.rfsentinel.app.alpr.AlprStore.MAX_SPAN_DEG
        if (box.latNorth - box.latSouth > span || box.lonEast - box.lonWest > span) return
        // The map starts at 0°,0° until it's moved to your position: don't fetch the ocean.
        if (kotlin.math.abs(box.centerLatitude) < 0.5 && kotlin.math.abs(box.centerLongitude) < 0.5) return
        // Another download (e.g. the car screen's) is running: look again shortly.
        if (com.rfsentinel.app.alpr.AlprStore.isBusy) { binding.map.postDelayed(autoDownloadCameras, 3_000); return }
        lifecycleScope.launch {
            val changed = com.rfsentinel.app.alpr.AlprStore.autoDownload(
                this@MapActivity, box.latSouth, box.lonWest, box.latNorth, box.lonEast
            )
            // Redrawing also re-checks the area now on screen, in case the map moved meanwhile.
            if (changed) { drawKnownAlpr(); refreshServiceLocation() }
        }
    }

    /** Draws the cameras in (and just around) the visible area; skipped when zoomed far out. */
    private fun drawKnownAlpr() {
        val map = binding.map
        map.removeCallbacks(autoDownloadCameras)
        map.postDelayed(autoDownloadCameras, 1_200)
        if (!com.rfsentinel.app.util.Prefs.showKnownAlpr(this) || map.zoomLevelDouble < 9.0) {
            knownAlpr.points = emptyList(); map.invalidate(); return
        }
        val box = map.boundingBox.increaseByScale(1.5f)
        val inView = com.rfsentinel.app.alpr.AlprStore.cameras.filter {
            it.lat in box.latSouth..box.latNorth && it.lon in box.lonWest..box.lonEast
        }.take(1500)
        val dp = resources.displayMetrics.density
        val icons = com.rfsentinel.app.alpr.KnownCamera.Kind.entries.associateWith { cameraIcon(dp, it) }
        knownAlpr.points = inView.map { cam ->
            // Cameras whose alerts you turned off stay on the map, faded.
            val ignored = com.rfsentinel.app.alpr.IgnoredCameras.contains(this@MapActivity, cam.osmId)
            PointsOverlay.Point(cam.lat, cam.lon, cam, icon = icons.getValue(cam.type), alpha = if (ignored) 90 else 255)
        }
        knownAlprDrawnFor = box
        map.invalidate()
    }

    private fun cameraIcon(dp: Float, kind: com.rfsentinel.app.alpr.KnownCamera.Kind) = MapIcons.cameraIcon(dp, kind)

    private fun showKnownCamera(cam: com.rfsentinel.app.alpr.KnownCamera) {
        val ignored = com.rfsentinel.app.alpr.IgnoredCameras.contains(this, cam.osmId)
        val text = buildString {
            if (ignored) append("Alerts for this camera are off.\n\n")
            append(when (cam.type) {
                com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR -> "A license-plate reader mapped in OpenStreetMap.\n\n"
                com.rfsentinel.app.alpr.KnownCamera.Kind.SPEED -> "A speed camera mapped in OpenStreetMap.\n\n"
                com.rfsentinel.app.alpr.KnownCamera.Kind.RED_LIGHT -> "A red-light camera mapped in OpenStreetMap.\n\n"
            })
            cam.brand?.let { append("Maker: $it\n") }
            cam.operator?.let { append("Operator: $it\n") }
            cam.direction?.let { append("Faces: $it°\n") }
            append("OSM: ${cam.osmId}\n\n")
            append("These cameras send their data over cellular or wire and have no Bluetooth/WiFi signature, so they can't be detected by radio - this map is the only warning. Mobile speed cameras move and are rarely mapped.\n\nMap data © OpenStreetMap contributors (ODbL).")
        }
        AlertDialog.Builder(this)
            .setTitle(cam.label)
            .setMessage(text)
            // Kept until turned back on here (or cleared in Settings).
            .setPositiveButton(if (ignored) "Alert again" else "Ignore alerts") { _, _ ->
                com.rfsentinel.app.alpr.IgnoredCameras.set(this, cam.osmId, !ignored)
                Toast.makeText(this, if (ignored) "You'll be warned about this camera again"
                    else "No more alerts for this camera (tap it again to undo)", Toast.LENGTH_SHORT).show()
                drawKnownAlpr()
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open in OpenStreetMap") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/${cam.osmId}"))) }
            }
            .show()
    }

    /** Towers with a map position (the Cells chip's count), refreshed with the layer. */
    private var towerCount = 0

    private fun drawTowers() {
        towerCount = com.rfsentinel.app.service.CellTowerStore.all(this).count { it.bestLat != null }
        // The Cells chip shows the towers (only them); All adds them when the map menu's switch is on;
        // every other chip hides them.
        val show = filter == DeviceFilter.CELLS ||
            (filter == DeviceFilter.ALL && com.rfsentinel.app.util.Prefs.showCellTowers(this))
        towers.points = if (tripId != null || !show) emptyList()
        else com.rfsentinel.app.service.CellTowerStore.all(this).filter { it.bestLat != null }.map {
            PointsOverlay.Point(it.bestLat!!, it.bestLon!!, it, icon = towerIcon)
        }
        binding.map.invalidate()
    }

    private fun showTower(t: com.rfsentinel.app.service.CellTowerStore.Tower) {
        val net = listOfNotNull(t.operator, "${t.mcc ?: "?"}-${t.mnc ?: "?"}").joinToString(" · ")
        AlertDialog.Builder(this)
            .setTitle(t.ratLabel)
            .setMessage("$net\nArea ${t.area ?: "?"} · Cell ${t.cellId}" +
                (t.bestDbm?.let { "\nStrongest signal $it dBm" } ?: "") +
                "\nSeen ${t.timesSeen}×, last ${dateFmt.format(java.util.Date(t.lastSeen))}" +
                "\n\nPlaced where your phone heard it strongest: an estimate, not the tower's exact position.")
            .setPositiveButton("All towers") { _, _ -> startActivity(Intent(this, CellTowersActivity::class.java)) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** One device, or a list to pick from when several were heard at the same spot. */
    private fun showPins(list: List<Pin>) {
        if (list.size == 1) { showPin(list[0]); return }
        val sorted = list.sortedByDescending { it.flagged }
        AlertDialog.Builder(this)
            .setTitle("${list.size} devices here")
            .setItems(sorted.map { (if (it.flagged) "⚠ " else "") + it.label }.toTypedArray()) { _, i -> showPin(sorted[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPin(p: Pin) {
        AlertDialog.Builder(this)
            .setTitle(p.label)
            .setMessage(p.details + "\n\nPosition = where your phone was when this device's signal was strongest (approximate).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this, p.mac) }
            .setNegativeButton("Close", null)
            .show()
    }

    private var stopObservingBulk: (() -> Unit)? = null

    /** Status text while a DeFlock download runs, or null when none does. */
    private fun bulkStatus(): String? = when (val s = com.rfsentinel.app.alpr.DeflockBulk.state) {
        is com.rfsentinel.app.alpr.DeflockBulk.State.Downloading ->
            "Downloading plate cameras · ${s.detail.ifEmpty { "${s.done}/${s.total} regions" }} · ${s.found} found"
        is com.rfsentinel.app.alpr.DeflockBulk.State.CheckingExtras ->
            "${s.cameras} plate cameras on the map · checking OpenStreetMap for more (up to 45 s)..."
        else -> null
    }

    /** Follows every DeFlock download (from here, Settings or the weekly refresh) with the progress bar. */
    private fun observeBulk() {
        stopObservingBulk = com.rfsentinel.app.alpr.DeflockBulk.observe { s ->
            if (isFinishing || isDestroyed) return@observe
            val bar = binding.downloadProgress
            when (s) {
                is com.rfsentinel.app.alpr.DeflockBulk.State.Downloading -> {
                    if (bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = false }
                    bar.visibility = View.VISIBLE
                    bar.setProgressCompat((s.fraction * 100).toInt(), true)
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.CheckingExtras -> {
                    // DeFlock's cameras are saved: show them now; the bar keeps moving for the extras.
                    com.rfsentinel.app.util.Prefs.setShowKnownAlpr(this, true)
                    drawKnownAlpr(); refreshServiceLocation()
                    bar.visibility = View.INVISIBLE; bar.isIndeterminate = true; bar.visibility = View.VISIBLE
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.Done -> {
                    bar.visibility = View.GONE
                    drawKnownAlpr(); refreshServiceLocation()
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.Failed -> {
                    bar.visibility = View.GONE
                }
                com.rfsentinel.app.alpr.DeflockBulk.State.Idle -> bar.visibility = View.GONE
            }
            bulkStatus()?.let { binding.statusText.text = it }
        }
    }

    // ---- Menu (trip mode) ---------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (tripId != null) {
            menu.add(0, 1, 0, "Export trace...")
            menu.add(0, 2, 1, "Rename")
            menu.add(0, 3, 2, "Delete")
        } else {
            menu.add(0, 13, 1, "Check Waze now")
            menu.add(0, 14, 2, "Waze status")
            // The map's options live in Settings > Map.
            menu.add(0, 12, 0, "Map settings").apply {
                setIcon(com.rfsentinel.app.R.drawable.ic_settings)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = tripId
        return when (item.itemId) {
            1 -> { id?.let { Exporter.showTripExport(this, it) }; true }
            2 -> { id?.let { rename(it) }; true }
            3 -> { id?.let { confirmDelete(it) }; true }
            12 -> {
                startActivity(Intent(this, com.rfsentinel.app.settings.SettingsActivity::class.java)
                    .putExtra(com.rfsentinel.app.settings.SettingsActivity.EXTRA_SECTION,
                        com.rfsentinel.app.settings.SettingsActivity.SECTION_MAP))
                true
            }
            13 -> { WazeUi.checkNow(this); true }
            14 -> { WazeUi.openStatus(this); true }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun rename(id: Long) {
        val input = EditText(this).apply { setText(trip?.name ?: ""); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Rename trace")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MapActivity).tripDao().rename(id, input.text.toString().trim().ifEmpty { "Trace" })
                    loadTrip()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete this trace?")
            .setMessage("The route and its device list are deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MapActivity).tripDao().delete(id)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** "1 device" / "3 devices". */
internal fun devicesLabel(n: Int) = if (n == 1) "1 device" else "$n devices"
