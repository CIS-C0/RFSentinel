package com.rfsentinel.app.nav

import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.alpr.KnownCamera.Kind
import com.rfsentinel.app.online.RoadMatch
import com.rfsentinel.app.service.DeviceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Routes around known cameras. Roads are drawn in metres east / north of an arbitrary point: the main road runs
 * north along x = 0, the way round is the same road with a stretch moved 300 m east.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CameraAvoidTest {

    private val frame = RoadMatch.Frame(10.0, 20.0)
    private val plate = setOf(Kind.ALPR)

    /** A route through these (east, north) points in metres, no steps. */
    private fun route(vararg pts: Pair<Double, Double>): Route {
        val ll = pts.map { (x, y) -> frame.toLatLon(x, y) }
        val lats = ll.map { it.first }.toDoubleArray(); val lons = ll.map { it.second }.toDoubleArray()
        val along = DoubleArray(lats.size)
        for (i in 1 until lats.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(lats, lons, along, along.last(), along.last() / 15.0, emptyList(), "", lats.last() to lons.last())
    }

    /** The main road: 3 km north along x = 0, a point every 50 m. */
    private fun main() = route(*(0..60).map { 0.0 to it * 50.0 }.toTypedArray())

    /** The same road, but with [from]..[to] metres of it taken 300 m to the east. */
    private fun roundIt(from: Double, to: Double): Route {
        val pts = ArrayList<Pair<Double, Double>>()
        for (k in 0..60) {
            val y = k * 50.0
            pts += (if (y in from..to) 300.0 else 0.0) to y
        }
        return route(*pts.toTypedArray())
    }

    private fun camera(id: String, x: Double, y: Double, kind: Kind = Kind.ALPR): KnownCamera {
        val (la, lo) = frame.toLatLon(x, y)
        return KnownCamera(id, la, lo, null, null, null, kind)
    }

    private fun near(p: Pair<Double, Double>, x: Double, y: Double, within: Double = 30.0): Boolean {
        val (la, lo) = frame.toLatLon(x, y)
        return DeviceRegistry.metersBetween(p.first, p.second, la, lo) <= within
    }

    // ---- finding the cameras on a route ----------------------------------------------------------

    @Test
    fun camerasOnTheLineAreFoundInOrderAndOthersAreNot() {
        val cams = listOf(camera("far", 120.0, 700.0), camera("b", 8.0, 1500.0), camera("a", -10.0, 400.0),
            camera("speed", 3.0, 900.0, Kind.SPEED))
        val found = CameraAvoid.hits(main(), cams, plate)
        assertEquals(listOf("a", "b"), found.map { it.camera.osmId })
        assertEquals(400.0, found[0].alongM, 3.0)
        assertEquals(10.0, found[0].distM, 1.0)
        assertEquals(listOf("speed"), CameraAvoid.hits(main(), cams, setOf(Kind.SPEED)).map { it.camera.osmId })
        assertEquals(4 - 1, CameraAvoid.hits(main(), cams, setOf(Kind.ALPR, Kind.SPEED)).size)
        assertTrue(CameraAvoid.hits(main(), cams, emptySet()).isEmpty())
    }

    @Test
    fun aCameraJustOutsideTheRadiusIsNotOnTheRoute() {
        assertTrue(CameraAvoid.hits(main(), listOf(camera("c", 40.0, 500.0)), plate).isEmpty())
        assertEquals(1, CameraAvoid.hits(main(), listOf(camera("c", 20.0, 500.0)), plate).size)
    }

    @Test
    fun anOldCacheEntryWithoutAKindIsAPlateReader() {
        val (la, lo) = frame.toLatLon(0.0, 500.0)
        val old = KnownCamera("old", la, lo, null, null, null, kind = null)
        assertEquals(1, CameraAvoid.hits(main(), listOf(old), plate).size)
    }

    @Test
    fun camerasCloseAlongTheRouteShareOnePlace() {
        val hits = CameraAvoid.hits(main(), listOf(camera("a", 0.0, 500.0), camera("b", 5.0, 520.0), camera("c", 0.0, 900.0)), plate)
        val spots = CameraAvoid.spots(hits)
        assertEquals(2, spots.size)
        assertEquals(setOf("a", "b"), spots[0].keys)
    }

    // ---- the plan --------------------------------------------------------------------------------

    private fun plan(cams: List<KnownCamera>, kinds: Set<Kind> = plate, ends: List<Pair<Double, Double>> = emptyList(),
                     ask: suspend (List<Pair<Double, Double>>, Int) -> List<Route>) =
        runBlocking { CameraAvoid.plan(kinds, { cams }, ends, 2, ask = ask) }

    @Test
    fun theRouteGoesRoundACameraAndSaysSo() {
        val s = main(); val d = roundIt(300.0, 700.0)
        val asked = ArrayList<List<Pair<Double, Double>>>()
        val routes = plan(listOf(camera("a", 0.0, 500.0))) { excl, _ ->
            asked += excl
            if (excl.any { near(it, 0.0, 500.0) }) listOf(d) else listOf(s)
        }
        assertEquals(2, asked.size)
        assertTrue("the camera's road was excluded", asked[1].size == 1 && near(asked[1][0], 0.0, 500.0, 20.0))
        assertTrue("goes round it", routes[0] === d || routes[0].lats.contentEquals(d.lats))
        assertEquals("no plate readers", routes[0].cameraLine)
        assertEquals("the fastest way stays on offer, with what is on it", "1 plate reader", routes[1].cameraLine)
        assertEquals(2, routes.size)
    }

    @Test
    fun aRouteWithNoCameraIsLeftAlone() {
        val s = main()
        var asks = 0
        val routes = plan(listOf(camera("away", 500.0, 500.0))) { _, _ -> asks++; listOf(s) }
        assertEquals(1, asks)
        assertEquals(1, routes.size)
        assertEquals("no plate readers", routes[0].cameraLine)
    }

    @Test
    fun theServersOwnAlternativesAreKept() {
        val s = main(); val alt = roundIt(1000.0, 1400.0)
        val routes = plan(emptyList()) { _, _ -> listOf(s, alt) }
        assertEquals(2, routes.size)
        assertTrue(routes[0].lats.contentEquals(s.lats))
    }

    @Test
    fun aCameraAtTheStartOrTheEndCantBeAvoided() {
        val s = main()
        var asks = 0
        val (sla, slo) = frame.toLatLon(0.0, 0.0)
        val routes = plan(listOf(camera("start", 0.0, 60.0)), ends = listOf(sla to slo)) { _, _ -> asks++; listOf(s) }
        assertEquals("nothing to try", 1, asks)
        assertEquals("1 plate reader", routes[0].cameraLine)
    }

    @Test
    fun aCameraOnTheOnlyBridgeStaysWhileOthersAreAvoided() {
        val s = main(); val d = roundIt(300.0, 700.0) // round the first one, but the second is on the only bridge
        var asks = 0
        val routes = plan(listOf(camera("a", 0.0, 500.0), camera("bridge", 0.0, 2000.0))) { excl, _ ->
            asks++
            when {
                excl.any { near(it, 0.0, 2000.0) } -> throw Valhalla.NoRouteException("No route found")
                excl.any { near(it, 0.0, 500.0) } -> listOf(d)
                else -> listOf(s)
            }
        }
        assertTrue("bounded: $asks questions", asks <= CameraAvoid.MAX_REQUESTS)
        assertEquals("avoided the first, kept the second", "1 plate reader", routes[0].cameraLine)
        assertTrue(routes[0].lats.contentEquals(d.lats))
    }

    @Test
    fun whenNothingCanBeAvoidedTheFirstRouteComesBackAndTheQuestionsAreFew() {
        val s = main()
        var asks = 0
        val routes = plan(listOf(camera("a", 0.0, 500.0))) { excl, _ -> asks++; if (excl.isEmpty()) listOf(s) else throw Valhalla.NoRouteException("none") }
        assertEquals(2, asks)
        assertEquals("1 plate reader", routes[0].cameraLine)
    }

    @Test
    fun anExcludedRoadThatStaysOnTheRouteIsTriedEitherSide() {
        val s = main(); val d = roundIt(300.0, 700.0)
        val asked = ArrayList<List<Pair<Double, Double>>>()
        // The server leaves the camera's own stretch alone (the place fell on a junction): only the road beside it moves the route.
        val routes = plan(listOf(camera("a", 0.0, 500.0))) { excl, _ ->
            asked += excl
            if (excl.any { near(it, 0.0, 475.0, 8.0) || near(it, 0.0, 525.0, 8.0) }) listOf(d) else listOf(s)
        }
        assertEquals("no plate readers", routes[0].cameraLine)
        assertTrue("asked again with places either side", asked.size >= 3)
    }

    @Test
    fun noMoreThanFortyEightPlacesAreEverExcluded() {
        val s = main()
        val cams = (1..58).map { camera("c$it", 0.0, it * 50.0 - 20.0) } // one every 50 m, more than the server takes
        var most = 0; var asks = 0
        plan(cams) { excl, _ -> asks++; most = maxOf(most, excl.size); listOf(s) }
        assertTrue("most $most", most <= CameraAvoid.MAX_EXCLUDES)
        assertTrue(asks <= CameraAvoid.MAX_REQUESTS)
    }

    @Test
    fun aLostConnectionKeepsWhatThereIs() {
        val s = main()
        var asks = 0
        val routes = plan(listOf(camera("a", 0.0, 500.0))) { _, _ -> asks++; if (asks == 1) listOf(s) else throw java.io.IOException("offline") }
        assertEquals(2, asks)
        assertEquals("1 plate reader", routes[0].cameraLine)
    }

    @Test
    fun theFirstQuestionStillFailsWhenThereIsNoRoute() {
        val failed = runCatching { plan(emptyList()) { _, _ -> throw Valhalla.NoRouteException("No route found") } }
        assertTrue(failed.exceptionOrNull() is Valhalla.NoRouteException)
    }

    @Test
    fun speedCamerasAndPlateReadersAreCountedApart() {
        val s = main()
        val cams = listOf(camera("p", 0.0, 400.0), camera("p2", 0.0, 800.0), camera("v", 0.0, 1200.0, Kind.SPEED))
        val both = plan(cams, kinds = setOf(Kind.ALPR, Kind.SPEED)) { excl, _ -> if (excl.isEmpty()) listOf(s) else throw Valhalla.NoRouteException("x") }
        assertEquals("2 plate readers, 1 speed camera", both[0].cameraLine)
        val onlySpeed = plan(cams, kinds = setOf(Kind.SPEED)) { excl, _ -> if (excl.isEmpty()) listOf(s) else throw Valhalla.NoRouteException("x") }
        assertEquals("1 speed camera", onlySpeed[0].cameraLine)
    }

    @Test
    fun theLineSaysWhenTheCameraMapIsIncompleteHere() {
        val s = main()
        val routes = runBlocking { CameraAvoid.plan(plate, { emptyList() }, emptyList(), 0, covered = { false }, ask = { _, _ -> listOf(s) }) }
        assertTrue(routes[0].cameraLine!!, routes[0].cameraLine!!.contains("incomplete"))
    }

    @Test
    fun wordsForWhatIsOnARoute() {
        assertEquals("no cameras", CameraAvoid.describe(emptyList(), setOf(Kind.ALPR, Kind.SPEED)))
        assertEquals("no speed cameras", CameraAvoid.describe(emptyList(), setOf(Kind.SPEED)))
        assertEquals("no plate readers", CameraAvoid.describe(emptyList(), plate))
        val one = CameraAvoid.hits(main(), listOf(camera("a", 0.0, 500.0)), plate)
        assertEquals("1 plate reader", CameraAvoid.describe(one, plate))
    }

    // ---- the camera map along a route; the request -----------------------------------------------------

    @Test
    fun aLongRouteIsCoveredInSeveralBoxesNoneTooWide() {
        // 350 km north, a point every 1 km.
        val long = route(*(0..350).map { 0.0 to it * 1000.0 }.toTypedArray())
        val boxes = CameraAvoid.boxes(long)
        assertTrue("${boxes.size} boxes", boxes.size in 2..4)
        for (b in boxes) assertTrue(b[2] - b[0] <= 1.6 + 0.05)
        for (i in 0 until long.size step 5) assertTrue("point $i covered", boxes.any { long.lats[i] in it[0]..it[2] && long.lons[i] in it[1]..it[3] })
        assertEquals(1, CameraAvoid.boxes(main()).size)
    }

    @Test
    fun theRequestCarriesTheExcludedPlacesOnlyWhenThereAreSome() {
        val none = Valhalla.request(10.0, 20.0, null, emptyList(), 10.1 to 20.1, Valhalla.Options())
        assertFalse(none.has("exclude_locations"))
        val some = Valhalla.request(10.0, 20.0, null, emptyList(), 10.1 to 20.1, Valhalla.Options(), exclude = listOf(10.05 to 20.05, 10.06 to 20.06))
        val arr = some.getJSONArray("exclude_locations")
        assertEquals(2, arr.length())
        assertEquals(10.05, arr.getJSONObject(0).getDouble("lat"), 1e-9)
        assertEquals(20.06, arr.getJSONObject(1).getDouble("lon"), 1e-9)
        // Alternatives still work beside exclusions.
        assertEquals(2, some.getInt("alternates"))
    }

    @Test
    fun theCameraChoicesAreOptionsLikeTheOtherAvoids() {
        val o = Valhalla.Options(avoidSpeedCameras = true)
        assertEquals(setOf(Kind.SPEED), CameraAvoid.kinds(o))
        assertEquals(setOf(Kind.SPEED, Kind.ALPR), CameraAvoid.kinds(o.copy(avoidPlateReaders = true)))
        assertTrue(CameraAvoid.kinds(Valhalla.Options()).isEmpty())
        assertNull(Route(doubleArrayOf(0.0, 0.1), doubleArrayOf(0.0, 0.1), doubleArrayOf(0.0, 10.0), 10.0, 1.0, emptyList(), "", 0.1 to 0.1).cameraLine)
        assertNotNull(CameraAvoid.describe(emptyList(), plate))
    }
}
