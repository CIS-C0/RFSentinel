package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The map of a scan, on the chosen [MapEngine] - OpenFreeMap or Mapbox ([RfMap] draws it). Live mode
 * shows your position, the trace being recorded and everything detected around you; trip mode
 * shows a saved trace. Devices are drawn where YOUR phone was when their signal was strongest -
 * an approximation - unless pinpointed.
 *
 * The map button turns it: north up -> heading up -> 3D driving view (a real 3D camera that
 * follows your heading, your arrow low on the screen, closer in town and further ahead at speed).
 *
 * Shown full screen by [MapActivity] (a saved trace, a place to look at, a notification) and
 * embedded in the main screen's Map view, where the main screen's filter chips drive it and
 * its menu items are in the main screen's menu.
 *
 * Network use: the map (OpenFreeMap, or Mapbox when chosen in Settings), cached by the map engine.
 */
class MapFragment : Fragment() {

    companion object {
        /** Heading up turns the map only above this speed (~5 km/h): stopped, GPS bearing is noise. */
        const val HEADING_MIN_SPEED_MS = 1.4f
        const val EXTRA_TRIP_ID = "trip_id"
        /** Open the map centred here (e.g. a cell tower), without following your position. */
        const val EXTRA_CENTER_LAT = "center_lat"
        const val EXTRA_CENTER_LON = "center_lon"
        /** Turn the cell tower layer on. */
        const val EXTRA_SHOW_TOWERS = "show_towers"
        /**
         * An aircraft's ICAO address (hex): the map opens on where it is now when the ADS-B feed still
         * has it, else on [EXTRA_CENTER_LAT] / [EXTRA_CENTER_LON] (its last known position), with its details.
         */
        const val EXTRA_AIRCRAFT = "aircraft"
        /** A Waze report's id: the map opens on it ([EXTRA_CENTER_LAT] / [EXTRA_CENTER_LON]) with its details while it's current. */
        const val EXTRA_WAZE = "waze"
        /** Inside the main screen (its chips drive the filter; the menu is a button on the map). */
        const val ARG_EMBEDDED = "embedded"
        /** A failed camera download stays in the status line this long (the retry runs after a minute). */
        private const val CAMERA_FAIL_SHOWN_MS = 90_000L
        private const val GPS_MOVING_MS = 1_000L
        private const val GPS_PARKED_MS = 5_000L
        /** [MapStart]'s levels are the classic raster map's: the vector map's are this much lower. */
        private const val ZOOM_SHIFT = MapStart.VECTOR_SHIFT
        /** 3D: your arrow this far down the map (navigation apps: about 70 %), so you see more road ahead. */
        internal const val ARROW_HEIGHT = 0.72

        fun create(args: Bundle) = MapFragment().apply { arguments = args }

        /**
         * 3D camera zoom from speed: close street level in town, wider on fast roads so the next
         * kilometre stays on screen (vector zoom levels: 17 up to 30 km/h, 15.4 from 110 km/h).
         */
        internal fun zoomFor(kmh: Float, base: Double = Prefs.MAP_ZOOM_WAZE): Double = when {
            kmh <= 30f -> base
            kmh >= 110f -> base - 1.6
            else -> base - (kmh - 30f) / 80f * 1.6
        }

        /** The flat views (north up / heading up) sit this much wider than the 3D view at the same default. */
        internal const val FLAT_ZOOM_OFFSET = 0.5

        /** 3D tilt from speed: 50° in town (junctions readable), up to 60° on fast roads (further down the road). */
        internal fun tiltFor(kmh: Float, base: Double = Prefs.MAP_TILT_DEFAULT): Double = when {
            kmh <= 30f -> base
            kmh >= 110f -> (base + 10.0).coerceAtMost(MAX_TILT)
            else -> (base + (kmh - 30f) / 80f * 10.0).coerceAtMost(MAX_TILT)
        }

        /** The most the map engines tilt (MapLibre's limit). */
        internal const val MAX_TILT = 60.0

        /** Top padding (px) that puts the camera's focus - your arrow - [ARROW_HEIGHT] of the way down a [height] px map. */
        internal fun topPaddingFor(height: Int): Double = height * (2 * ARROW_HEIGHT - 1)
    }

    private var _binding: ActivityMapBinding? = null
    private val binding get() = _binding!!
    private val act get() = requireActivity() as androidx.appcompat.app.AppCompatActivity
    private val args get() = arguments ?: Bundle.EMPTY
    private val embedded get() = args.getBoolean(ARG_EMBEDDED, false)
    /**
     * Still on screen: delayed redraws / downloads and GPS callbacks can arrive after the map was
     * closed or the main screen's Map view turned off, and must then do nothing.
     */
    private val alive get() = _binding != null && isAdded && activity != null
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
    /** 3D: you pinched - your zoom until the follow button. */
    private var userZoom = false

