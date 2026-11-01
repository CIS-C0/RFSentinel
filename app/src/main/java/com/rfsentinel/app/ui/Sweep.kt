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

    /** How long you faced each sector since a reading was last heard there. */
    private val silent = LongArray(SECTORS)
    private var facedAt = 0L
    private var facedSector = -1

    /** A new signal reading [rssi] heard while facing [heading] (compass degrees). */
    fun add(now: Long, heading: Float, rssi: Int) {
        val h = (heading % 360f + 360f) % 360f
        val i = sector(h)
        readings.addLast(Reading(now, h, rssi))
        silent[i] = 0L
        // Each sector keeps its newest few: standing still facing one way doesn't push the others out.
        if (readings.count { sector(it.heading) == i } > PER_SECTOR) readings.removeAt(readings.indexOfFirst { sector(it.heading) == i })
        while (readings.isNotEmpty() && now - readings.first().time > WINDOW_MS) readings.removeFirst()
    }

    /** The way you face now, often (between readings too): facing a way long with nothing heard is your body's shadow. */
    fun face(now: Long, heading: Float) {
        val i = sector((heading % 360f + 360f) % 360f)
        if (i == facedSector && facedAt > 0) silent[i] += now - facedAt
        facedSector = i; facedAt = now
    }

    fun clear() { readings.clear(); candidate = null; silent.fill(0L); facedAt = 0L; facedSector = -1 }

    private fun sector(heading: Float) = (heading / SECTOR_DEG).toInt().coerceIn(0, SECTORS - 1)

    /**
     * Sectors of the turn covered in the last [WINDOW_MS] (out of [SECTORS]): those with a reading, and a single
     * one skipped between two with readings (a quicker turn passes a sector between two packets).
     */
    fun covered(now: Long) = covered(sectors(now))

    private fun covered(m: Array<Float?>) = m.indices.count { i ->
        m[i] != null || (m[(i + SECTORS - 1) % SECTORS] != null && m[(i + 1) % SECTORS] != null)
    }

    /**
     * The signal (dBm) facing each sector, null where you haven't faced yet. Sector i is centred on [centre]. The
     * median heard there (one deep multipath fade or one lucky packet doesn't swing it); a sector faced
     * [SILENT_MS] with nothing heard counts as [SILENT_DB] under the weakest heard: your back is to it.
     */
    fun sectors(now: Long): Array<Float?> {
        val m = heard(now)
        val weakest = m.filterNotNull().minOrNull() ?: return m
        val quiet = silentMs()
        return Array(SECTORS) { i -> m[i] ?: if (silent[i] >= quiet) weakest - SILENT_DB else null }
    }

    /** Silence counts after [SILENT_MS], or twice the device's usual gap between packets if it sends slowly. */
    private fun silentMs(): Long {
        if (readings.size < 3) return SILENT_MS
        val gaps = readings.zipWithNext { a, b -> b.time - a.time }.sorted()
        return maxOf(SILENT_MS, 2 * gaps[gaps.size / 2])
    }

    private fun heard(now: Long): Array<Float?> {
        val by = Array(SECTORS) { ArrayList<Int>() }
        for (r in readings) if (now - r.time <= WINDOW_MS) by[sector(r.heading)] += r.rssi
        return Array(SECTORS) { i -> by[i].takeIf { it.isNotEmpty() }?.sorted()?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2f } }
    }

    /**
     * Fits one rise and fall over the turn (the first harmonic of the signal against the heading, least squares
     * over the sectors heard, so skipped ones don't tilt it): its peak is the direction. Null until [MIN_SECTORS]
     * are covered, [MIN_HEARD] of them heard.
     */
    fun estimate(now: Long): Estimate? {
        val m = sectors(now)
        val k = m.indices.filter { m[it] != null }
        val covered = covered(m)
        if (covered < MIN_SECTORS || heard(now).count { it != null } < MIN_HEARD) return null
        // Normal equations for m = c + a cos t + b sin t, the stronger side weighing more: facing the device the
        // signal is clean, behind you it's lost packets and reflections.
        val lo = k.minOf { m[it]!! }; val span = (k.maxOf { m[it]!! } - lo).coerceAtLeast(1f)
        val n = Array(3) { DoubleArray(4) }
        for (i in k) {
            val t = Math.toRadians(centre(i).toDouble())
            val f = doubleArrayOf(1.0, cos(t), sin(t))
            val w = 1.0 + FRONT_WEIGHT * (m[i]!! - lo) / span
            for (r in 0..2) { for (c in 0..2) n[r][c] += w * f[r] * f[c]; n[r][3] += w * f[r] * m[i]!! }
        }
        val (mean, a, b) = solve3(n) ?: return null
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
        val nullFactor = if (behind != null && behind <= weakest + NULL_TOLERANCE_DB) 1f else 0.75f
        val confidence = covered.toFloat() / SECTORS * (amp / (amp + rest + NOISE_DB)).toFloat() * nullFactor
        return Estimate(bearing, confidence.coerceIn(0f, 1f), (2 * amp).toFloat())
    }

    /** Gaussian elimination of a 3 x 3 system (augmented); null when singular. */
    private fun solve3(n: Array<DoubleArray>): DoubleArray? {
        for (c in 0..2) {
            val p = (c..2).maxBy { abs(n[it][c]) }
            if (abs(n[p][c]) < 1e-9) return null
            n[c] = n[p].also { n[p] = n[c] }
            for (r in 0..2) if (r != c) {
                val f = n[r][c] / n[c][c]
                for (j in c..3) n[r][j] -= f * n[c][j]
            }
        }
        return DoubleArray(3) { n[it][3] / n[it][it] }
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
        /** Sectors with their own reading among those: the rest bridged single gaps. */
        const val MIN_HEARD = 7
        /** Readings older than this drop out (walking somewhere new clears the sweep too: [LiveRead], [RadarTrack]). */
        const val WINDOW_MS = 120_000L
        private const val PER_SECTOR = 6
        /** Faced this long with nothing heard (at least; see [silentMs]): the shadow, this much under the weakest heard. */
        const val SILENT_MS = 1_500L
        private const val SILENT_DB = 3f
        /** The strongest sector weighs this much more than the weakest in the fit. */
        private const val FRONT_WEIGHT = 2.0
        /** Locked once this sure, and the body shadow shows by at least [LOCK_MIN_DB]. */
        const val LOCK_CONFIDENCE = 0.6f
        const val LOCK_MIN_DB = 4f
        /** Signal readings jump about this much on their own; keeps a tiny wave on a quiet signal from looking sure. */
        private const val NOISE_DB = 0.5
        /** The null counts as behind you within this of the opposite side, and within this many dB of the weakest. */
        private const val NULL_SLACK_DEG = 60f
        private const val NULL_TOLERANCE_DB = 1f
        const val SETTLE_DEG = 20f
        const val SETTLE_MS = 1_000L

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
