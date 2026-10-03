package com.rfsentinel.app.alpr

import android.content.Context

/**
 * Known cameras the user silenced from the map (tap a camera > Ignore alerts),
 * by OpenStreetMap id. Kept until the user turns the camera back on (or clears
 * the list in Settings) - they still show on the map, faded.
 */
object IgnoredCameras {

    private const val PREFS = "ignored_cameras"
    private const val KEY = "osm_ids"

    @Volatile private var ids: Set<String> = emptySet()
    @Volatile private var loaded = false

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            ids = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null).orEmpty().toSet()
            loaded = true
        }
    }

    fun load(context: Context) = ensure(context)

    fun contains(context: Context, osmId: String): Boolean {
        ensure(context)
        return osmId in ids
    }

    @Synchronized
    fun set(context: Context, osmId: String, ignored: Boolean) {
        ensure(context)
        ids = if (ignored) ids + osmId else ids - osmId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, ids).apply()
    }

    fun count(context: Context): Int {
        ensure(context)
        return ids.size
    }

    @Synchronized
    fun clear(context: Context) {
        ensure(context)
        ids = emptySet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
