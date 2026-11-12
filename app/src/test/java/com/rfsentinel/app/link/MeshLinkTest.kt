package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.online.PoliceAircraft
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
        // Flagged ones wait for their turn to come back; a plain one goes again in any packet with room.
        assertEquals(listOf("00:00:00:00:00:01"), box.take(3_000, MeshCodec.MAX_BODY).map { it.mac })
        box.add(adv("00:00:00:00:00:03"), listOf(watch), 61_000)
        box.add(adv("00:00:00:00:00:02"), listOf(cam), 61_000)
        assertEquals(listOf("00:00:00:00:00:03", "00:00:00:00:00:01"), box.take(62_500, MeshCodec.MAX_BODY).map { it.mac })
        // Small room: the most important one that fits.
        box.add(adv("00:00:00:00:00:04"), listOf(watch), 63_000)
        box.add(adv("00:00:00:00:00:05"), emptyList(), 63_000)
        assertEquals(listOf("00:00:00:00:00:05"), box.take(63_500, 10).map { it.mac })
        // Not heard for a while: not sent.
        assertTrue(box.take(63_000 + MeshOutbox.FRESH_MS + 1, MeshCodec.MAX_BODY).isEmpty())
    }

    @Test
    fun silencingTheBuzzerKeepsTheNodesOtherAlertSettings() {
        // FromRadio.moduleConfig (9) > ModuleConfig.external_notification (3): enabled, output_ms 1000, use_pwm, alert_message_buzzer.
        val ext = byteArrayOf(0x08, 0x01, 0x10, 0xE8.toByte(), 0x07, 0x38, 0x01, 0x58, 0x01)
        val module = byteArrayOf(0x1A, ext.size.toByte()) + ext
        val read = MeshProto.readFromRadio(byteArrayOf(0x4A, module.size.toByte()) + module) as MeshProto.From.ExtNotif
        assertTrue(read.enabled)
        assertEquals(mapOf(1 to 1L, 2 to 1000L, 7 to 1L, 11 to 1L), read.fields)

        // AdminMessage.set_module_config (35) with the same settings, enabled left out (off).
        val admin = MeshProto.setExtNotif(read.fields!!, enabled = false)
        val r = MeshProto.R(admin)
        var back: MeshProto.From? = null
        r.fields { f, w -> if (f == 35 && w == 2) { back = MeshProto.readModuleConfig(r.bytes()); true } else false }
        val off = back as MeshProto.From.ExtNotif
        assertFalse(off.enabled)
        assertEquals(mapOf(2 to 1000L, 7 to 1L, 11 to 1L), off.fields)
        assertTrue((MeshProto.readModuleConfig(MeshProto.R(MeshProto.setExtNotif(off.fields!!, enabled = true)).let { rr ->
            var b = ByteArray(0); rr.fields { f, w -> if (f == 35 && w == 2) { b = rr.bytes(); true } else false }; b
        }) as MeshProto.From.ExtNotif).enabled)
    }

    @Test
    fun aBuzzerSettingThatIsntAVarintIsLeftAlone() {
        // A length-delimited field 20 (a future firmware's): not written back.
        val ext = byteArrayOf(0x08, 0x01, 0xA2.toByte(), 0x01, 0x01, 0x41)
        val module = byteArrayOf(0x1A, ext.size.toByte()) + ext
        assertNull((MeshProto.readModuleConfig(module) as MeshProto.From.ExtNotif).fields)
    }

    @Test
    fun loraCyclesThroughEveryDeviceWithFlaggedOnesFirst() {
        val box = MeshOutbox()
        fun adv(i: Int) = Advert("00:00:00:00:01:%02x".format(i), Advert.Source.WIFI, -60, timestamp = 0)
        for (i in 0 until 40) box.add(adv(i), emptyList(), 1_000)
        box.add(adv(99), listOf(cam), 1_000)
        val room = MeshCodec.MAX_BODY / 3
        val first = box.take(1_000, room)
        assertEquals("flagged first", "00:00:00:00:01:63", first.first().mac)
        // Plain devices come round in turn, packet after packet, until all were sent.
        val seen = first.map { it.mac }.toMutableSet()
        var t = 1_000L
        while (seen.size < 41 && t < 60_000) { t += 1_000; box.add(adv(0), emptyList(), t); seen += box.take(t, room).map { it.mac } }
        assertEquals("every device went out", 41, seen.size)
    }

    @Test
    fun theInternetRelayRepeatsADeviceStillHeardWithinTheReceiversShortestWindow() {
        // The sender keeps hearing a plain device; the receiver lists it for 30 s after each arrival.
        fun adv() = Advert("00:00:00:00:00:01", Advert.Source.WIFI, -60, timestamp = 0)
        val net = MeshOutbox(MeshOutbox.NET_REPEAT_MS)
        val lora = MeshOutbox()
        for (box in listOf(net, lora)) { box.add(adv(), emptyList(), 1_000); assertEquals(1, box.take(1_000, MeshCodec.MAX_BODY).size) }
        net.add(adv(), emptyList(), 25_000); lora.add(adv(), emptyList(), 25_000)
        assertEquals(1, net.take(25_000, MeshCodec.MAX_BODY).size)
        assertEquals("LoRa sends it again in the next packet with room", 1, lora.take(25_000, MeshCodec.MAX_BODY).size)
        // Not heard any more: no repeats, so the receiver's time runs out.
        assertTrue(net.take(25_000 + MeshOutbox.FRESH_MS + 1, MeshCodec.MAX_BODY).isEmpty())
    }

    @Test
    fun repeatsTakeTurnsWhenAPacketCantHoldThemAll() {
        val box = MeshOutbox(MeshOutbox.NET_REPEAT_MS)
        val macs = (1..60).map { "00:00:00:00:01:%02X".format(it) }
        macs.forEach { box.add(Advert(it, Advert.Source.WIFI, -60, timestamp = 0), emptyList(), 1_000) }
        val rounds = ArrayList<Set<String>>(); var t = 1_000L
        while (rounds.flatten().toSet().size < macs.size && t < 20_000) { rounds += box.take(t, MeshCodec.MAX_BODY).map { d -> d.mac }.toSet(); t += 1_000 }
        val first = rounds.first()
        assertTrue(rounds.size > 1 && first.size < macs.size)
        assertEquals(macs.toSet(), rounds.flatten().toSet())
        // Round two: the ones that waited longest go first, none of the ones just sent.
        macs.forEach { box.add(Advert(it, Advert.Source.WIFI, -60, timestamp = 0), emptyList(), 30_000) }
        val next = box.take(30_000, MeshCodec.MAX_BODY).map { it.mac }
        assertTrue(next.isNotEmpty() && next.none { it in rounds.last() })
        assertTrue(next.count { it in first } > next.size / 2)
    }

    @Test
    fun fortyWifiNetworksAllReachTheOtherPhoneWithinAFewPackets() {
        val key = MeshCodec.messageKey(ByteArray(32) { 7 })
        val box = MeshOutbox()
        val got = HashSet<String>()
        // Made-up networks, heard on every WiFi scan (30 s apart); one packet a minute.
        for (sec in 0..480 step 30) {
            val now = 1_700_000_000_000L + sec * 1000L
            for (n in 0 until 40) box.add(Advert("02:00:00:00:00:%02X".format(n), Advert.Source.WIFI, -70, name = "Network-%02d".format(n), timestamp = now), emptyList(), now)
            if (sec % 60 != 0) continue
            val devices = box.take(now, MeshCodec.MAX_BODY - MeshCodec.baseSize(true, "Phone"))
            val m = MeshCodec.open(key, MeshCodec.seal(key, 1L, sec, now, 45.0, -73.0, "Phone", devices))!!
            m.devices.forEach { assertEquals(Advert.Source.WIFI, it.source); got += it.mac }
        }
        assertEquals(40, got.size)
    }

    @Test
    fun aircraftGoOverTheMeshAndBluetoothAndOlderBatchesStillRead() {
        val key = MeshCodec.messageKey(LinkCrypto.random(32))
        val police = PoliceAircraft.Plane("a1b2c3", "N123PD", "N123PD", "B407", "County Sheriff Office", 10.51234, -20.54321,
            1_450, 95.0, 271.0, false)
        val plain = PoliceAircraft.Plane("0f00ff", null, null, null, null, 10.6, -20.6, null, null, null, true)
        val odd = PoliceAircraft.Plane("~12345", null, null, null, null, 10.6, -20.6, null, null, null, false) // not an ICAO address
        val fits = MeshCodec.fit(listOf(police, odd, plain), MeshCodec.MAX_BODY)
        assertEquals(listOf(police, plain), fits)
        val sealed = MeshCodec.sealAircraft(key, 9L, 4, 1_700_000_000_000L, 10.5, -20.5, null, fits)
        assertTrue(sealed.size <= MeshProto.MAX_PAYLOAD)
        val m = MeshCodec.open(key, sealed)!!
        assertTrue(m.devices.isEmpty())
        assertEquals(2, m.planes.size)
        val p = m.planes[0]
        assertEquals("a1b2c3", p.hex); assertEquals("N123PD", p.callsign); assertEquals("B407", p.type)
        assertEquals("County Sheriff Office", p.owner); assertEquals(10.51234, p.lat, 1e-5); assertEquals(-20.54321, p.lon, 1e-5)
        assertEquals(1_450, p.altitudeFt); assertEquals(95.0, p.speedKt!!, 0.5); assertEquals(271.0, p.trackDeg!!, 1.5)
        assertEquals(plain, m.planes[1])
        assertEquals(m.planes, MeshCodec.toBatch(m).planes)
        // Many aircraft: one packet holds the first that fit.
        val many = List(40) { police.copy(hex = "%06x".format(it)) }
        assertTrue(MeshCodec.fit(many, MeshCodec.MAX_BODY).size in 2 until 40)
        assertTrue(MeshCodec.sealAircraft(key, 9L, 5, 1_700_000_000_000L, 10.5, -20.5, "Pixel", many).size <= MeshProto.MAX_PAYLOAD)

        // Bluetooth: they ride at the end of a batch, and a batch from an older version (no aircraft) still reads.
        val b = LinkCodec.Batch(1L, 2, 0, 1_000_000L, 10.5, -20.5, emptyList(), emptyList(), listOf(police, plain))
        assertEquals(listOf(police, plain), LinkCodec.readBatch(LinkCodec.batch(b))!!.planes)
        val older = LinkCodec.batch(LinkCodec.Batch(1L, 3, 0, 1_000_000L, 10.5, -20.5, emptyList(), emptyList()))
        val cut = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(java.util.zip.InflaterInputStream(older.inputStream(1, older.size - 1)).readBytes().dropLast(2).toByteArray()) }
        }.toByteArray()
        val r = LinkCodec.readBatch(byteArrayOf(LinkCodec.BATCH.toByte()) + cut)!!
        assertEquals(3, r.seq); assertTrue(r.planes.isEmpty())
    }
}
