package com.rfsentinel.app.detect

import com.rfsentinel.app.detect.Locator.Reading
import org.junit.Assert.assertEquals
import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pinpointing a parked device from readings taken as you drive past it. Synthetic coordinates. */
class LocatorTest {
    private val lat0 = 10.5
    private val lon0 = -20.5
    private val mx = 111_320.0 * cos(Math.toRadians(lat0))
    private val my = 110_540.0

    private fun lat(y: Double) = lat0 + y / my
    private fun lon(x: Double) = lon0 + x / mx

    /** What the phone hears at (x, y) from a device at (dx, dy): -59 dBm at 1 m, exponent 2.5, plus [noise]. */
    private fun rssi(x: Double, y: Double, dx: Double, dy: Double, noise: Double = 0.0): Int {
        val d = sqrt((x - dx) * (x - dx) + (y - dy) * (y - dy)).coerceAtLeast(1.0)
        return (-59 - 25 * log10(d) + noise).roundToInt()
    }

    /** A drive along [route] (metres, one reading per metre stepped), hearing a device at (dx, dy). */
    private fun drive(route: List<Pair<Double, Double>>, dx: Double, dy: Double, noise: (Int) -> Double = { 0.0 }): List<Reading> {
        val out = ArrayList<Reading>()
        var t = 1_000_000L
        for (i in 0 until route.size - 1) {
            val (ax, ay) = route[i]; val (bx, by) = route[i + 1]
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = (len / 2).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val x = ax + (bx - ax) * s / steps; val y = ay + (by - ay) * s / steps
                if (sqrt((x - dx) * (x - dx) + (y - dy) * (y - dy)) > 140) continue // out of Bluetooth range
                out += Reading(t, lat(y), lon(x), rssi(x, y, dx, dy, noise(out.size)))
                t += 500
            }
        }
        return out
    }

    private fun errorM(e: Locator.Estimate, dx: Double, dy: Double) =
        DeviceRegistry.metersBetween(e.lat, e.lon, lat(dy), lon(dx))

    /** Repeatable pseudo-random fades of up to +-[amp] dB. */
    private fun fades(amp: Double): (Int) -> Double = { i -> (((i * 7919 + 13) % 101) / 100.0 * 2 - 1) * amp }

    @Test
    fun turningACornerPinpointsTheDevice() {
        // East along a street, then north up a cross street; the device sits in the block between.
        val route = listOf(-120.0 to 0.0, 60.0 to 0.0, 60.0 to 150.0)
        val e = Locator.estimate(drive(route, 25.0, 40.0), wifi = false)
        assertNotNull(e)
        val err = errorM(e!!, 25.0, 40.0)
        assertTrue("within 8 m, was ${err.roundToInt()} m", err < 8)
        assertTrue("a tight circle, was ${e.radiusM.roundToInt()} m", e.radiusM < 25)
    }

    @Test
    fun fadesStillLandNearAndInsideTheCircle() {
        val route = listOf(-120.0 to 0.0, 60.0 to 0.0, 60.0 to 150.0)
        val e = Locator.estimate(drive(route, 25.0, 40.0, fades(6.0)), wifi = false)!!
        val err = errorM(e, 25.0, 40.0)
        assertTrue("within 20 m, was ${err.roundToInt()} m", err < 20)
        assertTrue("the true spot is inside the circle (${err.roundToInt()} vs ${e.radiusM.roundToInt()} m)", err <= e.radiusM)
    }

    @Test
    fun aStraightRoadCannotTellTheSideSoTheCircleCoversBoth() {
        // Driving straight past: the device 35 m north looks the same as 35 m south.
        val e = Locator.estimate(drive(listOf(-150.0 to 0.0, 150.0 to 0.0), 0.0, 35.0), wifi = false)!!
        assertTrue("the true spot is inside the circle", errorM(e, 0.0, 35.0) <= e.radiusM)
        assertTrue("and its mirror on the other side too", errorM(e, 0.0, -35.0) <= e.radiusM)
        assertTrue("but it still says where along the road (${e.radiusM.roundToInt()} m)", e.radiusM < 80)
    }

    @Test
    fun standingStillGivesNoEstimate() {
        val here = (1..60).map { Reading(1_000_000L + it * 500, lat(0.0), lon(0.0), -70 + it % 3) }
        assertNull(Locator.estimate(here, wifi = false))
    }

    @Test
    fun aFewMetresOfShufflingIsNotEnough() {
        assertNull(Locator.estimate(drive(listOf(0.0 to 0.0, 10.0 to 0.0), 20.0, 20.0), wifi = false))
    }

    @Test
    fun oldReadingsAreDropped() {
        val route = listOf(-120.0 to 0.0, 60.0 to 0.0, 60.0 to 150.0)
        val old = drive(route, 25.0, 40.0)
        val muchLater = old.last().time + Locator.WINDOW_MS + 60_000
        assertNull("a parked car may have left", Locator.estimate(old, wifi = false, now = muchLater))
    }

    @Test
    fun aDeviceThatMovesWithYouIsNotPinned() {
        // It stays loud wherever you go, then fades and comes back at random: no single spot explains that.
        var i = 0
        val route = listOf(-120.0 to 0.0, 60.0 to 0.0, 60.0 to 150.0)
        val readings = drive(route, 25.0, 40.0).map { it.copy(rssi = if (i++ % 2 == 0) -45 else -88) }
        assertNull(Locator.estimate(readings, wifi = false))
    }

    @Test
    fun wifiAccessPointsAreFoundToo() {
        val route = listOf(-200.0 to 0.0, 80.0 to 0.0, 80.0 to 200.0)
        val e = Locator.estimate(drive(route, 30.0, 50.0), wifi = true)!!
        assertTrue(errorM(e, 30.0, 50.0) < 12)
    }

    @Test
    fun describeReadsNaturally() {
        val e = Locator.Estimate(lat0, lon0, 12.4, 18, 40, 90.0, 0L)
        assertTrue(Locator.describe(e), Locator.describe(e) == "±12 m from 18 spots you passed")
    }

    // ---- Through the device registry, the way the scanner feeds it

    private fun scan(mac: String, hits: List<Hit>) {
        DeviceRegistry.startSession(0)
        val route = listOf(-120.0 to 0.0, 60.0 to 0.0, 60.0 to 150.0)
        for (r in drive(route, 25.0, 40.0)) {
            DeviceRegistry.report(Advert(mac, Advert.Source.BLE, r.rssi, null, emptyMap(), timestamp = r.time), hits,
                DeviceIntel.Identity("x", emptyList()), null, null, DeviceRegistry.GeoSample(r.time, r.lat, r.lon), r.time)
        }
        DeviceRegistry.awaitFits()
    }

    @Test
    fun aFlaggedDeviceIsPlacedWhereItWasPinpointed() {
        scan("5A:10:20:30:40:50", listOf(Hit(Category.BODY_CAM, "Body camera", 80, "e", "s")))
        val s = DeviceRegistry.get("5A:10:20:30:40:50")!!
        val e = s.located
        assertNotNull(e)
        assertTrue(errorM(e!!, 25.0, 40.0) < 10)
        assertEquals(e.lat, s.place!!.lat, 1e-9)
        assertEquals(e.lon, s.place!!.lon, 1e-9)
    }

    @Test
    fun anOrdinaryDeviceKeepsItsStrongestSpot() {
        scan("5A:10:20:30:40:51", emptyList())
        val s = DeviceRegistry.get("5A:10:20:30:40:51")!!
        assertNull("only flagged devices are pinpointed", s.located)
        assertEquals(s.bestPosition, s.place)
    }

    @Test
    fun aFixIsMovedOnByYourSpeedAndCourse() {
        // 20 m/s due east for 1.5 s: 30 m east.
        val (la, lo) = Locator.ahead(lat0, lon0, 20.0, 90.0, 1.5)
        assertEquals(30.0, DeviceRegistry.metersBetween(lat0, lon0, la, lo), 0.5)
        assertTrue(lo > lon0)
        assertEquals("never more than 3 s ahead", 60.0,
            Locator.ahead(lat0, lon0, 20.0, 0.0, 10.0).let { DeviceRegistry.metersBetween(lat0, lon0, it.first, it.second) }, 0.5)
        assertEquals("stopped: the course is noise", lat0 to lon0, Locator.ahead(lat0, lon0, 0.5, 90.0, 2.0))
    }
}
