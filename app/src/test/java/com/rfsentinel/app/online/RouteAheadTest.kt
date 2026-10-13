package com.rfsentinel.app.online

import com.rfsentinel.app.nav.Route
import com.rfsentinel.app.nav.Step
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.online.RoadMatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** While guided, a report counts when it is on the stretch of the route still ahead of you. */
class RouteAheadTest {
    private val lat0 = 10.0; private val lon0 = 20.0
    private val mPerDegLat = 110_540.0
    private fun lat(north: Double) = lat0 + north / mPerDegLat
    private fun lon(east: Double) = lon0 + east / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    /** A route through the given (north, east) points in metres, with its distances along. */
    private fun route(vararg pts: Pair<Double, Double>): Route {
        val lats = DoubleArray(pts.size) { lat(pts[it].first) }; val lons = DoubleArray(pts.size) { lon(pts[it].second) }
        val along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val step = Step("depart", null, "", null, "", 0.0, 0.0, along.last(), 60.0, 0.0, along.last(), 0, emptyList(), emptyList())
        return Route(lats, lons, along, along.last(), 120.0, listOf(step), "", lats.last() to lons.last())
    }

    private fun straightNorth(km: Int) = route(*(0..km * 10).map { it * 100.0 to 0.0 }.toTypedArray())

    private fun v(r: Route, alongM: Double, north: Double, east: Double, reach: Double = 3_000.0) =
        RouteAhead.verdict(r, alongM, lat(north), lon(east), reach)

    @Test
    fun aReportOnTheRouteAheadIsOnYourWay() {
        assertEquals(Verdict.ON_MY_ROAD, v(straightNorth(3), 0.0, 800.0, 6.0))
    }

    @Test
    fun aReportOnAnotherStreetIsNot() {
        // 800 m away as the crow flies, off to the north-west: nowhere near the route.
        assertEquals(Verdict.OTHER_ROAD, v(straightNorth(3), 0.0, 300.0, -700.0))
        // A parallel street 120 m beside the route.
        assertEquals(Verdict.OTHER_ROAD, v(straightNorth(3), 0.0, 500.0, 120.0))
    }

    @Test
    fun aReportYouHavePassedIsBehind() {
        assertEquals(Verdict.BEHIND, v(straightNorth(3), 1_500.0, 1_100.0, 4.0))
        // Just behind your position is still "here" (GPS error, the report's own rounding).
        assertEquals(Verdict.ON_MY_ROAD, v(straightNorth(3), 1_500.0, 1_485.0, 4.0))
    }

    @Test
    fun aReportFartherOnThanTheAlertReachesIsNotYet() {
        assertEquals(Verdict.OTHER_ROAD, v(straightNorth(3), 0.0, 2_500.0, 3.0, reach = 1_500.0))
        assertEquals(Verdict.ON_MY_ROAD, v(straightNorth(3), 1_200.0, 2_500.0, 3.0, reach = 1_500.0))
    }

    @Test
    fun aReportRoundTheNextCornerIsOnYourWayThoughItIsToTheSide() {
        // North 500 m, then west for a kilometre. A report on the west leg, 300 m past the corner: straight-line it
        // is 580 m away to the north-north-west - sideways to a car heading north - but it is on the route.
        val l = route(0.0 to 0.0, 500.0 to 0.0, 500.0 to -1_000.0)
        assertEquals(Verdict.ON_MY_ROAD, v(l, 0.0, 500.0, -300.0, reach = 1_500.0))
        // The same place for a route that turns the other way is another street.
        val r = route(0.0 to 0.0, 500.0 to 0.0, 500.0 to 1_000.0)
        assertEquals(Verdict.OTHER_ROAD, v(r, 0.0, 500.0, -300.0, reach = 1_500.0))
    }

    @Test
    fun aRouteThatPassesTheReportTwiceCountsThePassStillAhead() {
        // Out along one street and back along another 30 m away.
        val r = route(0.0 to 0.0, 1_000.0 to 0.0, 1_000.0 to 30.0, 0.0 to 30.0)
        // At the start the first pass (30 m from it, going out) is the one still in front of you.
        assertEquals(Verdict.ON_MY_ROAD, v(r, 0.0, 400.0, 30.0, reach = 3_000.0))
        // Coming back, 70 m short of it: ahead.
        assertEquals(Verdict.ON_MY_ROAD, v(r, 1_560.0, 400.0, 30.0, reach = 3_000.0))
        // Past it on the way back: both passes are behind you.
        assertEquals(Verdict.BEHIND, v(r, 1_750.0, 400.0, 30.0, reach = 3_000.0))
    }

    @Test
    fun aRouteWithoutLengthSaysNothing() {
        val r = route(0.0 to 0.0)
        assertEquals(Verdict.UNKNOWN, v(r, 0.0, 10.0, 0.0))
    }
}
