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

    @Test
    fun nearFindsCamerasInRadiusNearestFirst() {
        val cams = KnownCameras.parse(sample)
        val found = KnownCameras.near(cams, 10.5010, -20.5000, 150.0) // ~111 m from each
        assertEquals(2, found.size)
        assertTrue(found[0].second <= found[1].second)
        assertTrue(KnownCameras.near(cams, 10.6, -20.5, 150.0).isEmpty())
    }
}
