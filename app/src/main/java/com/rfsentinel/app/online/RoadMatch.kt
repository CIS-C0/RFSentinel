package com.rfsentinel.app.online

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Is a report on the road you're on, ahead of you? Finds your road from your position and heading
 * (the nearest road segment that runs your way), follows it forward - through intersections, taking
 * the straightest way on when the line ends, as you would keep driving - out to [RoadMatch.Path.maxM],
 * then checks each report against that stretch. A report on a parallel road, a cross street or an
 * overpass, or behind you, isn't on it. Plain geometry on [RoadTiles.Road] lines (tested without a map).
 */
object RoadMatch {

    enum class Verdict { ON_MY_ROAD, OTHER_ROAD, BEHIND, UNKNOWN }

    /** Your road ahead, as points in metres around you, with the distance along it at each point. */
    class Path internal constructor(
        internal val xs: DoubleArray, internal val ys: DoubleArray, internal val along: DoubleArray,
        /** Road segments the path runs on (road index, segment index). */
        internal val used: Set<Long>,
        /** The road each stretch (point i to i + 1) runs on. */
        internal val roadOf: IntArray,
        /** It stopped where no map data was loaded yet (rather than at a dead end or [maxM]). */
        val stoppedForData: Boolean,
        internal val frame: Frame,
        internal val roads: Index,
        internal val headingDeg: Double
    ) {
        val lengthM get() = along.lastOrNull() ?: 0.0
        /** Where it ended, for loading the map there. */
        fun endLatLon(): Pair<Double, Double> = frame.toLatLon(xs.last(), ys.last())
    }

    /** Your position counts as on a road within this (the caller adds room for the fix's own error). */
    internal const val ME_MAX_M = 30.0
    /** A report counts as on the road within this (Waze places reports on or right beside it). */
    private const val REPORT_MAX_M = 30.0
    /** Your heading and the road's direction must agree within this. */
    private const val HEADING_TOLERANCE = 40.0
    /** At the end of a line, the way on must turn less than this ("straight on"). */
    private const val CONTINUE_TOLERANCE = 50.0
    /** Lines meet (a junction, or the same road cut at a tile edge) within this. */
    private const val JOIN_M = 10.0

    /**
     * Your road ahead from ([lat], [lon]) going [headingDeg], up to [maxM] along it; null when you're
     * not on a known road (or no roads are loaded). [meMaxM] is how far from a road you can be and
     * still be taken as on it (room for the fix's own error). [loaded] says whether map data covers a
     * point (the walk stops there for want of data, not at a dead end).
     */
    fun pathAhead(roads: List<RoadTiles.Road>, lat: Double, lon: Double, headingDeg: Double, maxM: Double,
                  meMaxM: Double = ME_MAX_M, loaded: (Double, Double) -> Boolean = { _, _ -> true }): Path? {
        if (roads.isEmpty()) return null
        val f = Frame(lat, lon)
        val idx = Index(roads, f)
        val start = idx.nearest(0.0, 0.0, meMaxM).mapNotNull { c ->
            // Which way along this segment you're going, if either agrees with your heading.
            val d = idx.dirDeg(c.road, c.seg)
            val fwd = angleDiff(d, headingDeg); val back = angleDiff(d + 180, headingDeg)
            val ow = roads[c.road].oneway
            val dirs = listOfNotNull(
                (1 to fwd).takeIf { fwd <= HEADING_TOLERANCE && ow != -1 },
                (-1 to back).takeIf { back <= HEADING_TOLERANCE && ow != 1 })
            dirs.minByOrNull { it.second }?.let { (sign, diff) -> Triple(c, sign, c.dist + diff * 0.4) }
        }.minByOrNull { it.third } ?: return null

        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val along = ArrayList<Double>()
        val used = HashSet<Long>()
        val roadOf = ArrayList<Int>()
        var total = 0.0
        var road = start.first.road; var seg = start.first.seg; var sign = start.second
        fun add(x: Double, y: Double) {
            if (xs.isNotEmpty()) { total += hypot(x - xs.last(), y - ys.last()); roadOf += road }
            xs += x; ys += y; along += total
        }
        add(start.first.px, start.first.py)
        var stoppedForData = false
        var guard = 0
        walk@ while (total < maxM && guard++ < 5000) {
            // Along this line to its end (in the direction of travel).
            val pts = idx.pts(road)
            var i = if (sign > 0) seg + 1 else seg
            while (i in pts.indices) {
                used += key(road, if (sign > 0) i - 1 else i)
                add(pts[i].first, pts[i].second)
                if (total >= maxM) break@walk
                i += sign
            }
            // The line ends: carry on along the straightest way that starts here.
            val endX = xs.last(); val endY = ys.last()
            val inDir = dirOf(xs[xs.size - 2], ys[ys.size - 2], endX, endY)
            val next = idx.nearest(endX, endY, JOIN_M).filter { key(it.road, it.seg) !in used && it.road != road }.mapNotNull { c ->
                val d = idx.dirDeg(c.road, c.seg)
                val ow = roads[c.road].oneway
                val opts = listOfNotNull(
                    (1 to angleDiff(d, inDir)).takeIf { ow != -1 },
                    (-1 to angleDiff(d + 180, inDir)).takeIf { ow != 1 })
                opts.minByOrNull { it.second }?.takeIf { it.second <= CONTINUE_TOLERANCE }?.let { (s, diff) ->
                    // The same kind of road is the likelier way on.
                    Triple(c, s, diff + (if (roads[c.road].cls == roads[road].cls) 0.0 else 15.0) + c.dist)
                }
            }.minByOrNull { it.third }
            if (next == null) {
                val (la, lo) = f.toLatLon(endX, endY)
                stoppedForData = !loaded(la, lo)
                break
            }
            road = next.first.road; seg = next.first.seg; sign = next.second
            if (hypot(next.first.px - endX, next.first.py - endY) > 0.5) add(next.first.px, next.first.py)
        }
        if (xs.size < 2) return null
        return Path(xs.toDoubleArray(), ys.toDoubleArray(), along.toDoubleArray(), used, roadOf.toIntArray(), stoppedForData, f, idx, headingDeg)
    }

