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
    /** The map keeps your position centred until you pan it yourself. */
    private var following = false

    private val trace = Polyline()
    private val pins by lazy { PointsOverlay<Pin>(resources.displayMetrics.density) { showPins(it) } }
    /** Live drones from Remote ID: aircraft, operator and the line between them. */
    private val drones = FolderOverlay()
    /** Aircraft from the ADS-B feeds (Settings > What to detect > Police / government aircraft). */
    private val aircraft = FolderOverlay()
    /** Plate-reader cameras mapped in OpenStreetMap (downloaded on request). */
    private val knownAlpr by lazy {
        PointsOverlay<com.rfsentinel.app.alpr.KnownCamera>(resources.displayMetrics.density) { showKnownCamera(it.first()) }
    }
    private var knownAlprDrawnFor: BoundingBox? = null
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
                    while (isActive) {
                        renderLive()
                        delay(3_000)
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
        map.controller.setZoom(16.0)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        trace.outlinePaint.apply {
            color = if (tripId == null) TRACE_COLOR else PAST_TRACE_COLOR
            strokeWidth = 7f * resources.displayMetrics.density / 2
        }
        map.overlays.add(knownAlpr)
        map.overlays.add(towers)
        map.overlays.add(trace)
        map.overlays.add(pins)
        map.overlays.add(drones)
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
                setFollow(true)
                myLocation.runOnFirstFix {
                    runOnUiThread { if (!centeredOnce) { centeredOnce = true; if (following) centerOnMe() } }
                }
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
        drawTowers()
    }

    override fun onPause() {
        if (tripId == null) ScanForegroundService.mapHidden(this)
        myLocation.disableMyLocation()
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopObservingBulk?.invoke()
        myLocation.disableMyLocation()
        binding.map.removeCallbacks(autoDownloadCameras)
        binding.map.removeCallbacks(redrawKnownAlpr)
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
            val n = if (f == DeviceFilter.CELLS) towerCount else placed.count { f.matches(it) }
            chip.text = if (f == DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
        }
    }

    /** Turns following on (re-centres now and keeps you centred) or off (after you pan). */
    private fun setFollow(on: Boolean) {
        following = on
        if (on) {
            myLocation.enableFollowLocation()
            centerOnMe()
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

    private fun centerOnMe() {
        val fix = myLocation.myLocation
            ?: TripRecorder.livePoints().lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
        if (fix != null) binding.map.controller.animateTo(fix)
        else Toast.makeText(this, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
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
        }
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

    /**
     * Aircraft from the latest ADS-B poll: police / government / circling ones in bold
     * purple with their agency and altitude, every other aircraft small and grey, each
     * pointing along its track. Tap one for its details.
     */
    private fun drawAircraft() {
        aircraft.items.clear()
        val list = com.rfsentinel.app.online.PoliceAircraft.latest
        if (list.isEmpty() || System.currentTimeMillis() - com.rfsentinel.app.online.PoliceAircraft.latestAt > 3 * 60_000L) return
        val dp = resources.displayMetrics.density
        // Ordinary traffic first, so flagged aircraft are drawn on top.
        for ((p, hit) in list.sortedBy { it.hit != null }) {
            val flagged = hit != null
            val at = GeoPoint(p.lat, p.lon)
            aircraft.add(Marker(binding.map).apply {
                position = at
                icon = android.graphics.drawable.BitmapDrawable(resources, MapIcons.planeIcon(dp, flagged))
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

    /**
     * DeFlock's snapshot - all of the US & Canada, or with [nearby] only within
     * the download radius set in Settings around you (or the map centre without a GPS fix). Progress in the status
     * bar; the map redraws when done.
     */
    private fun downloadAllPlateCameras(nearby: Boolean = false) {
        val bulk = com.rfsentinel.app.alpr.DeflockBulk
        val radius = com.rfsentinel.app.util.Prefs.cameraRadiusKm(this)
        announceWhere = if (nearby) "within ~$radius km" else "(US & Canada)"
        // One download at a time: tapping again while one runs just follows its progress (bar on the map).
        if (bulk.isRunning) return
        val around = if (!nearby) null else {
            val fix = myLocation.myLocation ?: binding.map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
            fix.latitude to fix.longitude
        }
        bulk.start(this, around, radius.toDouble())
    }

    /** Picks the radius (same setting as in Settings and the setup wizard), then downloads. */
    private fun askNearbyRadius() {
        val d = resources.displayMetrics.density
        val body = CameraRadiusSlider.create(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Download nearby cameras")
            .setView(body)
            .setPositiveButton("Download") { _, _ -> downloadAllPlateCameras(nearby = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Set when a download was started (or followed) from this screen: show its result. */
    private var announceWhere: String? = null
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
                    announceWhere?.let { where ->
                        Toast.makeText(this, "${s.cameras} plate cameras saved $where" +
                            if (s.extrasSkipped) " (OpenStreetMap was busy - a few extras will come next time)" else "",
                            Toast.LENGTH_LONG).show()
                    }
                    announceWhere = null
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.Failed -> {
                    bar.visibility = View.GONE
                    announceWhere?.let { Toast.makeText(this, s.reason, Toast.LENGTH_LONG).show() }
                    announceWhere = null
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
            menu.add(0, 4, 0, "Recorded traces")
            menu.add(0, 5, 1, "Download nearby cameras...")
            menu.add(0, 8, 1, "Download all US & CA")
            menu.add(0, 6, 2, "Show known cameras").apply {
                isCheckable = true; isChecked = com.rfsentinel.app.util.Prefs.showKnownAlpr(this@MapActivity)
            }
            menu.add(0, 7, 3, "Delete downloaded cameras")
            menu.add(0, 9, 4, "Show cell towers").apply {
                isCheckable = true; isChecked = com.rfsentinel.app.util.Prefs.showCellTowers(this@MapActivity)
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
            4 -> { startActivity(Intent(this, TripsActivity::class.java)); true }
            5 -> { askNearbyRadius(); true }
            6 -> {
                val show = !com.rfsentinel.app.util.Prefs.showKnownAlpr(this)
                com.rfsentinel.app.util.Prefs.setShowKnownAlpr(this, show)
                item.isChecked = show
                drawKnownAlpr(); true
            }
            7 -> {
                // Stop a running download first, or it would put cameras back afterwards.
                com.rfsentinel.app.alpr.DeflockBulk.cancel()
                com.rfsentinel.app.alpr.AlprStore.clear(this)
                drawKnownAlpr(); refreshServiceLocation()
                Toast.makeText(this, "Downloaded cameras deleted", Toast.LENGTH_SHORT).show(); true
            }
            8 -> { downloadAllPlateCameras(); true }
            9 -> {
                val show = !com.rfsentinel.app.util.Prefs.showCellTowers(this)
                com.rfsentinel.app.util.Prefs.setShowCellTowers(this, show)
                item.isChecked = show
                drawTowers(); true
            }
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
