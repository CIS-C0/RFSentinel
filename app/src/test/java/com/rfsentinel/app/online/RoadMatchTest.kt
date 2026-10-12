package com.rfsentinel.app.online

import com.rfsentinel.app.online.RoadMatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * "Only alert for reports ahead of me" on the map's roads: a report counts only on the road you're on,
 * ahead of you. Roads are drawn in metres east / north of an arbitrary point.
 */
class RoadMatchTest {

    private val lat0 = 10.0; private val lon0 = 20.0
    private val frame = RoadMatch.Frame(lat0, lon0)

    private fun road(vararg pts: Pair<Double, Double>, cls: String = "primary", oneway: Int = 0): RoadTiles.Road {
        val ll = pts.map { (x, y) -> frame.toLatLon(x, y) }
        return RoadTiles.Road(ll.map { it.first }.toDoubleArray(), ll.map { it.second }.toDoubleArray(), cls, oneway, null, 0)
    }

    private fun verdict(roads: List<RoadTiles.Road>, x: Double, y: Double, heading: Double = 0.0, maxM: Double = 2000.0,
                        loaded: (Double, Double) -> Boolean = { _, _ -> true }): Pair<Verdict, Double?> {
        val path = RoadMatch.pathAhead(roads, lat0, lon0, heading, maxM, loaded = loaded)
        val (la, lo) = frame.toLatLon(x, y)
        return RoadMatch.check(path, la, lo)
    }

    private val northSouth = road(0.0 to -1500.0, 0.0 to 3000.0)

    @Test
    fun aReportOnYourRoadAheadCounts() {
        val (v, along) = verdict(listOf(northSouth), 4.0, 800.0)
        assertEquals(Verdict.ON_MY_ROAD, v)
        assertEquals(800.0, along!!, 5.0)
    }

    @Test
    fun behindYouDoesNot() {
        assertEquals(Verdict.BEHIND, verdict(listOf(northSouth), 0.0, -300.0).first)
    }

    @Test
    fun aParallelRoadDoesNot() {
        val parallel = road(60.0 to -1500.0, 60.0 to 3000.0)
        assertEquals(Verdict.OTHER_ROAD, verdict(listOf(northSouth, parallel), 60.0, 800.0).first)
    }

    @Test
    fun aServiceRoadRightBesideYoursDoesNot() {
        val service = road(20.0 to 0.0, 20.0 to 1500.0, cls = "service")
        assertEquals(Verdict.OTHER_ROAD, verdict(listOf(northSouth, service), 20.0, 600.0).first)
    }

    @Test
    fun aCrossStreetDoesNotEvenNearTheCorner() {
        val cross = road(-600.0 to 500.0, 600.0 to 500.0, cls = "minor")
        assertEquals("150 m down the cross street", Verdict.OTHER_ROAD, verdict(listOf(northSouth, cross), 150.0, 500.0).first)
        assertEquals("20 m down the cross street", Verdict.OTHER_ROAD, verdict(listOf(northSouth, cross), 20.0, 500.0).first)
    }

    @Test
    fun anOverpassDoesNot() {
        val bridge = RoadTiles.Road(road(-400.0 to 700.0, 400.0 to 700.0).lat, road(-400.0 to 700.0, 400.0 to 700.0).lon, "motorway", 0, "bridge", 1)
        assertEquals(Verdict.OTHER_ROAD, verdict(listOf(northSouth, bridge), 12.0, 701.0).first)
    }

    @Test
    fun yourRoadIsFollowedThroughAJunctionAndRoundABend() {
        val first = road(0.0 to -200.0, 0.0 to 400.0)
        val bend = road(0.0 to 400.0, 40.0 to 800.0, 150.0 to 1200.0)
        val cross = road(-500.0 to 400.0, 500.0 to 400.0, cls = "minor")
        val (v, along) = verdict(listOf(first, bend, cross), 148.0, 1190.0)
        assertEquals(Verdict.ON_MY_ROAD, v)
        assertTrue("measured along the road", along!! > 1190.0)
        assertEquals("not down the cross street", Verdict.OTHER_ROAD, verdict(listOf(first, bend, cross), 400.0, 402.0).first)
    }

    @Test
    fun aRoadCutAtATileEdgeIsStillYourRoad() {
        // Tiles cut lines with an overlap: the two halves run side by side for a few metres.
        val a = road(0.0 to -100.0, 0.0 to 500.0)
        val b = road(0.0 to 480.0, 0.0 to 1500.0)
        assertEquals(Verdict.ON_MY_ROAD, verdict(listOf(a, b), 0.0, 1300.0).first)
    }

    @Test
    fun theOtherCarriagewayOfADividedHighwayIsTheSameRoad() {
        val mine = road(0.0 to -1000.0, 0.0 to 3000.0, cls = "motorway", oneway = 1)
        val theirs = road(25.0 to 3000.0, 25.0 to -1000.0, cls = "motorway", oneway = 1)
        assertEquals(Verdict.ON_MY_ROAD, verdict(listOf(mine, theirs), 25.0, 900.0).first)
    }

