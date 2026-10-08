package com.rfsentinel.app.online

import com.rfsentinel.app.online.MotionTracker.State
import com.rfsentinel.app.util.Spoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the adaptive checks, the approach trend, the readable report text and the spoken call-outs. */
class MotionTrackerTest {
    /** Degrees of latitude covered in one second at 25 m/s (about 90 km/h). */
    private val northPerSecond = 25.0 / 111_000.0

    @Test
    fun beforeAnyPositionItActsAsDriving() {
        assertEquals(State.MOVING, MotionTracker().state(0L))
    }

    @Test
    fun parkedOnlyAfterNinetySecondsWithinSixtyMetres() {
        val m = MotionTracker()
        for (s in 0..80) m.update(10.0 + (s % 3) * 0.00005, 10.0, 0f, s * 1000L) // wanders about 5 m
        assertEquals(State.MOVING, m.state(80_000L))
        for (s in 81..95) m.update(10.0, 10.0, 0f, s * 1000L)
        assertEquals(State.PARKED, m.state(95_000L))
    }

    @Test
    fun leavingTheSpotEndsParked() {
        val m = MotionTracker()
        for (s in 0..100) m.update(10.0, 10.0, 0f, s * 1000L)
        assertEquals(State.PARKED, m.state(100_000L))
        m.update(10.0 + 200.0 / 111_000.0, 10.0, 5f, 101_000L) // 200 m away
        assertEquals(State.MOVING, m.state(101_000L))
    }

    @Test
    fun fastRoadFromTheFixSpeed() {
        val m = MotionTracker()
        for (s in 0..10) m.update(10.0 + s * northPerSecond, 10.0, 25f, s * 1000L)
        assertEquals(State.FAST, m.state(10_000L))
    }

    @Test
    fun speedIsWorkedOutFromPositionsWhenTheFixHasNone() {
        val m = MotionTracker()
        for (s in 0..10) m.update(10.0 + s * northPerSecond, 10.0, null, s * 1000L)
        assertEquals(State.FAST, m.state(10_000L))
    }

    @Test
    fun walkingPaceIsJustMoving() {
        val m = MotionTracker()
        for (s in 0..120) m.update(10.0 + s * (1.4 / 111_000.0), 10.0, 1.4f, s * 1000L)
        assertEquals(State.MOVING, m.state(120_000L))
    }
}

class WazeTimingTest {
    @Test
    fun withAdaptingOffItIsTheChosenInterval() {
        for (s in State.entries) assertEquals(60, WazeTiming.effectiveS(60, s, true, false))
    }

    @Test
    fun directParkedIsFourTimesSlowerButNeverOverFiveMinutes() {
        assertEquals(240, WazeTiming.effectiveS(60, State.PARKED, true, true))
        assertEquals(300, WazeTiming.effectiveS(90, State.PARKED, true, true))
        assertEquals(300, WazeTiming.effectiveS(300, State.PARKED, true, true))
        assertEquals(60, WazeTiming.effectiveS(15, State.PARKED, true, true))
    }

    @Test
    fun directOnAFastRoadIsTwiceAsOftenButNeverUnderFifteenSeconds() {
        assertEquals(30, WazeTiming.effectiveS(60, State.FAST, true, true))
        assertEquals(15, WazeTiming.effectiveS(30, State.FAST, true, true))
        assertEquals(15, WazeTiming.effectiveS(15, State.FAST, true, true))
        assertEquals(60, WazeTiming.effectiveS(60, State.MOVING, true, true))
    }

    @Test
    fun openWebNinjaOnlySlowsDownBecauseEveryCheckIsBilled() {
        assertEquals(600, WazeTiming.effectiveS(240, State.PARKED, false, true))
        assertEquals(480, WazeTiming.effectiveS(120, State.PARKED, false, true))
        assertEquals(600, WazeTiming.effectiveS(600, State.PARKED, false, true))
        assertEquals(240, WazeTiming.effectiveS(240, State.FAST, false, true))
        assertEquals(180, WazeTiming.effectiveS(180, State.MOVING, false, true))
    }

    @Test
    fun aCheckIsDueFirstThenWhenTheIntervalRunsOutOrWhenAskedForByHand() {
        assertTrue("the very first", WazeTiming.checkDue(1_000L, 0L, 60, false, 10))
        assertFalse(WazeTiming.checkDue(50_000L, 1_000L, 60, false, 10))      // 49 s into 60 s
        assertTrue(WazeTiming.checkDue(61_000L, 1_000L, 60, false, 10))       // 60 s passed
        assertTrue("asked for, well after the last", WazeTiming.checkDue(20_000L, 1_000L, 60, true, 10))
        assertFalse("asked for, too soon after the last", WazeTiming.checkDue(5_000L, 1_000L, 60, true, 10))
        assertFalse("nothing asked, not due", WazeTiming.checkDue(20_000L, 1_000L, 60, false, 10))
    }

