package com.rfsentinel.app.alpr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Download the cameras around me": finds where you are and fetches the known
 * plate, speed and red-light cameras within the radius set in Settings (100 km
 * by default), so they're on the map and
 * warn you offline from the start. Runs in the background - leaving the screen
 * that started it doesn't stop it. Only the area is sent, never your scans.
 */
object CameraPrefetch {

    sealed interface State {
        data object Idle : State
        data object Locating : State
        data class Downloading(val done: Int, val total: Int, val found: Int = 0, val detail: String = "") : State
        data class Done(val cameras: Int, val failedAreas: Int = 0, val radiusKm: Int = 100) : State
        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val listeners = mutableSetOf<(State) -> Unit>()

    @Volatile var state: State = State.Idle
        private set

    fun canRun(context: Context) =
        Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Called on the main thread with every state change (and once right away). */
    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    private var appContext: Context? = null
    private var lastNotified = 0L
    private val trailingNotify = Runnable { lastNotified = 0L; notify(state) }

    val isRunning: Boolean get() = job?.isActive == true

    private fun set(s: State) = main.post {
        state = s
        listeners.toList().forEach { it(s) }
        notify(s)
    }

    /** Mirrors the state in a notification, so you can leave the app while it downloads. */
    private fun notify(s: State) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val final = s is State.Done || s is State.Failed
        main.removeCallbacks(trailingNotify)
        if (!final && now - lastNotified < 400) {
            // Android drops too-frequent updates: show the latest one a moment later instead.
            main.postDelayed(trailingNotify, 400)
            return
        }
        lastNotified = now
        val h = com.rfsentinel.app.util.NotificationHelper
        when (s) {
            State.Idle -> Unit
            State.Locating -> h.showCameraDownload(ctx, "Downloading known cameras", "Finding your position...", 0, 0, true)
            is State.Downloading -> h.showCameraDownload(
                ctx, "Downloading known cameras \u00b7 ${s.done}/${s.total} areas",
                "${s.detail.replaceFirstChar { it.uppercase() }}\n${s.found} cameras so far \u00b7 you can keep using your phone",
                s.done, s.total, true
            )
            is State.Done -> h.showCameraDownload(
                ctx, "Known cameras ready",
                "${s.cameras} plate, speed and red-light cameras saved within ~${s.radiusKm} km - they work offline." +
                    (if (s.failedAreas > 0) " ${s.failedAreas} area(s) failed; the map will fetch them when you look there." else ""),
                0, 0, false
            )
            is State.Failed -> h.showCameraDownload(ctx, "Couldn't download known cameras", s.reason, 0, 0, false)
        }
    }

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        appContext = app
        job = scope.launch {
            set(State.Locating)
            val here = currentLocation(app)
            if (here == null) { set(State.Failed("No location yet - try again outdoors, or open the map later")); return@launch }
            val radius = com.rfsentinel.app.util.Prefs.cameraRadiusKm(app)
            set(State.Downloading(0, 4, detail = "starting"))
            try {
                var failed = 0
                val n = AlprStore.downloadAround(app, here.latitude, here.longitude, radius.toDouble()) { p ->
                    failed = p.failed
                    set(State.Downloading(p.done, p.total, p.found, p.detail))
                }
                set(State.Done(n, failed, radius))
                // A running scan starts watching for the new cameras.
                if (ScanForegroundService.isRunning) runCatching {
                    app.startService(Intent(app, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_REFRESH_LOCATION))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                set(State.Failed(e.message ?: "OpenStreetMap servers busy - try again later"))
            }
        }
    }

    /** A recent last-known position, or a fresh fix (up to 30 s); null without permission or fix. */
    @SuppressLint("MissingPermission") // checked by canRun()
    private suspend fun currentLocation(context: Context): Location? {
        if (!canRun(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) return last
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return last
        val fresh = withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { cont ->
                val cancel = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        return fresh ?: last
    }
}
