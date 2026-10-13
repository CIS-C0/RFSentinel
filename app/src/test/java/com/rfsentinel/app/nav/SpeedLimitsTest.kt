package com.rfsentinel.app.nav

import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.ui.SpeedGaugeView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The speedometer's posted limit: reading the route server's answer, along a route, live, and the gauge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpeedLimitsTest {

    @After fun reset() { SpeedLimits.fetcher = null; SpeedLimits.Live.reset() }

    /** A straight route north, a point every 10 m. */
    private fun route(points: Int): Route {
        val lats = DoubleArray(points) { 10.0 + it * 0.00009 }
        val lons = DoubleArray(points) { 20.0 }
        val along = DoubleArray(points) { it * 10.0 }
        return Route(lats, lons, along, along.last(), 100.0, emptyList(), "", lats.last() to lons.last())
    }

    private fun waitFor(what: String, ms: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(10) }
    }

    @Test
    fun theRequestAsksForTheLimitAndWhichRoadEachPointIsOn() {
        val r = SpeedLimits.request(listOf(10.0 to 20.0, 10.1 to 20.1))
        assertEquals(2, r.getJSONArray("shape").length())
        assertEquals(20.1, r.getJSONArray("shape").getJSONObject(1).getDouble("lon"), 1e-9)
        assertEquals("map_snap", r.getString("shape_match"))
        val attrs = r.getJSONObject("filters").getJSONArray("attributes").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(attrs.containsAll(listOf("edge.speed_limit", "matched.edge_index")))
    }

    @Test
    fun eachPointGetsItsRoadsLimitAndGapsAreUnknown() {
        val answer = """{"units":"kilometers","edges":[{"speed_limit":50},{"speed_limit":255},{},{"speed_limit":3}],
            "matched_points":[{"type":"matched","edge_index":0},{"type":"interpolated","edge_index":0},{"type":"matched","edge_index":1},
                              {"type":"matched","edge_index":2},{"type":"unmatched"},{"type":"matched","edge_index":3}]}"""
        val got = SpeedLimits.parse(answer, 6)
        assertEquals(listOf(50, 50, 0, 0, 0, 0), got.toList()) // 255 = no limit, no limit mapped, unmatched, and 3 km/h is no real limit
        assertEquals(listOf(0, 0), SpeedLimits.parse("""{"units":"kilometers"}""", 2).toList())
    }

    @Test
    fun milesAreShownInFives() {
        assertEquals(30, SpeedLimits.shown(50, true))
        assertEquals(55, SpeedLimits.shown(89, true))
        assertEquals(60, SpeedLimits.shown(100, true))
        assertEquals(75, SpeedLimits.shown(120, true))
        assertEquals(50, SpeedLimits.shown(50, false))
    }

    @Test
    fun theLimitAlongARouteArrivesAfterItIsAskedForAndFollowsTheRoad() {
        var asks = 0
        SpeedLimits.fetcher = { pts -> asks++; IntArray(pts.size) { if (it < 100) 50 else 90 } }
        val limits = SpeedLimits.RouteLimits(route(300))
        assertNull("not here yet", limits.at(0.0))
        waitFor("first stretch") { limits.at(0.0) != null }
        assertEquals(50, limits.at(500.0))
        assertEquals("90 after the 100th point (1 km)", 90, limits.at(1_500.0))
        assertEquals("one question covers a short route", 1, asks)
    }

    @Test
    fun aLongRouteIsAskedAStretchAtATimeAndTheNextOneBeforeYouGetThere() {
        val stretches = ArrayList<Int>()
        SpeedLimits.fetcher = { pts -> synchronized(stretches) { stretches += pts.size }; IntArray(pts.size) { 70 } }
        val limits = SpeedLimits.RouteLimits(route(3_200)) // three stretches of 1500 points
        limits.at(0.0)
        waitFor("two stretches") { synchronized(stretches) { stretches.size } >= 2 }
        assertEquals(70, limits.at(0.0) ?: run { waitFor("first") { limits.at(0.0) != null }; limits.at(0.0) })
        assertEquals("the stretch has one point of overlap", SpeedLimits.CHUNK + 1, stretches[0])
        // Not asked for the third stretch yet, nor repeatedly for the same ones.
        Thread.sleep(100)
        assertEquals(2, synchronized(stretches) { stretches.size })
        limits.at(16_000.0) // into the second stretch: the third is now asked for
        waitFor("third stretch") { synchronized(stretches) { stretches.size } >= 3 }
    }

    @Test
    fun aFailedQuestionIsAskedAgainOnlyAfterAWhile() {
        var asks = 0; var now = 1_000_000L
        SpeedLimits.fetcher = { asks++; throw java.io.IOException("offline") }
        val limits = SpeedLimits.RouteLimits(route(50)).apply { clock = { now } }
        limits.at(0.0)
        waitFor("first try") { asks >= 1 }
        Thread.sleep(50)
        limits.at(0.0); Thread.sleep(50)
        assertEquals("not again at once", 1, asks)
        now += 61_000L
        limits.at(0.0)
        waitFor("second try") { asks >= 2 }
    }

    @Test
    fun theLiveLimitIsAskedFromWhereYouDroveAndOnlyWhileYouDrive() {
        var asked: List<Pair<Double, Double>>? = null
        SpeedLimits.fetcher = { pts -> asked = pts; IntArray(pts.size) { 80 } }
        val t0 = 5_000_000L
        // Standing still: nothing.
        for (i in 0 until 5) SpeedLimits.Live.onFix(10.0 + i * 0.0003, 20.0, 0f, t0 + i * 1_000)
        assertNull(asked)
        // Driving: a point every ~33 m; the third one asks.
        for (i in 0 until 5) SpeedLimits.Live.onFix(10.0 + i * 0.0003, 20.0, 15f, t0 + 10_000 + i * 1_000)
        waitFor("a question") { asked != null }
        assertTrue("from the recent trail", asked!!.size in 3..5)
        waitFor("an answer") { SpeedLimits.Live.now(10.0012, 20.0, System.currentTimeMillis()) == 80 }
        assertNull("far from where it was asked", SpeedLimits.Live.now(10.1, 20.0, System.currentTimeMillis()))
    }

    // ---- the gauge ---------------------------------------------------------------------------------------

    private fun gauge() = SpeedGaugeView(ApplicationProvider.getApplicationContext())

    @Test
    fun theGaugeShowsYourSpeedAndTurnsRedOverTheLimit() {
        val g = gauge()
        g.set(null, null, false, false)
        assertEquals("no speed yet: hidden", android.view.View.GONE, g.visibility)
        g.set(48f, 50, false, false)
        assertEquals(android.view.View.VISIBLE, g.visibility)
        assertFalse(g.over)
        g.set(53f, 50, false, false)
        assertTrue(g.over)
        g.set(53f, null, false, false)
        assertFalse("no limit known: nothing to be over", g.over)
    }

    @Test
    fun theGaugeSpeaksInMilesWhenAsked() {
        val g = gauge()
        g.set(100f, 100, true, false) // 62 mph on a 60 mph road
        assertTrue(g.contentDescription.toString(), g.contentDescription.toString().contains("62 miles per hour") &&
            g.contentDescription.toString().contains("limit 60"))
        assertTrue(g.over)
        g.set(100f, 100, false, false)
        assertTrue(g.contentDescription.toString().contains("100 kilometres per hour"))
        assertFalse(g.over)
    }
}
