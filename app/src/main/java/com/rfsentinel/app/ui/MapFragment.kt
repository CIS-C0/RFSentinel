package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenStreetMap view of a scan. Live mode shows your position, the trace being
 * recorded and nearby devices; trip mode shows a saved trace. Devices are drawn
 * where YOUR phone was when their signal was strongest - an approximation.
 *
 * Shown full screen by [MapActivity] (a saved trace, a place to look at, a notification) and
 * embedded in the main screen's Map view, where the main screen's filter chips drive it and
 * its menu items are in the main screen's menu.
 *
 * Network use: map tiles only (OpenStreetMap), cached in the app's cache dir.
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
        /** Inside the main screen (its chips drive the filter; the menu is a button on the map). */
        const val ARG_EMBEDDED = "embedded"
        /** A failed camera download stays in the status line this long (the retry runs after a minute). */
        private const val CAMERA_FAIL_SHOWN_MS = 90_000L
        private const val TRACE_COLOR = 0xFFE0622D.toInt()
        private const val PAST_TRACE_COLOR = 0xFF1F5FBF.toInt()
        private const val DRONE_COLOR = 0xFF1F5FBF.toInt()
        private const val GPS_MOVING_MS = 1_000L
        private const val GPS_PARKED_MS = 5_000L
        /**
         * 3D view: the tilt, and the camera distance in map-area heights (proportional, so the
         * perspective looks the same in a small pane and full screen; the size is worked out in tiltFit).
         */
        /** Upper limit for the tilted view's height, in map-area heights. */
        private const val TILT_MAX_HEIGHT = 6f
        private const val TILT_DEGREES = 50f
        private const val TILT_CAMERA = 14.3f

        fun create(args: Bundle) = MapFragment().apply { arguments = args }
    }

    /** One device to draw, from the live registry or a saved trip. */
    private data class Pin(
        val mac: String, val lat: Double, val lon: Double, val label: String, val flagged: Boolean,
        val color: Int, val details: String
    )

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
    /** CCTV cameras mapped in OpenStreetMap (Settings > Known cameras, off by default): icons over their view cones. */
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
    private val scaleBar by lazy { ScaleBarOverlay(binding.map).apply { setAlignBottom(false); setScaleBarOffset(20, 180) } }

    private fun placeScaleBar() {
        val b = _binding ?: return
        val gap = (8 * resources.displayMetrics.density).toInt()
        val box = b.statusBox
        val y = if (box.height > 0) box.bottom + box.translationY.toInt() + gap else box.top + box.translationY.toInt()
        scaleBar.setScaleBarOffset(20, y.coerceAtLeast(gap))
        // The 3D view's map credit takes the scale bar's place.
        b.osmCredit.translationY = y.coerceAtLeast(gap).toFloat()
        b.map.invalidate()
    }
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // osmdroid: identify ourselves to the tile server (OSM tile policy) and keep
        // the tile cache inside the app's private cache.
        MapIcons.configureOsm(requireContext())
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

        if (args.getBoolean(EXTRA_SHOW_TOWERS, false)) com.rfsentinel.app.util.Prefs.setShowCellTowers(act, true)
        setupMap()
        binding.map.post { if (_binding != null) drawKnownAlpr() }
        // Every device heard around you by default (same colours as the list and radar).
        setupFilterChips()
        styleFollowButton()
        // Outlined "Traces" in the theme highlight (the stock dark teal was unreadable on dark themes).
        ChipStyle.accent(act).first.let { accent ->
            binding.tracesButton.setTextColor(accent)
            binding.tracesButton.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        }
        binding.centerButton.setOnClickListener {
            if (tripId == null) setFollow(true) else centerOnMe()
        }
        binding.tracesButton.setOnClickListener { startActivity(Intent(act, TripsActivity::class.java)) }
        setupThemeButton()
        setupMenu()

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
            loadTrip()
        }
        if (embedded) compactControls()
    }

    /** The main screen's filter chip, for the embedded map. */
    fun setFilter(f: DeviceFilter) {
        if (f == filter || _binding == null) { filter = f; return }
        filter = f
        if (tripId != null) loadTrip() else renderLive()
        drawTowers()
    }

    // ---- Light / dark map -----------------------------------------------------------------------------

    private fun darkTiles(): Boolean = com.rfsentinel.app.util.Prefs.mapDark(act)
        ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

    /** Dark map = the OpenStreetMap tiles with their colours inverted (as in dark mode before). */
    private fun applyTiles() {
        binding.map.overlayManager.tilesOverlay.setColorFilter(if (darkTiles()) TilesOverlay.INVERT_COLORS else null)
        styleThemeButton()
        binding.map.invalidate()
    }

    private fun setupThemeButton() {
        binding.themeButton.setOnClickListener {
            val dark = !darkTiles()
            com.rfsentinel.app.util.Prefs.setMapDark(act, dark)
            applyTiles()
            Toast.makeText(act, if (dark) "Dark map" else "Light map", Toast.LENGTH_SHORT).show()
        }
        applyTiles()
    }

    private fun styleThemeButton() {
        val dark = darkTiles()
        binding.themeButton.setImageResource(if (dark) com.rfsentinel.app.R.drawable.ic_light_mode else com.rfsentinel.app.R.drawable.ic_dark_mode)
        binding.themeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        binding.themeButton.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        binding.themeButton.contentDescription = if (dark) "Dark map (tap for light)" else "Light map (tap for dark)"
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
        // A short pane has no room for four buttons stacked on the right: light / dark and full
        // screen join Record and Traces along the bottom; the compass and follow stay on the right.
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (v in listOf(binding.themeButton, binding.fullscreenButton)) {
            (v.parent as? ViewGroup)?.removeView(v)
            binding.compactControls.addView(v, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
        }
        binding.recordFab.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        binding.recordFab.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
        binding.statusText.textSize = 12f
    }

    private fun setupMap() {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Pinch to zoom; no + / - buttons.
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Open where you are, not on (0, 0): the live map used to sit on open sea until the first GPS fix.
        val liveHere = tripId == null && !args.containsKey(EXTRA_CENTER_LAT)
        val knownFix = if (liveHere) knownFix() else null
        val start = if (liveHere) MapStart.pick(
            listOf(knownFix?.let { MapStart.Fix(it.latitude, it.longitude, it.time) }),
            com.rfsentinel.app.util.Prefs.lastMapCenter(act), com.rfsentinel.app.util.Prefs.mapZoom(act), System.currentTimeMillis()
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

        myLocation = object : MyLocationNewOverlay(gps, map) {
            override fun onLocationChanged(location: android.location.Location?, source: org.osmdroid.views.overlay.mylocation.IMyLocationProvider?) {
                super.onLocationChanged(arrowFix(location), source)
                if (location != null) activity?.runOnUiThread { if (alive) { turnToHeading(location); adaptGpsRate(location); autoZoomFor(location) } }
            }
            init { defaultPerson = mPersonBitmap; defaultArrow = mDirectionArrowBitmap }
        }
        if (Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            myLocation.enableMyLocation()
            if (args.containsKey(EXTRA_CENTER_LAT)) {
                // Opened on a place (a cell tower): look there instead of following you.
                map.controller.setZoom(15.0)
                map.controller.setCenter(GeoPoint(args.getDouble(EXTRA_CENTER_LAT), args.getDouble(EXTRA_CENTER_LON)))
                centeredOnce = true
            } else if (tripId == null) {
                // Live map: keep your position centred from the first fix until you pan away.
                setFollow(true, quiet = true)
                myLocation.runOnFirstFix {
                    activity?.runOnUiThread {
                        if (alive && !centeredOnce) {
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
        // Under the device dots, drones, Waze reports and aircraft (above the trace and the camera layers), so it never hides what was detected.
        map.overlays.add(map.overlays.indexOf(pins).coerceAtLeast(0), myLocation)
        setupOrientationButton()
        map.overlays.add(scaleBar)
        setup3dPinch(map)
        // The scale sits just under the status banner, or at the very top when there is none.
        binding.statusBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeScaleBar() }
        map.overlays.add(CopyrightOverlay(act)) // "© OpenStreetMap contributors" (required attribution)
        binding.root.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, or, ob ->
            if (r - l != or - ol || btm - t != ob - ot) binding.root.post { apply3d() }
        }
    }

    // ---- Battery: the map's own GPS ---------------------------------------------------------------------

    /**
     * The position arrow's GPS: a fix a second (2 m) while moving - smooth enough for driving -
     * and every 5 s (10 m) after a minute parked, instead of osmdroid's default of every fix.
     * Runs only while the map is on screen (off with the screen, the app in the background,
     * or the main screen's Map view turned off).
     */
    private val gps by lazy {
        GpsMyLocationProvider(act).apply { locationUpdateMinTime = GPS_MOVING_MS; locationUpdateMinDistance = 2f }
    }
    private var gpsParked = false
    private var stillSince = 0L

    private fun adaptGpsRate(fix: android.location.Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        val moving = fix.hasSpeed() && fix.speed >= 1.0f
        if (moving) stillSince = 0L else if (stillSince == 0L) stillSince = now
        val parked = !moving && stillSince != 0L && now - stillSince > 60_000L
        if (parked == gpsParked) return
        gpsParked = parked
        gps.locationUpdateMinTime = if (parked) GPS_PARKED_MS else GPS_MOVING_MS
        gps.locationUpdateMinDistance = if (parked) 10f else 2f
        // Re-register with the new rate; the arrow stays where it is meanwhile.
        if (myLocation.isMyLocationEnabled) { gps.stopLocationProvider(); gps.startLocationProvider(myLocation) }
    }

    // ---- 3D driving view ---------------------------------------------------------------------------------

    private fun is3d() = tripId == null && com.rfsentinel.app.util.Prefs.map3d(act) && com.rfsentinel.app.util.Prefs.mapHeadingUp(act)

    /**
     * Like a car navigation app: heading up, the map tilted back in perspective so you see further
     * down the road, and your position low on the screen. The map view is made larger than the
     * screen and tilted (osmdroid has no 3D of its own); taps still land on the right dot.
     */
    private var pinching = false
    private var pinchSpan = 0f
    private var pinchZoom = 0.0

    /**
     * 3D pinch zoom. The map view is tilted, so Android hands it finger positions on the tilted
     * plane: near the top a tiny finger move is a long way on the map, and osmdroid's pinch zoom
     * jumps. In 3D the pinch is measured on the screen instead and zooms around your position
     * (like a car navigation app); one-finger drags and taps still go to the map.
     */
    /** The distance between two fingers on the screen itself (not on the tilted map). */
    @androidx.annotation.RequiresApi(29)
    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getRawX(0) - e.getRawX(1); val dy = e.getRawY(0) - e.getRawY(1)
        return kotlin.math.hypot(dx, dy)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setup3dPinch(map: org.osmdroid.views.MapView) {
        map.setOnTouchListener { v, e ->
            if (rewriting) return@setOnTouchListener false
            if (android.os.Build.VERSION.SDK_INT < 29 || !is3d()) { pinching = false; return@setOnTouchListener false }
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (!pinching && e.pointerCount == 2) {
                    // Take the gesture over: the map forgets the drag it had started.
                    MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.dispatchTouchEvent(it); it.recycle() }
                    pinching = true
                    pinchSpan = span(e)
                    pinchZoom = map.zoomLevelDouble
                }
                MotionEvent.ACTION_MOVE -> if (pinching) {
                    val now = span(e)
                    if (now > 10f && pinchSpan > 10f) {
                        val z = (pinchZoom + kotlin.math.ln((now / pinchSpan).toDouble()) / kotlin.math.ln(2.0))
                            .coerceIn(map.minZoomLevel, map.maxZoomLevel)
                        map.controller.setZoom(z)
                        autoZoom = false // your zoom now, until the follow button
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pinching) {
                    pinching = false
                    drawKnownAlprSoon()
                    return@setOnTouchListener true
                }
            }
            if (pinching) return@setOnTouchListener true
            // One finger (tap, drag, long-press): hand the map the point really under the finger.
            if (e.pointerCount == 1) {
                val at = screenToMap(map, e.rawX, e.rawY) ?: return@setOnTouchListener false
                val fixed = MotionEvent.obtain(e).apply { setLocation(at[0], at[1]) }
                rewriting = true
                try { v.dispatchTouchEvent(fixed) } finally { rewriting = false; fixed.recycle() }
                return@setOnTouchListener true
            }
            false
        }
    }

    private var rewriting = false

    /**
     * Where a point of the screen falls on the tilted map view: the view's own 3D matrix,
     * inverted. (Android's own conversion of touches through a tilted view is off, so taps
     * missed the icons drawn under the finger.)
     */
    private fun screenToMap(map: org.osmdroid.views.MapView, rawX: Float, rawY: Float): FloatArray? {
        val parent = map.parent as? View ?: return null
        val inverse = android.graphics.Matrix()
        if (!map.matrix.invert(inverse)) return null
        val origin = IntArray(2).also { parent.getLocationOnScreen(it) }
        val pts = floatArrayOf(rawX - origin[0] - map.left, rawY - origin[1] - map.top)
        inverse.mapPoints(pts)
        return pts
    }

    /**
     * The tilted map view's size for a [w] x [h] map area, and the centre offset that puts your
     * position a quarter of the way up: asks the view's own 3D matrix (with the tilt and camera
     * already set) where its points land, so nothing depends on screen size or density.
     * Returns width, height, centre offset (px).
     */
    private fun tiltFit(m: org.osmdroid.views.MapView, w: Int, h: Int): IntArray {
        val pt = FloatArray(2)
        // Pivot on the bottom-centre; x measured from the centre line.
        fun project(height: Float, x: Float, y: Float): FloatArray {
            m.pivotX = 0f; m.pivotY = height
            pt[0] = x; pt[1] = y
            m.matrix.mapPoints(pt)
            return pt
        }
        // Where the top edge lands on screen (from the map area's top) for a view this tall.
        fun topOnScreen(height: Float) = (h - height) + project(height, 0f, 0f)[1]
        // Smallest height whose top edge reaches the top (the edge only rises as the view grows).
        var lo = h.toFloat(); var hi = h * TILT_MAX_HEIGHT
        if (topOnScreen(hi) <= 0f) repeat(24) { val mid = (lo + hi) / 2; if (topOnScreen(mid) <= 0f) hi = mid else lo = mid }
        val height = hi + h * 0.01f
        // How much the far edge shrinks: the view must be this much wider than the area.
        val shrink = project(height, 1000f, 0f)[0] / 1000f
        val width = (w / shrink.coerceIn(0.2f, 1f)) * 1.02f
        // Your position: the point of the view that lands 3/4 of the way down the map area.
        val target = h * 0.75f
        var a = 0f; var bb = height
        repeat(24) { val mid = (a + bb) / 2; if ((h - height) + project(height, 0f, mid)[1] < target) a = mid else bb = mid }
        return intArrayOf(width.toInt(), height.toInt(), (a - height / 2f).toInt())
    }

    // ---- 3D: navigation zoom and arrow -------------------------------------------------------------

    /**
     * What the position arrow is drawn from. Heading up / 3D: below walking-to-crawling speed the
     * GPS direction is noise, and the map (rightly) stops turning - so the arrow drops it too and
     * stays pointing up, the way you were last going, instead of swinging sideways. Moving, the
     * arrow follows the GPS course, which is also what turns the map: it points straight up the road.
     */
    private fun arrowFix(fix: android.location.Location?): android.location.Location? {
        if (fix == null || !fix.hasBearing() || !alive || tripId != null || !com.rfsentinel.app.util.Prefs.mapHeadingUp(act)) return fix
        if (fix.hasSpeed() && fix.speed >= HEADING_MIN_SPEED_MS) return fix
        return android.location.Location(fix).apply {
            @Suppress("DEPRECATION") removeBearing()
        }
    }

    /** Street-level zoom from your speed while following you in 3D; off once you pinch, back with the follow button. */
    private var autoZoom = true
    private var smoothKmh = -1f
    private var defaultPerson: android.graphics.Bitmap? = null
    private var defaultArrow: android.graphics.Bitmap? = null
    private val navArrow by lazy { navArrowIcon(resources.displayMetrics.density) }

    /** In 3D the zoom levels are shifted (screen-density tiles): the flat map's level minus this. */
    private fun tileShift() = kotlin.math.ln(resources.displayMetrics.density.toDouble()) / kotlin.math.ln(2.0)

    /**
     * Like a car navigation app: close street level in town, gradually wider on faster roads
     * (flat-map zoom 18 up to 30 km/h, down to 16 from 110 km/h), eased in so it doesn't pump.
     */
    private fun autoZoomFor(fix: android.location.Location, now: Boolean = false) {
        val m = _binding?.map ?: return
        if (!is3d() || !autoZoom || !following || pinching) return
        val kmh = if (fix.hasSpeed()) fix.speed * 3.6f else 0f
        smoothKmh = if (smoothKmh < 0f || now) kmh else smoothKmh * 0.7f + kmh * 0.3f
        val flat = when {
            smoothKmh <= 30f -> 18.0
            smoothKmh >= 110f -> 16.0
            else -> 18.0 - (smoothKmh - 30f) / 80f * 2.0
        }
        val target = (flat - tileShift()).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        if (kotlin.math.abs(target - m.zoomLevelDouble) < 0.15) return
        if (now) m.controller.setZoom(target)
        else if (!m.isAnimating) m.controller.zoomTo(target, 900L)
    }

    /** Heading up and 3D: a blue navigation arrow for your position (pointing the way you go); north up: osmdroid's own icons. */
    private fun styleMyLocation(on: Boolean) {
        if (!::myLocation.isInitialized) return
        if (on) {
            myLocation.setDirectionIcon(navArrow); myLocation.setPersonIcon(navArrow)
            myLocation.setDirectionAnchor(0.5f, 0.5f); myLocation.setPersonAnchor(0.5f, 0.5f)
        } else {
            defaultArrow?.let { myLocation.setDirectionIcon(it); myLocation.setDirectionAnchor(0.5f, 0.5f) }
            // osmdroid's person is anchored near its feet.
            defaultPerson?.let { myLocation.setPersonIcon(it); myLocation.setPersonAnchor(0.5f, 0.8125f) }
        }
    }

    private fun navArrowIcon(dp: Float): android.graphics.Bitmap {
        val size = (46 * dp).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        // Soft white disc behind, like the navigation apps' puck.
        c.drawCircle(r, r, r - 1 * dp, paint.apply { color = 0xE6FFFFFF.toInt(); style = android.graphics.Paint.Style.FILL; setShadowLayer(3 * dp, 0f, 1 * dp, 0x55000000) })
        paint.clearShadowLayer()
        val path = android.graphics.Path().apply {
            moveTo(r, 7 * dp); lineTo(size - 11 * dp, size - 10 * dp)
            lineTo(r, size - 16 * dp); lineTo(11 * dp, size - 10 * dp); close()
        }
        c.drawPath(path, paint.apply { color = 0xFF1A73E8.toInt(); style = android.graphics.Paint.Style.FILL })
        c.drawPath(path, paint.apply { color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeJoin = android.graphics.Paint.Join.ROUND })
        return bmp
    }

    private fun apply3d() {
        val b = _binding ?: return
        val w = b.root.width; val h = b.root.height
        if (w == 0 || h == 0) return
        val m = b.map
        val on = is3d()
        // The tilted view is several screens' worth of map: drawn with screen-density tiles it needs
        // ~7x fewer of them (smooth zooming, readable street names). The zoom level is shifted so
        // switching in or out of 3D keeps the same scale.
        if (m.isTilesScaledToDpi != on) {
            val shift = kotlin.math.ln(resources.displayMetrics.density.toDouble()) / kotlin.math.ln(2.0)
            val zoom = m.zoomLevelDouble + if (on) -shift else shift
            m.isTilesScaledToDpi = on
            m.controller.setZoom(zoom.coerceIn(m.minZoomLevel, m.maxZoomLevel))
        }
        m.cameraDistance = TILT_CAMERA * h
        m.rotationX = if (on) TILT_DEGREES else 0f
        val lp = m.layoutParams as android.widget.FrameLayout.LayoutParams
        var mw = w; var mh = h; var offsetY = 0
        if (on) {
            // Exact size, from the tilt itself: just tall enough that the far edge reaches the top of
            // the map area, just wide enough there to cover its width (no black corners at any size).
            val fit = tiltFit(m, w, h)
            mw = fit[0]; mh = fit[1]; offsetY = fit[2]
        }
        val wantW = if (on) mw else android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        val wantH = if (on) mh else android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        if (lp.width != wantW || lp.height != wantH) {
            lp.width = wantW; lp.height = wantH
            lp.gravity = if (on) android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
            m.layoutParams = lp
        }
        m.pivotX = mw / 2f
        m.pivotY = mh.toFloat()
        m.setMapCenterOffset(0, offsetY)
        scaleBar.isEnabled = !on
        b.osmCredit.visibility = if (on) View.VISIBLE else View.GONE
        // The navigation arrow whenever the map turns with you (heading up and 3D).
        styleMyLocation(tripId == null && com.rfsentinel.app.util.Prefs.mapHeadingUp(act))
        if (on) lastFixForHeading?.let { autoZoomFor(it, now = true) }
        m.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        applyTiles()
        // The location overlay's GPS runs only while the map is on screen.
        if (Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            myLocation.enableMyLocation()
            if (following) myLocation.enableFollowLocation()
        }
        // Precise GPS while the map is on screen, so devices land where they were heard.
        if (tripId == null) ScanForegroundService.mapShown(act)
        // Back from Settings > Map: show what's switched on now (and nothing that was switched off).
        drawTowers()
        drawKnownAlpr()
        if (com.rfsentinel.app.util.Prefs.showCctv(act)) viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rfsentinel.app.alpr.CctvStore.load(act) }
            drawCctv()
            maybeDownloadCctv()
        } else drawCctv()
    }

    override fun onPause() {
        saveView()
        if (tripId == null) ScanForegroundService.mapHidden(act)
        myLocation.disableMyLocation()
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        stopObservingBulk?.invoke()
        com.rfsentinel.app.alpr.CctvStore.onChanged = null
        myLocation.disableMyLocation()
        binding.map.removeCallbacks(autoDownloadCameras)
        binding.map.removeCallbacks(redrawKnownAlpr)
        // These two were left pending: one firing after onDetach() below touches detached overlays and crashes.
        binding.map.removeCallbacks(redrawCctv)
        binding.map.removeCallbacks(downloadCctv)
        binding.map.onDetach()
        _binding = null
        super.onDestroyView()
    }

    private fun setupFilterChips() {
        if (embedded) {
            binding.mapFilterScroll.visibility = View.GONE
            filter = DeviceFilter.ALL
            return
        }
        val options = if (tripId == null) DeviceFilter.entries.filter { it != DeviceFilter.RADIO } else listOf(DeviceFilter.ALL, DeviceFilter.FLAGGED)
        filter = DeviceFilter.parse(com.rfsentinel.app.util.Prefs.mapFilter(act)).takeIf { it in options } ?: DeviceFilter.ALL
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
            com.rfsentinel.app.util.Prefs.setMapFilter(act, picked.name)
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

    // ---- North up / heading up ------------------------------------------------------------------

    /** The map button: north up (fixed) or heading up (turns with your direction of travel). */
    private fun setupOrientationButton() {
        val btn = binding.orientationButton
        if (tripId != null) { btn.visibility = View.GONE; return }
        btn.setOnClickListener {
            // North up -> heading up -> 3D driving view -> north up.
            val prefs = com.rfsentinel.app.util.Prefs
            val heading = prefs.mapHeadingUp(act); val tilt = prefs.map3d(act)
            when {
                !heading -> { prefs.setMapHeadingUp(act, true); prefs.setMap3d(act, false) }
                !tilt -> prefs.setMap3d(act, true)
                else -> { prefs.setMapHeadingUp(act, false); prefs.setMap3d(act, false) }
            }
            if (prefs.mapHeadingUp(act)) {
                lastFixForHeading?.let { turnToHeading(it, force = true) }
                // 3D only makes sense around you: follow again.
                if (prefs.map3d(act)) setFollow(true, quiet = true)
                Toast.makeText(act, if (prefs.map3d(act)) "3D driving view" else "Map turns with your direction of travel", Toast.LENGTH_SHORT).show()
            } else {
                binding.map.mapOrientation = 0f
                Toast.makeText(act, "Map fixed with north up", Toast.LENGTH_SHORT).show()
            }
            apply3d()
            styleOrientationButton()
        }
        styleOrientationButton()
        if (!com.rfsentinel.app.util.Prefs.mapHeadingUp(act)) binding.map.mapOrientation = 0f
    }

    private fun styleOrientationButton() {
        val heading = com.rfsentinel.app.util.Prefs.mapHeadingUp(act)
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

    private var lastFixForHeading: android.location.Location? = null

    /**
     * Heading up: the direction of travel points to the top of the screen. Only from the GPS
     * course while moving (above ~5 km/h) - stopped, the bearing is noise, so the map keeps its angle.
     */
    private fun turnToHeading(fix: android.location.Location, force: Boolean = false) {
        lastFixForHeading = fix
        if (tripId != null || !com.rfsentinel.app.util.Prefs.mapHeadingUp(act)) return
        if (!fix.hasBearing() || (!force && (!fix.hasSpeed() || fix.speed < HEADING_MIN_SPEED_MS))) return
        val target = -fix.bearing
        // Small wobbles at speed aren't worth a redraw.
        val diff = ((target - binding.map.mapOrientation + 540f) % 360f) - 180f
        if (!force && kotlin.math.abs(diff) < 3f) return
        binding.map.mapOrientation = (target + 360f) % 360f
    }

    /** Turns following on (re-centres now and keeps you centred) or off (after you pan). */
    private fun setFollow(on: Boolean, quiet: Boolean = false) {
        following = on
        if (on) {
            myLocation.enableFollowLocation()
            centerOnMe(quiet)
            autoZoom = true
            lastFixForHeading?.let { autoZoomFor(it, now = true) }
        } else {
            myLocation.disableFollowLocation()
        }
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

    private fun centerOnMe(quiet: Boolean = false) {
        val fix = myLocation.myLocation
            ?: TripRecorder.livePoints().lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
        if (fix != null) goTo(fix)
        else if (!quiet) Toast.makeText(act, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
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
        if (!Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(act, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = act.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        return runCatching {
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    /** Remembers where the live map is looking, so it can open there next time. */
    private fun saveView() {
        if (tripId != null || args.containsKey(EXTRA_CENTER_LAT)) return
        val c = binding.map.mapCenter
        if (kotlin.math.abs(c.latitude) < 1e-4 && kotlin.math.abs(c.longitude) < 1e-4) return // it never looked anywhere
        // In 3D the zoom level is shifted (bigger tiles, see apply3d): save the flat map's equivalent.
        val shift = if (binding.map.isTilesScaledToDpi) kotlin.math.ln(resources.displayMetrics.density.toDouble()) / kotlin.math.ln(2.0) else 0.0
        com.rfsentinel.app.util.Prefs.saveMapView(act, c.latitude, c.longitude, binding.map.zoomLevelDouble + shift)
    }

    // ---- Live mode ----------------------------------------------------------------

    private fun renderLive() {
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

        // Only the trace being recorded right now; a stopped one is under Traces.
        trace.setPoints(if (recording) TripRecorder.livePoints().map { GeoPoint(it.lat, it.lon) } else emptyList())

        // Includes devices from a scan that just stopped: the registry drops them after 3 minutes.
        val devices = DeviceRegistry.snapshot()
        val drawn = devices.mapNotNull { s ->
            val pos = s.bestPosition ?: return@mapNotNull null
            val flagged = DeviceColors.isFlagged(s)
            if (!filter.matches(s) || filter == DeviceFilter.RADIO) return@mapNotNull null
            val best = s.best
            Pin(
                s.mac, pos.lat, pos.lon,
                best?.label ?: s.name ?: s.deviceType,
                flagged,
                DeviceColors.forDevice(act, s),
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
        val text = listOfNotNull(status, cctvLine).joinToString("\n")
        binding.statusText.text = text
        binding.statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        binding.statusText.background.mutate().setTint(if (recording) 0xFFB3261E.toInt() else 0xCC0B5C63.toInt())
        binding.map.invalidate()
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
        AlertDialog.Builder(act)
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
        AlertDialog.Builder(act)
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
        AlertDialog.Builder(act)
            .setTitle("Drone (Remote ID)")
            .setMessage(text + "\n\nPositions are broadcast by the drone itself (ASTM F3411 Remote ID).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(act, s.mac) }
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

    private val redrawKnownAlpr = Runnable { if (alive) drawKnownAlpr() }

    private fun drawKnownAlprSoon() {
        binding.map.removeCallbacks(redrawKnownAlpr)
        binding.map.postDelayed(redrawKnownAlpr, 250)
        binding.map.removeCallbacks(redrawCctv)
        binding.map.postDelayed(redrawCctv, 250)
    }

    // ---- CCTV cameras (OpenStreetMap) ---------------------------------------------------------------

    private val redrawCctv = Runnable { if (alive) drawCctv() }
    private fun drawCctvSoon() {
        binding.map.removeCallbacks(redrawCctv)
        binding.map.postDelayed(redrawCctv, 300)
    }
    private val downloadCctv = Runnable { if (alive) maybeDownloadCctv() }

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
        com.rfsentinel.app.alpr.CctvStore.onChanged = { if (alive) drawCctvSoon() }
        val map = binding.map
        val on = com.rfsentinel.app.util.Prefs.showCctv(act) && tripId == null
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
        val private = com.rfsentinel.app.util.Prefs.cctvPrivate(act)
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
        if (!com.rfsentinel.app.util.Prefs.showCctv(act) || tripId != null) return
        val store = com.rfsentinel.app.alpr.CctvStore
        if (map.isAnimating || store.isBusy) { map.postDelayed(downloadCctv, 2_000); return }
        val here = myLocation.myLocation ?: ScanForegroundService.lastFix?.let { GeoPoint(it.latitude, it.longitude) }
        if (here != null) {
            val radius = com.rfsentinel.app.util.Prefs.cameraRadiusKm(act).toDouble()
            store.ensureAround(act, here.latitude, here.longitude, radius)
            if (store.isBusy) return
        }
        if (map.zoomLevelDouble < cctvMinZoom) return
        val box = map.boundingBox
        viewLifecycleOwner.lifecycleScope.launch {
            com.rfsentinel.app.alpr.CctvStore.autoDownload(act, box.latSouth, box.lonWest, box.latNorth, box.lonEast)
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
        AlertDialog.Builder(act)
            .setTitle(com.rfsentinel.app.alpr.Cctv.title(c))
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Open in OpenStreetMap") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/${c.osmId}"))) }
            }
            .show()
    }

    private val autoDownloadCameras = Runnable { if (alive) maybeAutoDownloadCameras() }

    /**
     * Fetches the cameras for the area on screen when it isn't cached yet, so
     * they're always on the map without a manual download. Runs once the map
     * has stopped moving for a moment.
     */
    private fun maybeAutoDownloadCameras() {
        val prefs = com.rfsentinel.app.util.Prefs
        if (!prefs.autoCameras(act) || !prefs.showKnownAlpr(act) || tripId != null) return
        // Still gliding to your position (or another target): wait until it stops.
        if (binding.map.isAnimating) { binding.map.postDelayed(autoDownloadCameras, 1_000); return }
        val box = binding.map.boundingBox
        val span = com.rfsentinel.app.alpr.AlprStore.MAX_SPAN_DEG
        if (box.latNorth - box.latSouth > span || box.lonEast - box.lonWest > span) return
        // The map starts at 0°,0° until it's moved to your position: don't fetch the ocean.
        if (kotlin.math.abs(box.centerLatitude) < 0.5 && kotlin.math.abs(box.centerLongitude) < 0.5) return
        // Another download (e.g. the car screen's) is running: look again shortly.
        if (com.rfsentinel.app.alpr.AlprStore.isBusy) { binding.map.postDelayed(autoDownloadCameras, 3_000); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = com.rfsentinel.app.alpr.AlprStore.autoDownload(
                act, box.latSouth, box.lonWest, box.latNorth, box.lonEast
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
        if (!com.rfsentinel.app.util.Prefs.showKnownAlpr(act) || map.zoomLevelDouble < 9.0) {
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
            val ignored = com.rfsentinel.app.alpr.IgnoredCameras.contains(act, cam.osmId)
            PointsOverlay.Point(cam.lat, cam.lon, cam, icon = icons.getValue(cam.type), alpha = if (ignored) 90 else 255)
        }
        knownAlprDrawnFor = box
        map.invalidate()
    }

    private fun cameraIcon(dp: Float, kind: com.rfsentinel.app.alpr.KnownCamera.Kind) = MapIcons.cameraIcon(dp, kind)

    private fun showKnownCamera(cam: com.rfsentinel.app.alpr.KnownCamera) {
        val ignored = com.rfsentinel.app.alpr.IgnoredCameras.contains(act, cam.osmId)
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
        AlertDialog.Builder(act)
            .setTitle(cam.label)
            .setMessage(text)
            // Kept until turned back on here (or cleared in Settings).
            .setPositiveButton(if (ignored) "Alert again" else "Ignore alerts") { _, _ ->
                com.rfsentinel.app.alpr.IgnoredCameras.set(act, cam.osmId, !ignored)
                Toast.makeText(act, if (ignored) "You'll be warned about this camera again"
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
        towerCount = com.rfsentinel.app.service.CellTowerStore.all(act).count { it.bestLat != null }
        // The Cells chip shows the towers (only them); All adds them when the map menu's switch is on;
        // every other chip hides them.
        val show = filter == DeviceFilter.CELLS ||
            (filter == DeviceFilter.ALL && com.rfsentinel.app.util.Prefs.showCellTowers(act))
        towers.points = if (tripId != null || !show) emptyList()
        else com.rfsentinel.app.service.CellTowerStore.all(act).filter { it.bestLat != null }.map {
            PointsOverlay.Point(it.bestLat!!, it.bestLon!!, it, icon = towerIcon)
        }
        binding.map.invalidate()
    }

    private fun showTower(t: com.rfsentinel.app.service.CellTowerStore.Tower) {
        val net = listOfNotNull(t.operator, "${t.mcc ?: "?"}-${t.mnc ?: "?"}").joinToString(" · ")
        AlertDialog.Builder(act)
            .setTitle(t.ratLabel)
            .setMessage("$net\nArea ${t.area ?: "?"} · Cell ${t.cellId}" +
                (t.bestDbm?.let { "\nStrongest signal $it dBm" } ?: "") +
                "\nSeen ${t.timesSeen}×, last ${dateFmt.format(java.util.Date(t.lastSeen))}" +
                "\n\nPlaced where your phone heard it strongest: an estimate, not the tower's exact position.")
            .setPositiveButton("All towers") { _, _ -> startActivity(Intent(act, CellTowersActivity::class.java)) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** One device, or a list to pick from when several were heard at the same spot. */
    private fun showPins(list: List<Pin>) {
        if (list.size == 1) { showPin(list[0]); return }
        val sorted = list.sortedByDescending { it.flagged }
        AlertDialog.Builder(act)
            .setTitle("${list.size} devices here")
            .setItems(sorted.map { (if (it.flagged) "⚠ " else "") + it.label }.toTypedArray()) { _, i -> showPin(sorted[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPin(p: Pin) {
        AlertDialog.Builder(act)
            .setTitle(p.label)
            .setMessage(p.details + "\n\nPosition = where your phone was when this device's signal was strongest (approximate).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(act, p.mac) }
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
                    com.rfsentinel.app.util.Prefs.setShowKnownAlpr(act, true)
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
            12 -> {
                startActivity(Intent(act, com.rfsentinel.app.settings.SettingsActivity::class.java)
                    .putExtra(com.rfsentinel.app.settings.SettingsActivity.EXTRA_SECTION,
                        com.rfsentinel.app.settings.SettingsActivity.SECTION_MAP))
                true
            }
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
