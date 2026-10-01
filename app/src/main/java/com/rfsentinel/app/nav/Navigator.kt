package com.rfsentinel.app.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.AlertPlayer
import com.rfsentinel.app.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turn-by-turn guidance along an [OsmRouting.Route]: follows your GPS position,
 * tells the next manoeuvre and what's left, re-routes when you leave the route
 * and stops on arrival. Runs on its own GPS updates, so it works with or
 * without a scan running.
 */
object Navigator {

    /** Where you are along the route (pure; unit-tested). */
    data class Progress(
        val nextStep: OsmRouting.Step?,
        val toNextStepM: Double,
        val remainingM: Double,
        /** Distance from the route line; large = off route. */
        val offRouteM: Double,
        val arrived: Boolean
    )

    const val OFF_ROUTE_M = 60.0
    const val ARRIVED_M = 35.0

    @Volatile var destination: OsmRouting.Destination? = null
        private set
    @Volatile var route: OsmRouting.Route? = null
        private set
    @Volatile var progress: Progress? = null
        private set
    @Volatile var lastFix: Location? = null
        private set
    @Volatile var status: String? = null
        private set

    val active: Boolean get() = destination != null

    /** Called on every change (main thread). */
    var onUpdate: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var appContext: Context? = null
    private var offRouteSince = 0L
    private var lastReroute = 0L
    private var spokenStep: OsmRouting.Step? = null
    private var spokenNear = false

    private val listener = LocationListener { onLocation(it) }

    private val userAgent get() = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"

    /** Plans a route from [from] and starts guiding. Throws if no route is found. */
    suspend fun start(context: Context, dest: OsmRouting.Destination, from: Location) {
        val app = context.applicationContext
        val r = withContext(Dispatchers.IO) { OsmRouting.route(from.latitude, from.longitude, dest.lat, dest.lon, userAgent) }
        appContext = app
        destination = dest
        route = r
        lastFix = from
        spokenStep = null; spokenNear = false; offRouteSince = 0L
        progress = progressOf(r, from.latitude, from.longitude)
        status = null
        listen(app, true)
        progress?.nextStep?.let { say(it.instruction) }
        onUpdate?.invoke()
    }

    fun stop() {
        appContext?.let { listen(it, false) }
        destination = null; route = null; progress = null; status = null
        onUpdate?.invoke()
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
        val p = progressOf(r, l.latitude, l.longitude)
        progress = p
        val now = System.currentTimeMillis()
        when {
            p.arrived -> {
                say("You have arrived")
                stop(); return
            }
            p.offRouteM > OFF_ROUTE_M -> {
                if (offRouteSince == 0L) offRouteSince = now
                if (now - offRouteSince > 5_000 && now - lastReroute > 20_000) reroute(l)
            }
            else -> offRouteSince = 0L
        }
        p.nextStep?.let { step ->
            if (step != spokenStep) { spokenStep = step; spokenNear = false; if (p.toNextStepM > 400) say("In ${roundDistance(p.toNextStepM)}, ${step.instruction}") }
            if (!spokenNear && p.toNextStepM < 150) { spokenNear = true; say(step.instruction) }
        }
        onUpdate?.invoke()
    }

    private fun reroute(from: Location) {
        val dest = destination ?: return
        lastReroute = System.currentTimeMillis()
        status = "Re-routing..."
        onUpdate?.invoke()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { OsmRouting.route(from.latitude, from.longitude, dest.lat, dest.lon, userAgent) } }
                .onSuccess { r ->
                    if (destination != null) {
                        route = r; offRouteSince = 0L; status = null
                        progress = progressOf(r, from.latitude, from.longitude)
                        say("Route updated")
                    }
                }
                .onFailure { status = "Off route - couldn't re-route (no connection?)" }
            onUpdate?.invoke()
        }
    }

    private fun say(text: String) {
        val ctx = appContext ?: return
        AlertPlayer.announce(ctx, text)
    }

    // ---- Pure geometry -------------------------------------------------------------

    fun roundDistance(m: Double): String = when {
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f kilometres", m / 1000).replace(".0 ", " ")
        m >= 100 -> "${(m / 50).toInt() * 50} metres"
        else -> "${(m / 10).toInt() * 10} metres"
    }

    /**
     * Snaps the position to the nearest route vertex, then: the next manoeuvre
     * beyond it, the distance to it and to the end, and how far off the route
     * you are.
     */
    fun progressOf(r: OsmRouting.Route, lat: Double, lon: Double): Progress {
        val pts = r.points
        if (pts.isEmpty()) return Progress(null, 0.0, 0.0, 0.0, true)
        var best = 0; var bestD = Double.MAX_VALUE
        pts.forEachIndexed { i, (a, b) ->
            val d = DeviceRegistry.metersBetween(lat, lon, a, b)
            if (d < bestD) { bestD = d; best = i }
        }
        // Cumulative distance along the line.
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + DeviceRegistry.metersBetween(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        val remaining = (cum.last() - cum[best]) + bestD
        val end = pts.last()
        val arrived = DeviceRegistry.metersBetween(lat, lon, end.first, end.second) < ARRIVED_M
        // Each step's position along the line = its nearest vertex.
        fun indexOf(s: OsmRouting.Step): Int {
            var bi = 0; var bd = Double.MAX_VALUE
            pts.forEachIndexed { i, (a, b) -> val d = DeviceRegistry.metersBetween(s.lat, s.lon, a, b); if (d < bd) { bd = d; bi = i } }
            return bi
        }
        val next = r.steps.firstOrNull { it.type != "depart" && indexOf(it) > best }
        val toNext = next?.let { (cum[indexOf(it)] - cum[best]) + bestD } ?: remaining
        return Progress(next, toNext, remaining, bestD, arrived)
    }
}
