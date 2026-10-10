package com.rfsentinel.app.detect

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pinpoints a stationary device from signal readings taken at many spots as you move past it
 * (RSSI multilateration, the way wardriving tools place access points).
 *
 * Model: rssi = P0 - 10 n log10(d). The device's power at 1 m (P0) is unknown and fitted for
 * every candidate spot, within what real radios can do, so a far device can't pass for a
 * loud near one. Every spot on a grid around your route is scored, and the result is the
 * best-fitting spot with a 95 % circle - so when you only drove a straight line and
 * the device could be on either side of the road, the circle honestly covers both sides
 * instead of guessing one.
 *
 * Pure (no Android), unit-tested.
 */
object Locator {

    /** One reading: where your phone was and how strong the device was there. */
    data class Reading(val time: Long, val lat: Double, val lon: Double, val rssi: Int)

    data class Estimate(
        val lat: Double,
        val lon: Double,
        /** 95 % circle: the device is very likely within this many metres. */
        val radiusM: Double,
        /** Distinct spots (about [CELL_M] m apart) the fit used. */
        val spots: Int,
        val readings: Int,
        /** How far apart the farthest two of those spots are. */
        val baselineM: Double,
        val time: Long
    )

    /** Readings closer than this are one spot; its strongest reading counts (fades only ever weaken a signal). */
    const val CELL_M = 4.0
    const val MIN_SPOTS = 4
    /** Spots must spread at least this far, else every direction fits about as well. */
    const val MIN_BASELINE_M = 15.0
    /** Readings older than this are dropped: a parked car may leave. */
    const val WINDOW_MS = 10 * 60_000L
    /** Rough signal noise (dB) from body, car and multipath fades. */
    private const val SIGMA_DB = 6.0
    /** A fit this far off means the device moved (or the model doesn't hold): no estimate. */
    private const val MAX_RMS_DB = 10.0
    private const val GRID = 90
    private const val MAX_SPOTS = 120
    /** A route whose spots spread less than this across their main direction counts as one straight line. */
    private const val STRAIGHT_M = 6.0
    /** GPS error added to every circle. */
    private const val GPS_ERROR_M = 5.0

    /** Path-loss exponent and the range of plausible powers at 1 m, per radio. */
    private class Radio(val n: Double, val p0Min: Double, val p0Max: Double, val reachM: Double)
    private val BLE = Radio(n = 2.5, p0Min = -85.0, p0Max = -35.0, reachM = 150.0)
    private val WIFI = Radio(n = 2.5, p0Min = -60.0, p0Max = -15.0, reachM = 250.0)

    fun estimate(readings: List<Reading>, wifi: Boolean, now: Long = readings.maxOfOrNull { it.time } ?: 0L): Estimate? {
        val recent = readings.filter { now - it.time <= WINDOW_MS }
        if (recent.size < MIN_SPOTS) return null
        val radio = if (wifi) WIFI else BLE
        val lat0 = recent.map { it.lat }.average()
        val lon0 = recent.map { it.lon }.average()
        val mx = 111_320.0 * cos(Math.toRadians(lat0))
        val my = 110_540.0

        // One spot per ~4 m cell: where the phone was (average) and the strongest reading there.
        class Spot(var x: Double, var y: Double, var rssi: Int, var n: Int)
        val cells = HashMap<Long, Spot>()
        for (r in recent) {
            val x = (r.lon - lon0) * mx
            val y = (r.lat - lat0) * my
            val key = ((x / CELL_M).roundToInt().toLong() shl 32) xor ((y / CELL_M).roundToInt().toLong() and 0xFFFFFFFFL)
            val s = cells[key]
            if (s == null) cells[key] = Spot(x, y, r.rssi, 1)
            else { s.x += (x - s.x) / (s.n + 1); s.y += (y - s.y) / (s.n + 1); s.n++; s.rssi = max(s.rssi, r.rssi) }
        }
        // The strongest spots say the most (and keep the fit quick on a long drive past it).
        val spots = cells.values.sortedByDescending { it.rssi }.take(MAX_SPOTS)
        if (spots.size < MIN_SPOTS) return null
        var baseline = 0.0
        for (i in spots.indices) for (j in i + 1 until spots.size)
            baseline = max(baseline, hypot(spots[i].x - spots[j].x, spots[i].y - spots[j].y))
        if (baseline < MIN_BASELINE_M) return null

        // Search everywhere the device could be heard from: the spots' box plus the radio's reach.
        val minX = spots.minOf { it.x } - radio.reachM; val maxX = spots.maxOf { it.x } + radio.reachM
        val minY = spots.minOf { it.y } - radio.reachM; val maxY = spots.maxOf { it.y } + radio.reachM
        val step = max(maxX - minX, maxY - minY) / GRID
        val xs = DoubleArray(spots.size) { spots[it].x }
        val ys = DoubleArray(spots.size) { spots[it].y }
        val rs = DoubleArray(spots.size) { spots[it].rssi.toDouble() }
        val tenN = 10 * radio.n

        // Squared error at a candidate spot, with the device's power fitted (within what radios can do).
        val logD = DoubleArray(spots.size)
        fun sseAt(gx: Double, gy: Double): Double {
            var p0 = 0.0
            for (k in xs.indices) {
                val dx = gx - xs[k]; val dy = gy - ys[k]
                logD[k] = tenN / 2 * log10(max(dx * dx + dy * dy, 1.0)) // 10 n log10(d), d at least 1 m
                p0 += rs[k] + logD[k]
            }
            p0 = (p0 / xs.size).coerceIn(radio.p0Min, radio.p0Max)
            var e = 0.0
            for (k in xs.indices) { val d = rs[k] - (p0 - logD[k]); e += d * d }
            return e
        }

        val nx = ((maxX - minX) / step).toInt() + 1
        val ny = ((maxY - minY) / step).toInt() + 1
        val sse = DoubleArray(nx * ny)
        var best = 0
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val i = iy * nx + ix
            sse[i] = sseAt(minX + ix * step, minY + iy * step)
            if (sse[i] < sse[best]) best = i
        }
        // The best spot, refined on a finer grid around the coarse one.
        var bx = minX + (best % nx) * step; var by = minY + (best / nx) * step
        var bestSse = sse[best]
        val fine = step / 8
        val cx0 = bx; val cy0 = by
        for (j in -8..8) for (i in -8..8) {
            val gx = cx0 + i * fine; val gy = cy0 + j * fine
            val e = sseAt(gx, gy)
            if (e < bestSse) { bestSse = e; bx = gx; by = gy }
        }
        val rms = sqrt(bestSse / spots.size)
        if (rms > MAX_RMS_DB) return null