    @Test
    fun manualChecksAreSpacedOutAndTheSlidersHaveTheirSteps() {
        assertEquals(10, WazeTiming.minGapS(true))
        assertEquals(60, WazeTiming.minGapS(false))
        assertEquals(listOf(15, 30, 60, 90, 120, 300), WazeTiming.DIRECT_STEPS_S.toList())
        assertEquals(listOf(120, 180, 240, 300, 600), WazeTiming.NINJA_STEPS_S.toList())
    }
}

class WazeTrendTest {
    @Test
    fun needsAFewSecondsOfHistory() {
        val t = WazeTrend()
        t.update("a", 500.0, 0L)
        t.update("a", 400.0, 2_000L)
        assertEquals(WazeTrend.Trend.STEADY, t.trend("a"))
    }

    @Test
    fun closingWhenTheDistanceDropsByFifteenMetresOrMore() {
        val t = WazeTrend()
        t.update("a", 500.0, 0L)
        t.update("a", 480.0, 3_000L)
        t.update("a", 450.0, 5_000L)
        assertEquals(WazeTrend.Trend.CLOSING, t.trend("a"))
    }

    @Test
    fun awayWhenItGrows() {
        val t = WazeTrend()
        t.update("a", 450.0, 0L)
        t.update("a", 500.0, 6_000L)
        assertEquals(WazeTrend.Trend.AWAY, t.trend("a"))
    }

    @Test
    fun smallChangesAreSteady() {
        val t = WazeTrend()
        t.update("a", 500.0, 0L)
        t.update("a", 490.0, 6_000L)
        assertEquals(WazeTrend.Trend.STEADY, t.trend("a"))
    }

    @Test
    fun onlyTheLastTenSecondsCount() {
        val t = WazeTrend()
        t.update("a", 1000.0, 0L)
        t.update("a", 900.0, 5_000L)
        t.update("a", 900.0, 20_000L)
        t.update("a", 900.0, 25_000L) // it was closing early on, but has been still for a while
        assertEquals(WazeTrend.Trend.STEADY, t.trend("a"))
    }

    @Test
    fun forgetsReportsThatAreGone() {
        val t = WazeTrend()
        t.update("a", 500.0, 0L)
        t.update("a", 400.0, 6_000L)
        t.keepOnly(emptySet())
        assertEquals(WazeTrend.Trend.STEADY, t.trend("a"))
    }
}

class WazeWhereTest {
    private fun at(heading: Double?, dLat: Double, dLon: Double) =
        WazePolice.where(10.0, 10.0, heading, 10.0 + dLat, 10.0 + dLon)

    @Test
    fun straightAheadWhenDrivingNorthToAPointNorth() {
        val w = at(0.0, 0.008, 0.0)
        assertEquals("N", w.compass)
        assertEquals(WazePolice.Side.AHEAD, w.side)
        assertEquals(889.6, w.distanceM, 3.0)
        assertFalse(w.isBehind)
    }

    @Test
    fun behindWhenDrivingAwayFromIt() {
        val w = at(0.0, -0.008, 0.0)
        assertEquals("S", w.compass)
        assertEquals(WazePolice.Side.BEHIND, w.side)
        assertTrue(w.isBehind)
    }

    @Test
    fun leftAndRightFollowTheHeading() {
        assertEquals(WazePolice.Side.RIGHT, at(0.0, 0.0, 0.008).side)
        assertEquals("E", at(0.0, 0.0, 0.008).compass)
        assertEquals(WazePolice.Side.LEFT, at(0.0, 0.0, -0.008).side)
        // Heading east: north is now on the left.
        assertEquals(WazePolice.Side.LEFT, at(90.0, 0.008, 0.0).side)
    }

    @Test
    fun withoutAHeadingThereIsNoSideAndNothingIsBehind() {
        val w = at(null, -0.008, 0.0)
        assertNull(w.side)
        assertNull(w.relDeg)
        assertFalse(w.isBehind)
        assertEquals("S", w.compass)
    }

    @Test
    fun behindStartsPastOneHundredDegreesOffTheHeading() {
        // 120 degrees off the heading: still called "to your right", but already counted as behind.
        val w = at(0.0, -0.004, 0.00704)
        assertEquals(WazePolice.Side.RIGHT, w.side)
        assertTrue(w.isBehind)
        // 80 degrees off: not behind.
        assertFalse(at(0.0, 0.001, 0.0057).isBehind)
    }

    @Test
    fun relativeTextSaysWhereAndWhichWay() {
        val ahead = WazePolice.Where(412.0, "NE", WazePolice.Side.AHEAD, 10f)
        assertEquals("412 m NE · ahead · closing in", WazePolice.relativeText(ahead, WazeTrend.Trend.CLOSING))
        assertEquals("412 m NE · ahead · moving away", WazePolice.relativeText(ahead, WazeTrend.Trend.AWAY))
        assertEquals("412 m NE · ahead", WazePolice.relativeText(ahead))
        val behind = WazePolice.Where(412.0, "SW", WazePolice.Side.BEHIND, 170f)
        assertEquals("412 m SW · behind you", WazePolice.relativeText(behind, WazeTrend.Trend.AWAY))
        assertEquals("1.25 km N", WazePolice.relativeText(WazePolice.Where(1250.0, "N", null, null)))
    }
}

