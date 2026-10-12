package com.rfsentinel.app.online

import android.content.Context
import android.location.Location

/**
 * "Only alert for reports ahead of me", on the map's roads: the road you're on, followed forward
 * from where you are ([RoadMatch]), with each report checked against it. Your road ahead is worked
 * out again when you've moved or turned, not every second; stopped (at a light), your last heading
 * still counts for a couple of minutes.
 */
internal class AheadOnRoad {

    private var path: RoadMatch.Path? = null
    /** Where and when the path in hand was worked out (not merely attempted). */
    private var pathLat = 0.0; private var pathLon = 0.0; private var pathHeading = 0.0; private var pathAt = 0L
    private var triedAt = 0L
    private var heading: Double? = null; private var headingAt = 0L
    private var headingLat = 0.0; private var headingLon = 0.0

    /**
     * True when the verdicts are thin because the map along your way has not arrived yet, so a caller
     * can wait a moment for it instead of deciding without it.
     */
    @Volatile var waitingForRoads = false
        private set

    /** Each report's verdict (by id); none while the map can't tell (no heading, no roads loaded yet). */
    fun verdicts(context: Context, me: Location, alertM: Double, reports: List<WazePolice.Report>): Map<String, RoadMatch.Verdict> {
        val h = headingOf(me) ?: run { waitingForRoads = false; return emptyMap() }
        val now = System.currentTimeMillis()
        val moved = com.rfsentinel.app.service.DeviceRegistry.metersBetween(pathLat, pathLon, me.latitude, me.longitude)
        val due = path == null || moved > 25 || RoadMatch.angleDiff(h, pathHeading) > 20 || now - pathAt > 5_000
        if (due && now - triedAt >= MIN_BUILD_GAP_MS) {
            triedAt = now
            val fresh = build(context, me.latitude, me.longitude, h, alertM, me)
            if (fresh != null) {
                path = fresh
                pathLat = me.latitude; pathLon = me.longitude; pathHeading = h; pathAt = now
            } else if (moved > KEEP_PATH_M || now - pathAt > KEEP_PATH_MS) {
                // A tile that hasn't arrived yet shouldn't throw away a good answer: the last path stands
                // for a short stretch, so a momentary gap doesn't drop every report to the plain corridor test.
                path = null
            }
        }
        val p = path
        waitingForRoads = p == null || p.stoppedForData
        if (p == null) return emptyMap()
        return reports.associate { it.id to RoadMatch.check(p, it.lat, it.lon).first }
    }

    /** Your heading while driving; stopped, the last one for 2 minutes (as long as you haven't moved off). */
    private fun headingOf(me: Location): Double? {
        val now = System.currentTimeMillis()
        if (me.hasBearing() && me.hasSpeed() && me.speed > 1.5f) {
            heading = me.bearing.toDouble(); headingAt = now; headingLat = me.latitude; headingLon = me.longitude
            return heading
        }
        val h = heading ?: return null
        val near = com.rfsentinel.app.service.DeviceRegistry.metersBetween(headingLat, headingLon, me.latitude, me.longitude) < 60
        return h.takeIf { now - headingAt < 120_000 && near }
    }

    /** Your road ahead from the roads loaded so far; asks for the map along the way (and where it ran out). */
    private fun build(context: Context, lat: Double, lon: Double, headingDeg: Double, alertM: Double, me: Location): RoadMatch.Path? {
        val maxM = (alertM * 1.3).coerceIn(500.0, 6_000.0)
        val tiles = corridor(lat, lon, headingDeg, maxM)
        val have = RoadTiles.roads(context, tiles)
        // Then the map further on, in the background, so the path is ready before you get there. Asked for
        // after the stretch in use, which the one loading thread therefore fetches first.
        val warm = corridor(lat, lon, headingDeg, (maxM * 2).coerceAtMost(WARM_MAX_M)) - tiles
        if (warm.isNotEmpty()) RoadTiles.roads(context, warm)
        // A phone's fix can sit a good way off the road it is on (buildings, trees): allow for what it
        // admits to, or you are taken as on no road at all and every report falls back to the corridor test.
        val meMaxM = RoadMatch.ME_MAX_M +
            (if (me.hasAccuracy()) me.accuracy.toDouble().coerceAtMost(ACCURACY_ALLOWANCE_M) else 0.0)
        val path = RoadMatch.pathAhead(have.values.flatten(), lat, lon, headingDeg, maxM, meMaxM) { la, lo ->
            RoadTiles.tileOf(la, lo) in have
        }
        // The road bends out of the strip along your heading: load where it went too.
        if (path != null && path.stoppedForData) {
            val (la, lo) = path.endLatLon()
            RoadTiles.roads(context, listOf(RoadTiles.tileOf(la, lo)))
        }
        return path
    }

    companion object {
        /** A path is not worked out again more often than this, however often the verdicts are asked for. */
        private const val MIN_BUILD_GAP_MS = 1_000L
        /** A path with no replacement yet stands for this long, and this far travelled, before it is dropped. */
        private const val KEEP_PATH_MS = 15_000L
        private const val KEEP_PATH_M = 100.0
        /** The most a fix's own accuracy widens how far from a road you can be and still be on it. */
        private const val ACCURACY_ALLOWANCE_M = 30.0
        /** The map is never fetched further ahead than this. */
        private const val WARM_MAX_M = 4_000.0
        /** The map tiles along your heading: every 250 m out to [maxM], and 300 m either side. */
        internal fun corridor(lat: Double, lon: Double, headingDeg: Double, maxM: Double): Set<RoadTiles.Tile> {
            val out = LinkedHashSet<RoadTiles.Tile>()
            var d = 0.0
            while (d <= maxM) {
                for (side in listOf(0.0, -300.0, 300.0)) {
                    val (a, b) = com.rfsentinel.app.ui.MapGeo.offset(lat, lon, d, headingDeg)
                    val (c, e) = if (side == 0.0) a to b else com.rfsentinel.app.ui.MapGeo.offset(a, b, kotlin.math.abs(side), headingDeg + if (side > 0) 90 else -90)
                    out += RoadTiles.tileOf(c, e)
                }
                d += 250.0
            }
            return out
        }
    }
}