    /** Whether the report at ([lat], [lon]) is on [path] - your road ahead - and how far along it. */
    fun check(path: Path?, lat: Double, lon: Double): Pair<Verdict, Double?> {
        if (path == null) return Verdict.UNKNOWN to null
        val (x, y) = path.frame.toXY(lat, lon)
        // Nearest point of your road ahead.
        var best = Double.MAX_VALUE; var bestAlong = 0.0; var bestDir = 0.0; var bestRoad = -1
        for (i in 0 until path.xs.size - 1) {
            val (d, t) = pointToSegment(x, y, path.xs[i], path.ys[i], path.xs[i + 1], path.ys[i + 1])
            if (d < best) {
                best = d
                bestAlong = path.along[i] + t * (path.along[i + 1] - path.along[i])
                bestDir = dirOf(path.xs[i], path.ys[i], path.xs[i + 1], path.ys[i + 1])
                bestRoad = path.roadOf[i]
            }
        }
        val ahead = angleDiff(dirOf(0.0, 0.0, x, y), path.headingDeg) <= 90
        // Right where you are and the wrong way: just behind you.
        if (bestAlong < 20.0 && best <= REPORT_MAX_M && hypot(x, y) > 10 && !ahead) return Verdict.BEHIND to null

        // The road the report itself is on, when it isn't yours.
        val other = path.roads.nearest(x, y, REPORT_MAX_M).filter { key(it.road, it.seg) !in path.used }.minByOrNull { it.dist }
        if (other != null && bestRoad >= 0) {
            val axis = angleDiff(path.roads.dirDeg(other.road, other.seg), bestDir) // 0 = same way, 180 = the other way
            val parallel = minOf(axis, 180 - axis) <= 30
            val mine = path.roads.road(bestRoad); val theirs = path.roads.road(other.road)
            // A divided highway: the other carriageway (a one-way road beside yours, the other way) is the same road.
            if (parallel && axis > 150 && theirs.oneway != 0 && mine.oneway != 0 && theirs.cls == mine.cls &&
                other.dist <= 15 && best <= 2 * REPORT_MAX_M) return Verdict.ON_MY_ROAD to bestAlong
            // Clearly on another road: one that crosses yours (a cross street, an overpass), or runs beside it (a service road).
            if (!parallel && other.dist + 6 < best) return Verdict.OTHER_ROAD to null
            if (parallel && other.dist + 10 < best) return Verdict.OTHER_ROAD to null
        }
        if (best > REPORT_MAX_M) {
            // Off the stretch that's loaded: it may still be on your road further on.
            if (path.stoppedForData && hypot(x - path.xs.last(), y - path.ys.last()) < hypot(x, y)) return Verdict.UNKNOWN to null
            return (if (ahead) Verdict.OTHER_ROAD else Verdict.BEHIND) to null
        }
        return Verdict.ON_MY_ROAD to bestAlong
    }