        // 95 % region: every spot that fits nearly as well (chi-square, 2 degrees of freedom).
        // The noise is never taken below what the best fit itself shows.
        val sigma2 = max(SIGMA_DB * SIGMA_DB, bestSse / max(1, spots.size - 3))
        val region = sse.indices.filter { (sse[it] - bestSse) / sigma2 <= 5.99 }
        fun gx(i: Int) = minX + (i % nx) * step
        fun gy(i: Int) = minY + (i / nx) * step

        // Driving one straight line can't tell which side of it the device is: both sides fit.
        // Then the centre goes on your line and the circle covers both sides, instead of a coin toss.
        var cx = bx; var cy = by
        val mX = spots.map { it.x }.average(); val mY = spots.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (sp in spots) { val dx = sp.x - mX; val dy = sp.y - mY; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        sxx /= spots.size; syy /= spots.size; sxy /= spots.size
        val half = (sxx + syy) / 2; val root = sqrt(((sxx - syy) / 2).let { it * it } + sxy * sxy)
        val minorSpread = sqrt(max(0.0, half - root))
        if (minorSpread < STRAIGHT_M) {
            // Unit vector along the route (largest spread) and across it.
            val ang = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
            val ux = cos(ang); val uy = kotlin.math.sin(ang)
            val across = { x: Double, y: Double -> (x - mX) * -uy + (y - mY) * ux }
            val side = max(step, 2 * STRAIGHT_M)
            if (region.any { across(gx(it), gy(it)) > side } && region.any { across(gx(it), gy(it)) < -side }) {
                val along = (bx - mX) * ux + (by - mY) * uy
                cx = mX + along * ux; cy = mY + along * uy
            }
        }
        var reach = 0.0
        for (i in region) reach = max(reach, hypot(gx(i) - cx, gy(i) - cy))
        reach = max(reach, hypot(bx - cx, by - cy))
        val radius = hypot(reach + step / 2, GPS_ERROR_M)
        // No better than "somewhere within reach": not worth showing.
        if (radius > radio.reachM) return null

        return Estimate(
            lat = lat0 + cy / my, lon = lon0 + cx / mx, radiusM = radius,
            spots = spots.size, readings = recent.size, baselineM = baseline, time = now
        )
    }

    /**
     * A fix moved on by [speedMs] along [bearingDeg] for [ageS] seconds (at most 3 s, and not when
     * nearly stopped: the course is noise then).
     */
    fun ahead(lat: Double, lon: Double, speedMs: Double, bearingDeg: Double, ageS: Double): Pair<Double, Double> {
        if (speedMs < 1.0 || ageS <= 0.0) return lat to lon
        val d = speedMs * ageS.coerceAtMost(3.0)
        val b = Math.toRadians(bearingDeg)
        return (lat + d * cos(b) / 110_540.0) to (lon + d * kotlin.math.sin(b) / (111_320.0 * cos(Math.toRadians(lat))))
    }

    /** "±12 m from 18 spots" */
    fun describe(e: Estimate): String = "±${e.radiusM.roundToInt()} m from ${e.spots} spots you passed"
}
