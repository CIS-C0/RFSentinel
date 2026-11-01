package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.DeviceIntel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps alerts placed on the radar (and Locate's arrow) while you walk. While the radar is on screen every alerting
 * device gets its own [Sweep]: phone held in front of you, your body shadows the signal from behind, so a turn on
 * the spot shows its side, kept as a line from where you stood. Every reading is kept too, with where you were
 * ([Motion]: steps + compass + GPS) and the way you faced. [solve] finds the spot that best explains all of it:
 * the lines' directions, and how the signal rose and fell along your path with your body's shadow, so the device
 * stays put on the map and its direction and distance from you update as you move. Main thread; [solve] is pure
 * (unit-tested).
 */
object RadarTrack {

    /** A settled direction ([bearing], compass degrees) from where you stood (metres in [Motion]'s frame). */
    class Line(val x: Double, val y: Double, val bearing: Float, val weight: Float, val time: Long)

    /** A raw reading heard at x/y while facing [heading]. */
    class Reading(val x: Double, val y: Double, val heading: Float, val rssi: Int, val time: Long)

    /** Signal expected 1 m away ([p0], dBm) and how fast it falls ([n]). */
    class Radio(val p0: Double, val n: Double)

    /** Where an alert shows from where you are: its direction and distance. */
    class Place(val bearing: Float, val distanceM: Float)

    private class Track(val generation: Int) {
        val sweep = Sweep()
        val lines = ArrayList<Line>()
        val readings = ArrayDeque<Reading>()
        var radio = Radio(-59.0, 2.5)
        var fed = 0L
        /** Where this sweep's readings started: walking off starts it over. */
        var sweepFrom: Motion.Pos? = null
        var spot: Motion.Pos? = null
        var solvedAt = 0L
        var dirty = false
    }
    private val tracks = HashMap<String, Track>()

    private fun track(mac: String, now: Long): Track {
        tracks.entries.removeAll { now - it.value.fed > WINDOW_MS || it.value.generation != Motion.generation }
        return tracks.getOrPut(mac) { Track(Motion.generation) }
    }

    /** A new reading of [mac] (the raw advert [a], heard at [time] facing [heading]); false when it was already in. */
    fun reading(mac: String, time: Long, heading: Float, a: Advert): Boolean {
        val pos = Motion.position ?: return false
        val t = track(mac, time)
        if (time == t.fed) return false
        t.fed = time
        t.radio = Radio(DeviceIntel.powerAt1m(a), DeviceIntel.pathLossExponent(a))
        val last = t.readings.lastOrNull()
        if (last == null || time - last.time >= READING_GAP_MS) {
            t.readings.addLast(Reading(pos.x, pos.y, heading, a.rssi, time))
            while (t.readings.size > MAX_READINGS || time - t.readings.first().time > WINDOW_MS) t.readings.removeFirst()
            t.dirty = true
        }
        return true
    }

    /** A direction a turn settled on for [mac], from where you stand now. */
    fun line(mac: String, bearing: Float, confidence: Float, time: Long) {
        val pos = Motion.position ?: return
        val t = track(mac, time)
        // Another turn at the same spot replaces the line there.
        t.lines.removeAll { time - it.time > WINDOW_MS || hypot(it.x - pos.x, it.y - pos.y) < SAME_SPOT_M }
        t.lines += Line(pos.x, pos.y, bearing, confidence, time)
        while (t.lines.size > MAX_LINES) t.lines.removeAt(0)
        t.dirty = true
    }

    /** The way you face now, for [mac]'s sweep (often, between readings too). */
    fun face(mac: String, now: Long, heading: Float) {
        val t = tracks[mac] ?: return
        val pos = Motion.position
        val from = t.sweepFrom
        if (pos != null && from != null && hypot(pos.x - from.x, pos.y - from.y) > SWEEP_MOVE_M) { t.sweep.clear(); t.sweepFrom = null }
        t.sweep.face(now, heading)
    }

    /** The radar's reading of an alert: kept, and run through its own sweep for a line. */
    fun feed(mac: String, time: Long, heading: Float, a: Advert) {
        if (!reading(mac, time, heading, a)) return
        val t = tracks[mac] ?: return
        t.sweep.add(time, heading, a.rssi)
        if (t.sweepFrom == null) t.sweepFrom = Motion.position
        val e = t.sweep.settled(time) ?: return
        // The next turn gives a new, independent line.
        t.sweep.clear(); t.sweepFrom = null
        line(mac, e.bearing, e.confidence, time)
    }

    /** Where [mac] is from where you stand now; null before any turn settled its side. */
    fun place(mac: String, now: Long): Place? {
        val pos = Motion.position ?: return null
        val t = tracks[mac]?.takeIf { it.generation == Motion.generation } ?: return null
        t.lines.removeAll { now - it.time > WINDOW_MS }
        if (t.lines.isEmpty()) return null
        if (t.dirty && now - t.solvedAt >= SOLVE_MS || t.spot == null) {
            t.spot = solve(t.lines, t.readings.toList(), t.radio)
            t.solvedAt = now; t.dirty = false
        }
        val s = t.spot ?: return null
        val dx = s.x - pos.x; val dy = s.y - pos.y
        return Place(((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat(), hypot(dx, dy).toFloat())
    }

    fun clear() = tracks.clear()

    /**
     * The spot (metres) that best explains the [lines] (each off by about [SIGMA_DEG]) and the [readings] (path
     * loss plus your body's shadow when you faced away, off by about [SIGMA_DB]; each 3 m spot counts once, as
     * readings there fade together). The power at 1 m is fitted, held near the [radio]'s usual one. Searched within
     * [RANGE_M] of the newest line, coarse then fine. Null without a line.
     */
    fun solve(lines: List<Line>, readings: List<Reading>, radio: Radio): Motion.Pos? {
        val o = lines.lastOrNull() ?: return null
        val n = readings.size
        val rx = DoubleArray(n) { readings[it].x }
        val ry = DoubleArray(n) { readings[it].y }
        val rr = DoubleArray(n) { readings[it].rssi.toDouble() }
        val fs = DoubleArray(n) { sin(Math.toRadians(readings[it].heading.toDouble())) }
        val fc = DoubleArray(n) { cos(Math.toRadians(readings[it].heading.toDouble())) }
        // Readings at one spot share their fading: together they weigh as one.
        val perCell = HashMap<Long, Int>()
        val cell = LongArray(n) { (rx[it] / CELL_M).roundToLong() * 1_000_003L + (ry[it] / CELL_M).roundToLong() }
        for (c in cell) perCell[c] = (perCell[c] ?: 0) + 1
        val w = DoubleArray(n) { 1.0 / perCell[cell[it]]!! }
        val wSum = w.sum()
        val model = DoubleArray(n)
        val tenN = 10 * radio.n
        val s2 = SIGMA_DB * SIGMA_DB
        val p2 = SIGMA_P0 * SIGMA_P0

        fun cost(px: Double, py: Double): Double {
            var c = 0.0
            for (l in lines) {
                val dx = px - l.x; val dy = py - l.y
                if (dx * dx + dy * dy < 1.0) continue
                val e = angleDiff(l.bearing.toDouble(), Math.toDegrees(atan2(dx, dy))) / SIGMA_DEG
                c += LINE_WEIGHT * l.weight * e * e
            }
            if (n == 0) return c
            var sum = 0.0
            for (k in 0 until n) {
                val dx = px - rx[k]; val dy = py - ry[k]
                val d = max(sqrt(dx * dx + dy * dy), 1.0)
                // Your body between you and it: 0 facing it, BODY_DB with your back to it.
                val facing = (dx * fs[k] + dy * fc[k]) / d
                model[k] = tenN * log10(d) + BODY_DB * (1 - facing) / 2
                sum += w[k] * (rr[k] + model[k])
            }
            val p0 = ((sum / s2 + radio.p0 / p2) / (wSum / s2 + 1 / p2)).coerceIn(radio.p0 - P0_SPAN, radio.p0 + P0_SPAN)
            for (k in 0 until n) { val r = rr[k] - (p0 - model[k]); c += w[k] * r * r / s2 }
            return c + (p0 - radio.p0) * (p0 - radio.p0) / p2
        }

        fun search(cx: Double, cy: Double, half: Double, step: Double): Triple<Double, Double, Double> {
            var best = Triple(cx, cy, Double.MAX_VALUE)
            val steps = (half / step).toInt()
            for (i in -steps..steps) for (j in -steps..steps) {
                val x = cx + i * step; val y = cy + j * step
                val c = cost(x, y)
                if (c < best.third) best = Triple(x, y, c)
            }
            return best
        }
        val coarse = search(o.x, o.y, RANGE_M, COARSE_M)
        val fine = search(coarse.first, coarse.second, COARSE_M, FINE_M)
        return Motion.Pos(fine.first, fine.second)
    }

    private fun angleDiff(a: Double, b: Double) = ((b - a) % 360 + 540) % 360 - 180

    /** Lines and readings older than this drop out: the device may have moved. */
    const val WINDOW_MS = 5 * 60_000L
    private const val MAX_LINES = 6
    private const val MAX_READINGS = 300
    private const val READING_GAP_MS = 1_000L
    private const val SOLVE_MS = 2_000L
    /** A turn within this of an earlier one is the same spot. */
    private const val SAME_SPOT_M = 8.0
    /** Walked this far mid-turn: that sweep starts over. */
    private const val SWEEP_MOVE_M = 5.0
    private const val CELL_M = 3.0
    /** How far off a settled line usually is, and how much it counts against a reading. */
    private const val SIGMA_DEG = 20.0
    private const val LINE_WEIGHT = 2.0
    private const val SIGMA_DB = 6.0
    /** How far a radio's power at 1 m strays from the usual, and the most it can. */
    private const val SIGMA_P0 = 8.0
    private const val P0_SPAN = 25.0
    /** Your body's shadow with your back to it (measured 5-14 dB at 2.4 GHz). */
    private const val BODY_DB = 8.0
    private const val RANGE_M = 120.0
    private const val COARSE_M = 6.0
    private const val FINE_M = 1.0
}