    private var engine: MapEngine? = null
    private var rf: RfMap? = null
    private val map: MapEngine? get() = rf?.map
    private var lastFix: Location? = null
    private var lastBearing: Float? = null
    private var smoothKmh = -1f
    private var stopObservingBulk: (() -> Unit)? = null
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tripId = args.getLong(EXTRA_TRIP_ID, -1).takeIf { it > 0 }
        if (!embedded) act.title = if (tripId == null) "Scan map" else "Recorded trace"

        // Full screen, edge-to-edge: keep the controls above the navigation bar.
        if (!embedded) ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controls.updatePadding(bottom = bars.bottom + (6 * resources.displayMetrics.density).toInt())
            binding.statusBox.translationY = bars.top.toFloat()
            insets
        }

        if (args.getBoolean(EXTRA_SHOW_TOWERS, false)) Prefs.setShowCellTowers(act, true)
        setupFilterChips()
        styleFollowButton()
        // Outlined "Traces" in the theme highlight (the stock dark teal was unreadable on dark themes).
        ChipStyle.accent(act).first.let { accent ->
            binding.tracesButton.setTextColor(accent)
            binding.tracesButton.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        }
        binding.centerButton.setOnClickListener { if (tripId == null) setFollow(true) else centerOnMe() }
        binding.tracesButton.setOnClickListener { startActivity(Intent(act, TripsActivity::class.java)) }
        setupThemeButton()
        setupSettingsButton()
        setupOrientationButton()
        setupMenu()
        // The scale and the map credit sit just under the status banner, or at the very top when there is none.
        binding.statusBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeScaleBar() }

        createEngine(savedInstanceState, null)
        binding.map.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyMode(animate = false)
        }

        if (tripId == null) {
            observeBulk()
            binding.recordButton.setOnClickListener { toggleRecording() }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // A new or cleared Waze report is drawn at once, not at the next 3 s tick.
                    val stopWaze = com.rfsentinel.app.online.WazePolice.addListener {
                        activity?.runOnUiThread { if (alive && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) renderLive() }
                    }
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
        }
        if (embedded) compactControls()
    }

    /** The main screen's filter chip, for the embedded map. */
    fun setFilter(f: DeviceFilter) {
        if (f == filter || _binding == null) { filter = f; rf?.filter = f; return }
        filter = f
        rf?.filter = f
        if (tripId != null) loadTrip() else renderLive()
        rf?.maybeRedrawStatic(force = true)
    }

    // ---- The map -----------------------------------------------------------------------------------

    /** The map engine chosen in Settings > Map; [restore] is where the one it replaces was looking. */
    private fun createEngine(state: Bundle?, restore: MapEngine.Camera?) {
        val e = MapEngine.create(act)
        engine = e
        binding.map.addView(e.view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        binding.osmCredit.text = e.credit
        e.onCreate(state)
        e.whenReady { if (alive && engine === e) onMapReady(e, restore) }
    }

    /** Back from Settings with the other map provider chosen: the map changes in place, looking at the same spot. */
    private fun swapEngineIfChanged() {
        val old = engine ?: return
        if (old.provider == MapEngine.chosen(act)) return
        val cam = old.camera.takeIf { rf != null }
        rf = null
        old.onPause(); old.onStop(); old.onDestroy()
        binding.map.removeView(old.view)
        createEngine(null, cam)
        engine?.apply { onStart(); onResume() }
    }

    private fun onMapReady(m: MapEngine, restore: MapEngine.Camera?) {
        val r = RfMap(act, m).also { rf = it }
        r.filter = filter
        r.tripMode = tripId != null
        r.onStaticChanged = { if (alive) { renderLive(); refreshServiceLocation() } }
        m.setMinZoom(2.0)
        // Two fingers in 3D: a pinch is your zoom now (until the follow button).
        m.onUserGesture = { if (following) userZoom = true }
        m.onFollowDismissed = { if (alive && following) { following = false; styleFollowButton() } }
        m.onCameraMove = { updateScale() }
        m.onIdle = { r.safe("static") { r.maybeRedrawStatic(); updateScale() } }
        m.onClick = { x, y -> r.safe("tap") { r.onTap(x, y, myPosition()) } }
        if (restore != null) { m.jumpTo(restore.lat, restore.lon, restore.zoom); centeredOnce = true }
        else placeCameraAtStart(m)
        placeScaleBar()
        loadStyle()
    }

    private fun darkTiles(): Boolean = Prefs.mapDark(act)
        ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

    private fun loadStyle() {
        val r = rf ?: return
        val dark = darkTiles()
        styleThemeButton()
        binding.osmCredit.setBackgroundColor(if (dark) 0x99000000.toInt() else 0x99FFFFFF.toInt())
        binding.osmCredit.setTextColor(if (dark) 0xFFDDDDDD.toInt() else 0xFF333333.toInt())
        r.loadStyle(dark) {
            if (!alive) return@loadStyle
            r.safe("location") { startLocation() }
            if (tripId != null) loadTrip() else renderLive()
            r.safe("static") { r.maybeRedrawStatic(force = true) }
        }
    }

    /**
     * Where the map first looks: a place it was opened on (an aircraft, a Waze report, a tower), else
     * where you are or were (never open sea at street zoom), else a world view.
     */
    private fun placeCameraAtStart(m: MapEngine) {
        val planeHex = args.getString(EXTRA_AIRCRAFT)
        val wazeId = args.getString(EXTRA_WAZE)
        val focusPlane = planeHex?.let { com.rfsentinel.app.online.PoliceAircraft.current(it) }
        val focusWaze = wazeId?.let { com.rfsentinel.app.online.WazePolice.current(it) }
        val focus = focusPlane?.let { it.plane.lat to it.plane.lon }
            ?: focusWaze?.let { it.report.lat to it.report.lon }
            ?: if (args.containsKey(EXTRA_CENTER_LAT)) args.getDouble(EXTRA_CENTER_LAT) to args.getDouble(EXTRA_CENTER_LON) else null
        if (focus != null) {
            // Aircraft: room around it to see where it's going.
            m.jumpTo(focus.first, focus.second, (if (planeHex != null) 12.0 else 15.0) - ZOOM_SHIFT)
            centeredOnce = true
            binding.map.post {
                if (!alive) return@post
                when {
                    focusPlane != null -> rf?.showAircraft(focusPlane, myPosition())
                    focusWaze != null -> MapDialogs.showWaze(act, focusWaze)
                    planeHex != null -> Toast.makeText(act, "No longer in range - this is where it was last seen", Toast.LENGTH_LONG).show()
                    wazeId != null -> Toast.makeText(act, "This Waze report has expired - this is where it was", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (tripId != null) return // framed on the trace once it's loaded
        val knownFix = knownFix()
        val start = MapStart.camera(
            listOf(knownFix?.let { MapStart.Fix(it.latitude, it.longitude, it.time) }),
            Prefs.lastMapCenter(act), MapStart.savedZoom(Prefs.mapDefaultZoom(act) - FLAT_ZOOM_OFFSET), System.currentTimeMillis()
        )
        startedOnWorld = start.zoom <= MapStart.WORLD_ZOOM - ZOOM_SHIFT
        m.jumpTo(start.lat, start.lon, start.zoom)
        if (start.fresh) { centeredOnce = true; lastFix = knownFix }
        // Live map: keep your position centred until you pan away.
        if (Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION)) following = true
        styleFollowButton()
    }

    /** Your position (lat, lon) for distances in the details, when known. */
    private fun myPosition(): Pair<Double, Double>? =
        (lastFix ?: ScanForegroundService.lastFix)?.let { it.latitude to it.longitude }

    // ---- Light / dark map -----------------------------------------------------------------------------

    private fun setupThemeButton() {
        binding.themeButton.setOnClickListener {
            val dark = !darkTiles()
            Prefs.setMapDark(act, dark)
            loadStyle()
            Toast.makeText(act, if (dark) "Dark map" else "Light map", Toast.LENGTH_SHORT).show()
        }
        styleThemeButton()
    }

    /** The gear on the map: Settings > Map (the map provider, what the map shows). */
    private fun setupSettingsButton() {
        binding.mapSettingsButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        binding.mapSettingsButton.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        binding.mapSettingsButton.setOnClickListener { openMapSettings() }
    }

    private fun openMapSettings() {
        startActivity(Intent(act, com.rfsentinel.app.settings.SettingsActivity::class.java)
            .putExtra(com.rfsentinel.app.settings.SettingsActivity.EXTRA_SECTION,
                com.rfsentinel.app.settings.SettingsActivity.SECTION_MAP))
    }

    private fun styleThemeButton() {
        val dark = darkTiles()
        binding.themeButton.setImageResource(if (dark) com.rfsentinel.app.R.drawable.ic_light_mode else com.rfsentinel.app.R.drawable.ic_dark_mode)
        binding.themeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        binding.themeButton.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        binding.themeButton.contentDescription = if (dark) "Dark map (tap for light)" else "Light map (tap for dark)"
    }

    // ---- Scale and credit ----------------------------------------------------------------------------

    private fun placeScaleBar() {
        val b = _binding ?: return
        val gap = (8 * resources.displayMetrics.density).toInt()
        val box = b.statusBox
        val y = (if (box.height > 0) box.bottom + box.translationY.toInt() + gap else box.top + box.translationY.toInt()).coerceAtLeast(gap)
        b.scaleBar.translationY = y.toFloat()
        b.osmCredit.translationY = (y + b.scaleBar.height).toFloat()
        // Mapbox's logo and info button just under the credit line.
        engine?.placeBranding(y + b.scaleBar.height + b.osmCredit.height + gap / 2)
    }

    private fun updateScale() {
        val m = map ?: return
        val b = _binding ?: return
        // Tilted, the scale differs across the screen: no single scale to show.
        if (m.camera.tilt > 5) { b.scaleBar.visibility = View.INVISIBLE; return }
        b.scaleBar.visibility = View.VISIBLE
        // Measured on the ground between two points 100 px apart across the middle of the map.
        val w = b.map.width; val h = b.map.height
        if (w < 200 || h == 0) return
        val a = m.toLatLon(w / 2f - 50f, h / 2f) ?: return
        val z = m.toLatLon(w / 2f + 50f, h / 2f) ?: return
        b.scaleBar.setMetersPerPixel(DeviceRegistry.metersBetween(a.first, a.second, z.first, z.second) / 100.0)
    }

    // ---- Embedded in the main screen ------------------------------------------------------------------

    /**
     * Inside the main screen the map shares the space, so Record / Traces become small
     * round buttons on the map instead of a bar under it; everything else is the same.
     */
    private fun compactControls() {
        binding.controls.visibility = View.GONE
        binding.compactControls.visibility = View.VISIBLE
        val (accent, onAccent) = ChipStyle.accent(act)
        binding.tracesFab.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        binding.tracesFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        binding.tracesFab.setOnClickListener { startActivity(Intent(act, TripsActivity::class.java)) }
        binding.recordFab.setOnClickListener { toggleRecording() }
        // The map on its own screen, as before (Record trace / Traces bar, its own filter chips).
        binding.fullscreenButton.visibility = View.VISIBLE
        binding.fullscreenButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        binding.fullscreenButton.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        binding.fullscreenButton.setOnClickListener { startActivity(Intent(act, MapActivity::class.java)) }
        // A short pane has no room for five buttons stacked on the right: map settings, light / dark and
        // full screen join Record and Traces along the bottom; the compass and follow stay on the right.
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (v in listOf(binding.mapSettingsButton, binding.themeButton, binding.fullscreenButton)) {
            (v.parent as? ViewGroup)?.removeView(v)
            binding.compactControls.addView(v, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
        }
        binding.recordFab.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        binding.recordFab.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
        binding.statusText.textSize = 12f
    }

    // ---- Your position --------------------------------------------------------------------------------

    private fun startLocation() {
        val m = map ?: return
        if (!m.startPuck()) return
        (lastFix ?: knownFix())?.let { m.pushFix(steady(it)) }
        applyMode(animate = false)
    }

    /**
     * A fix as the map gets it: stopped or crawling, the GPS course is noise - keep the last real
     * heading so a turning map doesn't swing around at a red light.
     */
    private fun steady(fix: Location): Location {
        val moving = fix.hasSpeed() && fix.speed >= HEADING_MIN_SPEED_MS && fix.hasBearing()
        if (moving) { lastBearing = fix.bearing; return fix }
        return Location(fix).apply { lastBearing?.let { bearing = it } }
    }

    private val gpsListener = LocationListener { fix -> rf?.safe("fix") { onFix(fix) } ?: onFix(fix) }

    private fun onFix(fix: Location) {
        if (!alive) return
        lastFix = fix
        val m = map?.takeIf { it.puckOn } ?: return
        m.pushFix(steady(fix))
        adaptGpsRate(fix)
        val kmh = if (fix.hasSpeed()) fix.speed * 3.6f else 0f
        val before = smoothKmh
        smoothKmh = if (smoothKmh < 0f) kmh else smoothKmh * 0.7f + kmh * 0.3f
        if (!centeredOnce && following) {
            centeredOnce = true
            if (startedOnWorld) { startedOnWorld = false; m.zoomTo(Prefs.mapDefaultZoom(act) - FLAT_ZOOM_OFFSET) }
            applyMode(animate = false)
        }
        // 3D: re-aim only when the speed changed enough to matter (no constant zoom pumping).
        val base = Prefs.mapDefaultZoom(act)
        val drives = is3d() || (tripId == null && Prefs.mapHeadingUp(act))
        if (drives && (before < 0f || kotlin.math.abs(zoomFor(smoothKmh, base) - zoomFor(before.coerceAtLeast(0f), base)) > 0.1)) applyMode(animate = true)
    }

    // ---- Battery: the map's own GPS ---------------------------------------------------------------------

    /**
     * The position arrow's GPS: a fix a second (2 m) while moving - smooth enough for driving -
     * and every 5 s (10 m) after a minute parked. Runs only while the map is on screen (off with
     * the screen, the app in the background, or the main screen's Map view turned off).
     */
    private var listening = false
    private var gpsParked = false
    private var stillSince = 0L

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (listening || !Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = act.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER))
            LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        val ms = if (gpsParked) GPS_PARKED_MS else GPS_MOVING_MS
        val meters = if (gpsParked) 10f else 2f
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val req = android.location.LocationRequest.Builder(ms)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateDistanceMeters(meters)
                    .build()
                lm.requestLocationUpdates(provider, req, act.mainExecutor, gpsListener)
            } else lm.requestLocationUpdates(provider, ms, meters, gpsListener, Looper.getMainLooper())
            listening = true
        }
    }

    private fun stopGps() {
        if (!listening) return
        runCatching { (act.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gpsListener) }
        listening = false
    }

    private fun adaptGpsRate(fix: Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        val moving = fix.hasSpeed() && fix.speed >= 1.0f
        if (moving) stillSince = 0L else if (stillSince == 0L) stillSince = now
        val parked = !moving && stillSince != 0L && now - stillSince > 60_000L
        if (parked == gpsParked) return
        gpsParked = parked
        // Re-register with the new rate; the arrow stays where it is meanwhile.
        if (listening) { stopGps(); startGps() }
    }

    // ---- North up / heading up / 3D --------------------------------------------------------------------

    private fun is3d() = tripId == null && Prefs.map3d(act) && Prefs.mapHeadingUp(act)

    /**
     * Points the camera for the mode: following you north up, turning with your heading, or the 3D
     * driving view (tilt and zoom from your speed, your arrow low on the screen); not following, it
     * stays where you left it.
     */
    private fun applyMode(animate: Boolean) {
        val m = map?.takeIf { it.puckOn } ?: return
        val ms = if (animate) 1_000L else 0L
        if (!following || tripId != null) {
            m.follow(MapEngine.Follow.NONE, null, 0.0, null, 0)
            return
        }
        val h = binding.map.height
        val base = Prefs.mapDefaultZoom(act)
        val kmh = smoothKmh.coerceAtLeast(0f)
        if (is3d()) {
            m.follow(MapEngine.Follow.HEADING, if (h > 0) topPaddingFor(h) else null, tiltFor(kmh, Prefs.map3dTilt(act)), if (userZoom) null else zoomFor(kmh, base), ms)
        } else if (Prefs.mapHeadingUp(act)) {
            // Turning with you: pulls back at speed like the 3D view; a pinch is yours until the follow button.
            m.follow(MapEngine.Follow.HEADING, 0.0, 0.0, if (userZoom) null else zoomFor(kmh, base) - FLAT_ZOOM_OFFSET, ms)
        } else {
            m.follow(MapEngine.Follow.NORTH, 0.0, 0.0, if (userZoom) null else base - FLAT_ZOOM_OFFSET, ms)
        }
    }

    /** The map button: north up -> heading up -> 3D driving view -> north up. */
    private fun setupOrientationButton() {
        val btn = binding.orientationButton
        if (tripId != null) { btn.visibility = View.GONE; return }
        btn.setOnClickListener {
            val prefs = Prefs
            val heading = prefs.mapHeadingUp(act); val tilt = prefs.map3d(act)
            when {
                !heading -> { prefs.setMapHeadingUp(act, true); prefs.setMap3d(act, false) }
                !tilt -> prefs.setMap3d(act, true)
                else -> { prefs.setMapHeadingUp(act, false); prefs.setMap3d(act, false) }
            }
            if (prefs.mapHeadingUp(act)) {
                // Turning with you (and 3D) only makes sense around you: follow again.
                setFollow(true, quiet = true)
                Toast.makeText(act, if (prefs.map3d(act)) "3D driving view" else "Map turns with your direction of travel", Toast.LENGTH_SHORT).show()
            } else {
                // Back to a flat, north-up map, wherever it was looking.
                map?.easeTo(bearing = 0.0, tilt = 0.0, flat = true, ms = 600)
                applyMode(animate = true)
                Toast.makeText(act, "Map fixed with north up", Toast.LENGTH_SHORT).show()
            }
            styleOrientationButton()
        }
        styleOrientationButton()
    }

    private fun styleOrientationButton() {
        val heading = Prefs.mapHeadingUp(act)
        val tilt = is3d()
        val (accent, onAccent) = ChipStyle.accent(act)
        binding.orientationButton.setImageResource(when {
            tilt -> com.rfsentinel.app.R.drawable.ic_view_3d
            heading -> com.rfsentinel.app.R.drawable.ic_heading_up
            else -> com.rfsentinel.app.R.drawable.ic_north_up
        })
        binding.orientationButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (heading) accent else 0xFFFFFFFF.toInt())
        binding.orientationButton.imageTintList = android.content.res.ColorStateList.valueOf(if (heading) onAccent else 0xFF0B5C63.toInt())
        binding.orientationButton.contentDescription = when {
            tilt -> "Map: 3D driving view (tap for north up)"
            heading -> "Map: heading up (tap for the 3D driving view)"
            else -> "Map: north up (tap to turn with your direction)"
        }
    }

    /** Turns following on (re-centres now and keeps you centred) or off (after you pan). */
    private fun setFollow(on: Boolean, quiet: Boolean = false) {
        following = on
        if (on) {
            userZoom = false
            if (map?.puckOn != true && !quiet) Toast.makeText(act, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
            else if (lastFix == null && ScanForegroundService.lastFix == null && !quiet) Toast.makeText(act, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
        }
        applyMode(animate = true)
        styleFollowButton()
    }

    /** Filled with the theme highlight while following, plain otherwise. */
    private fun styleFollowButton() {
        val on = following
        val (accent, onAccent) = ChipStyle.accent(act)
        val plain = 0xFFFFFFFF.toInt()
        binding.centerButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) accent else plain)
        binding.centerButton.imageTintList = android.content.res.ColorStateList.valueOf(if (on) onAccent else 0xFF0B5C63.toInt())
        binding.centerButton.contentDescription = if (on) "Following your position" else "Follow my position"
    }

    /** A saved trace: glides to where you are (the map doesn't follow you there). */
    private fun centerOnMe() {
        val p = myPosition() ?: TripRecorder.livePoints().lastOrNull()?.let { it.lat to it.lon }
        if (p != null) map?.easeTo(p.first, p.second, ms = 600)
        else Toast.makeText(act, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
    }

    /** The best position known before this screen's own GPS answers: the running scan's fix, else the system's last known. */
    private fun knownFix(): Location? = listOfNotNull(ScanForegroundService.lastFix, systemLastKnown()).maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private fun systemLastKnown(): Location? {
        if (!Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(act, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = act.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    /** Remembers where the live map is looking, so it can open there next time. */
    private fun saveView() {
        if (tripId != null || args.containsKey(EXTRA_CENTER_LAT) || args.containsKey(EXTRA_AIRCRAFT) || args.containsKey(EXTRA_WAZE)) return
        val cam = map?.camera ?: return
        if (kotlin.math.abs(cam.lat) < 1e-4 && kotlin.math.abs(cam.lon) < 1e-4) return // it never looked anywhere
        if (startedOnWorld) return
        Prefs.saveMapView(act, cam.lat, cam.lon, MapStart.savedZoom(cam.zoom))
    }

    // ---- Lifecycle (the map engine needs every step) ------------------------------------------------

    override fun onStart() { super.onStart(); engine?.onStart() }

    override fun onResume() {
        super.onResume()
        engine?.onResume()
        swapEngineIfChanged()
        startGps()
        // Precise GPS while the map is on screen, so devices land where they were heard.
        if (tripId == null) ScanForegroundService.mapShown(act)
        com.rfsentinel.app.alpr.CctvStore.onChanged = { activity?.runOnUiThread { if (alive) rf?.safe("static") { rf?.maybeRedrawStatic(force = true); renderLive() } } }
        // Back from Settings > Map: show what's switched on now (and nothing that was switched off).
        if (Prefs.showCctv(act)) viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rfsentinel.app.alpr.CctvStore.load(act) }
            rf?.safe("static") { rf?.maybeRedrawStatic(force = true) }
        } else rf?.safe("static") { rf?.maybeRedrawStatic(force = true) }
        if (rf != null && rf?.isDark != darkTiles()) loadStyle()
        styleOrientationButton()
        applyMode(animate = false)
    }

    override fun onPause() {
        saveView()
        stopGps()
        if (tripId == null) ScanForegroundService.mapHidden(act)
        engine?.onPause()
        super.onPause()
    }

    override fun onStop() { engine?.onStop(); super.onStop() }

    override fun onLowMemory() { super.onLowMemory(); engine?.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        engine?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        stopObservingBulk?.invoke()
        com.rfsentinel.app.alpr.CctvStore.onChanged = null
        stopGps()
        rf = null
        engine?.onDestroy()
        engine = null
        _binding = null
        super.onDestroyView()
    }

    // ---- Filter chips ---------------------------------------------------------------------------------

    private fun setupFilterChips() {
        if (embedded) {
            binding.mapFilterScroll.visibility = View.GONE
            filter = DeviceFilter.ALL
            return
        }
        val options = if (tripId == null) DeviceFilter.entries.filter { it != DeviceFilter.RADIO } else listOf(DeviceFilter.ALL, DeviceFilter.FLAGGED)
        filter = DeviceFilter.parse(Prefs.mapFilter(act)).takeIf { it in options } ?: DeviceFilter.ALL
        val group = binding.mapFilterChips
        for (f in options) {
            val chip = com.google.android.material.chip.Chip(act, null, com.google.android.material.R.attr.chipStyle).apply {
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
            rf?.filter = picked
            Prefs.setMapFilter(act, picked.name)
            if (tripId != null) loadTrip() else renderLive()
            rf?.safe("static") { rf?.maybeRedrawStatic(force = true) }
        }
    }

    /** Chip labels with how many devices each filter would show ("Trackers 2"). */
    private fun updateFilterCounts(devices: List<DeviceRegistry.Snapshot>) {
        val placed = devices.filter { it.place != null }
        for ((f, chip) in filterChips) {
            val n = when (f) {
                DeviceFilter.CELLS -> rf?.towerCount ?: 0
                DeviceFilter.WAZE -> com.rfsentinel.app.online.WazePolice.latest.size
                else -> placed.count { f.matches(it) }
            }
            chip.text = if (f == DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
        }
    }

    // ---- Live mode ----------------------------------------------------------------

    private fun renderLive() {
        if (!alive || tripId != null) return
        val recording = TripRecorder.isRecording
        binding.recordButton.text = if (recording) "Stop recording" else "Record trace"
        val (accent, onAccent) = ChipStyle.accent(act)
        binding.recordButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (recording) 0xFFB3261E.toInt() else accent)
        val recordFg = if (recording) 0xFFFFFFFF.toInt() else onAccent
        binding.recordButton.setTextColor(recordFg)
        binding.recordButton.iconTint = android.content.res.ColorStateList.valueOf(recordFg)
        // Embedded: the round record button, red with a stop square while recording.
        binding.recordFab.backgroundTintList = android.content.res.ColorStateList.valueOf(if (recording) 0xFFB3261E.toInt() else accent)
        binding.recordFab.imageTintList = android.content.res.ColorStateList.valueOf(recordFg)
        binding.recordFab.setImageResource(if (recording) com.rfsentinel.app.R.drawable.ic_car_stop else com.rfsentinel.app.R.drawable.ic_record)
        binding.recordFab.contentDescription = if (recording) "Stop recording" else "Record trace"

        // Includes devices from a scan that just stopped: the registry drops them after 3 minutes.
        val devices = DeviceRegistry.snapshot()
        var drawn = emptyList<MapPin>()
        rf?.let { r -> r.safe("live") { drawn = r.drawLive(devices) } }
        updateFilterCounts(devices)

        val positioned = devices.count { it.place != null }
        val status = when {
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
            // On the main screen the device count is already in the status line above the map.
            embedded -> null
            else -> "Scanning · ${drawn.size} on map" + if (filter != DeviceFilter.ALL) " (${filter.label} only)" else ""
        }
        val text = listOfNotNull(status, rf?.cctvLine).joinToString("\n")
        binding.statusText.text = text
        binding.statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        binding.statusText.background.mutate().setTint(if (recording) 0xFFB3261E.toInt() else 0xCC0B5C63.toInt())
    }

    private fun toggleRecording() {
        if (TripRecorder.isRecording) {
            viewLifecycleOwner.lifecycleScope.launch {
                val id = TripRecorder.activeTripId
                TripRecorder.stop(act)
                refreshServiceLocation()
                val saved = id?.let { AppDatabase.getInstance(act).tripDao().get(it) }
                if (saved != null) {
                    AlertDialog.Builder(act)
                        .setTitle("Trace saved")
                        .setMessage(String.format(Locale.US, "%s\n%.2f km · %s (%d flagged)",
                            saved.name, saved.distanceM / 1000, devicesLabel(saved.deviceCount), saved.flaggedCount))
                        .setPositiveButton("View") { _, _ -> openTrip(saved.id) }
                        .setNeutralButton("Export") { _, _ -> Exporter.showTripExport(act, saved.id) }
                        .setNegativeButton("Close", null)
                        .show()
                } else {
                    Toast.makeText(act, "Nothing recorded (no GPS fix)", Toast.LENGTH_LONG).show()
                }
                renderLive()
            }
            return
        }
        if (Permissions.missingRequired(act).isNotEmpty()) {
            Toast.makeText(act, "Grant location and nearby-devices first (Start scanning on the main screen)", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (!ScanForegroundService.isRunning) {
                runCatching { ScanForegroundService.start(act) }
                delay(600)
            }
            TripRecorder.start(act)
            refreshServiceLocation()
            Toast.makeText(act, "Recording your trace - keeps going with the screen off", Toast.LENGTH_SHORT).show()
            renderLive()
        }
    }

    /** Tells the running service to switch location updates to trace mode (or back). */
    private fun refreshServiceLocation() {
        // Only a running scanner needs refreshing; sending it otherwise would start scanning.
        if (!ScanForegroundService.isRunning) return
        runCatching {
            act.startService(Intent(act, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_REFRESH_LOCATION))
        }
    }

    private fun openTrip(id: Long) {
        startActivity(Intent(act, MapActivity::class.java).putExtra(EXTRA_TRIP_ID, id))
    }

    // ---- Trip mode ------------------------------------------------------------------

    private fun loadTrip() {
        val id = tripId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(act).tripDao()
            val t = dao.get(id) ?: run { act.finish(); return@launch }
            trip = t
            val points = dao.points(id).map { it.lat to it.lon }
            val devices = dao.devices(id)
            if (!alive) return@launch
            val r = rf
            r?.safe("trip") {
                r.drawTrace(points, RfMap.PAST_TRACE_COLOR)
                r.drawPins(devices.filter { it.lat != null && (it.flagged || filter == DeviceFilter.ALL) }.map { it.toPin() })
            }
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            binding.statusText.text = String.format(
                Locale.US, "%s\n%s · %d:%02d · %.2f km · %s (%d flagged)",
                t.name, dateFmt.format(Date(t.startTime)), mins / 60, mins % 60, t.distanceM / 1000,
                devicesLabel(t.deviceCount), t.flaggedCount
            )
            binding.statusText.background.mutate().setTint(0xCC1F5FBF.toInt())
            val all = points + devices.filter { it.lat != null }.map { it.lat!! to it.lon!! }
            val m = map
            if (all.isNotEmpty() && m != null && !centeredOnce) {
                centeredOnce = true
                binding.map.post {
                    if (!alive) return@post
                    r?.safe("frame") {
                        if (all.size == 1) m.jumpTo(all[0].first, all[0].second, MapStart.DEFAULT_ZOOM - ZOOM_SHIFT)
                        else m.fit(all, (48 * resources.displayMetrics.density).toInt())
                    }
                }
            }
        }
    }

    private fun TripDeviceEntity.toPin(): MapPin {
        val cat = Category.parse(category)
        return MapPin(
            mac, lat!!, lon!!, label, flagged,
            DeviceColors.forMatch(act, if (flagged) cat else null, confidence),
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

    // ---- DeFlock downloads ----------------------------------------------------------------

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
            if (!alive) return@observe
            val bar = binding.downloadProgress
            when (s) {
                is com.rfsentinel.app.alpr.DeflockBulk.State.Downloading -> {
                    if (bar.isIndeterminate) { bar.visibility = View.INVISIBLE; bar.isIndeterminate = false }
                    bar.visibility = View.VISIBLE
                    bar.setProgressCompat((s.fraction * 100).toInt(), true)
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.CheckingExtras -> {
                    // DeFlock's cameras are saved: show them now; the bar keeps moving for the extras.
                    Prefs.setShowKnownAlpr(act, true)
                    rf?.safe("static") { rf?.maybeRedrawStatic(force = true) }; refreshServiceLocation()
                    bar.visibility = View.INVISIBLE; bar.isIndeterminate = true; bar.visibility = View.VISIBLE
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.Done -> {
                    bar.visibility = View.GONE
                    rf?.safe("static") { rf?.maybeRedrawStatic(force = true) }; refreshServiceLocation()
                }
                is com.rfsentinel.app.alpr.DeflockBulk.State.Failed -> {
                    bar.visibility = View.GONE
                }
                com.rfsentinel.app.alpr.DeflockBulk.State.Idle -> bar.visibility = View.GONE
            }
            bulkStatus()?.let { binding.statusText.text = it; binding.statusText.visibility = View.VISIBLE }
        }
    }

    // ---- Menu ---------------------------------------------------------------------------

    /** Full screen: the action bar's menu. Embedded, the main screen's menu has the same items (Waze, Settings). */
    private fun setupMenu() {
        if (embedded) return
        act.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = addMenuItems(menu)
            override fun onMenuItemSelected(menuItem: MenuItem) = onMenuItem(menuItem.itemId)
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun addMenuItems(menu: Menu) {
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
    }

    private fun onMenuItem(itemId: Int): Boolean {
        val id = tripId
        return when (itemId) {
            1 -> { id?.let { Exporter.showTripExport(act, it) }; true }
            2 -> { id?.let { rename(it) }; true }
            3 -> { id?.let { confirmDelete(it) }; true }
            12 -> { openMapSettings(); true }
            13 -> { WazeUi.checkNow(act); true }
            14 -> { WazeUi.openStatus(act); true }
            else -> false
        }
    }

    private fun rename(id: Long) {
        val input = EditText(act).apply { setText(trip?.name ?: ""); setSelection(text.length) }
        AlertDialog.Builder(act)
            .setTitle("Rename trace")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppDatabase.getInstance(act).tripDao().rename(id, input.text.toString().trim().ifEmpty { "Trace" })
                    loadTrip()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(act)
            .setTitle("Delete this trace?")
            .setMessage("The route and its device list are deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppDatabase.getInstance(act).tripDao().delete(id)
                    act.finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** "1 device" / "3 devices". */
internal fun devicesLabel(n: Int) = if (n == 1) "1 device" else "$n devices"
