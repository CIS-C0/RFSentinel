package com.rfsentinel.app.ui

import kotlin.math.abs

/**
 * Where the live map first looks. Waiting for the phone's own GPS fix left it on (0, 0) at street zoom,
 * which is open sea, until the first fix arrived a few seconds later. With a position already known it opens
 * on your area at once, and the tiles seen there last time come straight from the cache.
 */
object MapStart {
    const val DEFAULT_ZOOM = 16.0
    /** Knowing nothing about where you are, a world view (a few cached tiles) beats zooming in on open sea. */
    const val WORLD_ZOOM = 3.0
    /** A fix this young is where you are, not just where you were. */
    const val FRESH_MS = 2 * 60_000L

    /** A position and when it was taken (epoch milliseconds). */
    data class Fix(val lat: Double, val lon: Double, val timeMs: Long)

    /** Where to centre, how far in, and whether the position is current (then the map need not move again). */
    data class View(val lat: Double, val lon: Double, val zoom: Double, val fresh: Boolean)

    /**
     * The newest of the known [fixes] (the running scan's, the system's last known), else the place the map
     * last looked ([saved], remembered only with GPS tagging on). [savedZoom] is reused when it is a
     * sensible street-to-city zoom; null when nothing at all is known.
     */
    fun pick(fixes: List<Fix?>, saved: Pair<Double, Double>?, savedZoom: Double?, now: Long): View? {
        val zoom = savedZoom?.takeIf { it in 11.0..19.0 } ?: DEFAULT_ZOOM
        val newest = fixes.filterNotNull().filter { !isOrigin(it.lat, it.lon) }.maxByOrNull { it.timeMs }
        if (newest != null) return View(newest.lat, newest.lon, zoom, fresh = now - newest.timeMs <= FRESH_MS)
        if (saved != null && !isOrigin(saved.first, saved.second)) return View(saved.first, saved.second, zoom, fresh = false)
        return null
    }

    /** (0, 0) is what a missing position looks like; it is never a real place to start from. */
    private fun isOrigin(lat: Double, lon: Double) = abs(lat) < 1e-4 && abs(lon) < 1e-4
}
