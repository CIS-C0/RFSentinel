package com.rfsentinel.app.esp

import com.rfsentinel.app.link.MeshProto
import com.rfsentinel.app.ui.DeviceSections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** What a Meshtastic node says about itself (battery, telemetry) and how the radio heard it (SNR, hops). */
class LoRaTelemetryTest {
    private val locale = Locale.getDefault()
    @Before fun setUp() { Locale.setDefault(Locale.US); LoRaStats.clear() }
    @After fun tearDown() { Locale.setDefault(locale); LoRaStats.clear() }

    private fun f(v: Float) = v.toRawBits().toLong() and 0xFFFFFFFFL

    @Test
    fun nodeListEntryCarriesMetricsSnrAndHops() {
        val b = MeshProto.W().apply {
            msg(4) {
                uint(1, 0xA1B2C3D4)
                msg(2) { string(2, "Hilltop") }
                fixed32(4, f(6.5f))
                fixed32(5, 99_900)
                msg(6) { uint(1, 87); fixed32(2, f(3.9f)); fixed32(3, f(12.5f)); fixed32(4, f(1.5f)); uint(5, 90_000) }
                uint(9, 2)
            }
        }.bytes()
        val log = MeshtasticLog { 100_000 }
        log.feed(MeshProto.readFromRadio(b))
        val n = LoRaStats.of("06:4d:a1:b2:c3:d4")!!
        assertEquals(6.5f, n.snr!!, 0f)
        assertEquals(2, n.hops)
        assertEquals(87, n.metrics.battery)
        assertEquals(90_000L, n.metrics.uptimeS)
        val rows = DeviceSections.nodeRows(n, now = n.at, imperial = false).toMap()
        assertEquals("87%", rows["Battery"])
        assertEquals("3.90 V", rows["Voltage"])
        assertEquals("6.5 dB", rows["Signal to noise"])
        assertEquals("Passed on by 2 nodes", rows["Hops"])
        assertEquals("12.5% · its own sends 1.5%", rows["Channel use"])
        assertEquals("1 d 1 h", rows["Up for"])
        assertEquals("Just now", rows["Node report"])
    }

    @Test
    fun telemetryPacketAddsToWhatWasKnown() {
        val packet = { port: Int, payload: ByteArray ->
            MeshProto.W().apply {
                msg(2) {
                    fixed32(1, 0x55)
                    fixed32(2, 0xFFFFFFFFL)
                    msg(4) { uint(1, port.toLong()); bytes(2, payload) }
                    fixed32(8, f(-4.25f))
                    uint(9, 3)
                    uint(12, (-90L) and 0xFFFFFFFFL)
                    uint(15, 3)
                }
            }.bytes()
        }
        var now = 1_000L
        val log = MeshtasticLog { now }
        val first = MeshProto.readFromRadio(packet(1, ByteArray(0))) as MeshProto.From.Packet
        assertEquals(0, first.hops)
        log.feed(first)
        val telemetry = MeshProto.W().apply {
            msg(2) { uint(1, 101) }
            msg(3) { fixed32(1, f(21.5f)); fixed32(2, f(40f)); fixed32(3, f(1013f)) }
        }.bytes()
        now += 300
        log.feed(MeshProto.readFromRadio(packet(MeshProto.PORT_TELEMETRY_APP, telemetry)))
        val n = LoRaStats.of("06:4D:00:00:00:55")!!
        assertEquals(-4.25f, n.snr!!, 0f)
        assertEquals(101, n.metrics.battery)
        assertEquals(21.5f, n.metrics.temperatureC!!, 0f)
        val rows = DeviceSections.nodeRows(n, now = n.at + 5 * 60_000, imperial = true).toMap()
        assertEquals("On outside power", rows["Battery"])
        assertEquals("Heard directly", rows["Hops"])
        assertEquals("70.7 °F", rows["Temperature"])
        assertEquals("40%", rows["Humidity"])
        assertEquals("1013 hPa", rows["Air pressure"])
        assertEquals("5 min ago", rows["Node report"])
    }

    @Test
    fun nothingKnownMeansNoRows() {
        assertNull(LoRaStats.of("06:4D:00:00:00:99"))
        assertEquals(emptyList<Pair<String, String>>(), DeviceSections.nodeRows(NodeStats(), 0))
    }

    @Test fun telemetryHistoryPlotsEachValueItReported() {
        val mac = "06:4D:00:00:00:77"
        for (i in 0 until 5) LoRaStats.note(mac, MeshProto.Metrics(battery = 90 - i * 5, temperatureC = 20f), 5f + i, 0, i * 1_800_000L)
        LoRaStats.note(mac, null, 9f, 0, 9_000_000L) // a position packet: not a report
        val h = LoRaStats.historyOf(mac)
        assertEquals(5, h.size)
        val rows = DeviceSections.trendRows(h, imperial = false).toMap()
        assertEquals("█▆▅▃▁  90% → 70%", rows["Battery over 2 h"])
        assertEquals("▄▄▄▄▄  20.0 °C → 20.0 °C", rows["Temperature over 2 h"])
        assertEquals(null, rows["Humidity over 2 h"])
        assertTrue(DeviceSections.trendRows(h.take(2), imperial = false).isEmpty())
    }
}
