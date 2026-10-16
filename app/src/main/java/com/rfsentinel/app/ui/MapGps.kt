package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import com.rfsentinel.app.util.Permissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The position arrow's GPS, one for the whole app: the small map on the main screen and the full screen map share
 * it, so going from one to the other never restarts it - the arrow is where it was, at once. It keeps running a
 * little after the last map goes ([LINGER_MS]: a rotation, a quick look at another screen), then stops. A fix a
 * second (2 m) while moving, every 5 s (10 m) after a minute parked.
 *
 * [best] is the position to start a map with: the freshest fix from any source that is precise enough, so a coarse
 * WiFi / cell position (often 100 m off, across the road or a block away) does not stand in for a GPS one.
 */
object MapGps {
    const val LINGER_MS = 20_000L
    private const val MOVING_MS = 1_000L
    private const val PARKED_MS = 5_000L
    private const val PARK_AFTER_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val subscribers = CopyOnWriteArrayList<(Location) -> Unit>()
    private var app: Context? = null
    private var listening = false
    private var parked = false
    private var stillSince = 0L
    private val stopLater = Runnable { stop() }

    // ---- the freshest good position, whoever got it -------------------------------------------------

    /** Recent fixes from every source (this GPS, guidance, the scan), newest last. */
    private val recent = ArrayDeque<Location>()

    /** Any fix the app gets (main thread or not): kept for [best]. */
    @Synchronized fun offer(fix: Location) {
        recent.addLast(Location(fix))
        while (recent.size > 8) recent.removeFirst()
    }

    /** The position to show before a fresh fix: the newest precise one (within [PRECISE_M]) not much older than the newest of all. */
    @Synchronized fun best(extra: List<Location?> = emptyList(), now: Long = System.currentTimeMillis()): Location? =
        pick(recent.toList() + extra.filterNotNull(), now)

    /** A fix this precise is a GPS one; a coarser one (WiFi, cell) is only used when there is nothing better. */
    const val PRECISE_M = 30f
    /** A precise fix may be this much older than a coarse one and still be preferred. */
    private const val PREFER_PRECISE_MS = 60_000L

    internal fun pick(all: List<Location>, now: Long): Location? {
        if (all.isEmpty()) return null
        val newest = all.maxBy { it.time }
        val precise = all.filter { it.hasAccuracy() && it.accuracy <= PRECISE_M }.maxByOrNull { it.time }
        return if (precise != null && newest.time - precise.time <= PREFER_PRECISE_MS) precise else newest
    }

    @Synchronized internal fun clearForTest() { recent.clear() }

    // ---- the shared GPS --------------------------------------------------------------------------

    private val listener = LocationListenerCompat { fix ->
        offer(fix)
        adapt(fix)
        subscribers.forEach { runCatching { it(fix) } }
    }

    /** Starts (or keeps) the GPS for [onFix]; the fix already known is handed over at once. Main thread. */
    fun subscribe(context: Context, onFix: (Location) -> Unit) {
        app = context.applicationContext
        main.removeCallbacks(stopLater)
        if (onFix !in subscribers) subscribers.add(onFix)
        start()
    }

    /** [onFix] no longer wants fixes; the GPS stops [LINGER_MS] after the last one goes. */
    fun unsubscribe(onFix: (Location) -> Unit) {
        subscribers.remove(onFix)
        if (subscribers.isEmpty()) { main.removeCallbacks(stopLater); main.postDelayed(stopLater, LINGER_MS) }
    }

    val running: Boolean get() = listening

    @SuppressLint("MissingPermission")
    private fun start() {
        val ctx = app ?: return
        if (listening || !Permissions.granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER))
            LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        val ms = if (parked) PARKED_MS else MOVING_MS
        val meters = if (parked) 10f else 2f
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val req = android.location.LocationRequest.Builder(ms)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateDistanceMeters(meters)
                    .build()
                lm.requestLocationUpdates(provider, req, ctx.mainExecutor, listener)
            } else lm.requestLocationUpdates(provider, ms, meters, listener, Looper.getMainLooper())
            listening = true
        }
    }

    private fun stop() {
        if (!listening || subscribers.isNotEmpty()) return
        val ctx = app ?: return
        runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener) }
        listening = false
    }

    /** Slower once parked for a minute, back to a fix a second when moving (re-registered; the arrow stays put meanwhile). */
    private fun adapt(fix: Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        val moving = fix.hasSpeed() && fix.speed >= 1.0f
        if (moving) stillSince = 0L else if (stillSince == 0L) stillSince = now
        val p = !moving && stillSince != 0L && now - stillSince > PARK_AFTER_MS
        if (p == parked) return
        parked = p
        if (listening) {
            val ctx = app ?: return
            runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener) }
            listening = false
            start()
        }
    }
}
