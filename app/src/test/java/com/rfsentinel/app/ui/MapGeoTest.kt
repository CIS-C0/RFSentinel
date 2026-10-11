package com.rfsentinel.app.ui

import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes the maps draw: pinpoint circles and CCTV view wedges, the right size and closed. */
class MapGeoTest {

    private val lat = 45.0; private val lon = -73.0

    @Test
    fun circlesHaveTheirRadiusAndClose() {
        val ring = MapGeo.circle(lat, lon, 120.0)
        assertEquals("closed", ring.first(), ring.last())
        ring.forEach { (la, lo) -> assertEquals(120.0, DeviceRegistry.metersBetween(lat, lon, la, lo), 1.0) }
    }

    @Test
    fun offsetsGoTheRightWay() {
        val (nLat, nLon) = MapGeo.offset(lat, lon, 1000.0, 0.0)
        assertTrue("north", nLat > lat); assertEquals(lon, nLon, 1e-9)
        val (eLat, eLon) = MapGeo.offset(lat, lon, 1000.0, 90.0)
        assertTrue("east", eLon > lon); assertEquals(lat, eLat, 1e-4)
    }

    @Test
    fun wedgesStartAtTheCameraAndFaceItsWay() {
        val w = MapGeo.wedge(lat, lon, 90.0, 60.0, 30.0)
        assertEquals("from the camera", lat to lon, w.first())
        assertEquals("closed", w.first(), w.last())
        // Every arc point is the range away, within 30° of east.
        w.drop(1).dropLast(1).forEach { (la, lo) ->
            assertEquals(30.0, DeviceRegistry.metersBetween(lat, lon, la, lo), 0.5)
            assertTrue(lo > lon)
        }
        assertEquals("a full circle for a 360° camera", MapGeo.circle(lat, lon, 30.0).size, MapGeo.wedge(lat, lon, 0.0, 360.0, 30.0).size)
    }

    /** The crash on opening: the map starts over the whole world, and growing that passed the poles. */
    @Test
    fun grownBoxesStayInTheWorld() {
        val (n, e, s, w) = RfMap.grownBox(85.0, 180.0, -85.0, -180.0, 0.5)
        assertTrue(n <= 85.0 && s >= -85.0 && e <= 180.0 && w >= -180.0)
        val (n2, _, s2, _) = RfMap.grownBox(10.1, 20.2, 10.0, 20.0, 0.5)
        assertEquals(10.15, n2, 1e-9); assertEquals(9.95, s2, 1e-9)
    }
}
