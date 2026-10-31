package com.rfsentinel.app.ui

import kotlin.math.roundToInt

/**
 * On-foot direction finding while Locate runs: hold the phone flat in front of you and turn slowly. Your body
 * blocks the signal from behind, so the device is heard strongest when you face it. Readings go into
 * [SECTORS] compass sectors; once most of a turn is covered, the strongest sector is the direction.
 * Pure (unit-tested): the compass and signal readings come from [LiveRead].
 */
class Sweep {

    private class Reading(val time: Long, val heading: Float, val rssi: Int)
    private val readings = ArrayDeque<Reading>()

    /** A new signal reading [rssi] heard while facing [heading] (compass degrees). */
    fun add(now: Long, heading: Float, rssi: Int) {
        readings.addLast(Reading(now, (heading % 360f + 360f) % 360f, rssi))
        while (readings.isNotEmpty() && now - readings.first().time > WINDOW_MS) readings.removeFirst()
    }

    fun clear() = readings.clear()

    /** Sectors with a reading in the last [WINDOW_MS] (out of [SECTORS]). */
    fun covered(now: Long) = strongest(now).count { it != null }

    /** Compass degrees of the strongest sector's centre, or null until a turn is covered and one sector stands out. */
    fun direction(now: Long): Float? {
        val best = strongest(now)
        val heard = best.filterNotNull()
        if (heard.size < MIN_SECTORS) return null
        val peak = heard.max()
        if (peak - heard.sorted()[heard.size / 2] < MIN_LEAD_DB) return null
        return best.indexOf(peak) * SECTOR_DEG + SECTOR_DEG / 2
    }

    private fun strongest(now: Long): Array<Int?> {
        val best = arrayOfNulls<Int>(SECTORS)
        for (r in readings) if (now - r.time <= WINDOW_MS) {
            val i = (r.heading / SECTOR_DEG).toInt().coerceIn(0, SECTORS - 1)
            best[i] = maxOf(best[i] ?: Int.MIN_VALUE, r.rssi)
        }
        return best
    }

    companion object {
        const val SECTORS = 12
        const val SECTOR_DEG = 360f / SECTORS
        /** A turn covers this many sectors (240°) before a direction is given. */
        const val MIN_SECTORS = 8
        /** The strongest sector must beat the typical one by this much, else the body shadow didn't show. */
        const val MIN_LEAD_DB = 4
        /** Readings older than this drop out, so walking somewhere new starts a fresh sweep. */
        const val WINDOW_MS = 30_000L

        private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun compassPoint(deg: Float) = POINTS[(((deg % 360f + 360f) % 360f) / 45f).roundToInt() % 8]

        /** Degrees to turn from [heading] to face [target]: positive right, negative left (-180..180). */
        fun turn(heading: Float, target: Float): Int = (((target - heading) % 360f + 540f) % 360f - 180f).roundToInt()
    }
}
