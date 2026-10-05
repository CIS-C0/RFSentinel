package com.rfsentinel.app.online

import android.content.Context
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.IgnoredCameras
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs

/**
 * Threats that aren't a device in the list: the nearest known camera (scored by
 * distance), a police aircraft overhead and a Waze police report. The threat headline
 * (phone banner, car screens) shows the strongest of these when it outranks the devices.
 */
object AmbientThreats {

    data class Threat(val score: Int, val label: String, val category: Category)

    /** Latest from the online sources, with when it was seen. */
    @Volatile var aircraft: Pair<Long, Threat>? = null
    @Volatile var waze: Pair<Long, Threat>? = null

    private const val ONLINE_TTL_MS = 3 * 60_000L
    private const val WAZE_TTL_MS = 6 * 60_000L

    fun clear() {
        aircraft = null
        waze = null
    }

    /** The strongest current ambient threat scoring 40 or more, if any. */
    fun top(context: Context, now: Long = System.currentTimeMillis()): Threat? = listOfNotNull(
        camera(context),
        aircraft?.takeIf { now - it.first < ONLINE_TTL_MS }?.second,
        waze?.takeIf { now - it.first < WAZE_TTL_MS }?.second
    ).filter { it.score >= 40 }.maxByOrNull { it.score }

    private fun camera(context: Context): Threat? {
        if (!ScanForegroundService.isRunning || !Prefs.categoryEnabled(context, Category.ALPR)) return null
        val loc = ScanForegroundService.lastFix ?: return null
        val cams = AlprStore.cameras.takeIf { it.isNotEmpty() } ?: return null
        val (cam, d) = KnownCameras.near(cams, loc.latitude, loc.longitude, 500.0).firstOrNull { (c, _) ->
            val wanted = if (c.type == KnownCamera.Kind.ALPR) Prefs.knownAlprAlerts(context) else Prefs.speedCameraAlerts(context)
            wanted && !IgnoredCameras.contains(context, c.osmId)
        } ?: return null
        val score = KnownCameras.proximityScore(d)
        return Threat(score, "${cam.label} ${(d / 10).toInt() * 10} m away", Category.ALPR)
    }
}
