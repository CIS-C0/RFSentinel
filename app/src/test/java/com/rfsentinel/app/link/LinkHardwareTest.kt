package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.link.LinkHardware.Item
import com.rfsentinel.app.link.LinkHardware.Kind
import com.rfsentinel.app.ui.StatusPanel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Another Sentinel's external hardware over Sentinel Link: the summary, its packet, when it goes out, what notifies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LinkHardwareTest {
    private val key = MeshCodec.messageKey(ByteArray(32) { it.toByte() })
    private val wifi = Item(Kind.WIFI, State.LIVE, "MT7612U")
    private val sdr = Item(Kind.SDR, State.LIVE, null)

    @Before
    fun clear() = LinkHardware.remote.clear()

    @Test
    fun statusLinesBecomeKindStateAndChipOnly() {
        val items = LinkHardware.items(listOf(
            Kind.WIFI to "USB WiFi (MT7612U) · live · 32 access points",
            Kind.BT to "",
            Kind.SDR to "Network SDR · 10.0.0.5:1234 · waterfall on 100.0 MHz",
            Kind.ESP to "ESP32 disconnected",
            Kind.OUISPY to "OUI-SPY: connecting…",
        ))
        assertEquals(listOf(wifi, Item(Kind.SDR, State.LIVE, null), Item(Kind.ESP, State.ERROR, null), Item(Kind.OUISPY, State.WAIT, null)), items)
        // Brackets holding anything but a chip name aren't sent.
        assertNull(LinkHardware.items(listOf(Kind.SDR to "SDR (192.168.1.20:1234) · live")).single().chip)
        assertNull(LinkHardware.items(listOf(Kind.SDR to "SDR (192.168.1.20) · live")).single().chip)
        assertNull(LinkHardware.items(listOf(Kind.SDR to "SDR (1234) · live")).single().chip)
        assertEquals(State.ERROR, LinkHardware.items(listOf(Kind.SDR to "RTL-SDR unplugged")).single().state)
    }

    @Test
    fun statusTextReadsBackWithTheSameState() {
        for (s in State.entries) {
            val item = Item(Kind.BT, s, "RTL8761BU")
            assertEquals(item, LinkHardware.items(listOf(Kind.BT to item.status)).single())
        }
    }

    @Test
    fun hardwarePacketRoundTripsAndStaysSmall() {
        val items = listOf(wifi, Item(Kind.BT, State.INFO, "MT7921AU"), sdr, Item(Kind.MESH, State.ERROR, null),
            Item(Kind.WIFI, State.LIVE, "MT7612U", wide = true))
        val bytes = MeshCodec.sealHardware(key, 42L, 7, 1_700_000_000_000L, items)
        assertTrue("${bytes.size} bytes", bytes.size < 70)
        val m = MeshCodec.open(key, bytes)!!
        assertEquals(42L, m.origin)
        assertEquals(1_700_000_000_000L, m.time)
        assertEquals(items, m.hardware)
        assertTrue(m.devices.isEmpty())
        assertTrue(m.lat.isNaN())
        assertNull(m.name)
    }

    @Test
    fun olderVersionsDropTheHardwarePacketAndOldPacketsHaveNoHardware() {
        // Versions before this one open only packet versions 1 (devices) and 2 (aircraft).
        val bytes = MeshCodec.sealHardware(key, 42L, 7, 1_700_000_000_000L, listOf(wifi))
        assertFalse(bytes[1].toInt() == 1 || bytes[1].toInt() == 2)
        val devices = MeshCodec.seal(key, 42L, 8, 1_700_000_000_000L, Double.NaN, Double.NaN, null,
            listOf(MeshCodec.Device("AA:BB:CC:DD:EE:FF", Advert.Source.BLE, -60, 3, null, emptyList())))
        val m = MeshCodec.open(key, devices)!!
        assertNull(m.hardware)
        assertEquals(1, m.devices.size)
    }

    @Test
    fun anUnknownKindFromANewerVersionIsSkipped() {
        val out = ByteArrayOutputStream()
        out.write(0x0F or 0x40); out.write(2); out.write("X1".toByteArray())
        LinkHardware.write(out, listOf(wifi))
        assertEquals(listOf(wifi), LinkHardware.read(ByteBuffer.wrap(out.toByteArray())))
    }

    @Test
    fun beatSendsOnChangeSpacedOutAndAHeartbeatOnlyWithHardware() {
        val beat = LinkHardware.Beat(60_000L, 900_000L)
        val t = 1_000_000L
        assertFalse("no hardware: nothing to send", beat.due(emptyList(), t))
        assertTrue(beat.due(listOf(wifi), t))
        beat.sent(listOf(wifi), t)
        assertFalse(beat.due(listOf(wifi), t + 60_000L))
        assertFalse("a change waits for the gap", beat.due(listOf(wifi, sdr), t + 30_000L))
        assertTrue(beat.due(listOf(wifi, sdr), t + 60_000L))
        assertTrue("heartbeat", beat.due(listOf(wifi), t + 900_000L))
        beat.sent(emptyList(), t + 60_000L)
        assertFalse("unplugged: one empty summary, no heartbeat", beat.due(emptyList(), t + 2_000_000L))
    }

    @Test
    fun aRunningAdapterThatGoesOrFailsNotifiesOnce() {
        val now = 5_000_000L
        assertTrue("first summary: nothing to compare", LinkHardware.received(1L, 100L, listOf(wifi, sdr), now).isEmpty())
        assertEquals(listOf(wifi), LinkHardware.received(1L, 200L, listOf(sdr), now))
        assertTrue("already gone", LinkHardware.received(1L, 300L, listOf(sdr), now).isEmpty())
        assertEquals(listOf(sdr), LinkHardware.received(1L, 400L, listOf(Item(Kind.SDR, State.ERROR, null)), now))
        // Waiting (reconnecting, a mode switch) isn't a stop; another Sentinel's list is its own.
        LinkHardware.received(1L, 500L, listOf(wifi), now)
        assertTrue(LinkHardware.received(1L, 600L, listOf(Item(Kind.WIFI, State.WAIT, "MT7612U")), now).isEmpty())
        assertTrue(LinkHardware.received(2L, 600L, emptyList(), now).isEmpty())
    }

    @Test
    fun aLateOlderSummaryChangesNothingAndOldOnesFade() {
        val now = 5_000_000L
        LinkHardware.received(1L, 200L, listOf(wifi), now)
        assertTrue(LinkHardware.received(1L, 100L, emptyList(), now).isEmpty())
        assertEquals(listOf(wifi), LinkHardware.of(1L, now))
        assertTrue(LinkHardware.of(1L, now + LinkHardware.STALE_MS).isEmpty())
    }
}