class WazeSubtypeTest {
    @Test
    fun knownSubtypesReadNaturally() {
        assertEquals("hiding", WazePolice.subtypeLabel("POLICE_HIDING"))
        assertEquals("visible", WazePolice.subtypeLabel("POLICE_VISIBLE"))
        assertEquals("mobile speed camera", WazePolice.subtypeLabel("POLICE_WITH_MOBILE_CAMERA"))
        assertEquals("major", WazePolice.subtypeLabel("ACCIDENT_MAJOR"))
        assertEquals("standstill", WazePolice.subtypeLabel("JAM_STAND_STILL_TRAFFIC"))
        assertEquals("pothole", WazePolice.subtypeLabel("HAZARD_ON_ROAD_POT_HOLE"))
        assertEquals("heavy rain", WazePolice.subtypeLabel("HAZARD_WEATHER_HEAVY_RAIN"))
        assertEquals("event", WazePolice.subtypeLabel("ROAD_CLOSED_EVENT"))
    }

    @Test
    fun unknownOnesAreTidiedUpAndNoneIsNull() {
        assertEquals("something new", WazePolice.subtypeLabel("HAZARD_SOMETHING_NEW"))
        assertEquals("hiding", WazePolice.subtypeLabel("police_hiding"))
        assertNull(WazePolice.subtypeLabel(null))
        assertNull(WazePolice.subtypeLabel(""))
        assertNull(WazePolice.subtypeLabel("NO_SUBTYPE"))
    }

    @Test
    fun theSubtypeIsInTheLabelAndTheTypeStillResolves() {
        val r = WazePolice.Report("x", 10.0, 10.0, null, null, null, 0, null, WazePolice.Type.POLICE, "POLICE_HIDING")
        val hit = WazePolice.hit(r, 10.0, 10.0, 0L)!!.first
        assertEquals("Police reported on Waze (hiding)", hit.label)
        assertEquals(WazePolice.Type.POLICE, WazePolice.Type.ofHit(hit))
        val plain = WazePolice.hit(r.copy(subtype = null), 10.0, 10.0, 0L)!!.first
        assertEquals("Police reported on Waze", plain.label)
    }

    @Test
    fun ninjaSubtypesAreRead() {
        val json = """{"data":{"alerts":[{"alert_id":"a","type":"POLICE","subtype":"POLICE_HIDING","latitude":10.0,"longitude":10.0}]}}"""
        assertEquals("POLICE_HIDING", WazePolice.parse(json).single().subtype)
    }
}

class WazeSpokenTest {
    @Test
    fun distancesAreSaidInWords() {
        assertEquals("50 meters", Spoken.distanceWords(20.0))
        assertEquals("300 meters", Spoken.distanceWords(310.0))
        assertEquals("950 meters", Spoken.distanceWords(940.0))
        assertEquals("1 kilometer", Spoken.distanceWords(990.0))
        assertEquals("1.2 kilometers", Spoken.distanceWords(1200.0))
        assertEquals("2 kilometers", Spoken.distanceWords(2000.0))
    }

    @Test
    fun theShortCallOutNamesTheEventTheSideAndTheDistance() {
        val police = WazePolice.Type.POLICE
        assertEquals("Police ahead, 800 meters", Spoken.wazePhrase(police, 800.0, WazePolice.Side.AHEAD, true))
        assertEquals("Police, 800 meters", Spoken.wazePhrase(police, 800.0, null, true))
        assertEquals("Accident behind you, 400 meters", Spoken.wazePhrase(WazePolice.Type.ACCIDENT, 400.0, WazePolice.Side.BEHIND, true))
        assertEquals("Hazard on your left, 150 meters", Spoken.wazePhrase(WazePolice.Type.HAZARD, 150.0, WazePolice.Side.LEFT, true))
    }

    @Test
    fun theFullCallOutSaysItIsFromWaze() {
        assertEquals("Police reported on Waze, 800 meters ahead",
            Spoken.wazePhrase(WazePolice.Type.POLICE, 800.0, WazePolice.Side.AHEAD, false))
        assertEquals("Traffic jam reported on Waze, 1.5 kilometers",
            Spoken.wazePhrase(WazePolice.Type.JAM, 1500.0, null, false))
    }
}

class WazeLevelDefaultsTest {
    @Test
    fun policeIsLoudJamsAreSilentTheRestNotifyOnly() {
        assertEquals(WazePolice.Level.LOUD, WazePolice.Type.POLICE.defaultLevel)
        assertEquals(WazePolice.Level.LOUD, WazePolice.Type.ACCIDENT.defaultLevel)
        assertEquals(WazePolice.Level.NOTIFY, WazePolice.Type.HAZARD.defaultLevel)
        assertEquals(WazePolice.Level.NOTIFY, WazePolice.Type.ROAD_CLOSED.defaultLevel)
        assertEquals(WazePolice.Level.LOG, WazePolice.Type.JAM.defaultLevel)
    }
}
