package com.rfsentinel.app.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.core.location.LocationListenerCompat
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.AlertPlayer
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turn-by-turn guidance along a [Route] on its own GPS updates (so it works with or without a scan
 * running): where you are on the route, the next turn and what is left, spoken directions, a new route
 * when you leave this one, stops on the way, and the end. [NavigationService] keeps it running with the
 * screen off.
 *
 * Planning a route ([plan]) changes nothing; [begin] starts guiding on one of the planned routes.
 */
object Navigator {

    /** Within this of the end, you have arrived. */
    const val ARRIVED_M = 35.0
    /** Further than this from the route's line, you are off it. */
    const val OFF_ROUTE_M = 60.0
    private const val OFF_ROUTE_AFTER_MS = 5_000L
    private const val REROUTE_GAP_MS = 15_000L
    /** Turned round (heading against the route) for this long also means a new route. */
    private const val WRONG_WAY_AFTER_MS = 8_000L
    /** Stopped this close to the end for this long: you have arrived (a car park 50 m off the line, a GPS fix that won't settle). */
    private const val NEAR_END_M = 120.0
    private const val STOPPED_AT_END_MS = 8_000L
    private const val STOPPED_MS = 0.8f
    /** A stop at the lights is part of the trip; one longer than this (you parked) is not counted in the pace. */
    private const val PARK_AFTER_MS = 45_000L

    @Volatile var destination: Place? = null
        private set
    @Volatile var route: Route? = null
        private set
    /** Where you are on [route]; null before the first fix. */
    @Volatile var state: RouteProgress.State? = null
        private set
    @Volatile var lastFix: Location? = null
        private set
    /** "Re-routing...", or a problem; null when all is well. */
    @Volatile var status: String? = null
        private set
    /** Stops still to make on the way to [destination]. */
    @Volatile var stops: List<Place> = emptyList()
        private set
    @Volatile var options: Valhalla.Options = Valhalla.Options()
        private set
    @Volatile var arrived: Boolean = false
        private set

    val active: Boolean get() = destination != null && route != null

    private var limits: SpeedLimits.RouteLimits? = null

    /**
     * The posted limit (km/h) where you are on the route, from the limits asked for along it (a stretch at a time,
     * ahead of you); null while it is unknown or not mapped. Asked for only when the screen wants it.
     */
    fun speedLimitKmh(): Int? {
        val r = route ?: return null
        val s = state ?: return null
        val rl = limits?.takeIf { it.route === r } ?: SpeedLimits.RouteLimits(r).also { limits = it }
        return rl.at(s.alongM)
    }

    /**
     * How fast you are really going compared with the route's plan (1 = as planned, 1.2 = a fifth slower),
     * learned from the drive so far: stops at lights, a slow road. It scales the time left, which is the
     * nearest thing to traffic-aware timing without a traffic service.
     */
    @Volatile var pace: Double = 1.0
        private set

    /** Seconds left to the end at your pace. */
    val remainingS: Double? get() = state?.let { it.remainingS * pace }

    /** When you will arrive (epoch ms), from the time left on the route at your pace. */
    val etaMillis: Long? get() = remainingS?.let { System.currentTimeMillis() + (it * 1000).toLong() }

    // Tests replace these: the time, the route service and the voice.
    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var planner: (suspend (Context, Place, Location, Valhalla.Options, List<Place>) -> List<Route>)? = null
    internal var speaker: ((String, Boolean) -> Unit)? = null
    internal var scanStarter: ((Context) -> Unit)? = null

    /** Bumped at every begin and stop, so an answer to a question asked of an earlier trip is ignored. */
    @Volatile internal var generation = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Calls [onChange] (on the main thread) at every fix and change; returns the function that removes it. */
    fun addListener(onChange: () -> Unit): () -> Unit {
        listeners.add(onChange)
        return { listeners.remove(onChange) }
    }

    private fun changed() = listeners.forEach { runCatching { it() } }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var appContext: Context? = null
    private var prompts = Prompts()
    private var offRouteSince = 0L
    private var wrongWaySince = 0L
    private var lastReroute = 0L
    private var legNow = 0
    private var rerouting = false
    /** Failed attempts at a new route in a row (offline...): the wait before the next one grows. */
    private var rerouteFailures = 0
    private var saidRerouting = false
    private var lastFixAt = 0L
    private var stoppedNearEndSince = 0L
    /** For the pace: when you were last standing still, and the standing time that doesn't count. */
    private var stillSince = 0L
    private var parkedMs = 0L
    /** Where on the route, and when, the pace is measured from (reset when a new route replaces it). */
    private var paceFromAlong: Double? = null
    private var paceFromAt = 0L

