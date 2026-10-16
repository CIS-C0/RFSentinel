package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
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
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.nav.NavCamera
import com.rfsentinel.app.nav.NavSearchActivity
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.nav.Place
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
        /** Open the map on a place to get directions to: plans the routes to it at once. */
        const val EXTRA_NAV_NAME = "nav_name"
        const val EXTRA_NAV_ADDRESS = "nav_address"
        const val EXTRA_NAV_LAT = "nav_lat"
        const val EXTRA_NAV_LON = "nav_lon"
        /** Inside the main screen (its chips drive the filter; the menu is a button on the map). */
        const val ARG_EMBEDDED = "embedded"
        /** A failed camera download stays in the status line this long (the retry runs after a minute). */
        private const val CAMERA_FAIL_SHOWN_MS = 90_000L
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

        /** After panning or zooming the map, it goes back to following you once this long has passed without a touch. */
        const val RECENTER_AFTER_MS = 10_000L

        /**
         * Whether the live map should take you back to your position now: the setting is on, it is not following you, nobody is
         * looking at a place's card, you are driving (guided, or moving at walking pace or faster) and the map has been left alone
         * for [afterMs]. Parked and browsing, it stays where you put it.
         */
        internal fun shouldRecenter(enabled: Boolean, following: Boolean, live: Boolean, guiding: Boolean, speedMs: Float,
                                    placeCardOpen: Boolean, idleMs: Long, afterMs: Long = RECENTER_AFTER_MS): Boolean =
            enabled && live && !following && !placeCardOpen && idleMs >= afterMs && (guiding || speedMs >= HEADING_MIN_SPEED_MS)

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
    private var nav: MapNav? = null
    private var navArgsHandled = false
    private var alertCards: AlertCardController? = null
    private var stopAlertCards: (() -> Unit)? = null
    private var rf: RfMap? = null
    private val map: MapEngine? get() = rf?.map
    private var lastFix: Location? = null
    private var lastBearing: Float? = null
    private var smoothKmh = -1f
    private var stopObservingBulk: (() -> Unit)? = null
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    /** Where to? The search screen's answer: a destination (plan the routes) or a stop on the way. */
    private val searchLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK || !alive) return@registerForActivityResult
        val place = NavSearchActivity.placeOf(r.data) ?: return@registerForActivityResult
        when (r.data?.getStringExtra(NavSearchActivity.EXTRA_MODE)) {
            NavSearchActivity.MODE_STOP -> Navigator.addStop(act, place)
            // The pane on the main screen stays where it is: the card and routes open in it, and only a tap on
            // the guidance banner or arrival time goes to the full screen map.
            else -> nav?.showPlace(place, plan = true)
        }
    }

    private val navHost = object : MapNav.Host {
        override fun navLookAtRoute() {
            if (following) { following = false; applyMode(animate = false); styleFollowButton() }
        }

        override fun navGuidingChanged(on: Boolean) {
            if (!alive) return
            binding.root.keepScreenOn = on && Prefs.navKeepScreenOn(act)
            // The Record / Traces bar gives way to the arrival bar while you are being guided.
            // In the small pane the round buttons stay while guiding: they sit just above the arrival bar.
            if (!embedded) { binding.controls.visibility = if (on || immersive) View.GONE else View.VISIBLE; layoutBottomInset() }
            if (on) { following = true; userZoom = false; applyMode(animate = true); styleFollowButton() }
            else { lastZoomTarget = 0.0; applyMode(animate = true) } // the zoom for a turn goes with the guidance
            renderLive()
        }

        override fun navCheckStyle() {
            if (alive && rf != null && rf?.isDark != darkTiles()) loadStyle()
        }

        override fun navSearch(mode: String) = searchLauncher.launch(NavSearchActivity.intent(act, mode))

        override fun navOpenFull(place: Place?) = openFullMap(place)

        override fun navPanelsChanged() { if (alive) { renderLive(); fitSideButtonsSoon(); layoutBottomInset() } }
    }

    /** The map on its own screen, on [place] to plan a route to when given (else on the route being followed). */
    private fun openFullMap(place: Place?) {
        startActivity(Intent(act, MapActivity::class.java).apply {
            if (place != null) {
                putExtra(EXTRA_NAV_NAME, place.name); putExtra(EXTRA_NAV_ADDRESS, place.address)
                putExtra(EXTRA_NAV_LAT, place.lat); putExtra(EXTRA_NAV_LON, place.lon)
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tripId = args.getLong(EXTRA_TRIP_ID, -1).takeIf { it > 0 }
        if (!embedded) act.title = if (tripId == null) "Scan map" else "Recorded trace"

        // Full screen, edge-to-edge: keep the bottom-most panel above the navigation bar.
        if (!embedded) {
            basePad = listOf(binding.navCard, binding.navBar, binding.mapFilterScroll, binding.controls).map { it.paddingBottom }
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                bottomInset = bars.bottom
                layoutBottomInset()
                binding.statusBox.translationY = bars.top.toFloat()
                insets
            }
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
        if (!embedded) setupImmersiveButton()
        binding.bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSideButtonsSoon() }
        // The scale and the map credit sit just under the status banner, or at the very top when there is none.
        binding.statusBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeScaleBar() }
        // Wider than tall: the round buttons become a row and the turn banner leaves them the right side.
        if (!embedded) binding.root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, or, ob -> if (r - l != or - ol || bt - t != ob - ot) fitSideButtonsSoon() }

        createEngine(savedInstanceState, null)
        if (tripId == null) {
            nav = MapNav(act, binding, viewLifecycleOwner.lifecycleScope, embedded, { alive }, { rf }, { lastFix ?: knownFix() }, navHost)
                .also { it.setup() }
            // Alerts pop up over the full screen map (the small pane on the main screen has the list under it).
            if (!embedded) {
                val cards = AlertCardController(act, binding, { lastFix ?: knownFix() }) { lat, lon ->
                    navHost.navLookAtRoute()
                    rf?.map?.easeTo(lat, lon, ms = 500)
                }
                alertCards = cards
                stopAlertCards = AlertCards.addListener { cards.show(it) }
                // One that came in just before this screen opened (tapping its notification, say) is still shown.
                AlertCards.latest?.takeIf { System.currentTimeMillis() - it.atMs < 6_000L }?.let { cards.show(it) }
            }
        } else {
            binding.searchButton.visibility = View.GONE
        }
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
        // The map's start-up is marked on disk until it has drawn: if the app dies in it, the next launch leaves the map off.
        safetyArmed = true
        MapSafety.arm(act, MapEngine.chosen(act))
        try {
            val e = MapEngine.create(act)
            engine = e
            markerKey = "" // the new engine has not been given the marker yet
            binding.map.addView(e.view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            binding.osmCredit.text = e.credit
            e.onCreate(state)
            e.whenReady { if (alive && engine === e) onMapReady(e, restore) }
        } catch (t: Throwable) {
            // A native library that will not load, a graphics context that cannot be made: an error that can be caught
            // is told, not crashed on (and the saved view stops asking for the map).
            android.util.Log.w("MapFragment", "the map could not start", t)
            engine = null
            safetyArmed = false
            MapSafety.disarm(act)
            if (embedded) Prefs.viewPanes(act).let { if ("map" in it) Prefs.setViewPanes(act, it - "map") }
            binding.statusText.text = "The map can't start on this phone (${t.javaClass.simpleName}). Everything else works."
            binding.statusText.visibility = View.VISIBLE
        }
    }

    private var safetyArmed = false

    /** The map has been up for a few seconds: its start-up did not take the app down. */
    private fun mapWorked() {
        if (!safetyArmed) return
        safetyArmed = false
        MapSafety.worked(act)
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
        // The graphics crash comes the moment the map's surface is made, long before this: a few seconds on, the
        // start-up is over. (Waiting for the camera to go idle is not enough: it never does while guiding.)
        binding.root.postDelayed({ if (alive && engine === m) mapWorked() }, MapSafety.SETTLE_MS)
        val r = RfMap(act, m).also { rf = it }
        r.filter = filter
        r.tripMode = tripId != null
        r.onStaticChanged = { if (alive) { renderLive(); refreshServiceLocation() } }
        m.setMinZoom(2.0)
        // Two fingers in 3D: a pinch is your zoom now (until the follow button).
        m.onUserGesture = { touchedAt = android.os.SystemClock.elapsedRealtime(); if (following) userZoom = true }
        m.onFollowDismissed = { touchedAt = android.os.SystemClock.elapsedRealtime(); if (alive && following) { following = false; styleFollowButton() } }
        m.onCameraMove = { updateScale() }
        m.onIdle = { r.safe("static") { r.maybeRedrawStatic(); updateScale() } }
        m.onClick = { x, y -> r.safe("tap") { r.onTap(x, y, myPosition()) } }
        if (tripId == null) m.onLongClick = { lat, lon -> r.safe("long press") { nav?.onLongPress(lat, lon) } }
        if (restore != null) { m.jumpTo(restore.lat, restore.lon, restore.zoom); centeredOnce = true }
        else placeCameraAtStart(m)
        placeScaleBar()
        loadStyle()
    }

    private fun systemDark() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The map's look: your choice (the sun / moon button), else - while guided - day or night where you are, else the phone's. */
    private fun darkTiles(): Boolean = Prefs.mapDark(act) ?: run {
        val here = if (Navigator.active && Prefs.navAutoNight(act)) lastFix ?: knownFix() else null
        if (here != null) com.rfsentinel.app.nav.Sun.isNight(here.latitude, here.longitude, System.currentTimeMillis()) else systemDark()
    }

    private fun loadStyle() {
        val r = rf ?: return
        val dark = darkTiles()
        styleThemeButton()
        binding.osmCredit.setBackgroundColor(if (dark) 0x40000000 else 0x99FFFFFF.toInt()) // the night one is barely there: the map shows through
        binding.osmCredit.setTextColor(if (dark) 0xFFDDDDDD.toInt() else 0xFF333333.toInt())
        r.loadStyle(dark) {
            if (!alive) return@loadStyle
            r.safe("location") { startLocation() }
            if (tripId != null) loadTrip() else renderLive()
            r.safe("static") { r.maybeRedrawStatic(force = true) }
            r.safe("nav") { nav?.redraw() }
            // Opened from a search on the main screen's map: the routes to that place.
            // (Not again after a rotation once you are being guided: that would plan a second route over the first.)
            if (!navArgsHandled && args.containsKey(EXTRA_NAV_LAT) && !Navigator.active) {
                navArgsHandled = true
                nav?.showPlace(Place(args.getString(EXTRA_NAV_NAME).orEmpty(), args.getString(EXTRA_NAV_ADDRESS).orEmpty(),
                    args.getDouble(EXTRA_NAV_LAT), args.getDouble(EXTRA_NAV_LON)), plan = true)
            }
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
        // On its side while guiding the banner fills the left and the arrival bar sits right under it: the credit goes
        // to the free top right corner instead of being hidden under the bar.
        val atEnd = wideScreen() && Navigator.active
        val y = (if (atEnd) box.translationY.toInt() + gap / 2 else if (box.height > 0) box.bottom + box.translationY.toInt() + gap else box.top + box.translationY.toInt()).coerceAtLeast(gap)
        b.scaleBar.translationY = y.toFloat()
        // The credit wraps to a second line rather than run under the compass and follow buttons at the right.
        val d = resources.displayMetrics.density
        if (b.root.width > 0) b.osmCredit.maxWidth = (b.root.width - 84 * d).toInt().coerceAtLeast((140 * d).toInt())
        val scaleH = if (b.scaleBar.visibility == View.GONE) 0 else b.scaleBar.height
        b.osmCredit.translationY = (y + scaleH).toFloat()
        b.osmCredit.translationX = if (atEnd && b.osmCredit.width > 0) (b.root.width - b.osmCredit.width - b.osmCredit.left - gap).toFloat() else 0f
        // Mapbox's logo and info button just under the credit line.
        engine?.placeBranding(y + scaleH + b.osmCredit.height + gap / 2, atEnd)
    }

    private fun updateScale() {
        val m = map ?: return
        val b = _binding ?: return
        // The small pane while guiding has no room for it between the turn banner and the buttons above the
        // arrival bar: the credit moves up in its place.
        if ((embedded || wideScreen()) && Navigator.active) {
            if (b.scaleBar.visibility != View.GONE) { b.scaleBar.visibility = View.GONE; placeScaleBar() }
            return
        }
        val wasGone = b.scaleBar.visibility == View.GONE
        // Tilted, the scale differs across the screen: no single scale to show.
        if (m.camera.tilt > 5) { b.scaleBar.visibility = View.INVISIBLE; if (wasGone) placeScaleBar(); return }
        b.scaleBar.visibility = View.VISIBLE
        if (wasGone) placeScaleBar()
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
        for (v in listOf(binding.searchButton, binding.mapSettingsButton, binding.themeButton, binding.fullscreenButton)) {
            (v.parent as? ViewGroup)?.removeView(v)
            binding.compactControls.addView(v, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
        }
        binding.recordFab.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        binding.recordFab.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
        binding.statusText.textSize = 12f
        // On a narrower screen the row of round buttons would run into the compass and follow buttons at the right.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSideButtonsSoon() }
        binding.root.addOnLayoutChangeListener(relayout)
        binding.compactControls.addOnLayoutChangeListener(relayout)
        binding.navCard.addOnLayoutChangeListener(relayout)
        binding.navBar.addOnLayoutChangeListener(relayout)
    }

    /**
     * Under the place card or the arrival bar, the compass and follow buttons stack in the same column and
     * would squeeze the card's buttons off the bottom of a short pane. They give way, the compass first,
     * when the card (at the height it needs) and they do not all fit; they come back when the card goes.
     */
    private fun fitSideButtons() {
        val b = _binding ?: return
        if (!embedded || b.root.height == 0) return
        val d = resources.displayMetrics.density
        val panel = listOf(b.navCard, b.navBar).firstOrNull { it.visibility == View.VISIBLE }
        var hide = emptyList<View>()
        if (panel != null) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(b.root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            // The turn banner (and the credit under it) take the top of the pane while guiding.
            val top = if (b.navBanner.visibility == View.VISIBLE) b.statusBox.bottom + b.statusBox.translationY + 8 * d else 0f
            val room = b.root.height - 8 * d - top
            val fab = 56 * d // a button and its margin
            // The round buttons ride above the arrival bar (the card hides them) and, when they are too wide to sit
            // beside the compass and follow buttons, those rise above them.
            val rowLift = if (rowCrowdsSideButtons()) b.compactControls.height + 8 * d else 0f
            val used = panel.measuredHeight + rowLift
            hide = when {
                used + 2 * fab <= room -> emptyList()
                used + fab <= room -> listOf(b.orientationButton)
                else -> listOf(b.orientationButton, b.centerButton)
            }
        }
        for (v in listOf(b.orientationButton, b.centerButton)) {
            val want = if (v in hide) View.GONE else View.VISIBLE
            if (v.visibility != want) v.visibility = want
        }
    }

    private var bottomInset = 0
    private var basePad: List<Int> = emptyList()

    /**
     * The navigation bar's height is added under the panel that is really at the bottom (the Record / Traces
     * bar, or the filter chips while guiding), not under every panel: the place card sits above the chips and
     * the Record bar, and carrying the inset too left a big empty band between its buttons and the chips.
     */
    private fun layoutBottomInset() {
        val b = _binding ?: return
        if (embedded || basePad.isEmpty()) return
        val panels = listOf(b.navCard, b.navBar, b.mapFilterScroll, b.controls)
        val last = panels.lastOrNull { it.visibility == View.VISIBLE }
        val six = (6 * resources.displayMetrics.density).toInt()
        panels.forEachIndexed { i, p ->
            val want = if (p === last) bottomInset + six else basePad[i]
            if (p.paddingBottom != want) p.updatePadding(bottom = want)
        }
    }

    private var sidePending = false

    /** Changing visibility or padding inside a layout pass is not reliably laid out again: do it right after. */
    private fun fitSideButtonsSoon() {
        val b = _binding ?: return
        if (sidePending) return
        sidePending = true
        b.root.post { sidePending = false; if (_binding != null) { arrangeForShape(); fitCompactRow(); liftSideButtons(); fitSideButtons(); placeGauge(); refollowForPanels() } }
    }

    /**
     * The small pane's row of round buttons (Record, History, Search, Settings, light / dark, full screen) must fit the
     * pane's width whatever it is: the space between them shrinks first (down to none), then the buttons themselves
     * (down to 32 dp), so the last one is never cut off at the edge.
     */
    private fun fitCompactRow() {
        val b = _binding ?: return
        val row = b.compactControls
        if (!embedded || row.visibility != View.VISIBLE || row.width == 0 || b.root.width == 0) return
        val d = resources.displayMetrics.density
        val kids = row.children.filter { it.visibility != View.GONE }.toList()
        if (kids.size < 2) return
        // Worked out from what is on screen now: how much room is left (or missing) between the row's end and the pane's edge.
        val diff = b.root.width - (8 * d).toInt() - row.left - row.width
        if (kotlin.math.abs(diff) < 2) return
        val fabs = kids.filterIsInstance<com.google.android.material.floatingactionbutton.FloatingActionButton>()
        val natural = (40 * d).toInt()
        val gapMax = (8 * d).toInt()
        val curGap = (kids[1].layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: return
        val gap = curGap + diff / (kids.size - 1)
        fun setGap(g: Int) = kids.forEachIndexed { i, v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val want = if (i == 0) 0 else g
            if (lp.marginStart != want) { lp.marginStart = want; v.layoutParams = lp }
        }
        fun resize(by: Int) {
            for (f in fabs) {
                val now = if (f.customSize > 0) f.customSize else f.width
                val to = (now + by).coerceIn((32 * d).toInt(), natural)
                if (to != f.customSize) f.customSize = to
            }
        }
        when {
            gap in 0..gapMax -> setGap(gap)
            // More room than the spacing needs: the buttons grow back to their size.
            gap > gapMax -> { setGap(gapMax); if (fabs.isNotEmpty()) resize((diff - (gapMax - curGap) * (kids.size - 1)) / fabs.size) }
            // Not enough even without spacing: the buttons get smaller (not under 32 dp).
            else -> { setGap(0); if (fabs.isNotEmpty()) resize((diff + curGap * (kids.size - 1)) / fabs.size) }
        }
    }

    private var origBasePad: List<Int>? = null
    private var compactLand = false
    private var landPad = -1

    /**
     * On its side every row of the screen counts: less padding in the turn banner, the arrival bar, the chips and the
     * Record bar. The padding is a share of the screen's height (between 4 and 12 dp), so a short phone gets less and a
     * tall tablet keeps the usual.
     */
    private fun compactForLandscape(on: Boolean) {
        val b = _binding ?: return
        val d = resources.displayMetrics.density
        for (c in b.mapFilterChips.children) (c as? com.google.android.material.chip.Chip)?.setEnsureMinTouchTargetSize(!on)
        val pad = if (on) (b.root.height * 0.017f).toInt().coerceIn((4 * d).toInt(), (12 * d).toInt()) else (12 * d).toInt()
        if (on == compactLand && pad == landPad) return
        compactLand = on; landPad = pad
        val small = pad / 3 // the bars' own top and bottom: a third of the banner's
        b.navBanner.setPadding(pad, pad, pad, pad)
        b.navDistance.textSize = if (on) 22f else 28f
        (b.navLanes.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = if (on) small else (8 * d).toInt()
        (b.navThen.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = if (on) 0 else (6 * d).toInt()
        b.navEta.textSize = if (on) 17f else 20f
        b.navBar.updatePadding(top = if (on) 0 else (6 * d).toInt())
        b.controls.updatePadding(top = if (on) small else (6 * d).toInt())
        val orig = origBasePad ?: basePad.also { origBasePad = it }
        basePad = orig.mapIndexed { i, v -> if (on && (i == 1 || i == 3)) small else v } // the arrival bar and the Record bar
        layoutBottomInset()
        b.root.requestLayout()
    }

    /** The full screen map is wider than tall (a phone on its side). */
    private fun wideScreen(): Boolean { val b = _binding ?: return false; return !embedded && b.root.width > b.root.height && b.root.height > 0 }

    /**
     * On its side the screen has no height for a column of five round buttons under the turn banner (they climbed
     * under the toolbar and the banner's mute button): they become a row above the bottom panels, at the right, and
     * the banner and the alert card stop at about half the width so the two never meet. Upright: the column as before.
     */
    private fun arrangeForShape() {
        val b = _binding ?: return
        if (embedded || b.root.width == 0) return
        val wide = wideScreen()
        val want = if (wide) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
        if (b.sideButtons.orientation != want) b.sideButtons.orientation = want
        val d = resources.displayMetrics.density
        // The banner gets what the row of buttons leaves (each is about 62 dp with its margin), not less than 40% of the width.
        val shown = b.sideButtons.children.count { it.visibility != View.GONE }
        val rowW = (shown * 62 * d + 24 * d).toInt()
        val width = if (wide) (b.root.width - rowW).coerceIn((b.root.width * 0.4f).toInt(), (520 * d).toInt().coerceAtLeast((b.root.width * 0.4f).toInt()))
            else ViewGroup.LayoutParams.MATCH_PARENT
        val lp = b.statusBox.layoutParams
        if (lp.width != width) { lp.width = width; b.statusBox.layoutParams = lp }
        compactForLandscape(wide)
        updateScale() // while guiding on its side the scale gives its place to the credit, as in the small pane
        placeScaleBar()
    }

    /**
     * The compass and follow buttons sit at the right edge, the round buttons along the bottom-left. When the
     * pane is too narrow for both side by side the compass and follow buttons move up above the row instead of
     * lying under it. Worked out from sizes alone, so it settles in one pass.
     */
    private fun liftSideButtons() {
        val b = _binding ?: return
        val row = b.compactControls
        val d = resources.displayMetrics.density
        // While guiding, the row rides just above the arrival bar instead of under it.
        row.translationY = if (row.visibility == View.VISIBLE && b.navBar.visibility == View.VISIBLE) -b.navBar.height.toFloat() else 0f
        // Crowded: the compass and follow buttons rise by the row's height (their own bottom margin, so the panel's
        // card or bar below them stays where it is).
        val lift = if (rowCrowdsSideButtons()) (row.height + 8 * d).toInt() else 0
        val lp = b.centerButton.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        val want = (8 * d).toInt() + lift
        if (lp.bottomMargin != want) { lp.bottomMargin = want; b.centerButton.layoutParams = lp }
    }

    /** The row of round buttons is too long to sit beside the compass and follow buttons (worked out from sizes alone). */
    private fun rowCrowdsSideButtons(): Boolean {
        val b = _binding ?: return false
        val row = b.compactControls
        val d = resources.displayMetrics.density
        val rightZone = (14 + 48 + 8) * d // the buttons' margin, their width and a gap
        return row.visibility == View.VISIBLE && row.width > 0 && row.width + 10 * d + rightZone > b.root.width
    }

    // ---- Speedometer -----------------------------------------------------------------------------------

    /** The newest fix from the screen's own GPS (not a position remembered from before). */
    private var gpsFix: Location? = null

    /**
     * Your speed and the posted limit, in the corner. The speed is the GPS's own (hidden when there is no recent
     * fix); the limit comes with the route while you are guided, else from the opt-in lookup of the road you are on.
     */
    private fun updateGauge() {
        val b = _binding ?: return
        val g = b.speedGauge
        val fix = gpsFix
        val fresh = fix != null && fix.hasSpeed() && System.currentTimeMillis() - fix.time < 6_000L
        if (tripId != null || !Prefs.mapSpeedometer(act) || !fresh) {
            if (g.visibility != View.GONE) { g.visibility = View.GONE; fitSideButtonsSoon() }
            return
        }
        val limit = when {
            Navigator.active -> Navigator.speedLimitKmh()
            Prefs.mapSpeedLimitLive(act) -> com.rfsentinel.app.nav.SpeedLimits.Live.now(fix!!.latitude, fix.longitude)
            else -> null
        }
        val wasShown = g.visibility == View.VISIBLE
        g.set(fix!!.speed * 3.6f, limit, com.rfsentinel.app.nav.NavFormat.miles(act), rf?.isDark ?: darkTiles())
        if (!wasShown) fitSideButtonsSoon()
    }

    /**
     * The gauge sits bottom left, just above whatever is at the bottom of the screen on that side: the filter chips, the
     * Record bar, the arrival bar or place card, or the small pane's row of round buttons (which may ride above the arrival
     * bar). Only moved, never resized: no layout pass of its own.
     */
    private fun placeGauge() {
        val b = _binding ?: return
        val g = b.speedGauge
        if (g.visibility != View.VISIBLE || b.root.height == 0) return
        val d = resources.displayMetrics.density
        var top = b.root.height.toFloat() - (if (embedded) 0 else bottomInset)
        for (p in listOf(b.navCard, b.navBar, b.mapFilterScroll, b.controls)) {
            if (p.visibility == View.VISIBLE && p.height > 0) top = minOf(top, (b.bottomPanel.top + p.top).toFloat())
        }
        val row = b.compactControls
        if (row.visibility == View.VISIBLE && row.height > 0) top = minOf(top, row.top + row.translationY)
        g.translationY = -(b.root.height - top + 8 * d)
    }

    // ---- Hiding the bars -------------------------------------------------------------------------------

    private val immersive get() = !embedded && Prefs.mapImmersive(act)

    /** The map's own button (full screen map only): hide the toolbar, the system bars and the bottom bar, or bring them back. */
    private fun setupImmersiveButton() {
        val btn = binding.fullscreenButton
        btn.visibility = View.VISIBLE
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        btn.imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B5C63.toInt())
        btn.setOnClickListener {
            val on = !Prefs.mapImmersive(act)
            Prefs.setMapImmersive(act, on)
            applyImmersive()
            if (on) Toast.makeText(act, "Bars hidden - swipe from the screen's edge for the system bars, tap again to bring the rest back", Toast.LENGTH_LONG).show()
        }
        applyImmersive()
    }

    /**
     * Full screen map hiding even more: the toolbar, the status and navigation bars, the filter chips, the Record bar and the
     * status line go; the map, its round buttons, the speedometer, the alert cards and the turn banner and arrival bar while
     * guiding stay (the credit line too - it is the map's licence).
     */
    private fun applyImmersive() {
        val b = _binding ?: return
        if (embedded) return
        val on = Prefs.mapImmersive(act)
        b.fullscreenButton.setImageResource(if (on) com.rfsentinel.app.R.drawable.ic_fullscreen_exit else com.rfsentinel.app.R.drawable.ic_fullscreen)
        b.fullscreenButton.contentDescription = if (on) "Show the toolbars again" else "Hide the toolbars (full screen)"
        act.supportActionBar?.let { if (on) it.hide() else it.show() }
        val bars = androidx.core.view.WindowCompat.getInsetsController(act.window, b.root)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        b.mapFilterScroll.visibility = if (on) View.GONE else View.VISIBLE
        b.controls.visibility = if (on || Navigator.active) View.GONE else View.VISIBLE
        layoutBottomInset()
        renderLive()
        fitSideButtonsSoon()
    }

    // ---- Your position --------------------------------------------------------------------------------

    private var markerKey = ""

    /** The marker chosen in Settings > Map: given to the map engine whenever the choice has changed. */
    private fun applyMarker() {
        val m = map ?: return
        val key = MarkerPicker.effective(act).key
        if (key == markerKey) return
        markerKey = key
        m.setMarker(MarkerPicker.current(act))
    }

    private fun startLocation() {
        val m = map ?: return
        applyMarker()
        if (!m.startPuck()) return
        (lastFix ?: knownFix())?.let { m.pushFix(steady(onRoute(it))) }
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

    /** When the map was last touched (panned, pinched...): the auto-recenter counts from it. */
    private var touchedAt = 0L

    private fun onFix(fix: Location) {
        if (!alive) return
        lastFix = fix
        gpsFix = fix
        if (shouldRecenter(Prefs.mapAutoRecenter(act), following, tripId == null, Navigator.active, if (fix.hasSpeed()) fix.speed else 0f,
                nav?.cardShown == true, android.os.SystemClock.elapsedRealtime() - touchedAt)) setFollow(true, quiet = true)
        if (tripId == null && !Navigator.active && Prefs.mapSpeedometer(act) && Prefs.mapSpeedLimitLive(act) && fix.hasSpeed())
            com.rfsentinel.app.nav.SpeedLimits.Live.onFix(fix.latitude, fix.longitude, fix.speed)
        updateGauge()
        val m = map?.takeIf { it.puckOn } ?: return
        m.pushFix(steady(onRoute(fix)))
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
        // Zoom in as a turn comes up while guided, out again after it (the speed's own zoom otherwise).
        val target = (if (drives) zoomFor(smoothKmh, base) else base) + turnBoost()
        val moved = kotlin.math.abs(target - lastZoomTarget) > 0.1
        if ((drives && before < 0f) || (moved && (drives || Navigator.active))) { lastZoomTarget = target; applyMode(animate = true) }
    }

    private var snapState: com.rfsentinel.app.nav.RouteProgress.State? = null

    /** While guided and close to the route: the fix drawn on it, turned along it ([com.rfsentinel.app.nav.RouteSnap]); else as it is. */
    private fun onRoute(fix: Location): Location {
        val route = com.rfsentinel.app.nav.Navigator.route
        if (!Navigator.active || route == null) { snapState = null; return fix }
        val moving = fix.hasSpeed() && fix.speed >= HEADING_MIN_SPEED_MS && fix.hasBearing()
        val s = com.rfsentinel.app.nav.RouteSnap.snap(route, fix.latitude, fix.longitude, if (moving) fix.bearing.toDouble() else null,
            if (fix.hasAccuracy()) fix.accuracy.toDouble() else null, snapState?.takeIf { route === snappedRoute })
        snappedRoute = route
        if (s == null) { snapState = null; return fix }
        snapState = s.state
        return Location(fix).apply {
            latitude = s.lat; longitude = s.lon
            if (moving) bearing = s.bearingDeg.toFloat()
        }
    }
    private var snappedRoute: com.rfsentinel.app.nav.Route? = null

    /** Zoom levels added for the turn coming up (only while guiding). */
    private fun turnBoost(): Double {
        val s = Navigator.state ?: return 0.0
        return if (Navigator.active) NavCamera.turnBoost(s.toManeuverM, smoothKmh.coerceAtLeast(0f)) else 0.0
    }
    private var lastZoomTarget = 0.0

    // ---- Battery: the map's own GPS ---------------------------------------------------------------------

    /**
     * The position arrow's GPS is [MapGps], shared by every map: the small map on the main screen and the full screen
     * map use the same one, so switching between them doesn't restart it. It runs while a map is on screen and stops a
     * little after the last one goes.
     */
    private var listening = false
    private val mapGpsListener: (Location) -> Unit = { fix -> rf?.safe("fix") { onFix(fix) } ?: onFix(fix) }

    private fun startGps() {
        if (listening || !Permissions.granted(act, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        listening = true
        MapGps.subscribe(act, mapGpsListener)
    }

    private fun stopGps() {
        if (!listening) return
        MapGps.unsubscribe(mapGpsListener)
        listening = false
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
        val boost = turnBoost()
        if (is3d()) {
            val pad = followPadding(h, ARROW_HEIGHT)
            lastFollowPad = pad
            m.follow(MapEngine.Follow.HEADING, pad, tiltFor(kmh, Prefs.map3dTilt(act)), if (userZoom) null else zoomFor(kmh, base) + boost, ms)
        } else if (Prefs.mapHeadingUp(act)) {
            // Turning with you: pulls back at speed like the 3D view; a pinch is yours until the follow button.
            lastFollowPad = followPadding(h, 0.5)
            m.follow(MapEngine.Follow.HEADING, lastFollowPad ?: 0.0, 0.0, if (userZoom) null else zoomFor(kmh, base) - FLAT_ZOOM_OFFSET + boost, ms)
        } else {
            lastFollowPad = followPadding(h, 0.5)
            m.follow(MapEngine.Follow.NORTH, lastFollowPad ?: 0.0, 0.0, if (userZoom) null else base - FLAT_ZOOM_OFFSET + boost, ms)
        }
    }

    private var lastFollowPad: Double? = null

    /**
     * The camera's top padding that puts your arrow [fraction] of the way down the part of the map you can see.
     * Full screen that is the whole map (as ever). In the small pane the turn banner covers the top and the
     * round buttons and the arrival bar the bottom, so the arrow is placed in what is left between them -
     * otherwise it sits behind the buttons.
     */
    private fun followPadding(h: Int, fraction: Double): Double? {
        if (h <= 0) return null
        if (!embedded) return if (fraction == ARROW_HEIGHT) topPaddingFor(h) else 0.0
        val b = _binding ?: return if (fraction == ARROW_HEIGHT) topPaddingFor(h) else 0.0
        val mapTop = IntArray(2).also { b.map.getLocationInWindow(it) }[1]
        fun top(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[1] - mapTop.toFloat()
        val topLimit = if (b.navBanner.visibility == View.VISIBLE) top(b.statusBox) + b.statusBox.height else 0f
        var bottomLimit = h.toFloat()
        for (v in listOf(b.navBar, b.compactControls, b.navCard)) if (v.visibility == View.VISIBLE && v.height > 0) bottomLimit = minOf(bottomLimit, top(v))
        if (bottomLimit <= topLimit + 40) return if (fraction == ARROW_HEIGHT) topPaddingFor(h) else 0.0 // no room to speak of
        val y = topLimit + fraction * (bottomLimit - topLimit)
        return (2 * y - h).coerceAtLeast(0.0)
    }

    /** After the pane's panels moved (the arrival bar and the buttons above it appeared), the arrow is placed again if it should move. */
    private fun refollowForPanels() {
        val b = _binding ?: return
        if (!embedded || !following || tripId != null || b.map.height == 0) return
        val want = followPadding(b.map.height, if (is3d()) ARROW_HEIGHT else 0.5)
        val had = lastFollowPad
        if (want != null && (had == null || kotlin.math.abs(want - had) > 8.0)) applyMode(animate = false)
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

    /** The best position known before this screen's own GPS answers: the freshest precise fix from any source (see [MapGps.best]). */
    private fun knownFix(): Location? = MapGps.best(listOf(ScanForegroundService.lastFix, com.rfsentinel.app.nav.Navigator.lastFix, systemLastKnown()))

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
        applyMarker() // back from Settings > Map: a new marker shows at once
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
        nav?.refresh()
    }

    override fun onPause() {
        if (safetyArmed) { safetyArmed = false; MapSafety.disarm(act) } // paused normally: not a crash
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
        if (safetyArmed) { safetyArmed = false; MapSafety.disarm(act) } // leaving normally is not a crash
        stopAlertCards?.invoke(); stopAlertCards = null
        alertCards?.hide(); alertCards = null
        nav?.destroy(); nav = null
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
            Navigator.active && !recording -> null // the guidance banner is the status
            // In the small pane the place card needs the room: the scan status is on the main screen above it anyway.
            embedded && nav?.cardShown == true && !recording -> null
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
        binding.statusText.visibility = if (text.isEmpty() || (immersive && !Navigator.active)) View.GONE else View.VISIBLE
        updateGauge()
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
