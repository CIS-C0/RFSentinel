package com.rfsentinel.app.service

/**
 * The follow check for a device that keeps turning up rather than staying in range (after
 * Chasing Your Tail's multi-location tracking): a flagged device heard at [MIN_PLACES] or more
 * separate places along your way within [WINDOW_MS], even with gaps between, is following you
 * as surely as one that never left. [DeviceRegistry.checkFollowing] covers the one that stays.
 *
 * A place is where *you* were when it was heard, so a fixed camera you pass by twice is one place.
 * Kept in memory only, for flagged devices, and forgotten after [WINDOW_MS].
 */
class PlaceFollow {

    private class Visit(val at: Long, val lat: Double, val lon: Double)

    private val visits = HashMap<String, ArrayDeque<Visit>>()
    /** When each device's follow alert was given: once per [WINDOW_MS], however often it drops out and returns. */
    private val alerted = HashMap<String, Long>()

    /** [mac] (flagged) was heard with you at [lat], [lon]; [previous] is its address before a rotation. */
    fun heard(mac: String, previous: String?, now: Long, lat: Double, lon: Double) {
        if (previous != null && previous != mac) {
            visits.remove(previous)?.let { old -> visits.getOrPut(mac) { ArrayDeque() }.addAll(0, old) }
            alerted.remove(previous)?.let { alerted[mac] = it }
        }
        val list = visits.getOrPut(mac) { ArrayDeque() }
        val last = list.lastOrNull()
        if (last != null && now - last.at < SAMPLE_MS) return
        list.addLast(Visit(now, lat, lon))
        while (list.isNotEmpty() && now - list.first().at > WINDOW_MS) list.removeFirst()
        if (visits.size > MAX_DEVICES) {
            visits.entries.removeIf { now - (it.value.lastOrNull()?.at ?: 0L) > WINDOW_MS }
            if (visits.size > MAX_DEVICES) visits.remove(visits.keys.first())
            alerted.entries.removeIf { now - it.value > WINDOW_MS }
        }
    }

    /**
     * How many separate places [mac] was heard at, when that makes it a follower: at least
     * [MIN_PLACES], spread over [minDurationMs] and [minDistanceM] (the follow settings). Else 0.
     */
    fun places(mac: String, minDurationMs: Long, minDistanceM: Double): Int {
        val list = visits[mac] ?: return 0
        if (list.size < MIN_PLACES || list.last().at - list.first().at < minDurationMs) return 0
        val places = ArrayList<Visit>()
        for (v in list) if (places.none { metres(it, v) < PLACE_RADIUS_M }) places += v
        if (places.size < MIN_PLACES) return 0
        val spread = places.maxOf { a -> places.maxOf { b -> metres(a, b) } }
        return if (spread >= minDistanceM) places.size else 0
    }

    /** True once per [WINDOW_MS] when [mac] is a follower by [places]: time to alert. */
    fun follower(mac: String, now: Long, minDurationMs: Long, minDistanceM: Double): Boolean {
        if (alerted[mac]?.let { now - it < WINDOW_MS } == true) return false
        if (places(mac, minDurationMs, minDistanceM) == 0) return false
        alerted[mac] = now
        return true
    }

    /** The follow alert for [mac] was given another way (it stayed in range): don't repeat it. */
    fun alertedNow(mac: String, now: Long) { alerted[mac] = now }

    private fun metres(a: Visit, b: Visit): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return r * Math.sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        const val MIN_PLACES = 3
        const val PLACE_RADIUS_M = 300.0
        const val WINDOW_MS = 2 * 60 * 60_000L
        /** At most one visit a minute per device. */
        private const val SAMPLE_MS = 60_000L
        private const val MAX_DEVICES = 500
    }
}
