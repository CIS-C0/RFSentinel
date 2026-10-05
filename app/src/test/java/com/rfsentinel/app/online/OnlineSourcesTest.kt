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
        {"hex":"a27635","flight":"N258LE  ","r":"N258LE","t":"OH58","lat":45.10,"lon":-73.10,"alt_baro":1200,"gs":90.0,"track":10.0},
        {"hex":"ab6800","flight":"JBU698","r":"N834JB","t":"A320","ownOp":"JETBLUE AIRWAYS","lat":45.12,"lon":-73.11,"alt_baro":"ground"},
        {"hex":"c0ffee","r":"N1PD","t":"B407","ownOp":"CITY OF SPRINGFIELD POLICE DEPARTMENT","lat":45.11,"lon":-73.12,"alt_baro":800},
        {"hex":"000001","lat":45.0}
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
            val p = base.copy(trackDeg = (i * 90.0) % 360, lat = 45.10 + 0.002 * (i % 2), lon = -73.10)
            circling = orbit.update(p, i * 60_000L)
        }
        assertTrue(circling)
        val straight = PoliceAircraft.LoiterTracker()
        var flagged = false
        for (i in 0..12) flagged = straight.update(base.copy(trackDeg = 10.0, lat = 45.10 + 0.02 * i), i * 60_000L)
        assertFalse(flagged)
    }

    @Test
    fun wazeParsingAndScore() {
        val json = """{"data":{"alerts":[
            {"alert_id":"a1","type":"POLICE","latitude":45.0,"longitude":-73.0,"publish_datetime_utc":"2026-10-05T12:00:00.000Z","street":"Main St","num_thumbs_up":2},
            {"alert_id":"a2","type":"ACCIDENT","latitude":45.0,"longitude":-73.0}]}}"""
        val r = WazePolice.parse(json).single()
        assertEquals("a1", r.id)
        assertEquals(74, WazePolice.score(50.0, 0, 2))      // close, fresh, confirmed
        assertEquals(40, WazePolice.score(2_000.0, 0, 0))   // edge of the search box
        assertEquals(58, WazePolice.score(100.0, WazePolice.MAX_AGE_MS, 0)) // 45 min old
        val hit = WazePolice.hit(r, 45.0, -73.0, r.publishedMs!! + 5 * 60_000L)!!.first
        assertEquals(Category.POLICE_REPORT, hit.category)
        assertTrue(hit.evidence.contains("5 min ago on Main St"))
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