    private val listener = LocationListenerCompat { onLocation(it) }
    private val userAgent get() = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"

    /** The route options chosen in Settings, for a route nobody has adjusted. */
    fun defaultOptions(context: Context) = Valhalla.Options(
        avoidTolls = Prefs.navAvoidTolls(context), avoidHighways = Prefs.navAvoidHighways(context),
        avoidFerries = Prefs.navAvoidFerries(context),
        avoidSpeedCameras = Prefs.navAvoidSpeedCameras(context), avoidPlateReaders = Prefs.navAvoidPlateReaders(context))

    /**
     * Keeps what was ticked (avoid tolls, highways, ferries) as the starting choice for the next
     * destination - the same setting as Settings > Navigation, so the two always agree.
     */
    fun remember(context: Context, options: Valhalla.Options) {
        Prefs.setNavAvoidTolls(context, options.avoidTolls)
        Prefs.setNavAvoidHighways(context, options.avoidHighways)
        Prefs.setNavAvoidFerries(context, options.avoidFerries)
        Prefs.setNavAvoidSpeedCameras(context, options.avoidSpeedCameras)
        Prefs.setNavAvoidPlateReaders(context, options.avoidPlateReaders)
    }

    // ---- planning ---------------------------------------------------------------------------------

    /** Routes from [from] to [dest] (best first); throws [Valhalla.NoRouteException] or an I/O error. */
    suspend fun plan(context: Context, dest: Place, from: Location, options: Valhalla.Options,
                     stops: List<Place> = emptyList()): List<Route> {
        planner?.let { return it(context, dest, from, options, stops) }
        val app = context.applicationContext
        val miles = NavFormat.miles(app)
        val heading = headingOf(from)
        val via = stops.map { it.lat to it.lon }
        suspend fun ask(exclude: List<Pair<Double, Double>>, alternatives: Int): List<Route> {
            val body = Valhalla.request(from.latitude, from.longitude, heading, via, dest.lat to dest.lon,
                options.copy(alternatives = alternatives), miles, exclude)
            return withContext(Dispatchers.IO) { Valhalla.route(body, userAgent) }
        }
        val kinds = CameraAvoid.kinds(options)
        if (kinds.isEmpty()) return ask(emptyList(), options.alternatives)
        // Round the known cameras: the camera map along the route is fetched first when it isn't there yet.
        return CameraAvoid.plan(kinds, { AlprStore.cameras }, listOf(from.latitude to from.longitude) + via + (dest.lat to dest.lon),
            options.alternatives, covered = { CameraCoverage.ensure(app, it) }, ask = ::ask)
    }

    /** Plans with the Settings defaults and starts on the best route (the car screens' one-step start). */
    suspend fun start(context: Context, dest: Place, from: Location) {
        val o = defaultOptions(context)
        begin(context, dest, plan(context, dest, from, o).first(), o, emptyList(), from)
    }

    private fun headingOf(l: Location): Double? = if (l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) l.bearing.toDouble() else null

    // ---- guiding ----------------------------------------------------------------------------------

    /** Starts guiding along [route] to [dest] (through [stops]). */
    fun begin(context: Context, dest: Place, route: Route, options: Valhalla.Options, stops: List<Place>, from: Location?) {
        val app = context.applicationContext
        appContext = app
        this.destination = dest
        this.route = route
        this.options = options
        this.stops = stops
        arrived = false
        generation++
        prompts = Prompts(NavFormat.miles(app))
        offRouteSince = 0L; wrongWaySince = 0L; lastReroute = clock()
        legNow = 0; rerouting = false; status = null
        rerouteFailures = 0; saidRerouting = false; stoppedNearEndSince = 0L; lastFixAt = 0L
        state = null
        pace = 1.0; paceFromAlong = null; stillSince = 0L; parkedMs = 0L
        lastFix = from
        Prefs.addRecentDestination(app, dest)
        listen(app, true)
        NavigationService.start(app)
        startScanToo(app)
        from?.let { onLocation(it) }
        changed()
    }

