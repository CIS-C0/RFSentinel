package com.rfsentinel.app.ui

import android.content.res.ColorStateList
import android.location.Location
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.R
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.nav.HandOff
import com.rfsentinel.app.nav.NavFormat
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.nav.Place
import com.rfsentinel.app.nav.Places
import com.rfsentinel.app.nav.Prompts
import com.rfsentinel.app.nav.Route
import com.rfsentinel.app.nav.Valhalla
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Getting somewhere on the map screen: a place card (from a search, or a long press on the map), the
 * routes to it to choose from, and then guidance - the banner with the next turn at the top, the
 * arrival bar at the bottom. [Navigator] does the guiding; this shows it.
 *
 * On the main screen's small map pane there is only the arrival bar (tap it for the full screen map).
 */
internal class MapNav(
    private val act: AppCompatActivity,
    private val b: ActivityMapBinding,
    private val scope: CoroutineScope,
    private val embedded: Boolean,
    private val alive: () -> Boolean,
    private val rf: () -> RfMap?,
    private val fix: () -> Location?,
    private val host: Host
) {
    interface Host {
        /** Looking at a route (not at yourself): stop following. */
        fun navLookAtRoute()
        /** Guidance began or ended: follow, keep the screen on, swap the bottom controls. */
        fun navGuidingChanged(on: Boolean)
        /** Sunset or sunrise may have passed: switch the map between its day and night look if it should. */
        fun navCheckStyle()
        /** Open the search screen for [mode] (see [com.rfsentinel.app.nav.NavSearchActivity]). */
        fun navSearch(mode: String)
        /** The small map pane: open the full screen map, on [place] when given (to plan a route to it). */
        fun navOpenFull(place: Place?)
        /** The place card or the arrival bar appeared or went: anything that would sit under them gives way. */
        fun navPanelsChanged()
    }

    private val dp = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var place: Place? = null
    private var routes: List<Route> = emptyList()
    private var selected = 0
    private var options = Navigator.defaultOptions(act)
    private var planJob: Job? = null
    private var removeListener: (() -> Unit)? = null
    private var wasActive = false
    private var drawnRoute: Route? = null
    private var drawnAt = 0L
    private var lanesKey = ""

    val cardShown: Boolean get() = b.navCard.visibility == View.VISIBLE

    /**
     * The device filter chips float over the map; under the place card or the arrival bar they would
     * show the map through between two solid panels. They sit on the same solid background then, so the
     * bottom of the screen reads as one panel (and are transparent over the bare map as before).
     */
    private var panelsKey = -1

    private fun styleBackdrop() {
        val card = b.navCard.visibility == View.VISIBLE
        val solid = card || b.navBar.visibility == View.VISIBLE
        // Called at every guidance update (once a second): only when the card or the bar came or went is there anything to do
        // (it wakes the map's redraw and a layout pass).
        val key = (if (card) 1 else 0) + (if (solid) 2 else 0)
        if (key == panelsKey) return
        panelsKey = key
        val scroll = b.mapFilterScroll
        // In the small pane the round buttons would sit on top of the card's buttons: they wait while it shows.
        // The arrival bar does not hide them: they ride just above it.
        if (embedded) b.compactControls.visibility = if (b.navCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        host.navPanelsChanged()
        if (solid) {
            val tv = android.util.TypedValue()
            act.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            scroll.setBackgroundColor(tv.data)
            scroll.setPadding(px(8), 0, px(8), scroll.paddingBottom) // the chips' own touch margin is the gap; the bottom is the screen's (navigation bar)
        } else {
            scroll.setBackgroundColor(0x00000000)
            scroll.setPadding(px(8), 0, px(8), scroll.paddingBottom)
        }
    }

    // ---- set up ------------------------------------------------------------------------------------

    fun setup() {
        val ink = ColorStateList.valueOf(ChipStyle.onSurface(act))
        for (v in listOf(b.navCardStar, b.navCardRename, b.navCardClose, b.navEnd, b.navOverview, b.navMenu)) v.imageTintList = ink
        b.navMute.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        b.searchButton.backgroundTintList = ColorStateList.valueOf(ChipStyle.accent(act).first)
        b.searchButton.imageTintList = ColorStateList.valueOf(ChipStyle.accent(act).second)
        b.searchButton.setOnClickListener { host.navSearch(com.rfsentinel.app.nav.NavSearchActivity.MODE_GO) }

        b.navCardClose.setOnClickListener { closeCard() }
        b.navCardStar.setOnClickListener { place?.let { Prefs.toggleNavFavorite(act, it); styleStar() } }
        b.navCardRename.setOnClickListener { renamePlace() }
        b.navCardTitle.setOnClickListener { if (place?.let { Prefs.isNavFavorite(act, it) } == true) renamePlace() }
        b.navStart.setOnClickListener { onStartButton() }
        b.navOpenIn.setOnClickListener { openInMenu(it) }
        ChipStyle.accent(act).first.let { accent ->
            b.navOpenIn.setTextColor(accent)
            b.navOpenIn.strokeColor = ColorStateList.valueOf(accent)
        }
        b.navEnd.setOnClickListener { Navigator.stop() }
        b.navMenu.setOnClickListener { menu(it) }
        b.navOverview.setOnClickListener { overview() }
        b.navMute.setOnClickListener { toggleDirections() }
        if (embedded) {
            b.navEtaBox.setOnClickListener { host.navOpenFull(null) }
            b.navBanner.setOnClickListener { host.navOpenFull(null) }
            compactBanner()
            compactCard()
        }
        styleMute()
        removeListener = Navigator.addListener { if (alive()) refresh() }
        refresh()
    }

    /**
     * The small map pane has little height to spare: the place card gets tighter padding, smaller buttons
     * and drops its address line once the routes (with their time and distance) are shown.
     */
    private fun compactCard() {
        b.navCard.setPadding(px(10), px(6), px(10), px(6))
        for (v in listOf(b.navCardStar, b.navCardRename, b.navCardClose)) v.layoutParams = LinearLayout.LayoutParams(px(40), px(40))
        for (v in listOf(b.navStart, b.navOpenIn)) { v.insetTop = 0; v.insetBottom = 0; v.minimumHeight = px(40) }
        (b.navStart.parent as? LinearLayout)?.let { row -> (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = px(4) }
    }

    /** The small map pane: the next turn in one slim line (no lanes, no "then"); tap it for the full screen map. */
    private fun compactBanner() {
        b.navBanner.setPadding(px(8), px(6), px(8), px(6))
        (b.navBanner.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = px(4)
        b.navIcon.layoutParams = LinearLayout.LayoutParams(px(32), px(32))
        b.navDistance.textSize = 18f
        b.navRoad.textSize = 13f
        b.navRoad.maxLines = 1
        b.navMute.visibility = View.GONE
        b.navLanes.visibility = View.GONE
        b.navThen.visibility = View.GONE
    }

    fun destroy() {
        planJob?.cancel()
        removeListener?.invoke(); removeListener = null
    }

    // ---- a place, and the routes to it ------------------------------------------------------------

    /** A long press on the map: the card for what is there, with Directions (or Add as stop while guiding). */
    fun onLongPress(lat: Double, lon: Double) {
        b.map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dropped = Place("Dropped pin", String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon), lat, lon)
        showPlace(dropped, plan = false)
        scope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) { Places.reverse(lat, lon, "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}") }
            }.getOrNull() ?: return@launch
            // Still the same pin (not closed or replaced meanwhile): say what is there.
            val now = place ?: return@launch
            if (alive() && now.lat == lat && now.lon == lon) {
                place = Place(found.name, found.address.ifBlank { now.address }, lat, lon)
                bindCard()
            }
        }
    }

    /** The card for [p]; with [plan] the routes are looked for at once. */
    fun showPlace(p: Place, plan: Boolean) {
        planJob?.cancel()
        routes = emptyList(); selected = 0
        options = Navigator.defaultOptions(act)
        place = p
        host.navLookAtRoute()
        b.navCard.visibility = View.VISIBLE
        b.navRoutesScroll.visibility = View.GONE
        b.navAvoidScroll.visibility = View.GONE
        styleBackdrop()
        bindCard()
        rf()?.drawRoutes(emptyList(), 0)
        rf()?.drawNavPlaces(emptyList(), p.lat to p.lon)
        rf()?.map?.easeTo(p.lat, p.lon, ms = 500)
        if (plan) plan()
    }

    private fun bindCard() {
        val p = place ?: return
        b.navCardTitle.text = p.name
        b.navCardSub.text = p.address
        b.navCardSub.visibility = if (p.address.isBlank()) View.GONE else View.VISIBLE
        styleStar()
        if (routes.isEmpty()) {
            b.navStart.isEnabled = true
            b.navStart.text = if (Navigator.active) "Add as stop" else "Directions"
        }
        b.navOpenIn.visibility = if (Navigator.active) View.GONE else View.VISIBLE
    }

    private fun styleStar() {
        val p = place ?: return
        val on = Prefs.isNavFavorite(act, p)
        b.navCardStar.setImageResource(if (on) R.drawable.ic_nav_star else R.drawable.ic_nav_star_border)
        b.navCardStar.imageTintList = ColorStateList.valueOf(if (on) ChipStyle.accent(act).first else ChipStyle.onSurface(act))
        b.navCardStar.contentDescription = if (on) "Starred - tap to remove" else "Star this place"
        b.navCardRename.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Your own name for the starred place on the card. */
    private fun renamePlace() {
        val p = place ?: return
        com.rfsentinel.app.nav.RenamePlace.ask(act, p) { renamed -> if (place == p) { place = renamed; bindCard() } }
    }

    private fun onStartButton() {
        when {
            routes.isNotEmpty() -> start()
            Navigator.active -> { place?.let { Navigator.addStop(act, it) }; closeCard() }
            else -> plan()
        }
    }

    private fun plan() {
        val p = place ?: return
        val f = fix()
        if (f == null) { Toast.makeText(act, "Waiting for a GPS fix to plan the route...", Toast.LENGTH_LONG).show(); return }
        b.navStart.isEnabled = false
        b.navStart.text = "Finding routes..."
        planJob?.cancel()
        planJob = scope.launch {
            // Not runCatching: a plan replaced by a newer one, or dropped when the card closed, must not end up as "no connection".
            com.rfsentinel.app.nav.attempt { Navigator.plan(act, p, f, options) }
                .onSuccess { found ->
                    val now = place
                    if (!alive() || now == null || now.lat != p.lat || now.lon != p.lon) return@onSuccess
                    routes = found; selected = 0
                    showRoutes(refit = true)
                }
                .onFailure { e ->
                    if (!alive()) return@onFailure
                    b.navStart.isEnabled = true
                    // Routes already shown stay startable (a change of options failed); else ask again.
                    b.navStart.text = if (routes.isNotEmpty()) "Start · " + NavFormat.duration(routes[selected].durationS) else "Directions"
                    Toast.makeText(act, (e as? Valhalla.NoRouteException)?.message ?: "Couldn't plan the route - no connection?", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showRoutes(refit: Boolean) {
        val p = place ?: return
        val miles = NavFormat.miles(act)
        b.navRoutesScroll.visibility = if (routes.size > 1) View.VISIBLE else View.GONE
        b.navRoutes.removeAllViews()
        val (accent, onAccent) = ChipStyle.accent(act)
        routes.forEachIndexed { i, r ->
            val chosen = i == selected
            b.navRoutes.addView(MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = NavFormat.duration(r.durationS) + "\n" + NavFormat.distance(r.distanceM, miles) +
                    (if (r.summary.isNotBlank()) " · via " + r.summary.substringBefore(',') else "") +
                    (r.cameraLine?.let { "\n$it" } ?: "")
                isAllCaps = false
                setTextColor(if (chosen) onAccent else accent)
                backgroundTintList = ColorStateList.valueOf(if (chosen) accent else 0x00000000)
                strokeColor = ColorStateList.valueOf(accent)
                setOnClickListener { selected = i; showRoutes(refit = false) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) })
        }
        buildAvoidChips()
        b.navStart.isEnabled = true
        b.navStart.text = "Start · " + NavFormat.duration(routes[selected].durationS)
        if (embedded) b.navCardSub.visibility = View.GONE
        rf()?.drawRoutes(routes.map { it.points() }, selected)
        rf()?.drawNavPlaces(emptyList(), p.lat to p.lon)
        if (refit) fitRoute(routes[selected])
    }

    private fun buildAvoidChips() {
        b.navAvoidScroll.visibility = View.VISIBLE
        b.navAvoid.removeAllViews()
        fun chip(label: String, on: Boolean, set: (Valhalla.Options, Boolean) -> Valhalla.Options) = FitChip(act).apply {
            text = label; isCheckable = true; isChecked = on; isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(false)
            ChipStyle.apply(this)
            setOnCheckedChangeListener { _, checked ->
                options = set(options, checked)
                Navigator.remember(act, options) // ticked once: the next destination starts with it too
                plan()
            }
        }
        b.navAvoid.addView(chip("Avoid tolls", options.avoidTolls) { o, v -> o.copy(avoidTolls = v) })
        b.navAvoid.addView(chip("Avoid highways", options.avoidHighways) { o, v -> o.copy(avoidHighways = v) })
        b.navAvoid.addView(chip("Avoid ferries", options.avoidFerries) { o, v -> o.copy(avoidFerries = v) })
        b.navAvoid.addView(chip("Avoid speed cameras", options.avoidSpeedCameras) { o, v -> o.copy(avoidSpeedCameras = v) })
        b.navAvoid.addView(chip("Avoid plate readers", options.avoidPlateReaders) { o, v -> o.copy(avoidPlateReaders = v) })
    }

    private fun fitRoute(r: Route) {
        val m = rf()?.map ?: return
        // A coarse outline is enough to frame it (the card covers the bottom of the map).
        val step = (r.size / 200).coerceAtLeast(1)
        val pts = (0 until r.size step step).map { r.lats[it] to r.lons[it] } + (r.lats.last() to r.lons.last())
        val pad = px(if (embedded) 36 else 72)
        m.fit(pts, pad)
        // The small pane is mostly card: once the card has its size (routes and options just appeared), frame
        // the route in the map above it rather than behind it.
        if (embedded) b.navCard.post {
            if (alive() && cardShown) rf()?.map?.fit(pts, pad, pad + b.navCard.height)
        }
    }

    private fun start() {
        val p = place ?: return
        val r = routes.getOrNull(selected) ?: return
        val f = fix()
        Navigator.begin(act, p, r, options, emptyList(), f)
        closeCard(keepRoute = true)
    }

    /** The place card as it is, for the other map screen to show the same; null when none shows. */
    fun card(): MapScreens.Card? = place?.takeIf { cardShown }?.let { MapScreens.Card(it, routes, selected, options) }

    /** The card as the other map screen left it: the same place and routes (not looked for again), or none. */
    fun restore(card: MapScreens.Card?) {
        if (card == null) { if (cardShown) closeCard(); return }
        planJob?.cancel()
        place = card.place; routes = card.routes; selected = card.selected.coerceIn(0, (card.routes.size - 1).coerceAtLeast(0)); options = card.options
        b.navCard.visibility = View.VISIBLE
        b.navRoutesScroll.visibility = View.GONE
        b.navAvoidScroll.visibility = View.GONE
        styleBackdrop()
        bindCard()
        if (routes.isNotEmpty()) showRoutes(refit = false)
        else { rf()?.drawRoutes(emptyList(), 0); rf()?.drawNavPlaces(emptyList(), card.place.lat to card.place.lon) }
    }

    fun closeCard(keepRoute: Boolean = false) {
        planJob?.cancel()
        b.navCard.visibility = View.GONE
        styleBackdrop()
        place = null; routes = emptyList()
        if (!keepRoute && !Navigator.active) {
            rf()?.drawRoutes(emptyList(), 0)
            rf()?.drawNavPlaces(emptyList(), null)
        } else if (Navigator.active) {
            // A place looked at while guided was only a look: the route goes back on the map and you are followed again.
            drawGuidance(force = true)
            host.navGuidingChanged(true)
        }
    }

    private fun openInMenu(anchor: View) {
        val p = place ?: return
        PopupMenu(act, anchor).apply {
            menu.add(0, 1, 0, "Google Maps"); menu.add(0, 2, 1, "Waze"); menu.add(0, 3, 2, "Another map app")
            setOnMenuItemClickListener {
                HandOff.open(act, p, when (it.itemId) { 1 -> "google"; 2 -> "waze"; else -> "geo" })
                true
            }
            show()
        }
    }

    // ---- guiding -----------------------------------------------------------------------------------

    /** After the map style (re)loaded: its layers are empty again. */
    fun redraw() {
        drawnRoute = null
        if (Navigator.active) drawGuidance(force = true)
        else if (routes.isNotEmpty()) place?.let {
            rf()?.drawRoutes(routes.map { r -> r.points() }, selected); rf()?.drawNavPlaces(emptyList(), it.lat to it.lon)
        } else if (cardShown) place?.let { rf()?.drawNavPlaces(emptyList(), it.lat to it.lon) }
    }

    private var styleCheckedAt = 0L

    /** Called at every fix and change while guiding, and once the screen is up. */
    fun refresh() {
        val active = Navigator.active
        if (active != wasActive) {
            wasActive = active
            host.navGuidingChanged(active)
            host.navCheckStyle()
            if (active) closeCard(keepRoute = true) else onEnded()
        }
        val now = System.currentTimeMillis()
        if (active && now - styleCheckedAt > 60_000L) { styleCheckedAt = now; host.navCheckStyle() }
        b.navBanner.visibility = if (active) View.VISIBLE else View.GONE
        b.navBar.visibility = if (active) View.VISIBLE else View.GONE
        styleBackdrop()
        if (!active) return
        val r = Navigator.route ?: return
        val s = Navigator.state
        val miles = NavFormat.miles(act)
        styleMute()

        if (s == null) {
            b.navIcon.setImageResource(R.drawable.ic_nav_straight)
            b.navDistance.text = ""
            b.navRoad.text = Navigator.status ?: "Waiting for GPS..."
            b.navLanes.visibility = View.GONE; b.navThen.visibility = View.GONE
            b.navEta.text = "Arrive " + clock(System.currentTimeMillis() + (r.durationS * 1000).toLong())
            b.navEtaSub.text = NavFormat.duration(r.durationS) + " · " + NavFormat.distance(r.distanceM, miles)
            return
        }
        val banner = Prompts.banner(r, s.stepIndex, s.toManeuverM)
        val next = r.steps.getOrNull(s.stepIndex + 1)
        b.navIcon.setImageResource(iconRes(NavFormat.iconFor(banner?.type ?: next?.type, banner?.modifier ?: next?.modifier, next?.drivingSide)))
        b.navDistance.text = NavFormat.distance(s.toManeuverM, miles)
        // A roundabout names its exit ("2nd exit") ahead of the road it leads to.
        val exit = next?.takeIf { (it.type == "roundabout" || it.type == "rotary") && (it.exit ?: 0) > 0 }?.exit?.let { NavFormat.ordinal(it) + " exit" }
        val road = banner?.text?.takeIf { it.isNotBlank() } ?: next?.name?.takeIf { it.isNotBlank() } ?: next?.instruction?.removeSuffix(".").orEmpty()
        b.navRoad.text = Navigator.status ?: if (exit == null) road else if (road.isBlank() || road.startsWith("Take the", true)) exit else "$exit · $road"
        if (!embedded) bindLanes(banner?.lanes.orEmpty())
        val then = if (embedded) null else Prompts.thenBanner(r, s.stepIndex)
        if (then == null) b.navThen.visibility = View.GONE else {
            b.navThen.visibility = View.VISIBLE
            b.navThenIcon.setImageResource(iconRes(NavFormat.iconFor(then.type, then.modifier, r.steps.getOrNull(s.stepIndex + 2)?.drivingSide)))
            b.navThenText.text = then.text
        }
        val left = Navigator.remainingS ?: s.remainingS
        b.navEta.text = "Arrive " + clock(System.currentTimeMillis() + (left * 1000).toLong())
        val stopsLeft = Navigator.stops.size
        b.navEtaSub.text = NavFormat.duration(left) + " · " + NavFormat.distance(s.remainingM, miles) +
            (if (stopsLeft > 0) " · $stopsLeft stop${if (stopsLeft == 1) "" else "s"}" else "")
        drawGuidance(force = false)
    }

    private fun onEnded() {
        rf()?.drawRoutes(emptyList(), 0)
        rf()?.drawNavPlaces(emptyList(), null)
        drawnRoute = null
        if (Navigator.arrived) Toast.makeText(act, "You have arrived", Toast.LENGTH_LONG).show()
    }

    /** The part of the route still ahead, drawn again every few seconds (and when the route changes). */
    private fun drawGuidance(force: Boolean) {
        val r = Navigator.route ?: return
        val now = System.currentTimeMillis()
        if (!force && r === drawnRoute && now - drawnAt < 3_000) return
        drawnRoute = r; drawnAt = now
        val from = Navigator.state?.alongM ?: 0.0
        rf()?.drawRoutes(listOf(r.pointsFrom(from)), 0)
        rf()?.drawNavPlaces(Navigator.stops.map { it.lat to it.lon }, Navigator.destination?.let { it.lat to it.lon })
    }

    private fun bindLanes(lanes: List<com.rfsentinel.app.nav.Step.Lane>) {
        val key = lanes.joinToString("|") { it.directions.joinToString(",") + it.active }
        if (key == lanesKey) return
        lanesKey = key
        b.navLanes.removeAllViews()
        b.navLanes.visibility = if (lanes.isEmpty()) View.GONE else View.VISIBLE
        for (lane in lanes) {
            b.navLanes.addView(ImageView(act).apply {
                setImageResource(iconRes(NavFormat.laneIcon(NavFormat.laneDirection(lane))))
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                alpha = if (lane.active) 1f else 0.35f
                setBackgroundColor(if (lane.active) 0x33FFFFFF else 0x00000000)
            }, LinearLayout.LayoutParams(px(32), px(32)).apply { marginStart = px(2); marginEnd = px(2); gravity = Gravity.CENTER })
        }
    }

    private fun overview() {
        val r = Navigator.route ?: return
        host.navLookAtRoute()
        val from = Navigator.state?.alongM ?: 0.0
        val pts = r.pointsFrom(from)
        val step = (pts.size / 200).coerceAtLeast(1)
        rf()?.map?.fit(pts.filterIndexed { i, _ -> i % step == 0 } + pts.last(), px(72))
    }

    private fun menu(anchor: View) {
        PopupMenu(act, anchor).apply {
            menu.add(0, 1, 0, if (Prefs.navVoice(act)) "Mute directions" else "Unmute directions")
            menu.add(0, 2, 1, "Add a stop...")
            menu.add(0, 3, 2, "Avoid tolls").apply { isCheckable = true; isChecked = Navigator.options.avoidTolls }
            menu.add(0, 4, 3, "Avoid highways").apply { isCheckable = true; isChecked = Navigator.options.avoidHighways }
            menu.add(0, 5, 4, "Avoid ferries").apply { isCheckable = true; isChecked = Navigator.options.avoidFerries }
            menu.add(0, 9, 4, "Avoid speed cameras").apply { isCheckable = true; isChecked = Navigator.options.avoidSpeedCameras }
            menu.add(0, 10, 4, "Avoid plate readers").apply { isCheckable = true; isChecked = Navigator.options.avoidPlateReaders }
            menu.add(0, 6, 5, "Open in Google Maps")
            menu.add(0, 7, 6, "Open in Waze")
            menu.add(0, 8, 7, "End navigation")
            setOnMenuItemClickListener { item ->
                val dest = Navigator.destination
                when (item.itemId) {
                    1 -> toggleDirections()
                    2 -> host.navSearch(com.rfsentinel.app.nav.NavSearchActivity.MODE_STOP)
                    3 -> Navigator.changeOptions(act, Navigator.options.copy(avoidTolls = !Navigator.options.avoidTolls))
                    4 -> Navigator.changeOptions(act, Navigator.options.copy(avoidHighways = !Navigator.options.avoidHighways))
                    5 -> Navigator.changeOptions(act, Navigator.options.copy(avoidFerries = !Navigator.options.avoidFerries))
                    9 -> Navigator.changeOptions(act, Navigator.options.copy(avoidSpeedCameras = !Navigator.options.avoidSpeedCameras))
                    10 -> Navigator.changeOptions(act, Navigator.options.copy(avoidPlateReaders = !Navigator.options.avoidPlateReaders))
                    6 -> dest?.let { HandOff.open(act, it, "google") }
                    7 -> dest?.let { HandOff.open(act, it, "waze") }
                    8 -> Navigator.stop()
                }
                true
            }
            show()
        }
    }

    /** Spoken directions on / off; the detection alerts have their own mute and keep sounding. */
    private fun toggleDirections() {
        val on = !Prefs.navVoice(act)
        Prefs.setNavVoice(act, on)
        styleMute()
        android.widget.Toast.makeText(act, if (on) "Spoken directions on" else "Directions muted - detection alerts still sound", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun styleMute() {
        val on = Prefs.navVoice(act)
        b.navMute.setImageResource(if (on) R.drawable.ic_car_volume else R.drawable.ic_car_volume_off)
        b.navMute.contentDescription = if (on) "Mute directions" else "Unmute directions"
    }

    private fun clock(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    private fun iconRes(i: NavFormat.Icon): Int = when (i) {
        NavFormat.Icon.STRAIGHT -> R.drawable.ic_nav_straight
        NavFormat.Icon.LEFT -> R.drawable.ic_nav_turn_left
        NavFormat.Icon.RIGHT -> R.drawable.ic_nav_turn_right
        NavFormat.Icon.SLIGHT_LEFT -> R.drawable.ic_nav_turn_slight_left
        NavFormat.Icon.SLIGHT_RIGHT -> R.drawable.ic_nav_turn_slight_right
        NavFormat.Icon.SHARP_LEFT -> R.drawable.ic_nav_turn_sharp_left
        NavFormat.Icon.SHARP_RIGHT -> R.drawable.ic_nav_turn_sharp_right
        NavFormat.Icon.U_TURN_LEFT -> R.drawable.ic_nav_u_turn_left
        NavFormat.Icon.U_TURN_RIGHT -> R.drawable.ic_nav_u_turn_right
        NavFormat.Icon.ROUNDABOUT -> R.drawable.ic_nav_roundabout_right
        NavFormat.Icon.ROUNDABOUT_LEFT -> R.drawable.ic_nav_roundabout_left
        NavFormat.Icon.MERGE -> R.drawable.ic_nav_merge
        NavFormat.Icon.FORK_LEFT -> R.drawable.ic_nav_fork_left
        NavFormat.Icon.FORK_RIGHT -> R.drawable.ic_nav_fork_right
        NavFormat.Icon.RAMP_LEFT -> R.drawable.ic_nav_ramp_left
        NavFormat.Icon.RAMP_RIGHT -> R.drawable.ic_nav_ramp_right
        NavFormat.Icon.ARRIVE -> R.drawable.ic_nav_flag
    }
}
