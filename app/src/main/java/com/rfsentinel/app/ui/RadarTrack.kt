package com.rfsentinel.app.ui

import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Places alerts on the main radar from the way you turn, with no Locate needed. While the radar is on screen every
 * alerting device gets its own [Sweep]: the phone held in front of you, your body shadows the signal from behind,
 * so a turn on the spot shows its side. Each direction a turn settles on is kept as a line from where you stood;
 * two or more lines from spots apart [cross] at the device, so its dot keeps its real place (direction and
 * distance) as you turn and walk. Main thread; [cross] is pure (unit-tested).
 */
object RadarTrack {

    /** A settled direction ([bearing], compass degrees) from where you stood (null without a GPS fix). */
    class Line(val lat: Double?, val lon: Double?, val bearing: Float, val weight: Float, val time: Long)

    /** Where an alert shows on the radar: its direction, and how far once lines crossed. */
    class Place(val bearing: Float, val distanceM: Float?)

    private class Track { val sweep = Sweep(); val lines = ArrayList<Line>(); var fed = 0L }
    private val tracks = HashMap<String, Track>()

    /** A new reading of [mac] ([rssi] raw, heard at [time] while facing [heading]); [lat]/[lon]: where you are. */
    fun feed(mac: String, time: Long, heading: Float, rssi: Int, lat: Double?, lon: Double?) {
        tracks.entries.removeAll { time - it.value.fed > LINE_MS }
        val t = tracks.getOrPut(mac) { Track() }
        if (time == t.fed) return
        t.fed = time
        t.sweep.add(time, heading, rssi)
        val e = t.sweep.settled(time) ?: return
        // The next turn gives a new, independent line.
        t.sweep.clear()
        // Another turn at the same spot replaces the line there.
        t.lines.removeAll { time - it.time > LINE_MS || near(it, lat, lon, SAME_SPOT_M) }
        t.lines += Line(lat, lon, e.bearing, e.confidence, time)
        while (t.lines.size > MAX_LINES) t.lines.removeAt(0)
    }

    /** Where [mac] shows from where you are now ([lat]/[lon], null without a fix); null when no turn settled it yet. */
    fun place(mac: String, lat: Double?, lon: Double?, now: Long): Place? {
        val t = tracks[mac] ?: return null
        t.lines.removeAll { now - it.time > LINE_MS }
        if (lat != null && lon != null) cross(t.lines)?.let { (dLat, dLon) ->
            val d = DeviceRegistry.metersBetween(lat, lon, dLat, dLon).toFloat()
            return Place(KnownCameras.bearingTo(lat, lon, dLat, dLon).toFloat(), d)
        }
        // Not crossed yet: the newest direction, while you still stand about where you took it.
        val l = t.lines.lastOrNull()?.takeIf { near(it, lat, lon, NEAR_M) } ?: return null
        return Place(l.bearing, null)
    }

    /** Forgets every line and sweep (scan stopped). */
    fun clear() = tracks.clear()

    private fun near(l: Line, lat: Double?, lon: Double?, m: Double): Boolean {
        if (l.lat == null || l.lon == null || lat == null || lon == null) return l.lat == null && lat == null
        return DeviceRegistry.metersBetween(l.lat, l.lon, lat, lon) <= m
    }

    /**
     * The weighted least-squares crossing (lat to lon) of the [lines] that have a position: null unless two of them
     * differ by [MIN_CROSS_DEG], and the crossing lies ahead of every line within [MAX_RANGE_M] (a crossing behind
     * you means one direction is wrong).
     */
    fun cross(lines: List<Line>): Pair<Double, Double>? {
        val ls = lines.filter { it.lat != null && it.lon != null }
        if (ls.size < 2) return null
        var spread = 0f
        for (i in ls.indices) for (j in i + 1 until ls.size) {
            val d = abs(Sweep.turn(ls[i].bearing, ls[j].bearing)).toFloat()
            spread = maxOf(spread, min(d, 180f - d))
        }
        if (spread < MIN_CROSS_DEG) return null
        val lat0 = ls[0].lat!!; val lon0 = ls[0].lon!!
        val mx = 111_320.0 * cos(Math.toRadians(lat0)); val my = 110_540.0
        fun x(l: Line) = (l.lon!! - lon0) * mx
        fun y(l: Line) = (l.lat!! - lat0) * my
        // Minimises the weighted squared distance across each line: sum w (I - d dT) (p - x) = 0.
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        for (l in ls) {
            val r = Math.toRadians(l.bearing.toDouble()); val dx = sin(r); val dy = cos(r)
            val w = l.weight.toDouble()
            val m11 = 1 - dx * dx; val m12 = -dx * dy; val m22 = 1 - dy * dy
            a11 += w * m11; a12 += w * m12; a22 += w * m22
            b1 += w * (m11 * x(l) + m12 * y(l)); b2 += w * (m12 * x(l) + m22 * y(l))
        }
        val det = a11 * a22 - a12 * a12
        if (abs(det) < 1e-9) return null
        val px = (b1 * a22 - b2 * a12) / det
        val py = (a11 * b2 - a12 * b1) / det
        for (l in ls) {
            val r = Math.toRadians(l.bearing.toDouble())
            val along = (px - x(l)) * sin(r) + (py - y(l)) * cos(r)
            if (along <= 0 || along > MAX_RANGE_M) return null
        }
        return lat0 + py / my to lon0 + px / mx
    }

    /** Lines older than this drop out: the device may have moved. */
    const val LINE_MS = 5 * 60_000L
    private const val MAX_LINES = 6
    /** A turn within this of an earlier one is the same spot. */
    private const val SAME_SPOT_M = 8.0
    /** A direction alone holds while you stay within this of where you took it. */
    private const val NEAR_M = 25.0
    /** Lines this close to parallel don't cross anywhere useful. */
    const val MIN_CROSS_DEG = 20f
    /** About as far as a phone hears Bluetooth. */
    private const val MAX_RANGE_M = 300.0
}
