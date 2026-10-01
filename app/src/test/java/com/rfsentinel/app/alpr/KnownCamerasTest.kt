package com.rfsentinel.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownCamerasTest {

    // Synthetic positions (not real places).
    private val sample = """
        {"elements":[
          {"type":"node","id":101,"lat":10.5000,"lon":-20.5000,
           "tags":{"surveillance:type":"ALPR","manufacturer":"Flock Safety","operator":"Example PD","direction":"135"}},
          {"type":"way","id":202,"center":{"lat":10.5020,"lon":-20.5000},
           "tags":{"surveillance:type":"ALPR","brand":"Motorola","camera:direction":"SE"}},
          {"type":"node","id":303,"tags":{"surveillance:type":"ALPR"}}
        ]}
    """.trimIndent()

    @Test
    fun parsesNodesAndWayCentres() {
        val cams = KnownCameras.parse(sample)
        assertEquals(2, cams.size) // the element without a position is skipped
        val flock = cams.first { it.osmId == "node/101" }
        assertEquals("Flock Safety (ALPR)", flock.label)
        assertEquals("Example PD", flock.operator)
        assertEquals(135, flock.direction)
        val way = cams.first { it.osmId == "way/202" }
        assertEquals(10.5020, way.lat, 1e-9)
        assertEquals(135, way.direction) // "SE"
    }

    @Test
    fun directionsAndQuery() {
        assertEquals(90, KnownCameras.parseDirection("90;270"))
        assertEquals(350, KnownCameras.parseDirection("-10"))
        assertEquals(0, KnownCameras.parseDirection("n"))
        assertNull(KnownCameras.parseDirection("forward"))
        val q = KnownCameras.query(1.0, 2.0, 3.0, 4.0)
        assertTrue(q.contains("\"surveillance:type\"=\"ALPR\"") && q.contains("(1.0,2.0,3.0,4.0)"))
    }

    @Test
    fun warnRadiusScalesWithSpeed() {
        assertEquals(150.0, KnownCameras.warnRadius(null), 0.0)
        assertEquals(150.0, KnownCameras.warnRadius(3f), 0.0)    // walking
        assertEquals(400.0, KnownCameras.warnRadius(20f), 0.0)   // 72 km/h
        assertEquals(600.0, KnownCameras.warnRadius(40f), 0.0)   // capped
    }

    // Synthetic: a speed camera mapped twice (node + enforcement relation), a red-light
    // relation, an mph camera elsewhere, and an unrelated element.
    private val enforcement = """
        {"elements":[
          {"type":"node","id":1,"lat":10.5000,"lon":-20.5000,"tags":{"highway":"speed_camera"}},
          {"type":"relation","id":2,"center":{"lat":10.5009,"lon":-20.5000},
           "tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"50"}},
          {"type":"relation","id":3,"center":{"lat":10.5000,"lon":-20.5000},
           "tags":{"type":"enforcement","enforcement":"traffic_signals"}},
          {"type":"node","id":4,"lat":11.0,"lon":-21.0,"tags":{"highway":"speed_camera","maxspeed":"30 mph"}},
          {"type":"node","id":5,"lat":11.5,"lon":-21.5,"tags":{"highway":"traffic_signals"}}
        ]}
    """.trimIndent()

    @Test
    fun speedAndRedLightCameras() {
        val cams = KnownCameras.parse(enforcement)
        assertEquals(3, cams.size) // duplicate speed camera merged, traffic light ignored
        val speed = cams.first { it.type == KnownCamera.Kind.SPEED && it.lat < 10.6 }
        assertEquals(50, speed.maxspeed) // kept the copy that has the limit (relation ~100 m away)
        assertEquals(10.5000, speed.lat, 1e-9) // ...at the camera node's exact position
        assertEquals("Speed camera (50 km/h)", speed.label)
        assertEquals("Speed camera ahead, 50", speed.spoken)
        assertEquals(48, cams.first { it.osmId == "node/4" }.maxspeed) // 30 mph
        assertEquals("Red-light camera", cams.first { it.type == KnownCamera.Kind.RED_LIGHT }.label)
        val q = KnownCameras.query(1.0, 2.0, 3.0, 4.0)
        assertTrue(q.contains("\"highway\"=\"speed_camera\"") && q.contains("traffic_signals"))
    }

    @Test
    fun oldCacheEntriesArePlateReaders() {
        val old = com.google.gson.Gson().fromJson(
            """{"osmId":"node/9","lat":1.0,"lon":2.0}""", KnownCamera::class.java
        )
        assertEquals(KnownCamera.Kind.ALPR, old.type)
    }

    @Test
    fun speedCamerasWarnEarlier() {
        assertEquals(300.0, KnownCameras.warnRadius(null, KnownCamera.Kind.SPEED), 0.0)
        assertEquals(840.0, KnownCameras.warnRadius(28f, KnownCamera.Kind.SPEED), 0.01) // ~100 km/h
        assertEquals(900.0, KnownCameras.warnRadius(40f, KnownCamera.Kind.RED_LIGHT), 0.0)
    }

    @Test
    fun nearFindsCamerasInRadiusNearestFirst() {
        val cams = KnownCameras.parse(sample)
        val found = KnownCameras.near(cams, 10.5010, -20.5000, 150.0) // ~111 m from each
        assertEquals(2, found.size)
        assertTrue(found[0].second <= found[1].second)
        assertTrue(KnownCameras.near(cams, 10.6, -20.5, 150.0).isEmpty())
    }
}
