package com.rfsentinel.app.nav

import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The turn zoom, the sun, the pace-based arrival time, roundabouts and the category search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NavExtrasTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ---- zooming in for a turn ----

    @Test
    fun theMapZoomsInAsATurnComesUp() {
        assertEquals(0.0, NavCamera.turnBoost(1_000.0, 30f), 0.0)
        assertEquals(0.0, NavCamera.turnBoost(350.0, 30f), 0.0)
        val mid = NavCamera.turnBoost(200.0, 30f)
        assertTrue(mid > 0.3 && mid < NavCamera.MAX_BOOST)
        assertEquals(NavCamera.MAX_BOOST, NavCamera.turnBoost(60.0, 30f), 1e-9)
        assertEquals("closer than that: no further", NavCamera.MAX_BOOST, NavCamera.turnBoost(5.0, 30f), 1e-9)
        assertTrue("it grows as you get nearer", NavCamera.turnBoost(100.0, 30f) > NavCamera.turnBoost(250.0, 30f))
    }

    @Test
    fun fastRoadsGetLessAndMotorwaySpeedNone() {
        val slow = NavCamera.turnBoost(100.0, 40f)
        val quick = NavCamera.turnBoost(100.0, 70f)
        assertTrue(quick < slow)
        assertEquals(0.0, NavCamera.turnBoost(100.0, 90f), 0.0)
        assertEquals(0.0, NavCamera.turnBoost(100.0, 130f), 0.0)
    }

    // ---- the sun ----

    @Test
    fun sunHeightAtKnownTimesAndPlaces() {
        // Greenwich, midsummer noon: about 90 - 51.5 + 23.4 = 62 degrees.
        assertEquals(61.9, Sun.elevationDeg(51.5, 0.0, utc(2026, 6, 21, 12)), 1.5)
        // The same place at midnight: about 51.5 + 23.4 - 90 = -15 degrees.
        assertEquals(-15.1, Sun.elevationDeg(51.5, 0.0, utc(2026, 6, 21, 0)), 1.5)
        // The equator at the March equinox, noon: nearly overhead.
        assertTrue(Sun.elevationDeg(0.0, 0.0, utc(2026, 3, 20, 12)) > 85.0)
        // Midwinter noon in the north: low.
        assertEquals(15.1, Sun.elevationDeg(51.5, 0.0, utc(2026, 12, 21, 12)), 1.5)
    }

    @Test
    fun longitudeShiftsTheLocalHour() {
        // 6 pm UTC in the east of Asia is the middle of the night; in the west of Europe it's evening light.
        assertTrue(Sun.isNight(35.0, 135.0, utc(2026, 3, 20, 18)))
        assertFalse(Sun.isNight(48.1, 11.6, utc(2026, 3, 20, 12)))
    }

    @Test
    fun nightStartsAfterSunsetNotAtIt() {
        // Munich, late June: sunset about 19:25 UTC. At 19:00 it's light, at 21:30 it's dark, noon is day.
        assertFalse(Sun.isNight(48.1, 11.6, utc(2026, 6, 21, 19)))
        assertTrue(Sun.isNight(48.1, 11.6, utc(2026, 6, 21, 21, 30)))
        assertFalse(Sun.isNight(48.1, 11.6, utc(2026, 6, 21, 12)))
        assertTrue(Sun.isNight(48.1, 11.6, utc(2026, 6, 21, 1)))
    }

    // ---- arrival time at your pace ----

    private val lat0 = 10.0; private val lon0 = 20.0
    private fun step(startM: Double, endM: Double, durationS: Double, type: String = "turn") =
        Step(type, null, "", null, "", 0.0, 0.0, endM - startM, durationS, startM, endM, 0, emptyList(), emptyList())

    /** 10 km north that the route plans to take 600 s (50 km/h... 16.7 m/s), in 10 steps. */
    private val tenKm by lazy {
        val ys = (0..100).map { it * 100.0 }
        val lats = ys.map { lat0 + it / 111_194.9 }.toDoubleArray(); val lons = DoubleArray(ys.size) { lon0 }
        val along = DoubleArray(ys.size)
        for (i in 1 until ys.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val steps = (0 until 10).map { step(it * 1000.0, (it + 1) * 1000.0, 60.0) }
        Route(lats, lons, along, along.last(), 600.0, steps, "", lats.last() to lons.last())
    }

    @Test
    fun plannedTimeIsSummedOverTheSteps() {
        assertEquals(0.0, RouteProgress.plannedSecondsAt(tenKm, 0.0), 0.5)
        assertEquals(30.0, RouteProgress.plannedSecondsAt(tenKm, 500.0), 1.0)
        assertEquals(150.0, RouteProgress.plannedSecondsAt(tenKm, 2_500.0), 1.0)
        assertEquals(600.0, RouteProgress.plannedSecondsAt(tenKm, 10_000.0), 1.0)
    }

    @Test
    fun noPaceUntilThereIsEnoughToGoOn() {
        assertNull("too short", RouteProgress.pace(tenKm, 0.0, 400.0, 200.0))
        assertNull("too soon", RouteProgress.pace(tenKm, 0.0, 2_000.0, 60.0))
    }

    @Test
    fun aSlowDriveStretchesTheTimeLeftAFastOneShrinksIt() {
        // 2 km planned in 120 s; it took 180 s: a half slower. With 2 km of 4 km behind the weight is half.
        val slow = RouteProgress.pace(tenKm, 0.0, 2_000.0, 180.0)!!
        assertTrue(slow > 1.1 && slow < 1.5)
        // After 4 km at the same relative slowness it counts in full.
        val slower = RouteProgress.pace(tenKm, 0.0, 4_000.0, 360.0)!!
        assertEquals(1.5, slower, 0.01)
        val fast = RouteProgress.pace(tenKm, 0.0, 4_000.0, 180.0)!!
        assertEquals(0.75, fast, 0.01)
    }

    @Test
    fun paceStaysWithinReason() {
        assertEquals("a crawl: at most twice as slow", 2.0, RouteProgress.pace(tenKm, 0.0, 5_000.0, 5_000.0)!!, 0.001)
        assertEquals("a rocket: at most 40 percent quicker", 0.6, RouteProgress.pace(tenKm, 0.0, 5_000.0, 10.0.coerceAtLeast(90.0))!!, 0.001)
    }

    @Test
    fun paceIsMeasuredFromWhereYouStartedOnThisRoute() {
        // Joined 5 km in (a new route after a re-route), then 2 km took the planned 120 s: as planned.
        assertEquals(1.0, RouteProgress.pace(tenKm, 5_000.0, 7_000.0, 120.0)!!, 0.001)
    }

    // ---- roundabouts and U-turns ----

    @Test
    fun ordinalNumbers() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "101st", "111th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 101, 111).map { NavFormat.ordinal(it) })
    }

    @Test
    fun leftHandTrafficTurnsTheOtherWay() {
        assertEquals(NavFormat.Icon.ROUNDABOUT, NavFormat.iconFor("roundabout", "right", "right"))
        assertEquals(NavFormat.Icon.ROUNDABOUT, NavFormat.iconFor("roundabout", "right"))
        assertEquals(NavFormat.Icon.ROUNDABOUT_LEFT, NavFormat.iconFor("roundabout", "left", "left"))
        assertEquals(NavFormat.Icon.U_TURN_LEFT, NavFormat.iconFor("turn", "uturn", "right"))
        assertEquals(NavFormat.Icon.U_TURN_RIGHT, NavFormat.iconFor("turn", "uturn", "left"))
        assertEquals("ordinary turns don't change", NavFormat.Icon.LEFT, NavFormat.iconFor("turn", "left", "left"))
    }

    @Test
    fun theDrivingSideIsReadFromTheRoute() {
        val json = """{"code":"Ok","routes":[{"distance":50.0,"duration":10.0,"geometry":"x","legs":[{"summary":"","steps":[
            {"maneuver":{"type":"depart","modifier":"left","location":[0.0,51.0]},"name":"A","distance":50.0,"duration":10.0,"driving_side":"left","geometry":"_izlhA_ulvUonqC_ulvU"},
            {"maneuver":{"type":"arrive","location":[0.0,51.0]},"name":"","distance":0.0,"duration":0.0,"geometry":"_izlhA_ulvU"}]}]}]}"""
        val r = Valhalla.parse(json).single()
        assertEquals("left", r.steps[0].drivingSide)
        assertEquals("right", r.steps[1].drivingSide) // not given: the common case
    }

    // ---- places of one kind ----

    private val overpass = """{"elements":[
        {"type":"node","lat":48.1400,"lon":11.5800,"tags":{"amenity":"fuel","name":"Example Fuel","brand":"Brandco","addr:street":"Main Street","addr:housenumber":"4","addr:city":"Exampletown"}},
        {"type":"way","center":{"lat":48.1500,"lon":11.5900},"tags":{"amenity":"fuel","brand":"Brandco"}},
        {"type":"node","lat":48.1400,"lon":11.5800,"tags":{"amenity":"fuel","name":"Example Fuel"}},
        {"type":"node","lat":48.3000,"lon":11.9000,"tags":{"amenity":"fuel","name":"Far Away"}},
        {"type":"relation","tags":{"amenity":"fuel","name":"No position"}}]}"""

    @Test
    fun overpassAnswersBecomePlaces() {
        val places = PlaceCategories.parse(overpass, PlaceCategories.Category.FUEL)
        assertEquals("the duplicate and the one with no position are left out", 3, places.size)
        assertEquals("Example Fuel", places[0].name)
        assertEquals("4 Main Street, Exampletown", places[0].address)
        assertEquals("a way is placed at its centre, named by its brand", "Brandco", places[1].name)
        assertEquals(48.15, places[1].lat, 1e-9)
    }

    @Test
    fun nearbyIsNearestFirstAndWithinReach() {
        val places = PlaceCategories.parse(overpass, PlaceCategories.Category.FUEL)
        val hits = PlaceCategories.rankNear(places, PlaceCategories.Category.FUEL, 48.1380, 11.5780)
        assertEquals(listOf("Example Fuel", "Brandco"), hits.map { it.place.name })
        assertTrue(hits[0].distanceM!! < hits[1].distanceM!!)
        assertTrue("Far Away is 30 km off", hits.none { it.place.name == "Far Away" })
    }

    @Test
    fun theQueriesAskForTheRightThingsAndFuzzThePosition() {
        val near = PlaceCategories.nearQuery(PlaceCategories.Category.FOOD, 48.137412, 11.575534)
        assertTrue(near, near.contains("\"amenity\"=\"restaurant\"") && near.contains("\"amenity\"=\"cafe\""))
        assertTrue("a box on a 1 km grid, not the exact spot: $near", near.contains("(48.08,11.49,48.20,11.67)") && !near.contains("48.1374"))
        assertFalse("a box, not a circle", near.contains("around"))
        assertTrue(near.contains("out center tags"))
        val along = PlaceCategories.alongQuery(PlaceCategories.Category.FUEL, listOf(48.137412 to 11.575534, 48.2 to 11.6))
        assertTrue(along, along.contains("around:1750,48.137,11.576,48.200,11.600"))
    }

    // ---- along the route ----

    private val northRoad by lazy {
        val ys = (0..50).map { it * 100.0 }
        val lats = ys.map { 48.0 + it / 111_194.9 }.toDoubleArray(); val lons = DoubleArray(ys.size) { 11.5 }
        val along = DoubleArray(ys.size)
        for (i in 1 until ys.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        Route(lats, lons, along, along.last(), 300.0, listOf(step(0.0, along.last(), 300.0)), "", lats.last() to lons.last())
    }

    private fun placeAt(name: String, northM: Double, eastM: Double) =
        Place(name, "", 48.0 + northM / 111_194.9, 11.5 + eastM / (111_194.9 * Math.cos(Math.toRadians(48.0))))

    @Test
    fun alongTheRouteIsInTheOrderYouReachThem() {
        val places = listOf(placeAt("Second", 3_000.0, 200.0), placeAt("Behind", 200.0, 0.0), placeAt("First", 1_500.0, -100.0),
            placeAt("Too far off", 2_000.0, 4_000.0), placeAt("Last", 4_500.0, 0.0))
        val hits = PlaceCategories.rankAlong(places, PlaceCategories.Category.FUEL, northRoad, fromAlongM = 1_000.0)
        assertEquals(listOf("First", "Second", "Last"), hits.map { it.place.name })
        assertEquals(500.0, hits[0].aheadM!!, 10.0)
        assertEquals(100.0, hits[0].offM!!, 5.0)
    }

    @Test
    fun theRouteAheadIsSampledEveryKilometre() {
        val pts = PlaceCategories.routeSample(northRoad, 1_000.0)
        assertTrue(pts.size in 4..6)
        assertEquals("the first point is where you are", 48.0 + 1_000.0 / 111_194.9, pts[0].first, 0.0002)
        assertEquals("and the last the end", northRoad.end, pts.last())
        assertNotNull(pts)
    }
}
