package com.rfsentinel.app.nav

import android.app.Application
import android.location.Location
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs
import org.junit.After
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

/** Following a route from fix to fix: where you are, a stop on the way, the end, and ending it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavigatorTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val lat0 = 10.0; private val lon0 = 20.0
    private val mPerDeg = 111_194.9
    private fun lat(y: Double) = lat0 + y / mPerDeg

    private var updates = 0
    private var remove: (() -> Unit)? = null

    @Before
    fun setUp() {
        Navigator.stop()
        Prefs.setNavVoice(ctx, false) // no speech engine in a unit test; the prompts have their own tests
        updates = 0
        remove = Navigator.addListener { updates++ }
    }

    @After
    fun tearDown() {
        remove?.invoke()
        Navigator.stop()
    }

    private fun step(type: String, startM: Double, endM: Double, leg: Int = 0) =
        Step(type, null, "", null, "", 0.0, 0.0, endM - startM, (endM - startM) / 15.0, startM, endM, leg, emptyList(), emptyList())

    /** North for 2 km; a stop at 1 km (end of the first leg) when [withStop]. */
    private fun route(withStop: Boolean): Route {
        val ys = (0..20).map { it * 100.0 }
        val lats = ys.map { lat(it) }.toDoubleArray(); val lons = DoubleArray(ys.size) { lon0 }
        val along = DoubleArray(ys.size)
        for (i in 1 until ys.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val steps = if (withStop) listOf(
            step("depart", 0.0, 1000.0, 0), step("arrive", 1000.0, 1000.0, 0),
            step("depart", 1000.0, 2000.0, 1), step("arrive", 2000.0, 2000.0, 1))
        else listOf(step("depart", 0.0, 2000.0), step("arrive", 2000.0, 2000.0))
        return Route(lats, lons, along, along.last(), along.last() / 15.0, steps, "", lats.last() to lons.last())
    }

    private fun fix(y: Double, x: Double = 0.0, speed: Float = 15f, bearing: Float = 0f) = Location("test").apply {
        latitude = lat(y); longitude = lon0 + x / (mPerDeg * Math.cos(Math.toRadians(lat0)))
        this.speed = speed; this.bearing = bearing
        accuracy = 5f
    }

    private fun feed(l: Location) {
        val m = Navigator::class.java.getDeclaredMethod("onLocation", Location::class.java)
        m.isAccessible = true
        m.invoke(Navigator, l)
    }

    private val dest = Place("Example Library", "1 Main Street", lat(2000.0), lon0)

    @Test
    fun followsTheRouteAndCountsDown() {
        val r = route(false)
        Navigator.begin(ctx, dest, r, Navigator.defaultOptions(ctx), emptyList(), fix(0.0))
        assertTrue(Navigator.active)
        assertEquals(dest, Navigator.destination)
        assertNotNull("the start fix already placed you", Navigator.state)
        val before = Navigator.state!!.remainingM
        feed(fix(600.0))
        val s = Navigator.state!!
        assertEquals(600.0, s.alongM, 8.0)
        assertTrue(s.remainingM < before)
        assertEquals(1400.0, s.remainingM, 10.0)
        assertTrue("listeners hear every fix", updates >= 2)
        assertFalse(Navigator.arrived)
    }

    @Test
    fun theDestinationIsRememberedForNextTime() {
        Prefs.clearRecentDestinations(ctx)
        Navigator.begin(ctx, dest, route(false), Navigator.defaultOptions(ctx), emptyList(), null)
        assertEquals(dest.name, Prefs.recentDestinations(ctx).first().name)
    }

    @Test
    fun arrivingEndsTheGuidance() {
        Navigator.begin(ctx, dest, route(false), Navigator.defaultOptions(ctx), emptyList(), fix(0.0))
        feed(fix(1_990.0))
        assertFalse("done", Navigator.active)
        assertTrue(Navigator.arrived)
        assertNull(Navigator.route)
        assertNull(Navigator.destination)
    }

    @Test
    fun aStopOnTheWayIsDroppedOnceYouAreOnTheNextLeg() {
        val stop = Place("Stop", "", lat(1000.0), lon0)
        Navigator.begin(ctx, dest, route(true), Navigator.defaultOptions(ctx), listOf(stop), fix(0.0))
        assertEquals(1, Navigator.stops.size)
        feed(fix(500.0))
        assertEquals("still heading for the stop", 1, Navigator.stops.size)
        feed(fix(1_100.0))
        assertTrue("past the stop: on to the destination", Navigator.stops.isEmpty())
        assertTrue(Navigator.active)
        feed(fix(1_990.0))
        assertTrue(Navigator.arrived)
    }

    @Test
    fun farFromTheRouteYouAreOffIt() {
        Navigator.begin(ctx, dest, route(false), Navigator.defaultOptions(ctx), emptyList(), fix(0.0))
        feed(fix(800.0, x = 250.0))
        assertTrue(Navigator.state!!.offM > Navigator.OFF_ROUTE_M)
        assertTrue("still guiding while a new route is looked for", Navigator.active)
    }

    @Test
    fun stoppingClearsEverything() {
        Navigator.begin(ctx, dest, route(false), Navigator.defaultOptions(ctx), emptyList(), fix(0.0))
        Navigator.stop()
        assertFalse(Navigator.active)
        assertNull(Navigator.state)
        assertTrue(Navigator.stops.isEmpty())
        assertFalse("ended by hand: not an arrival", Navigator.arrived)
    }

    @Test
    fun theSettingsDefaultsShapeEveryRoute() {
        Prefs.setNavAvoidTolls(ctx, true)
        Prefs.setNavAvoidFerries(ctx, true)
        val o = Navigator.defaultOptions(ctx)
        assertTrue(o.avoidTolls); assertTrue(o.avoidFerries); assertFalse(o.avoidHighways)
        Prefs.setNavAvoidTolls(ctx, false); Prefs.setNavAvoidFerries(ctx, false)
    }

    @Test
    fun placesAreKeptStarredAndForgotten() {
        Prefs.clearNavPlaces(ctx)
        val a = Place("A", "x", 10.001, 20.001)
        assertTrue(Prefs.toggleNavFavorite(ctx, a))
        assertTrue(Prefs.isNavFavorite(ctx, a))
        assertFalse("again: no longer starred", Prefs.toggleNavFavorite(ctx, a))
        Prefs.setNavHome(ctx, a)
        assertEquals("A", Prefs.navHome(ctx)?.name)
        Prefs.clearNavPlaces(ctx)
        assertNull(Prefs.navHome(ctx))
    }
}
