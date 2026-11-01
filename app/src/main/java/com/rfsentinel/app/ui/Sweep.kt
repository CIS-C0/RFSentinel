package com.rfsentinel.app.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-foot direction finding while Locate runs: hold the phone against your chest and turn slowly on the spot.
 * Your body blocks the signal from behind, so over one full turn the signal rises once (facing the device) and
 * falls once (your back to it). Readings go into [SECTORS] compass sectors; [estimate] fits that one rise and fall
 * and gives the direction with a confidence. Pure (unit-tested): the heading (gyro + magnetometer, [Compass]) and
 * signal readings come from [LiveRead].
 */
class Sweep {

    /**
     * [bearing]: compass degrees the device most likely is; [confidence] 0..1 (how much of the turn is covered and
     * how well one rise and fall explains the readings); [frontBackDb]: how much stronger facing it than turned away.
     */
    data class Estimate(val bearing: Float, val confidence: Float, val frontBackDb: Float)

    private class Reading(val time: Long, val heading: Float, val rssi: Int)
    private val readings = ArrayDeque<Reading>()

    /** A new signal reading [rssi] heard while facing [heading] (compass degrees). */
    fun add(now: Long, heading: Float, rssi: Int) {
        readings.addLast(Reading(now, (heading % 360f + 360f) % 360f, rssi))
        while (readings.isNotEmpty() && now - readings.first().time > WINDOW_MS) readings.removeFirst()
    }

    fun clear() { readings.clear(); candidate = null }

    /** Sectors with a reading in the last [WINDOW_MS] (out of [SECTORS]). */
    fun covered(now: Long) = sectors(now).count { it != null }

    /**
     * The median signal (dBm) heard facing each sector, null where you haven't faced yet. Sector i is centred on
     * [centre]. The median, not the mean: one deep multipath fade or one lucky packet doesn't swing a sector.
     */
    fun sectors(now: Long): Array<Float?> {
        val by = Array(SECTORS) { ArrayList<Int>() }
        for (r in readings) if (now - r.time <= WINDOW_MS) by[(r.heading / SECTOR_DEG).toInt().coerceIn(0, SECTORS - 1)] += r.rssi
        return Array(SECTORS) { i -> by[i].takeIf { it.isNotEmpty() }?.sorted()?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2f } }
    }

    /**
     * Fits one rise and fall over the turn (the first harmonic of the signal against the heading): its peak is the
     * direction. Null until [MIN_SECTORS] are covered.
     */
    fun estimate(now: Long): Estimate? {
        val m = sectors(now)
        val k = m.indices.filter { m[it] != null }
        if (k.size < MIN_SECTORS) return null
        val mean = k.sumOf { m[it]!!.toDouble() } / k.size
        var a = 0.0; var b = 0.0
        for (i in k) {
            val t = Math.toRadians(centre(i).toDouble())
            val d = m[i]!! - mean
            a += d * cos(t); b += d * sin(t)
        }
        a *= 2.0 / k.size; b *= 2.0 / k.size
        val amp = hypot(a, b)
        val peak = atan2(b, a)
        // What the one rise and fall leaves unexplained: fading and reflections.
        val rest = sqrt(k.sumOf { i ->
            val r = m[i]!! - mean - amp * cos(Math.toRadians(centre(i).toDouble()) - peak); r * r
        } / k.size)
        val bearing = ((Math.toDegrees(peak) % 360 + 360) % 360).toFloat()
        // Body fade (as fox hunters use it): the sharp null is behind you. The weakest reading anywhere else is a
        // reflection or a fade, so the rise and fall it fitted is less sure.
        val weakest = k.minOf { m[it]!! }
        val behind = k.filter { abs(turn(bearing + 180f, centre(it))) <= NULL_SLACK_DEG }.minOfOrNull { m[it]!! }
        val nullFactor = if (behind != null && behind <= weakest + NULL_TOLERANCE_DB) 1f else 0.5f
        val confidence = k.size.toFloat() / SECTORS * (amp / (amp + rest + NOISE_DB)).toFloat() * nullFactor
        return Estimate(bearing, confidence.coerceIn(0f, 1f), (2 * amp).toFloat())
    }

    private var candidate: Estimate? = null
    private var candidateAt = 0L

    /**
     * An estimate good enough to keep: it [locks] and has stayed within [SETTLE_DEG] for [SETTLE_MS] of new
     * readings (one lucky reading at the end of a turn can't lock a wrong side). Null until then.
     */
    fun settled(now: Long): Estimate? {
        val e = estimate(now)?.takeIf { locks(it) } ?: run { candidate = null; return null }
        val c = candidate
        if (c == null || abs(turn(c.bearing, e.bearing)) > SETTLE_DEG) { candidate = e; candidateAt = now; return null }
        return if (now - candidateAt >= SETTLE_MS) e else null
    }

    companion object {
        const val SECTORS = 12
        const val SECTOR_DEG = 360f / SECTORS
        /** A turn covers this many sectors (300°) before a direction is given. */
        const val MIN_SECTORS = 10
        /** Readings older than this drop out, so walking somewhere new starts a fresh sweep. */
        const val WINDOW_MS = 45_000L
        /** Locked once this sure, and the body shadow shows by at least [LOCK_MIN_DB]. */
        const val LOCK_CONFIDENCE = 0.6f
        const val LOCK_MIN_DB = 4f
        /** Signal readings jump about this much on their own; keeps a tiny wave on a quiet signal from looking sure. */
        private const val NOISE_DB = 0.5
        /** The null counts as behind you within this of the opposite side, and within this many dB of the weakest. */
        private const val NULL_SLACK_DEG = 60f
        private const val NULL_TOLERANCE_DB = 1f
        const val SETTLE_DEG = 20f
        const val SETTLE_MS = 1_500L

        fun centre(sector: Int) = sector * SECTOR_DEG + SECTOR_DEG / 2

        /** Good enough to lock on and keep. */
        fun locks(e: Estimate) = e.confidence >= LOCK_CONFIDENCE && e.frontBackDb >= LOCK_MIN_DB

        private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun compassPoint(deg: Float) = POINTS[(((deg % 360f + 360f) % 360f) / 45f).roundToInt() % 8]

        /**
         * The way you faced at [t], between two compass reads ([t0], [h0]) and ([t1], [h1]): a reading is picked up
         * to a sampling tick after it was heard, and a turn moves on meanwhile.
         */
        fun headingAt(t: Long, t0: Long, h0: Float, t1: Long, h1: Float): Float {
            if (t1 <= t0) return h1
            val f = ((t - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)
            return h0 + (((h1 - h0) % 360f + 540f) % 360f - 180f) * f
        }

        /** Degrees to turn from [heading] to face [target]: positive right, negative left (-180..180). */
        fun turn(heading: Float, target: Float): Int = (((target - heading) % 360f + 540f) % 360f - 180f).roundToInt()
    }
}
