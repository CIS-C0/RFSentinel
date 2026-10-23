package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Sentinel Link over Meshtastic: the phone-API protobufs, RF Sentinel's packet, and what goes out first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MeshLinkTest {

    @Test
    fun wantConfigMatchesTheFirmwaresSpecialNonce() {
        // ToRadio.want_config_id (field 3) = 69420.
        assertArrayEquals(byteArrayOf(0x18, 0xAC.toByte(), 0x9E.toByte(), 0x04), MeshProto.wantConfig(MeshProto.CONFIG_ONLY))
    }

    @Test
    fun readsPacketsAndSkipsUnknownFields() {
        val payload = byteArrayOf(1, 2, 3)
        val bytes = MeshProto.W().apply {
            uint(1, 77) // FromRadio.id
            msg(2) {
                fixed32(1, 0xA1B2C3D4L)
                fixed32(2, MeshProto.BROADCAST)
                uint(3, 2)
                msg(4) { uint(1, MeshProto.PORT_PRIVATE_APP.toLong()); bytes(2, payload); uint(9, 5) }
                fixed32(7, 1_700_000_000L)
                int32(12, -97)
                uint(99, 12345)
            }
        }.bytes()
        val p = MeshProto.readFromRadio(bytes) as MeshProto.From.Packet
        assertEquals(0xA1B2C3D4L, p.from)
        assertEquals(MeshProto.BROADCAST, p.to)
        assertEquals(2, p.channel)
        assertEquals(MeshProto.PORT_PRIVATE_APP, p.port)
        assertArrayEquals(payload, p.payload)
        assertEquals(-97, p.rxRssi)
    }

    @Test
    fun readsTheNodesSettings() {
        val my = MeshProto.W().apply { msg(3) { uint(1, 0xDEADBEEFL) } }.bytes()
        assertEquals(MeshProto.From.MyInfo(0xDEADBEEFL), MeshProto.readFromRadio(my))
        val lora = MeshProto.W().apply { msg(5) { msg(6) { uint(2, 0); uint(7, 1); uint(8, 4); bool(9, true) } } }.bytes()
        assertEquals(MeshProto.From.Lora(4, 1, true), MeshProto.readFromRadio(lora))
        val done = MeshProto.W().apply { uint(7, MeshProto.CONFIG_ONLY.toLong()) }.bytes()
        assertEquals(MeshProto.From.ConfigComplete(MeshProto.CONFIG_ONLY.toLong()), MeshProto.readFromRadio(done))
        val psk = ByteArray(32) { it.toByte() }
        val ch = MeshProto.W().apply { msg(10) { uint(1, 3); msg(2) { bytes(2, psk); string(3, "RFSentinel") }; uint(3, 2) } }.bytes()
        val c = MeshProto.readFromRadio(ch) as MeshProto.From.ChannelInfo
        assertEquals(3, c.index); assertEquals(2, c.role); assertEquals("RFSentinel", c.name); assertArrayEquals(psk, c.psk)
        assertTrue(MeshProto.readFromRadio(MeshProto.W().apply { uint(16, 1) }.bytes()) === MeshProto.From.Other)
        assertTrue(runCatching { MeshProto.readFromRadio(byteArrayOf(0x12, 0x7F)) }.isFailure)
    }

    @Test
    fun outgoingPacketHasTheRightFields() {
        val payload = byteArrayOf(9, 8, 7)
        val toRadio = MeshProto.packet(MeshProto.BROADCAST, 2, MeshProto.PORT_PRIVATE_APP, payload, 0x01020304L, 3,
            MeshProto.PRIORITY_BACKGROUND)
        // ToRadio.packet is field 1, FromRadio.packet field 2: same MeshPacket inside.
        assertEquals(0x0A, toRadio[0].toInt())
        val p = MeshProto.readFromRadio(byteArrayOf(0x12) + toRadio.copyOfRange(1, toRadio.size)) as MeshProto.From.Packet
        assertEquals(MeshProto.BROADCAST, p.to); assertEquals(2, p.channel); assertEquals(MeshProto.PORT_PRIVATE_APP, p.port)
        assertArrayEquals(payload, p.payload)
    }

    @Test
    fun setChannelIsASecondaryChannelWithOurKey() {
        val psk = ByteArray(32) { 7 }
        val admin = MeshProto.setChannel(4, MeshCodec.CHANNEL_NAME, psk)
        // AdminMessage.set_channel = field 33: key 33 << 3 | 2 = 266, two varint bytes.
        assertEquals(0x8A, admin[0].toInt() and 0xFF); assertEquals(0x02, admin[1].toInt())
        val c = MeshProto.readFromRadio(byteArrayOf(0x52) + admin.copyOfRange(2, admin.size)) as MeshProto.From.ChannelInfo
        assertEquals(4, c.index); assertEquals(MeshProto.ROLE_SECONDARY, c.role); assertEquals(MeshCodec.CHANNEL_NAME, c.name)
        assertArrayEquals(psk, c.psk)
    }

    private val watch = Hit(Category.CUSTOM, "My watched camera", 90, "prefix", "Watchlist")
    private val cam = Hit(Category.BODY_CAM, "Axon body camera", 80, "company id", "BLE")

    @Test
    fun meshPacketRoundTripsAndFitsOnePacket() {
        val gk = LinkCrypto.random(32)
        val key = MeshCodec.messageKey(gk)
        val devices = listOf(
            MeshCodec.Device("AA:BB:CC:00:11:22", Advert.Source.BLE, -61, 3, "Axon Body 3", listOf(watch, cam)),
            MeshCodec.Device("11:22:33:44:55:66", Advert.Source.WIFI, -80, 40, null, emptyList()),
        )
        val sealed = MeshCodec.seal(key, 42L, 7, 1_700_000_000_000L, 10.5, -20.25, "Pixel 7", devices)
        assertTrue(sealed.size <= MeshProto.MAX_PAYLOAD)
        val m = MeshCodec.open(key, sealed)!!
        assertEquals(42L, m.origin); assertEquals(7, m.counter); assertEquals(1_700_000_000_000L, m.time)
        assertEquals(10.5, m.lat, 1e-5); assertEquals(-20.25, m.lon, 1e-5); assertEquals("Pixel 7", m.name)
        assertEquals(2, m.devices.size)
        val d = m.devices[0]
        assertEquals("AA:BB:CC:00:11:22", d.mac); assertEquals(Advert.Source.BLE, d.source); assertEquals(-61, d.rssi)
        assertEquals(3, d.ageSec); assertEquals("Axon Body 3", d.name)
        assertEquals(listOf(Category.CUSTOM, Category.BODY_CAM), d.hits.map { it.category })
        assertEquals("Axon body camera", d.hits[1].label); assertEquals(80, d.hits[1].confidence)
        assertEquals(Advert.Source.WIFI, m.devices[1].source); assertNull(m.devices[1].name)

        // Another group, or changed on the way: not read.
        assertNull(MeshCodec.open(MeshCodec.messageKey(LinkCrypto.random(32)), sealed))
        assertNull(MeshCodec.open(key, sealed.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }))
        assertNull(MeshCodec.open(key, sealed.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() })) // origin is authenticated too

        // No position, no name, too many devices: still one packet, the rest left out.
        val many = (0 until 60).map { MeshCodec.Device("AA:00:00:00:00:%02X".format(it), Advert.Source.BLE, -70, 1, "Device $it", emptyList()) }
        val full = MeshCodec.seal(key, 1L, 8, 1_700_000_000_000L, Double.NaN, Double.NaN, null, many)
        assertTrue(full.size <= MeshProto.MAX_PAYLOAD)
        val back = MeshCodec.open(key, full)!!
        assertTrue(back.lat.isNaN())
        assertTrue(back.devices.size in 5 until 60)
    }

    @Test
    fun meshMessageBecomesABatchApartFromBluetoothOnes() {
        val m = MeshCodec.Message(5L, 3, 1_000_000L, 1.0, 2.0, null,
            listOf(MeshCodec.Device("AA:BB:CC:00:11:22", Advert.Source.BLE, -60, 10, "x", listOf(cam))))
        val b = MeshCodec.toBatch(m)
        assertTrue(b.seq < 0)
        assertEquals(1, b.hops)
        assertEquals(990_000L, b.sightings[0].advert.timestamp)
        assertEquals(listOf(cam.category), b.sightings[0].hits.map { it.category })
    }

    @Test
    fun textIsCutWithoutBreakingCharacters() {
        val b = MeshCodec.utf8("caméra 📷📷📷", 10)
        assertTrue(b.size <= 10)
        assertFalse(String(b, Charsets.UTF_8).contains('�'))
    }

    @Test
    fun watchlistGoesFirstThenFlaggedThenTheRest() {
        val box = MeshOutbox()
        fun adv(mac: String) = Advert(mac, Advert.Source.BLE, -60, timestamp = 0)
        box.add(adv("00:00:00:00:00:01"), emptyList(), 1_000)
        box.add(adv("00:00:00:00:00:02"), listOf(cam), 1_000)
        box.add(adv("00:00:00:00:00:03"), listOf(watch), 1_000)
        assertTrue(box.urgent(1_000))
        val first = box.take(2_000, MeshCodec.MAX_BODY)
        assertEquals(listOf("00:00:00:00:00:03", "00:00:00:00:00:02", "00:00:00:00:00:01"), first.map { it.mac })
        assertFalse(box.urgent(2_000))
        // Nothing new: nothing due until each one's turn comes back.
        assertTrue(box.take(3_000, MeshCodec.MAX_BODY).isEmpty())
        box.add(adv("00:00:00:00:00:03"), listOf(watch), 61_000)
        box.add(adv("00:00:00:00:00:02"), listOf(cam), 61_000)
        assertEquals(listOf("00:00:00:00:00:03"), box.take(62_500, MeshCodec.MAX_BODY).map { it.mac })
        // Small room: the most important one that fits.
        box.add(adv("00:00:00:00:00:04"), listOf(watch), 63_000)
        box.add(adv("00:00:00:00:00:05"), emptyList(), 63_000)
        assertEquals(listOf("00:00:00:00:00:05"), box.take(63_500, 10).map { it.mac })
        // Not heard for a while: not sent.
        assertTrue(box.take(63_000 + MeshOutbox.FRESH_MS + 1, MeshCodec.MAX_BODY).isEmpty())
    }
}
