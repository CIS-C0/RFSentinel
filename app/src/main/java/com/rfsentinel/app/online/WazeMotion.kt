package com.rfsentinel.app.online

import com.rfsentinel.app.service.DeviceRegistry

/**
 * Tells whether the phone is parked, moving or on a fast road, from the position it is fed about
 * once a second. "Parked" means it has stayed within [parkedRadiusM] of one spot for [parkedAfterMs]
 * (GPS wander while standing still is smaller than that).
 */
class MotionTracker(
    private val parkedRadiusM: Double = 60.0,
    private val parkedAfterMs: Long = 90_000L,
    private val fastMps: Double = 22.0 // about 80 km/h
) {
    enum class State { PARKED, MOVING, FAST }

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorSince = -1L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAt = -1L
    private var speedMps = 0.0

    /** [speed] is the fix's own speed in m/s when it has one; otherwise it is worked out from the last two positions. */
    fun update(lat: Double, lon: Double, speed: Float?, now: Long) {
        if (anchorSince < 0 || DeviceRegistry.metersBetween(anchorLat, anchorLon, lat, lon) > parkedRadiusM) {
            anchorLat = lat; anchorLon = lon; anchorSince = now
        }
        speedMps = when {
            speed != null && speed >= 0f -> speed.toDouble()
            lastAt >= 0 && now - lastAt >= 1_000 -> DeviceRegistry.metersBetween(lastLat, lastLon, lat, lon) / ((now - lastAt) / 1000.0)
            else -> speedMps
        }
        lastLat = lat; lastLon = lon; lastAt = now
    }

    /** Before the first position it acts as if driving, so nothing is slowed down on a guess. */
    fun state(now: Long): State = when {
        anchorSince < 0 -> State.MOVING
        now - anchorSince >= parkedAfterMs -> State.PARKED
        speedMps >= fastMps -> State.FAST
        else -> State.MOVING
    }
}

/** How often Waze is asked, and how that adapts to how the phone is moving. */
object WazeTiming {
    /** The seconds each backend's slider steps through. */
    val DIRECT_STEPS_S = intArrayOf(15, 30, 60, 90, 120, 300)
    /** OpenWeb Ninja bills every check, so it never goes faster than 2 minutes. */
    val NINJA_STEPS_S = intArrayOf(120, 180, 240, 300, 600)
    const val DIRECT_DEFAULT_S = 60
    const val NINJA_DEFAULT_S = 240

    fun steps(direct: Boolean) = if (direct) DIRECT_STEPS_S else NINJA_STEPS_S

    /** Fewest seconds between two checks when one is asked for by hand ("Check now"). */
    fun minGapS(direct: Boolean) = if (direct) 10 else 60

    /**
     * Whether a check is due: the very first one ([lastStart] 0), one whose interval has run out, or one
     * asked for by hand ([manual]) that is not too soon after the last.
     */
    fun checkDue(now: Long, lastStart: Long, everyS: Int, manual: Boolean, minGapS: Int): Boolean =
        lastStart == 0L || now - lastStart >= everyS * 1000L || (manual && now - lastStart >= minGapS * 1000L)

    /**
     * The seconds to wait before the next check. Parked: four times as long (never slower than the
     * slowest step). On a fast road: half as long, but only for Waze direct (free; OpenWeb Ninja
     * would bill twice as much). Otherwise the chosen interval.
     */
    fun effectiveS(baseS: Int, state: MotionTracker.State, direct: Boolean, adapt: Boolean): Int {
        if (!adapt) return baseS
        val steps = steps(direct)
        return when (state) {
            MotionTracker.State.PARKED -> minOf(baseS * 4, maxOf(steps.last(), baseS))
            MotionTracker.State.FAST -> if (direct) maxOf(baseS / 2, steps.first()) else baseS
            MotionTracker.State.MOVING -> baseS
        }
    }
}

/**
 * Whether each report is getting closer or further, from its distance over the last few seconds.
 * Needs about 4 s of history and a change of 15 m or more; anything less is "steady".
 */
class WazeTrend {
    enum class Trend { CLOSING, AWAY, STEADY }

    private class Sample(val at: Long, val distM: Double)

    private val history = HashMap<String, ArrayDeque<Sample>>()

    fun update(id: String, distM: Double, now: Long) {
        val h = history.getOrPut(id) { ArrayDeque() }
        h.addLast(Sample(now, distM))
        while (h.size > 1 && now - h.first().at > WINDOW_MS) h.removeFirst()
    }

    fun trend(id: String): Trend {
        val h = history[id] ?: return Trend.STEADY
        if (h.size < 2) return Trend.STEADY
        val first = h.first()
        val last = h.last()
        if (last.at - first.at < MIN_SPAN_MS) return Trend.STEADY
        val delta = last.distM - first.distM
        return when {
            delta <= -MIN_CHANGE_M -> Trend.CLOSING
            delta >= MIN_CHANGE_M -> Trend.AWAY
            else -> Trend.STEADY
        }
    }

    /** Forgets reports that are no longer around. */
    fun keepOnly(ids: Set<String>) { history.keys.retainAll(ids) }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val MIN_SPAN_MS = 4_000L
        const val MIN_CHANGE_M = 15.0
    }
}