    @Test
    fun aOneWayRoadIsntYoursTheWrongWay() {
        val oneWaySouth = road(0.0 to 2000.0, 0.0 to -2000.0, oneway = 1)
        assertNull(RoadMatch.pathAhead(listOf(oneWaySouth), lat0, lon0, 0.0, 1000.0))
        assertNotNull(RoadMatch.pathAhead(listOf(oneWaySouth), lat0, lon0, 180.0, 1000.0))
    }

    @Test
    fun beyondTheLoadedMapItCantTell() {
        val stub = road(0.0 to -100.0, 0.0 to 500.0)
        val loaded = { la: Double, _: Double -> frame.toXY(la, lon0).second < 450 }
        assertEquals(Verdict.UNKNOWN, verdict(listOf(stub), 0.0, 900.0, loaded = loaded).first)
        assertEquals("no roads at all", Verdict.UNKNOWN, verdict(emptyList(), 0.0, 900.0).first)
        assertEquals("off any road", Verdict.UNKNOWN, verdict(listOf(road(500.0 to 0.0, 500.0 to 900.0)), 500.0, 800.0).first)
    }

    @Test
    fun theTilesAlongYourWayAreAskedFor() {
        val tiles = AheadOnRoad.corridor(lat0, lon0, 90.0, 3000.0)
        assertTrue(RoadTiles.tileOf(lat0, lon0) in tiles)
        val (la, lo) = frame.toLatLon(3000.0, 0.0)
        assertTrue("where you'll be", RoadTiles.tileOf(la, lo) in tiles)
    }

    // ---- the tile format ----------------------------------------------------------------------------

    private fun varint(out: ByteArrayOutputStream, v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
    private fun field(out: ByteArrayOutputStream, f: Int, bytes: ByteArray) { varint(out, (f shl 3 or 2).toLong()); varint(out, bytes.size.toLong()); out.write(bytes) }
    private fun fieldV(out: ByteArrayOutputStream, f: Int, v: Long) { varint(out, (f shl 3).toLong()); varint(out, v) }
    private fun packed(vararg v: Long) = ByteArrayOutputStream().also { o -> v.forEach { varint(o, it) } }.toByteArray()
    private fun zz(n: Int) = ((n shl 1) xor (n shr 31)).toLong()
    private fun str(s: String) = ByteArrayOutputStream().also { field(it, 1, s.toByteArray()) }.toByteArray()

    /** A tile with a primary road (one-way, two points), a footpath, and a polygon. */
    private fun tile(): ByteArray {
        val layer = ByteArrayOutputStream()
        field(layer, 1, "transportation".toByteArray())
        fun feature(tags: LongArray, type: Int, geom: LongArray) = ByteArrayOutputStream().also { f ->
            field(f, 2, packed(*tags)); fieldV(f, 3, type.toLong()); field(f, 4, packed(*geom))
        }.toByteArray()
        // keys: class, oneway; values: "primary", "path", 1
        field(layer, 2, feature(longArrayOf(0, 0, 1, 2), 2, longArrayOf(9, zz(100), zz(200), 10, zz(1000), zz(0))))
        field(layer, 2, feature(longArrayOf(0, 1), 2, longArrayOf(9, zz(10), zz(10), 10, zz(5), zz(5))))
        field(layer, 2, feature(longArrayOf(0, 0), 3, longArrayOf(9, zz(0), zz(0), 18, zz(10), zz(0), zz(0), zz(10), 15)))
        field(layer, 3, "class".toByteArray()); field(layer, 3, "oneway".toByteArray())
        field(layer, 4, str("primary")); field(layer, 4, str("path"))
        field(layer, 4, ByteArrayOutputStream().also { fieldV(it, 4, 1) }.toByteArray())
        fieldV(layer, 5, 4096)
        val other = ByteArrayOutputStream().also { field(it, 1, "water".toByteArray()) }
        return ByteArrayOutputStream().also { field(it, 3, other.toByteArray()); field(it, 3, layer.toByteArray()) }.toByteArray()
    }

    @Test
    fun tilesDecodeToDrivableRoads() {
        val t = RoadTiles.Tile(8000, 7000)
        val roads = RoadTiles.decode(tile(), t)
        assertEquals("the road only (not the path, not the polygon)", 1, roads.size)
        val r = roads[0]
        assertEquals("primary", r.cls); assertEquals(1, r.oneway)
        val (la0, lo0) = RoadTiles.toLatLon(t, 100.0, 200.0, 4096)
        val (la1, lo1) = RoadTiles.toLatLon(t, 1100.0, 200.0, 4096)
        assertEquals(la0, r.lat[0], 1e-9); assertEquals(lo0, r.lon[0], 1e-9)
        assertEquals(la1, r.lat[1], 1e-9); assertEquals(lo1, r.lon[1], 1e-9)
        assertTrue("east along the tile", r.lon[1] > r.lon[0])
        assertEquals("garbage gives nothing, not a crash", 0, RoadTiles.decode(byteArrayOf(0x1a, 0x7f, 0x01), t).size)
    }

    @Test
    fun tileNumbersRoundTrip() {
        val t = RoadTiles.tileOf(lat0, lon0)
        val (la, lo) = RoadTiles.toLatLon(t, 2048.0, 2048.0, 4096)
        assertEquals(t, RoadTiles.tileOf(la, lo))
    }
}
