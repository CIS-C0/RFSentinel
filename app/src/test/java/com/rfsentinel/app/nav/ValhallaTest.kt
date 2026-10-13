package com.rfsentinel.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The route service's reply (a real one, trimmed: public map data) and the request we send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ValhallaTest {

    private val json = javaClass.getResource("/valhalla_route.json")!!.readText()
    private val routes = Valhalla.parse(json)
    private val route = routes[0]

    @Test
    fun readsEveryRouteOffered() {
        assertEquals(2, routes.size)
        assertEquals(8124.0, route.distanceM, 1.0)
        assertEquals(1110.0, route.durationS, 1.0)
        assertTrue(route.summary, route.summary.contains("Bayerstra"))
        assertEquals(12, route.steps.size)
    }

    @Test
    fun theLineIsContinuousAndMeasuredAlongItself() {
        assertTrue(route.size > 30)
        assertEquals(0.0, route.along[0], 0.0)
        for (i in 1 until route.size) assertTrue("along rises at $i", route.along[i] > route.along[i - 1])
        // The measured length matches the service's own figure (it rounds, and the line is an approximation).
        assertEquals(route.distanceM, route.lengthM, route.distanceM * 0.02)
        // Starts in the city centre it was asked for.
        assertEquals(48.1374, route.lats[0], 0.001)
        assertEquals(11.5755, route.lons[0], 0.001)
    }

    @Test
    fun stepsFollowOnFromEachOther() {
        for (i in 1 until route.steps.size) {
            assertEquals("step $i starts where step ${i - 1} ended", route.steps[i - 1].endM, route.steps[i].startM, 0.5)
        }
        assertEquals("depart", route.steps.first().type)
        assertTrue(route.steps.last().isArrival)
        assertEquals(route.lengthM, route.steps.last().endM, 1.0)
        // A step's own length is about what its line measures.
        for (s in route.steps) assertEquals(s.lengthM, s.endM - s.startM, maxOf(8.0, s.lengthM * 0.05))
    }

    @Test
    fun voicePromptsComeFarthestFirstWithTheirWords() {
        val step = route.steps[6] // the long stretch with the ramp to take at its end
        assertTrue(step.voice.size >= 3)
        assertEquals(step.voice.sortedByDescending { it.beforeM }, step.voice)
        assertTrue(step.voice.first().text, step.voice.first().text.startsWith("Continue for"))
        assertTrue(step.voice.any { it.text.startsWith("In 300 meters") })
        assertTrue(step.voice.last().text, step.voice.last().text.contains("ramp"))
    }

    @Test
    fun lanesAreReadFromTheBannerAndShowWhichToBeIn() {
        val step = route.steps[6]
        val withLanes = step.banners.first { it.lanes.isNotEmpty() }
        assertEquals(3, withLanes.lanes.size)
        assertEquals(listOf("left"), withLanes.lanes[0].directions)
        assertFalse(withLanes.lanes[0].active)
        assertEquals(listOf("straight", "right"), withLanes.lanes[2].directions)
        assertTrue(withLanes.lanes[2].active)
        assertEquals("right", withLanes.lanes[2].activeDirection)
        // The farther banner (no lanes yet) comes first; the lanes appear closer to the turn.
        assertTrue(step.banners.first().beforeM > withLanes.beforeM)
        // A lane that serves two directions names the one to take.
        val two = route.steps.flatMap { it.banners }.flatMap { it.lanes }.first { it.directions.size == 2 && it.active }
        assertNotNull(two.activeDirection)
    }

    @Test
    fun bannerChoiceFollowsTheDistanceToTheTurn() {
        val far = Prompts.banner(route, 6, 2_000.0)!!
        val near = Prompts.banner(route, 6, 300.0)!!
        assertTrue("far: no lanes yet", far.lanes.isEmpty())
        assertTrue("near: lanes shown", near.lanes.isNotEmpty())
    }

    @Test
    fun polylineSixDecodes() {
        // The first step's own line.
        val pts = Valhalla.decodePolyline6("qt_yzAsmmaU|CoAfB}@pFsB^OzAk@")
        assertEquals(6, pts.size)
        assertEquals(48.1365, pts[0].first, 0.001)
        assertEquals(11.5745, pts[0].second, 0.001)
        // Each point is a little way from the last (a short city street).
        for (i in 1 until pts.size) assertTrue(Math.abs(pts[i].first - pts[i - 1].first) < 0.002)
    }

    @Test
    fun anErrorReplyBecomesAReadableMessage() {
        val e = runCatching { Valhalla.parse("""{"code":"NoRoute","message":"No path could be found for input"}""") }.exceptionOrNull()
        assertTrue(e is Valhalla.NoRouteException)
        assertEquals("No route found between these places", e!!.message)
        assertEquals("Too far for the route server (it plans up to a few hundred km)",
            Valhalla.errorOf("""{"code":"Other","message":"Path distance exceeds the max distance limit"}"""))
        assertNull(Valhalla.errorOf("not json"))
    }

    // ---- the request ----

    private val opts = Valhalla.Options()

    @Test
    fun theRequestAsksForEverythingNavigationNeeds() {
        val r = Valhalla.request(48.0, 11.0, 90.0, emptyList(), 48.1 to 11.1, opts)
        assertEquals("auto", r.getString("costing"))
        assertEquals("osrm", r.getString("format"))
        assertEquals("polyline6", r.getString("shape_format"))
        assertTrue(r.getBoolean("banner_instructions"))
        assertTrue(r.getBoolean("voice_instructions"))
        assertTrue(r.getBoolean("turn_lanes"))
        assertEquals(2, r.getInt("alternates"))
        val from = r.getJSONArray("locations").getJSONObject(0)
        assertEquals(90, from.getInt("heading")) // not to start with a U-turn
        assertEquals(2, r.getJSONArray("locations").length())
        assertEquals("kilometers", r.getJSONObject("directions_options").getString("units"))
    }

    @Test
    fun noHeadingWhenNotMovingAndNoAlternativesWithStops() {
        val r = Valhalla.request(48.0, 11.0, null, listOf(48.05 to 11.05), 48.1 to 11.1, opts, miles = true)
        assertFalse(r.getJSONArray("locations").getJSONObject(0).has("heading"))
        assertFalse("alternatives aren't offered on a route with a stop", r.has("alternates"))
        val locs = r.getJSONArray("locations")
        assertEquals(3, locs.length())
        assertEquals("break", locs.getJSONObject(1).getString("type"))
        assertEquals("miles", r.getJSONObject("directions_options").getString("units"))
    }

    @Test
    fun avoidingTollsHighwaysAndFerries() {
        val r = Valhalla.request(48.0, 11.0, null, emptyList(), 48.1 to 11.1,
            Valhalla.Options(avoidTolls = true, avoidHighways = true, avoidFerries = true, alternatives = 0))
        val auto = r.getJSONObject("costing_options").getJSONObject("auto")
        assertEquals(0.0, auto.getDouble("use_tolls"), 0.0)
        assertEquals(0.0, auto.getDouble("use_highways"), 0.0)
        assertEquals(0.0, auto.getDouble("use_ferry"), 0.0)
        assertFalse(r.has("alternates"))
        val plain = Valhalla.request(48.0, 11.0, null, emptyList(), 48.1 to 11.1, opts).getJSONObject("costing_options").getJSONObject("auto")
        assertEquals(1.0, plain.getDouble("use_highways"), 0.0)
        assertEquals(0.5, plain.getDouble("use_tolls"), 0.0)
    }
}