    /**
     * A destination set means you are driving: the scan starts with the guidance (Settings > Navigation
     * can turn that off). A scan already running is left as it is, and one that cannot start yet (the
     * permissions are not given) is skipped - guidance never waits on it. The scan is not stopped on
     * arrival; it is a scan like any other you started.
     */
    private fun startScanToo(app: Context) {
        if (!Prefs.navStartScan(app) || ScanForegroundService.isRunning) return
        scanStarter?.let { it(app); return }
        if (Permissions.missingRequired(app).isNotEmpty()) return
        runCatching { ScanForegroundService.start(app) }
    }

    /** Ends guiding. */
    fun stop() {
        appContext?.let { listen(it, false); NavigationService.stop(it) }
        generation++
        destination = null; route = null; state = null; status = null; stops = emptyList(); rerouting = false; limits = null
        lastFix = null // a fix kept after the trip would stand in for a newer position later (the car screens take the newest of two)
        changed()
    }

    /** Adds a stop on the way to the destination and plans again from where you are. */
    fun addStop(context: Context, stop: Place) {
        val dest = destination ?: return
        val from = lastFix ?: return
        status = "Adding stop..."
        changed()
        replan(context.applicationContext, from, dest, stops + stop, then = { stops = it })
    }

    /** Plans again from where you are with other route options (avoid tolls, highways or ferries). */
    fun changeOptions(context: Context, newOptions: Valhalla.Options) {
        val dest = destination ?: return
        val from = lastFix ?: return
        status = "Re-routing..."
        options = newOptions
        remember(context.applicationContext, newOptions)
        changed()
        replan(context.applicationContext, from, dest, stops)
    }

    private fun replan(app: Context, from: Location, dest: Place, via: List<Place>, then: ((List<Place>) -> Unit)? = null) {
        if (rerouting) return
        rerouting = true
        lastReroute = clock()
        val gen = generation
        scope.launch {
            val opts = options.copy(alternatives = 0)
            runCatching { plan(app, dest, from, opts, via).first() }
                .onSuccess { r -> finishReplan(gen, r, via, then) }
                .onFailure {
                    // Only if it is still the trip that asked: a trip started or ended meanwhile owns the screen now.
                    if (gen == generation && destination != null) {
                        rerouteFailures++
                        lastReroute = clock()
                        status = "No new route (${failureText(it)}) - keeping the old one"
                    }
                }
            if (gen == generation) rerouting = false
            changed()
        }
    }

    /** Why a new route could not be had, in words for a driver: the server's reason, else "no connection" (never a host name or a stack message). */
    internal fun failureText(e: Throwable): String = (e as? Valhalla.NoRouteException)?.message?.takeIf { it.isNotBlank() } ?: "no connection"

    /** A new route came in for the trip of generation [gen]; ignored when that trip is over or another has begun. */
    internal fun finishReplan(gen: Int, r: Route, via: List<Place>, then: ((List<Place>) -> Unit)? = null) {
        if (gen != generation || destination == null) return
        then?.invoke(via)
        route = r; state = null; status = null; offRouteSince = 0L; wrongWaySince = 0L
        rerouteFailures = 0; saidRerouting = false
        paceFromAlong = null; stillSince = 0L; parkedMs = 0L // the pace is measured afresh on the new route
        prompts.reset(); legNow = 0; lastFixAt = 0L
        lastFix?.let { onLocation(it) }
    }

