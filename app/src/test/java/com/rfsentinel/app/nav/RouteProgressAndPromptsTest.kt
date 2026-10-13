package com.rfsentinel.app.nav

import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where you are on a route, and which spoken prompt is due - on made-up roads in metres around (10, 20). */
class RouteProgressAndPromptsTest {

    private val lat0 = 10.0; private val lon0 = 20.0
    // Metres as the great-circle formula counts them (what a route's own distances use).
    private val mPerDeg = 111_194.9
    private fun lat(y: Double) = lat0 + y / mPerDeg
    private fun lon(x: Double) = lon0 + x / (mPerDeg * Math.cos(Math.toRadians(lat0)))

    private fun step(type: String, mod: String?, startM: Double, endM: Double, leg: Int = 0,
                     voice: List<Step.Voice> = emptyList(), banners: List<Step.Banner> = emptyList()) =
        Step(type, mod, "", null, "", 0.0, 0.0, endM - startM, (endM - startM) / 15.0, startM, endM, leg, voice, banners)

    /** A route through [pts] (x east, y north, metres) with [steps]. */
    private fun route(pts: List<Pair<Double, Double>>, steps: List<Step>): Route {
        val lats = pts.map { lat(it.second) }.toDoubleArray(); val lons = pts.map { lon(it.first) }.toDoubleArray()
        val along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(lats, lons, along, along.last(), along.last() / 15.0, steps, "", lats.last() to lons.last())
    }

    /** North for 2 km: depart 0-500, then a long stretch 500-1500, then 1500-2000, then arrive. */
    private val straight by lazy {
        route((0..20).map { 0.0 to it * 100.0 }, listOf(
            step("depart", null, 0.0, 500.0),
            step("turn", "right", 500.0, 1500.0),
            step("turn", "left", 1500.0, 2000.0),
            step("arrive", null, 2000.0, 2000.0)))
    }

    private fun locate(r: Route, x: Double, y: Double, bearing: Double? = null, last: RouteProgress.State? = null) =
        RouteProgress.locate(r, lat(y), lon(x), bearing, last)

    @Test
    fun knowsTheStepAndWhatIsLeft() {
        val s = locate(straight, 3.0, 700.0, 0.0)
        assertEquals(700.0, s.alongM, 3.0)
        assertEquals(1, s.stepIndex)
        assertEquals("to the turn at the end of the step", 800.0, s.toManeuverM, 3.0)
        assertEquals(1300.0, s.remainingM, 3.0)
        assertEquals(3.0, s.offM, 1.0)
        assertTrue("time left is about what's left at the route's speed", Math.abs(s.remainingS - 1300.0 / 15.0) < 8.0)
    }

    @Test
    fun stepsChangeWhenYouPassTheirStart() {
        assertEquals(0, locate(straight, 0.0, 400.0, 0.0).stepIndex)
        assertEquals(1, locate(straight, 0.0, 600.0, 0.0).stepIndex)
        assertEquals(2, locate(straight, 0.0, 1600.0, 0.0).stepIndex)
    }

    @Test
    fun howFarOffTheLineYouAre() {
        assertEquals(45.0, locate(straight, 45.0, 900.0, 0.0).offM, 2.0)
        assertTrue(locate(straight, 400.0, 900.0).offM > 300)
    }

    @Test
    fun headingAgainstTheLineIsMeasured() {
        assertEquals(0.0, locate(straight, 0.0, 900.0, 0.0).headingOffDeg!!, 1.0)
        assertEquals(180.0, locate(straight, 0.0, 900.0, 180.0).headingOffDeg!!, 1.0)
        assertNull("not moving: no heading to compare", locate(straight, 0.0, 900.0, null).headingOffDeg)
    }

    @Test
    fun aRoadThatDoublesBackDoesNotMakeYouJump() {
        // North for 1 km, across 20 m, and back south beside itself: both stretches are 20 m apart.
        val out = (0..10).map { 0.0 to it * 100.0 }
        val back = (0..10).map { 20.0 to 1000.0 - it * 100.0 }
        val u = route(out + back, listOf(step("depart", null, 0.0, 1020.0), step("turn", "right", 1020.0, 2020.0), step("arrive", null, 2020.0, 2020.0)))
        // On the way out at 500 m, then a moment later still on the way out.
        val first = locate(u, 0.0, 500.0, 0.0)
        assertEquals(500.0, first.alongM, 5.0)
        val next = locate(u, 0.0, 520.0, 0.0, first)
        assertEquals(520.0, next.alongM, 5.0)
        // On the way back at the same spot (heading south): the later half, not the first.
        val coming = locate(u, 20.0, 500.0, 180.0, locate(u, 20.0, 700.0, 180.0, locate(u, 20.0, 900.0, 180.0, null)))
        assertEquals(1020.0 + 500.0, coming.alongM, 10.0)
    }

    @Test
    fun withoutAPreviousMatchTheWholeRouteIsSearched() {
        val s = locate(straight, 0.0, 1800.0, 0.0, null)
        assertEquals(1800.0, s.alongM, 3.0)
    }

    @Test
    fun pointsAlongTheRoute() {
        val p = RouteProgress.pointAt(straight, 750.0)
        assertEquals(lat(750.0), p.first, 0.0001)
        assertEquals(straight.end, RouteProgress.pointAt(straight, 99_999.0))
        val ahead = straight.pointsFrom(1_950.0)
        assertEquals("from where you are to the end", 2, ahead.size)
    }

    @Test
    fun aPlaceBesideTheRouteIsProjectedOnto() {
        val (along, off) = RouteProgress.project(straight, lat(1200.0), lon(25.0))
        assertEquals(1200.0, along, 3.0)
        assertEquals(25.0, off, 2.0)
    }

