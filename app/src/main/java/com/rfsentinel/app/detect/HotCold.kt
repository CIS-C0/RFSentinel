package com.rfsentinel.app.detect

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Locate's memory of a hunt on foot, the way a metal detector is used: the strongest the device has been heard
 * and where you stood then. Fades only ever weaken a signal, so the peaks are what says where it is. A reading that
 * beats the strongest so far is a "warmer" ping; once the signal has dropped well under it and you have walked on,
 * you passed the device. Pure, unit-tested.
 */
class HotCold {
    private val recent = ArrayDeque<Int>()
    private var readings = 0
    /** The level now: the middle of the last three readings, so one lucky or faded packet doesn't move it. */
    var level: Int? = null
        private set
    /** The strongest [level] so far. */
    var best: Int? = null
        private set
    private var bestX = 0.0
    private var bestY = 0.0
    private var bestPlaced = false

    /**
     * A raw reading heard at [x], [y] (metres; null while your position isn't known). True when it is the strongest
     * so far by [STEP_DB] or more: getting warmer.
     */
    fun add(rssi: Int, x: Double?, y: Double?): Boolean {
        recent.addLast(rssi)
        if (recent.size > 3) recent.removeFirst()
        val now = recent.sorted()[recent.size / 2]
        level = now
        readings++
        val before = best
        if (before != null && now < before + STEP_DB) return false
        best = now
        bestPlaced = x != null && y != null
        if (x != null && y != null) { bestX = x; bestY = y }
        // The first readings only set the mark.
        return before != null && readings > WARM_UP
    }

    /** Positions from before can't be compared with positions now (the step tracker started over). */
    fun lostPlace() { bestPlaced = false }

    /**
     * Metres back to where it was strongest, once the signal is [DROP_DB] under that and you have walked [MIN_M]
     * on: you passed it. Null otherwise.
     */
    fun passedM(x: Double?, y: Double?): Int? {
        val b = best ?: return null
        val l = level ?: return null
        if (!bestPlaced || x == null || y == null || b - l < DROP_DB) return null
        val d = hypot(x - bestX, y - bestY)
        return if (d >= MIN_M) d.roundToInt() else null
    }

    companion object {
        /** A new strongest counts from this much over the last one: about what a step closer gives up close. */
        const val STEP_DB = 2
        /** Body, pocket and multipath fades reach 6 dB: under the strongest by more than that is distance. */
        const val DROP_DB = 8
        const val MIN_M = 3.0
        private const val WARM_UP = 3
    }
}
