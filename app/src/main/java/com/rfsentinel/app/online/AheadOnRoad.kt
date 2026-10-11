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
    private var pathLat = 0.0; private var pathLon = 0.0; private var pathHeading = 0.0; private var pathAt = 0L
    private var heading: Double? = null; private var headingAt = 0L
    private var headingLat = 0.0; private var headingLon = 0.0

    /** Each report's verdict (by id); none while the map can't tell (no heading, no roads loaded yet). */
    fun verdicts(context: Context, me: Location, alertM: Double, reports: List<WazePolice.Report>): Map<String, RoadMatch.Verdict> {
        val h = headingOf(me) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val moved = com.rfsentinel.app.service.DeviceRegistry.metersBetween(pathLat, pathLon, me.latitude, me.longitude)
        if (path == null || moved > 25 || RoadMatch.angleDiff(h, pathHeading) > 20 || now - pathAt > 5_000) {
            path = build(context, me.latitude, me.longitude, h, alertM)
            pathLat = me.latitude; pathLon = me.longitude; pathHeading = h; pathAt = now
        }
        val p = path ?: return emptyMap()
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
    private fun build(context: Context, lat: Double, lon: Double, headingDeg: Double, alertM: Double): RoadMatch.Path? {
        val maxM = (alertM * 1.3).coerceIn(500.0, 6_000.0)
        val tiles = corridor(lat, lon, headingDeg, maxM)
        val have = RoadTiles.roads(context, tiles)
        val path = RoadMatch.pathAhead(have.values.flatten(), lat, lon, headingDeg, maxM) { la, lo -> RoadTiles.tileOf(la, lo) in have }
        // The road bends out of the strip along your heading: load where it went too.
        if (path != null && path.stoppedForData) {
            val (la, lo) = path.endLatLon()
            RoadTiles.roads(context, listOf(RoadTiles.tileOf(la, lo)))
        }
        return path
    }

    companion object {
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
