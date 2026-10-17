package com.rfsentinel.app.alpr

import android.content.Context

/**
 * Which way a known camera faces, as the user set it (tap a camera > Set direction) for cameras
 * whose direction isn't mapped in OpenStreetMap. Kept on this phone only, by OSM id; a mapped
 * direction always wins. Used for the map's cone and for the camera-ahead warnings.
 */
object CameraDirections {

    private const val PREFS = "camera_directions"
    private const val KEY = "dirs"

    @Volatile private var dirs: Map<String, Int> = emptyMap()
    @Volatile private var loaded = false

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            dirs = parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null).orEmpty())
            loaded = true
        }
    }

    internal fun parse(set: Set<String>): Map<String, Int> = set.mapNotNull { e ->
        val id = e.substringBeforeLast('=', "").ifEmpty { return@mapNotNull null }
        val deg = e.substringAfterLast('=').toIntOrNull()?.takeIf { it in 0..359 } ?: return@mapNotNull null
        id to deg
    }.toMap()

    /** The direction [cam] faces: mapped, else set by the user, else null. */
    fun of(context: Context, cam: KnownCamera): Int? = cam.direction ?: run { ensure(context); dirs[cam.osmId] }

    /** The user's own direction for [osmId], if any. */
    fun userSet(context: Context, osmId: String): Int? { ensure(context); return dirs[osmId] }

    /** Sets (0-359) or clears (null) the user's direction for [osmId]. */
    @Synchronized
    fun set(context: Context, osmId: String, deg: Int?) {
        ensure(context)
        dirs = if (deg == null) dirs - osmId else dirs + (osmId to ((deg % 360) + 360) % 360)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, dirs.map { (k, v) -> "$k=$v" }.toSet()).apply()
    }
}
