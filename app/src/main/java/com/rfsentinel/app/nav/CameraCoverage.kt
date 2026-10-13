package com.rfsentinel.app.nav

import android.content.Context
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The camera map along a route, for [CameraAvoid]: the cameras of the area it crosses are fetched when they
 * are not on the phone yet (as the map does for the area you look at - the same setting, "Download cameras
 * automatically", decides), a little at a time so a busy server doesn't hold the plan up.
 */
object CameraCoverage {

    /** The longest the camera downloads may hold up a route plan. */
    private const val WAIT_MS = 30_000L

    /** Fetches what is missing along [route] (if allowed) and says whether the camera map now covers all of it. */
    suspend fun ensure(context: Context, route: Route): Boolean {
        val boxes = CameraAvoid.boxes(route)
        fun missing() = boxes.any { AlprStore.needsDownload(it[0], it[1], it[2], it[3]) }
        if (!missing()) return true
        if (!Prefs.autoCameras(context)) return false
        withTimeoutOrNull(WAIT_MS) {
            for (b in boxes) if (AlprStore.needsDownload(b[0], b[1], b[2], b[3])) AlprStore.autoDownload(context, b[0], b[1], b[2], b[3])
        }
        return !missing()
    }
}