    @SuppressLint("MissingPermission") // checked via Permissions
    private fun listen(context: Context, on: Boolean) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        lm.removeUpdates(listener)
        if (!on || !Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER))
            LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val req = android.location.LocationRequest.Builder(1_000L)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY).build()
                lm.requestLocationUpdates(provider, req, context.mainExecutor, listener)
            } else {
                lm.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            }
        }
    }

    private fun onLocation(l: Location) {
        val r = route ?: return
        lastFix = l
        val now = clock()
        val moving = l.hasSpeed() && l.speed > 1.5f
        val stopped = l.hasSpeed() && l.speed < STOPPED_MS
        val bearing = if (moving && l.hasBearing()) l.bearing.toDouble() else null
        // The most you could have moved along the route since the last fix (a generous allowance for speed and GPS noise).
        val dtS = if (lastFixAt == 0L) 30.0 else ((now - lastFixAt) / 1000.0).coerceIn(0.5, 120.0)
        lastFixAt = now
        val speed = if (l.hasSpeed()) l.speed.toDouble() else 0.0
        val s = RouteProgress.locate(r, l.latitude, l.longitude, bearing, state, advanceLimitM = maxOf(100.0, (speed + 10.0) * dtS * 1.5 + 50.0))
        state = s
        trackPace(r, s, now, moving, l.hasSpeed() && l.speed < 0.5f)

        // The end: at it, or stopped close to it for a few seconds (a car park off the line, a fix that won't settle).
        val atEnd = s.remainingM < ARRIVED_M && s.offM < OFF_ROUTE_M
        if (stopped && s.remainingM < NEAR_END_M && s.offM < OFF_ROUTE_M) { if (stoppedNearEndSince == 0L) stoppedNearEndSince = now } else stoppedNearEndSince = 0L
        if (atEnd || (stoppedNearEndSince != 0L && now - stoppedNearEndSince > STOPPED_AT_END_MS)) {
            arrived = true
            say("You have arrived at your destination", urgent = true)
            stop()
            return
        }

        // A stop on the way: the next leg begins.
        val leg = r.steps.getOrNull(s.stepIndex)?.leg ?: legNow
        if (leg > legNow) {
            legNow = leg
            stops = stops.drop(1)
        }

        // Off the route, or going the wrong way along it, for a few seconds: plan again.
        val off = s.offM > OFF_ROUTE_M
        val wrong = moving && (s.headingOffDeg ?: 0.0) > 120.0 && s.offM > 20.0
        if (off) { if (offRouteSince == 0L) offRouteSince = now } else offRouteSince = 0L
        if (wrong) { if (wrongWaySince == 0L) wrongWaySince = now } else wrongWaySince = 0L
        val lost = (offRouteSince != 0L && now - offRouteSince > OFF_ROUTE_AFTER_MS) ||
            (wrongWaySince != 0L && now - wrongWaySince > WRONG_WAY_AFTER_MS)
        // Each failed attempt (no signal...) doubles the wait, up to a minute and a half: no hammering, no nagging.
        val wait = REROUTE_GAP_MS * (1 + rerouteFailures.coerceAtMost(4))
        if (lost && !rerouting && now - lastReroute > wait) {
            val app = appContext
            val dest = destination
            if (app != null && dest != null) {
                status = "Re-routing..."
                if (!saidRerouting) { saidRerouting = true; say("Re-routing", urgent = false) }
                replan(app, l, dest, stops)
            }
        } else if (!off && !wrong && !rerouting) {
            // Back on the line: a failed attempt's note goes, and the next time off the route is told afresh.
            if (status != null && status != "Re-routing...") status = null
            saidRerouting = false; rerouteFailures = 0
        }

        if (!off) prompts.next(r, s.stepIndex, s.toManeuverM, if (l.hasSpeed()) l.speed.toDouble() else 0.0)?.let { say(it.text, it.urgent) }
        changed()
    }

    /**
     * The pace is measured from the first moment you move - not from when the route was chosen, which may
     * be long before you pull out - and a long stop (parked) doesn't count against it.
     */
    private fun trackPace(r: Route, s: RouteProgress.State, now: Long, moving: Boolean, still: Boolean) {
        if (still) { if (stillSince == 0L) stillSince = now }
        else if (stillSince != 0L) {
            val stillFor = now - stillSince
            if (stillFor > PARK_AFTER_MS) parkedMs += stillFor - PARK_AFTER_MS
            stillSince = 0L
        }
        if (paceFromAlong == null) {
            if (!moving) return
            paceFromAlong = s.alongM; paceFromAt = now; parkedMs = 0L
        }
        val ongoing = if (stillSince != 0L && now - stillSince > PARK_AFTER_MS) now - stillSince - PARK_AFTER_MS else 0L
        val elapsedS = (now - paceFromAt - parkedMs - ongoing) / 1000.0
        RouteProgress.pace(r, paceFromAlong ?: return, s.alongM, elapsedS)?.let { pace = it }
    }

    private fun say(text: String, urgent: Boolean) {
        speaker?.let { it(text, urgent); return }
        val ctx = appContext ?: return
        AlertPlayer.navigate(ctx, text, urgent)
    }
}
