package com.rfsentinel.app.online

import android.app.Application
import android.location.Location
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.nav.Place
import com.rfsentinel.app.nav.Route
import com.rfsentinel.app.nav.Step
import com.rfsentinel.app.nav.Valhalla
import com.rfsentinel.app.online.WazePolice.Level
import com.rfsentinel.app.online.WazePolice.Type
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The checker's second-by-second work: which reports are in view, which deserve an alert (the moment one
 * comes in range, not only at the next check), which are behind you, and when to call out again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WazeWatchTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val alerts = ArrayList<OnlineWatch.Alert>()
    private val calls = ArrayList<String>()
    private var here = fix(10.0, 10.0)
    private lateinit var watch: WazeWatch

    private fun fix(lat: Double, lon: Double, bearing: Float? = null, speed: Float? = null) = Location("test").apply {
        latitude = lat
        longitude = lon
        bearing?.let { this.bearing = it }
        speed?.let { this.speed = it }
    }

    /** A report [metersNorth] of the origin (negative: south). */
    private fun report(id: String, metersNorth: Double, type: Type = Type.POLICE) =
        WazePolice.Report(id, 10.0 + metersNorth / 110_574.0, 10.0, null, null, null, 0, null, type)

    @Before
    fun setUp() {
        WazePolice.publish(emptyList(), 0L)
        AmbientThreats.waze = null
        here = fix(10.0, 10.0)
        watch = WazeWatch(ctx, CoroutineScope(Job()), { here }, { alerts += it }, { calls += it })
    }

    @After
    fun tearDown() {
        Navigator.stop()
        Navigator.speaker = null
        Navigator.planner = null
    }

    @Test
    fun aReportInRangeAlertsOnceAndIsShown() {
        watch.ingest(listOf(report("a", 800.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(1, alerts.size)
        val a = alerts[0]
        assertTrue(a.log)
        assertEquals(Level.LOUD, a.level)
        assertEquals("waze:a", a.key)
        assertTrue(a.spoken!!, a.spoken!!.contains("800 meters"))
        assertEquals(1, WazePolice.latest.size)
        watch.evaluate(2_000L, publish = false)
        assertEquals("no second alert for the same report", 1, alerts.size)
    }

    @Test
    fun aReportOutsideTheAlertRangeIsShownAndAlertsWhenYouGetClose() {
        watch.ingest(listOf(report("far", 3_000.0)), WazePolice.VIA_DIRECT, 1_000L) // view range 5 km, alert range 2 km
        assertTrue(alerts.isEmpty())
        assertEquals(1, WazePolice.latest.size)
        here = fix(10.0 + 1_500.0 / 110_574.0, 10.0) // drive 1.5 km towards it, between two checks
        watch.evaluate(2_000L, publish = false)
        assertEquals(1, alerts.size)
    }

    @Test
    fun reportsBehindYouDoNotAlertWhenOnlyAheadCounts() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0, bearing = 0f, speed = 10f) // driving north
        watch.ingest(listOf(report("behind", -800.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertTrue(alerts.isEmpty())
        assertTrue("still listed", WazePolice.latest.single().where.isBehind)
        here = fix(10.0, 10.0, bearing = 180f, speed = 10f) // turn round: now it is ahead
        // No road data in a test, so it waits for the map before geometry alone decides.
        watch.evaluate(2_000L, publish = false)
        assertTrue("waiting for the map", alerts.isEmpty())
        watch.evaluate(2_000L + WazeWatch.ROAD_HOLD_MS, publish = false)
        assertEquals(1, alerts.size)
    }

    /** A report [north] of the origin and [east] of it, in metres. */
    private fun reportAt(id: String, north: Double, east: Double) = WazePolice.Report(
        id, 10.0 + north / 110_574.0, 10.0 + east / (111_320.0 * Math.cos(Math.toRadians(10.0))),
        null, null, null, 0, null, Type.POLICE
    )

    @Test
    fun aReportOffToTheSideIsNotOnYourRoadEvenThoughItIsAhead() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0, bearing = 0f, speed = 15f) // driving north
        // 150 m up the road but 120 m to the east: a parallel street, not the one you are on.
        watch.ingest(listOf(reportAt("beside", 150.0, 120.0)), WazePolice.VIA_DIRECT, 1_000L)
        watch.evaluate(1_000L + WazeWatch.ROAD_HOLD_MS, publish = false)
        assertTrue(alerts.toString(), alerts.isEmpty())
        assertEquals("still on the list and the map", 1, WazePolice.latest.size)
    }

    @Test
    fun aReportStraightAheadStillAlertsOnceTheWaitIsOver() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0, bearing = 0f, speed = 15f)
        watch.ingest(listOf(reportAt("ahead", 150.0, 8.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertTrue("held while the map is awaited", alerts.isEmpty())
        watch.evaluate(1_000L + WazeWatch.ROAD_HOLD_MS, publish = false)
        assertEquals(1, alerts.size)
    }

    @Test
    fun theWaitIsOnlyForOnlyAheadMode() {
        here = fix(10.0, 10.0, bearing = 0f, speed = 15f)
        watch.ingest(listOf(reportAt("beside", 150.0, 120.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals("no road filtering asked for: alerts at once", 1, alerts.size)
    }

    @Test
    fun behindStillAlertsWhenOnlyAheadIsOff() {
        here = fix(10.0, 10.0, bearing = 0f, speed = 10f)
        watch.ingest(listOf(report("behind", -800.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(1, alerts.size)
    }

    @Test
    fun aWeakReportAlertsLaterWhenItClearsTheThreshold() {
        Prefs.setAlertThreshold(ctx, 99)
        watch.ingest(listOf(report("a", 800.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals("seen and logged at once", 1, alerts.size)
        assertTrue(alerts[0].log)
        Prefs.setAlertThreshold(ctx, 50)
        watch.evaluate(2_000L, publish = false)
        assertEquals(2, alerts.size)
        assertFalse("already logged: only the alert is repeated", alerts[1].log)
        watch.evaluate(3_000L, publish = false)
        assertEquals(2, alerts.size)
    }

    @Test
    fun callsOutAgainAtOneKilometerFiveHundredAndTwoHundredMeters() {
        here = fix(10.0, 10.0, bearing = 0f, speed = 25f)
        watch.ingest(listOf(report("p", 1_500.0)), WazePolice.VIA_DIRECT, 0L)
        assertTrue(alerts.single().spoken!!.contains("ahead"))
        // Drive towards it at 25 m/s for a minute.
        for (t in 1..55) {
            here = fix(10.0 + 25.0 * t / 110_574.0, 10.0, bearing = 0f, speed = 25f)
            watch.evaluate(t * 1_000L, publish = false)
        }
        assertEquals(calls.toString(), 3, calls.size)
        assertTrue(calls.all { it.startsWith("Police ahead, ") })
        assertEquals("only the first sighting alerts", 1, alerts.size)
    }

    @Test
    fun noCallOutsWhenItIsNotGettingCloser() {
        here = fix(10.0, 10.0, bearing = 0f, speed = 25f)
        watch.ingest(listOf(report("p", 1_500.0)), WazePolice.VIA_DIRECT, 0L)
        for (t in 1..30) watch.evaluate(t * 1_000L, publish = false) // standing still
        assertTrue(calls.isEmpty())
    }

    @Test
    fun noCallOutsWhenSwitchedOff() {
        Prefs.setWazeApproach(ctx, false)
        here = fix(10.0, 10.0, bearing = 0f, speed = 25f)
        watch.ingest(listOf(report("p", 1_500.0)), WazePolice.VIA_DIRECT, 0L)
        for (t in 1..55) {
            here = fix(10.0 + 25.0 * t / 110_574.0, 10.0, bearing = 0f, speed = 25f)
            watch.evaluate(t * 1_000L, publish = false)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun jamsAreLoggedOnlyAndNeverTheHeadline() {
        Prefs.setWazeTypes(ctx, setOf("JAM"))
        watch.ingest(listOf(report("j", 500.0, Type.JAM)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(Level.LOG, alerts.single().level)
        assertNull(AmbientThreats.waze)
    }

    @Test
    fun hazardsDefaultToNotificationOnlyAndPoliceBecomesTheHeadline() {
        Prefs.setWazeTypes(ctx, setOf("POLICE", "HAZARD"))
        watch.ingest(listOf(report("h", 500.0, Type.HAZARD)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(Level.NOTIFY, alerts.single().level)
        watch.ingest(listOf(report("p", 400.0)), WazePolice.VIA_DIRECT, 2_000L)
        val headline = AmbientThreats.waze
        assertNotNull(headline)
        assertTrue(headline!!.second.label.startsWith("Police reported"))
    }

    @Test
    fun theLevelSetInSettingsIsUsed() {
        Prefs.setWazeLevel(ctx, Type.POLICE, Level.NOTIFY)
        watch.ingest(listOf(report("p", 500.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(Level.NOTIFY, alerts.single().level)
    }

    @Test
    fun reportsAreDroppedWhenNoCheckHasAnsweredForTooLong() {
        watch.ingest(listOf(report("a", 800.0)), WazePolice.VIA_DIRECT, 0L)
        assertEquals(1, WazePolice.latest.size)
        watch.evaluate(20 * 60_000L, publish = false)
        assertTrue(WazePolice.latest.isEmpty())
    }

    @Test
    fun screensAreWokenOnlyWhenSomethingTheyShowHasChanged() {
        var wakes = 0
        val stop = WazePolice.addListener { wakes++ }
        try {
            watch.ingest(listOf(report("a", 800.0)), WazePolice.VIA_DIRECT, 1_000L)
            val afterCheck = wakes
            assertTrue(afterCheck >= 1)
            watch.evaluate(2_000L, publish = false)
            watch.evaluate(3_000L, publish = false)
            assertEquals("nothing changed: no wake-up", afterCheck, wakes)
            here = fix(10.0 + 6_000.0 / 110_574.0, 10.0) // drive away until it leaves the 5 km view
            watch.evaluate(4_000L, publish = false)
            assertTrue("the report left the view", wakes > afterCheck)
            assertTrue(WazePolice.latest.isEmpty())
        } finally {
            stop()
        }
    }

    @Test
    fun distanceAndDirectionFollowYouBetweenChecks() {
        here = fix(10.0, 10.0, bearing = 0f, speed = 10f)
        watch.ingest(listOf(report("a", 800.0)), WazePolice.VIA_DIRECT, 0L)
        val first = WazePolice.latest.single().distanceM
        here = fix(10.0 + 300.0 / 110_574.0, 10.0, bearing = 0f, speed = 10f)
        watch.evaluate(1_000L, publish = false)
        val second = WazePolice.latest.single().distanceM
        assertTrue("$first -> $second", first - second in 290.0..310.0)
        assertEquals(WazePolice.Side.AHEAD, WazePolice.latest.single().where.side)
    }

    // ---- while guided, "ahead" is the route ----

    /** Guidance along a road running 3 km due north from the origin, starting from [from]. */
    private fun guidedNorth(from: Location) {
        val ys = (0..30).map { it * 100.0 }
        val lats = DoubleArray(ys.size) { 10.0 + ys[it] / 110_540.0 }
        val lons = DoubleArray(ys.size) { 10.0 }
        val along = DoubleArray(ys.size) { ys[it] }
        val step = Step("depart", null, "", null, "", 0.0, 0.0, 3_000.0, 180.0, 0.0, 3_000.0, 0, emptyList(), emptyList())
        val route = Route(lats, lons, along, 3_000.0, 180.0, listOf(step), "", lats.last() to 10.0)
        Navigator.speaker = { _, _ -> }
        Prefs.setNavStartScan(ctx, false)
        Navigator.begin(ctx, Place("Destination", "", lats.last(), 10.0), route, Valhalla.Options(), emptyList(), from)
    }

    @Test
    fun whileGuidedAReportOffTheRouteIsQuietEvenWhenTheGpsHasNoHeading() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0) // at a light, or crawling: no bearing and no speed
        guidedNorth(here)
        watch.ingest(listOf(report("onroute", 800.0), reportAt("beside", 300.0, -700.0)), WazePolice.VIA_DIRECT, 1_000L)
        // The route needs no map data, so the one on it alerts at once; the one 700 m off to the side never does.
        assertEquals(listOf("waze:onroute"), alerts.map { it.key })
        assertEquals("both stay on the map", 2, WazePolice.latest.size)
        assertEquals("the list keeps only what is on your way", listOf("onroute"),
            WazePolice.latest.filter { it.onMyWay }.map { it.report.id })
    }

    @Test
    fun whileGuidedAReportYouHavePassedIsQuiet() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0 + 1_500.0 / 110_540.0, 10.0) // 1.5 km along the route
        guidedNorth(here)
        watch.ingest(listOf(report("passed", 1_100.0), report("coming", 2_000.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals(listOf("waze:coming"), alerts.map { it.key })
        assertEquals(listOf("coming"), WazePolice.latest.filter { it.onMyWay }.map { it.report.id })
    }

    @Test
    fun withOnlyAheadOffGuidanceChangesNothing() {
        Prefs.setWazeAheadOnly(ctx, false)
        here = fix(10.0, 10.0)
        guidedNorth(here)
        watch.ingest(listOf(report("onroute", 800.0), reportAt("beside", 300.0, -700.0)), WazePolice.VIA_DIRECT, 1_000L)
        assertEquals("everything in range, as chosen", 2, alerts.size)
        assertTrue(WazePolice.latest.all { it.onMyWay })
    }

    @Test
    fun leavingTheRouteHandsOverToTheRoadsAndTheHeadingAgain() {
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0)
        guidedNorth(here)
        // 400 m east of the route (off it, being re-planned): the route no longer decides.
        Navigator.planner = { _, _, _, _, _ -> throw java.io.IOException("offline") }
        here = fix(10.0, 10.0 + 400.0 / (111_320.0 * Math.cos(Math.toRadians(10.0))), bearing = 0f, speed = 12f)
        val onLocation = Navigator::class.java.getDeclaredMethod("onLocation", Location::class.java)
        onLocation.isAccessible = true
        onLocation.invoke(Navigator, here)
        assertTrue("the guidance knows it is off the route", Navigator.state!!.offM > Navigator.OFF_ROUTE_M)
        // A report 700 m ahead on that road, beside where you now are.
        watch.ingest(listOf(reportAt("beside", 300.0, 410.0)), WazePolice.VIA_DIRECT, 1_000L)
        // No verdict from the route; the (empty here) map decides after its wait, as before guidance existed.
        assertTrue(alerts.isEmpty())
        watch.evaluate(1_000L + WazeWatch.ROAD_HOLD_MS, publish = false)
        assertEquals(1, alerts.size)
    }

    @Test
    fun theListLeavesOutWhatIsNotOnYourWayByDefaultAndTheSettingBringsItBack() {
        assertTrue("hidden by default", Prefs.wazeHideOffWay(ctx))
        Prefs.setWazeAheadOnly(ctx, true)
        here = fix(10.0, 10.0)
        guidedNorth(here)
        watch.ingest(listOf(report("onroute", 800.0), reportAt("beside", 300.0, -700.0)), WazePolice.VIA_DIRECT, 1_000L)
        val all = WazePolice.latest
        assertEquals(listOf("onroute"), WazePolice.forList(all, 2_000.0, aheadOnly = true, hideOffWay = true).map { it.report.id })
        assertEquals("the setting off: listed again (quiet, marked)", setOf("onroute", "beside"),
            WazePolice.forList(all, 2_000.0, aheadOnly = true, hideOffWay = false).map { it.report.id }.toSet())
        assertEquals("with only-ahead off nothing is hidden", 2,
            WazePolice.forList(all, 2_000.0, aheadOnly = false, hideOffWay = true).size)
        assertEquals("the alert range still applies", 0, WazePolice.forList(all, 100.0, aheadOnly = false, hideOffWay = true).size)
    }
}