    // ---- geometry ----------------------------------------------------------------------------------

    private fun key(road: Int, seg: Int) = (road.toLong() shl 20) or seg.toLong()

    /** Bearing (degrees clockwise from north) from one point to another, in the local metre frame. */
    private fun dirOf(x0: Double, y0: Double, x1: Double, y1: Double) = (Math.toDegrees(atan2(x1 - x0, y1 - y0)) + 360) % 360

    /** 0..180 between two bearings. */
    internal fun angleDiff(a: Double, b: Double): Double { val d = abs(((a - b) % 360 + 540) % 360 - 180); return d }

    /** Distance from a point to a segment, and where along it (0..1) the nearest point is. */
    private fun pointToSegment(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Pair<Double, Double> {
        val dx = x1 - x0; val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy)) to t
    }

    /** Metres east / north of a point (fine for the few kilometres involved). */
    internal class Frame(private val lat0: Double, private val lon0: Double) {
        private val kx = 111_320.0 * cos(Math.toRadians(lat0))
        private val ky = 110_540.0
        fun toXY(lat: Double, lon: Double) = (lon - lon0) * kx to (lat - lat0) * ky
        fun toLatLon(x: Double, y: Double) = (lat0 + y / ky) to (lon0 + x / kx)
    }

    /** A segment near a point: which, how far, and the nearest point on it. */
    internal class Near(val road: Int, val seg: Int, val dist: Double, val px: Double, val py: Double)

    /** Road segments in metres, on a 100 m grid for finding what's near a point. */
    internal class Index(private val source: List<RoadTiles.Road>, f: Frame) {
        private val points: List<List<Pair<Double, Double>>> = source.map { r -> r.lat.indices.map { f.toXY(r.lat[it], r.lon[it]) } }
        private val grid = HashMap<Long, MutableList<Long>>()
        private val cell = 100.0

        init {
            for ((ri, pts) in points.withIndex()) for (si in 0 until pts.size - 1) {
                val (x0, y0) = pts[si]; val (x1, y1) = pts[si + 1]
                for (cx in cellOf(minOf(x0, x1))..cellOf(maxOf(x0, x1)))
                    for (cy in cellOf(minOf(y0, y1))..cellOf(maxOf(y0, y1)))
                        grid.getOrPut(cellKey(cx, cy)) { ArrayList() } += key(ri, si)
            }
        }

        private fun cellOf(v: Double) = Math.floor(v / cell).toInt()
        private fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        fun pts(road: Int) = points[road]
        fun road(i: Int) = source[i]

        fun dirDeg(road: Int, seg: Int): Double {
            val (x0, y0) = points[road][seg]; val (x1, y1) = points[road][seg + 1]
            return dirOf(x0, y0, x1, y1)
        }

        fun nearest(x: Double, y: Double, maxM: Double): List<Near> {
            val out = ArrayList<Near>()
            val seen = HashSet<Long>()
            for (cx in cellOf(x - maxM)..cellOf(x + maxM)) for (cy in cellOf(y - maxM)..cellOf(y + maxM)) {
                for (k in grid[cellKey(cx, cy)] ?: continue) {
                    if (!seen.add(k)) continue
                    val ri = (k ushr 20).toInt(); val si = (k and 0xfffff).toInt()
                    val (x0, y0) = points[ri][si]; val (x1, y1) = points[ri][si + 1]
                    val (d, t) = pointToSegment(x, y, x0, y0, x1, y1)
                    if (d <= maxM) out += Near(ri, si, d, x0 + t * (x1 - x0), y0 + t * (y1 - y0))
                }
            }
            return out
        }
    }
}