    // ---- spoken prompts ----

    private fun voices() = listOf(Step.Voice(1000.0, "Continue for 1 kilometer."), Step.Voice(500.0, "In 500 meters, turn right."), Step.Voice(80.0, "Turn right."))

    private val promptRoute by lazy {
        route((0..20).map { 0.0 to it * 100.0 }, listOf(
            step("depart", null, 0.0, 500.0),
            step("turn", "right", 500.0, 1500.0, voice = voices()),
            step("arrive", null, 1500.0, 2000.0)))
    }

    @Test
    fun eachPromptIsSpokenOnceAsTheTurnComesNearer() {
        val p = Prompts()
        assertEquals("Continue for 1 kilometer.", p.next(promptRoute, 1, 1000.0, 15.0)?.text)
        assertNull(p.next(promptRoute, 1, 900.0, 15.0))
        val mid = p.next(promptRoute, 1, 520.0, 15.0)!!
        assertEquals("In 500 meters, turn right.", mid.text)
        assertFalse("not the last one before the turn", mid.urgent)
        assertNull(p.next(promptRoute, 1, 400.0, 15.0))
        val last = p.next(promptRoute, 1, 100.0, 15.0)!! // a little early: speech takes a moment to start
        assertEquals("Turn right.", last.text)
        assertTrue("the last prompt before a turn may cut into other speech", last.urgent)
        assertNull("never twice", p.next(promptRoute, 1, 90.0, 15.0))
    }

    @Test
    fun joiningAStepLateSkipsWhatIsNoLongerTrue() {
        // 250 m from the turn: "In 500 meters" would be wrong, and so would "Continue for 1 kilometer".
        val p = Prompts()
        assertNull(p.next(promptRoute, 1, 250.0, 15.0))
        assertEquals("Turn right.", p.next(promptRoute, 1, 100.0, 15.0)?.text)
        // Straight into the last 100 m: only the last prompt.
        assertEquals("Turn right.", Prompts().next(promptRoute, 1, 60.0, 15.0)?.text)
    }

    @Test
    fun aFasterCarIsToldEarlier() {
        val slow = Prompts().next(promptRoute, 1, 110.0, 5.0)
        val fast = Prompts().next(promptRoute, 1, 110.0, 30.0)
        assertNull("5 m/s: the prompt is due at 87 m", slow)
        assertEquals("Turn right.", fast?.text) // 30 m/s: due at 80 + 45
    }

    @Test
    fun promptsForAnotherStepAreIndependent() {
        val p = Prompts()
        assertNotNull(p.next(promptRoute, 1, 1000.0, 15.0))
        assertNull("the arrival step has no prompts of its own", p.next(promptRoute, 2, 400.0, 15.0))
        p.reset()
        assertNotNull("a new route starts afresh", p.next(promptRoute, 1, 1000.0, 15.0))
    }

    private val longStretch by lazy {
        route((0..40).map { 0.0 to it * 100.0 }, listOf(
            step("depart", null, 0.0, 100.0),
            step("turn", "right", 100.0, 4100.0, voice = listOf(
                Step.Voice(3990.0, "Continue for 4 kilometers."), Step.Voice(500.0, "In 500 meters, keep right."), Step.Voice(150.0, "Keep right."))),
            step("arrive", null, 4100.0, 4100.0)))
    }

    @Test
    fun aLongStretchGetsAHeadsUpTwoKilometersOut() {
        val withUp = Prompts().withHeadsUp(longStretch.steps[1])
        assertEquals(4, withUp.size)
        assertEquals("In 2 kilometers, keep right.", withUp.first { it.beforeM == 2000.0 }.text)
        assertEquals("In 1 mile, keep right.", Prompts(miles = true).withHeadsUp(longStretch.steps[1]).first { it.beforeM == 1609.0 }.text)
        val p = Prompts()
        p.next(longStretch, 1, 3990.0, 15.0)
        assertEquals("In 2 kilometers, keep right.", p.next(longStretch, 1, 2_010.0, 15.0)?.text)
        assertEquals("In 500 meters, keep right.", p.next(longStretch, 1, 520.0, 15.0)?.text)
    }

    @Test
    fun noHeadsUpOnAShortStretchOrWhenOneIsAlreadyThere() {
        assertEquals(3, Prompts().withHeadsUp(promptRoute.steps[1]).size)
        val near = longStretch.steps[1].copy(voice = longStretch.steps[1].voice + Step.Voice(1800.0, "In 2 kilometers, keep right."))
        assertEquals(4, Prompts().withHeadsUp(near).size)
    }

    @Test
    fun theComingTurnAndTheOneAfterIt() {
        val banners = listOf(Step.Banner(400.0, "Main Street", "turn", "right", emptyList()))
        val r = route((0..20).map { 0.0 to it * 100.0 }, listOf(
            step("depart", null, 0.0, 500.0),
            step("turn", "right", 500.0, 800.0, banners = listOf(Step.Banner(300.0, "Oak Road", "turn", "left", emptyList()))),
            step("turn", "left", 800.0, 1200.0),
            step("arrive", null, 1200.0, 1200.0)))
        assertEquals("Oak Road", Prompts.banner(r, 1, 250.0)?.text)
        assertEquals("a short step after the coming turn is worth a 'then'", null, Prompts.thenBanner(r, 0)?.takeIf { it.text != "Oak Road" })
        assertNull("room before the next turn: no 'then'", Prompts.thenBanner(r, 2))
        assertNull(Prompts.thenBanner(r, 3))
    }
}
