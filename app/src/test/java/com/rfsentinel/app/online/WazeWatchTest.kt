package com.rfsentinel.app.online

import android.app.Application
import android.location.Location
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
        watch.evaluate(2_000L, publish = false)
        assertEquals(1, alerts.size)
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
}
