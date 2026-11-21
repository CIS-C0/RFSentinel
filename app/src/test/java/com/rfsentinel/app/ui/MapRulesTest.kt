package com.rfsentinel.app.ui

import com.rfsentinel.app.service.DeviceRegistry.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapRulesTest {
    private fun pin(mac: String, lat: Double, lon: Double, flagged: Boolean = false, radius: Double? = null) =
        MapPin(mac, lat, lon, mac, flagged, 0, "", radius)

    @Test
    fun wideCirclesDrawNoDotAndSameSpotDotsMergeFlaggedFirst() {
        val pins = listOf(
            pin("a", 10.000001, 20.0), pin("b", 10.000002, 20.0, flagged = true), // same metre
            pin("c", 10.001, 20.0, flagged = true, radius = 35.0), // small enough: a dot
            pin("d", 10.002, 20.0, flagged = true, radius = 80.0), // circle only
        )
        val dots = MapPins.dots(pins)
        assertEquals(listOf(listOf("b", "a"), listOf("c")), dots.map { g -> g.map { it.mac } })
        assertEquals(listOf("d"), pins.filter { MapPins.ringOnly(it) }.map { it.mac })
    }

    @Test
    fun statusFromSignalAndClosing() {
        fun at(vararg r: Pair<Int, Int>) = r.map { (s, rssi) -> Sample(s * 1000L, rssi) }
        assertNull(DeviceRows.status(emptyList()))
        assertEquals("FAR", DeviceRows.status(at(0 to -90, 1 to -88, 2 to -91))!!.first)
        assertEquals("NEARBY", DeviceRows.status(at(0 to -75))!!.first)
        assertEquals("CLOSE", DeviceRows.status(at(0 to -60, 1 to -40, 2 to -62))!!.first) // one lucky packet doesn't count
        assertEquals("VERY CLOSE", DeviceRows.status(at(0 to -50))!!.first)
        // 8 dB stronger than 30 s before: one step up, shown as closing.
        val closing = DeviceRows.status(at(0 to -78, 30 to -70, 31 to -70, 32 to -70))!!
        assertEquals("CLOSING", closing.first)
        assertEquals(DeviceRows.status(at(0 to -60))!!.second, closing.second)
        // Old readings from more than a minute before don't count.
        assertEquals("NEARBY", DeviceRows.status(at(0 to -90, 90 to -75))!!.first)
    }
}
