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
 * plate, speed and red-light cameras within ~100 km, so they're on the map and
 * warn you offline from the start. Runs in the background - leaving the screen
 * that started it doesn't stop it. Only the area is sent, never your scans.
 */
object CameraPrefetch {

    sealed interface State {
        data object Idle : State
        data object Locating : State
        data class Downloading(val done: Int, val total: Int) : State
        data class Done(val cameras: Int) : State
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

    private fun set(s: State) = main.post { state = s; listeners.toList().forEach { it(s) } }

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            set(State.Locating)
            val here = currentLocation(app)
            if (here == null) { set(State.Failed("No location yet - try again outdoors, or open the map later")); return@launch }
            set(State.Downloading(0, 4))
            try {
                val n = AlprStore.downloadAround(app, here.latitude, here.longitude) { done, total ->
                    set(State.Downloading(done, total))
                }
                set(State.Done(n))
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
