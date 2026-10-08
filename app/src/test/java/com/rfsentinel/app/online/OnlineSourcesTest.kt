package com.rfsentinel.app.online

import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.detect.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSourcesTest {

    private val feed = """{"now":1,"aircraft":[
        {"hex":"a27635","flight":"N258LE  ","r":"N258LE","t":"OH58","lat":10.10,"lon":-20.10,"alt_baro":1200,"gs":90.0,"track":10.0},
        {"hex":"ab6800","flight":"JBU698","r":"N834JB","t":"A320","ownOp":"JETBLUE AIRWAYS","lat":10.12,"lon":-20.11,"alt_baro":"ground"},
        {"hex":"c0ffee","r":"N1PD","t":"B407","ownOp":"CITY OF SPRINGFIELD POLICE DEPARTMENT","lat":10.11,"lon":-20.12,"alt_baro":800},
        {"hex":"000001","lat":10.0}
    ]}"""

    @Test
    fun parsesBothFeedFormats() {
        val planes = PoliceAircraft.parseFeed(feed)
        assertEquals(3, planes.size) // the one without a longitude is dropped
        assertEquals("N258LE", planes[0].callsign)
        assertEquals(0, planes[1].altitudeFt) // "ground"
        val lol = PoliceAircraft.parseFeed("""{"ac":[{"hex":"~abc123","lat":1.0,"lon":2.0,"dbFlags":1}]}""")
        assertEquals("abc123", lol.single().hex)
        assertTrue(lol.single().military)
    }

    @Test
    fun registryOwnerAndOrdinaryTraffic() {
        val registry = PoliceAircraft.parseRegistry("# header\na27635\tN258LE\tCOLLIER COUNTY SHERIFFS OFFICE\tBELL OH-58A\n")
        val (sheriff, airline, police) = PoliceAircraft.parseFeed(feed)
        val h1 = PoliceAircraft.classify(sheriff, registry, circling = false, distanceM = 800.0)!!
        assertEquals(Category.AIRCRAFT, h1.category)
        assertEquals(85, h1.confidence)
        assertTrue(h1.label.contains("Collier County Sheriffs Office"))
        assertNull(PoliceAircraft.classify(airline, registry, circling = false, distanceM = 2000.0))
        assertEquals(80, PoliceAircraft.classify(police, registry, circling = false, distanceM = 3000.0)!!.confidence)
        val circling = PoliceAircraft.classify(airline.copy(altitudeFt = 1500), registry, circling = true, distanceM = 500.0)!!
        assertEquals("Aircraft circling overhead", circling.label)
    }

    @Test
    fun loiterNeedsCirclesOverOneSpot() {
        val base = PoliceAircraft.parseFeed(feed)[0]
        val orbit = PoliceAircraft.LoiterTracker()
        var circling = false
        for (i in 0..12) { // a full turn every 4 minutes, 1 fix a minute, staying put
            val p = base.copy(trackDeg = (i * 90.0) % 360, lat = 10.10 + 0.002 * (i % 2), lon = -20.10)
            circling = orbit.update(p, i * 60_000L)
        }
        assertTrue(circling)
        val straight = PoliceAircraft.LoiterTracker()
        var flagged = false
        for (i in 0..12) flagged = straight.update(base.copy(trackDeg = 10.0, lat = 10.10 + 0.02 * i), i * 60_000L)
        assertFalse(flagged)
    }

    @Test
    fun wazeParsingAndScore() {
        val json = """{"data":{"alerts":[
            {"alert_id":"a1","type":"POLICE","latitude":10.0,"longitude":-20.0,"publish_datetime_utc":"2026-10-05T12:00:00.000Z","street":"Main St","num_thumbs_up":2},
            {"alert_id":"a2","type":"ACCIDENT","latitude":10.0,"longitude":-20.0}]}}"""
        val r = WazePolice.parse(json).single()
        assertEquals("a1", r.id)
        assertEquals(94, WazePolice.score(50.0, 0, 2))      // close, fresh, confirmed
        assertEquals(70, WazePolice.score(2_000.0, 0, 0))   // edge of the search box
        assertEquals(82, WazePolice.score(100.0, WazePolice.MAX_AGE_MS, 0)) // 45 min old
        val hit = WazePolice.hit(r, 10.0, -20.0, r.publishedMs!! + 5 * 60_000L)!!.first
        assertEquals(Category.POLICE_REPORT, hit.category)
        assertTrue(hit.evidence.contains("5 min ago on Main St"))
    }

    @Test
    fun wazeDirectAlertIsScoredWithThumbsUp() {
        val a = com.rfsentinel.app.online.wazert.WazeRtFetcher.PoliceAlert("u1", "", 10.0, -20.0, 1_000_000L, 3, "POLICE")
        val r = WazePolice.fromDirect(a)
        val hit = WazePolice.hit(r, 10.0, -20.0, 1_000_000L + 60_000L, WazePolice.VIA_DIRECT)!!.first
        assertEquals(95, hit.confidence)  // 90 close, -0.2 decay (1 min), +6 for 3 thumbs up
        assertTrue(hit.evidence.contains("Waze direct"))
        assertTrue(hit.evidence.contains("3 drivers confirmed"))
    }

    @Test
    fun wazeTypesAndRadius() {
        val json = """{"data":{"alerts":[
            {"alert_id":"p","type":"POLICE","latitude":10.0,"longitude":-20.0},
            {"alert_id":"h","type":"HAZARD","latitude":10.0,"longitude":-20.0},
            {"alert_id":"c","type":"ROAD_CLOSED","latitude":10.0,"longitude":-20.0}]}}"""
        assertEquals(listOf("p"), WazePolice.parse(json).map { it.id })  // police only by default
        val picked = setOf(WazePolice.Type.POLICE, WazePolice.Type.HAZARD)
        assertEquals(listOf("p", "h"), WazePolice.parse(json, picked).map { it.id })
        assertTrue(WazePolice.url(10.0, -20.0, 2_000.0, picked).contains("alert_types=POLICE,HAZARD"))
        // A hazard starts lower than police, and a bigger radius keeps a far report alive.
        assertEquals(70, WazePolice.score(50.0, 0, 0, base = WazePolice.Type.HAZARD.baseScore))
        val r = WazePolice.parse(json, picked)[0]
        assertEquals(null, WazePolice.hit(r, 10.04, -20.0, 0L, radiusM = 2_000.0))   // 4.4 km away
        assertTrue(WazePolice.hit(r, 10.04, -20.0, 0L, radiusM = 5_000.0) != null)
    }

    @Test
    fun wazeViewRangeDoesNotChangeTheScore() {
        val r = WazePolice.Report("x", 10.0, -20.0, null, null, null, 0, null)
        val near = WazePolice.hit(r, 10.01, -20.0, 0L, radiusM = 2_000.0, maxM = 5_000.0)!!.first   // ~1.1 km
        val wider = WazePolice.hit(r, 10.01, -20.0, 0L, radiusM = 2_000.0, maxM = 20_000.0)!!.first
        assertEquals(near.confidence, wider.confidence)                                              // view range only decides what is kept
        assertEquals(null, WazePolice.hit(r, 10.1, -20.0, 0L, radiusM = 2_000.0, maxM = 5_000.0))    // ~11 km: past the view range
    }

    @Test
    fun cameraScoreFallsWithDistance() {
        assertEquals(90, KnownCameras.proximityScore(30.0))
        assertEquals(75, KnownCameras.proximityScore(100.0))
        assertEquals(50, KnownCameras.proximityScore(200.0))
        assertEquals(25, KnownCameras.proximityScore(350.0))
        assertEquals(0, KnownCameras.proximityScore(600.0))
    }
}
