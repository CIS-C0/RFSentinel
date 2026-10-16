package com.rfsentinel.app.nav

import android.app.Application
import android.location.Location
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.ui.MapGps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The arrow on the route while guided, and the position a map starts with. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RouteSnapTest {
    private val lat0 = 45.0; private val lon0 = 7.0
    private fun lat(y: Double) = lat0 + y / 110_540.0
    private fun lon(x: Double) = lon0 + x / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    /** 2 km due north. */
    private fun northRoad(): Route {
        val ys = (0..20).map { it * 100.0 }
        val lats = ys.map { lat(it) }.toDoubleArray(); val lons = DoubleArray(ys.size) { lon0 }
        val along = DoubleArray(ys.size)
        for (i in 1 until ys.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(lats, lons, along, along.last(), 120.0, emptyList(), "", lats.last() to lons.last())
    }

    @Test
    fun aFixAcrossTheRoadIsDrawnOnTheRouteFacingAlongIt() {
        // 12 m east of the road (the other side of it), heading a little off.
        val s = RouteSnap.snap(northRoad(), lat(500.0), lon(12.0), 20.0, 10.0, null)
        assertNotNull(s)
        assertEquals(lon0, s!!.lon, 1e-7)
        assertEquals(lat(500.0), s.lat, 2e-6)
        assertTrue("faces north: ${s.bearingDeg}", s.bearingDeg < 1.0 || s.bearingDeg > 359.0)
    }

    @Test
    fun aFixWellOffTheRouteIsLeftWhereItIs() {
        assertNull(RouteSnap.snap(northRoad(), lat(500.0), lon(60.0), 0.0, 5.0, null))
        // A rough fix allows a little more room, never past 40 m.
        assertNotNull(RouteSnap.snap(northRoad(), lat(500.0), lon(35.0), 0.0, 30.0, null))
        assertNull(RouteSnap.snap(northRoad(), lat(500.0), lon(45.0), 0.0, 100.0, null))
    }

    @Test
    fun goingAgainstTheRouteIsNotOnIt() {
        assertNull(RouteSnap.snap(northRoad(), lat(500.0), lon(8.0), 180.0, 5.0, null))
        // Stopped (no heading): put on it.
        assertNotNull(RouteSnap.snap(northRoad(), lat(500.0), lon(8.0), null, 5.0, null))
    }

    private fun fix(t: Long, acc: Float) = Location("t").apply { time = t; accuracy = acc; latitude = 1.0; longitude = 2.0 }

    @Test
    fun aMapStartsWithAPreciseFixRatherThanANewerCoarseOne() {
        val gps = fix(100_000, 8f); val cell = fix(130_000, 120f)
        assertSame(gps, MapGps.pick(listOf(gps, cell), 140_000))
        // A precise fix too old to trust: the newest one, coarse or not.
        val oldGps = fix(10_000, 8f)
        assertSame(cell, MapGps.pick(listOf(oldGps, cell), 140_000))
        assertNull(MapGps.pick(emptyList(), 0))
    }
}
