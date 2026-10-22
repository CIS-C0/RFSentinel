package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.UUID

/**
 * Sentinel Link's crypto, wire format, framing and send throttle. Conscrypt off: once another Robolectric test
 * has installed it, the JVM's own Cipher can't start in the same test run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SentinelLinkTest {

    @Test
    fun bothPhonesShowTheSameCodeAndKeys() {
        val joiner = LinkCrypto.ecKeyPair()
        val host = LinkCrypto.ecKeyPair()
        val jPub = LinkCrypto.publicBytes(joiner)
        val hPub = LinkCrypto.publicBytes(host)
        val jN = LinkCrypto.random(16)
        val hN = LinkCrypto.random(16)
        val sJ = LinkCrypto.ecdh(joiner, LinkCrypto.publicFrom(hPub))
        val sH = LinkCrypto.ecdh(host, LinkCrypto.publicFrom(jPub))
        assertArrayEquals(sJ, sH)
        val code = LinkCrypto.code(sJ, jPub, hPub, jN, hN)
        assertEquals(code, LinkCrypto.code(sH, jPub, hPub, jN, hN))
        assertTrue(code.matches(Regex("\\d{6}")))
        // A phone in the middle has its own key pair: its code differs.
        val mitm = LinkCrypto.ecKeyPair()
        val sM = LinkCrypto.ecdh(mitm, LinkCrypto.publicFrom(hPub))
        assertNotEquals(code, LinkCrypto.code(sM, LinkCrypto.publicBytes(mitm), hPub, jN, hN))

        val toHost = LinkCrypto.pairKey(sJ, jN, hN, toHost = true)
        assertArrayEquals(toHost, LinkCrypto.pairKey(sH, jN, hN, toHost = true))
        assertFalse(toHost.contentEquals(LinkCrypto.pairKey(sH, jN, hN, toHost = false)))
    }

    @Test
    fun sealedDataOpensOnlyUnchangedWithItsKeyAndCounter() {
        val key = LinkCrypto.random(32)
        val sealed = LinkCrypto.seal(key, 7, "hello".toByteArray())
        assertEquals("hello", String(LinkCrypto.open(key, 7, sealed)!!))
        assertNull(LinkCrypto.open(key, 8, sealed))
        assertNull(LinkCrypto.open(LinkCrypto.random(32), 7, sealed))
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(LinkCrypto.open(key, 7, tampered))
    }

    @Test
    fun sessionKeysPairUpAndProofsNeedTheGroupKey() {
        val gk = LinkCrypto.random(32)
        val c = LinkCrypto.random(16)
        val s = LinkCrypto.random(16)
        val clientTx = LinkCrypto.sessionKey(gk, c, s, toServer = true)
        val serverRx = LinkCrypto.sessionKey(gk, c, s, toServer = true)
        assertArrayEquals(clientTx, serverRx)
        assertFalse(clientTx.contentEquals(LinkCrypto.sessionKey(gk, c, s, toServer = false)))
        assertFalse(LinkCrypto.proof(gk, "c", c).contentEquals(LinkCrypto.proof(LinkCrypto.random(32), "c", c)))
        assertFalse(LinkCrypto.proof(gk, "c", c).contentEquals(LinkCrypto.proof(gk, "s", c)))
    }

    @Test
    fun advertTagsChangeEverySlotAndNameThePhone() {
        val gk = LinkCrypto.random(32)
        assertFalse(LinkCrypto.groupTag(gk, 10).contentEquals(LinkCrypto.groupTag(gk, 11)))
        assertFalse(LinkCrypto.nodeTag(gk, 10, 1L).contentEquals(LinkCrypto.nodeTag(gk, 10, 2L)))
        assertArrayEquals(LinkCrypto.nodeTag(gk, 10, 1L), LinkCrypto.nodeTag(gk, 10, 1L))
        assertEquals(4, LinkCrypto.groupTag(gk, 10).size)
    }

    @Test
    fun batchRoundTripKeepsEverything() {
        val uuid = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
        val ble = Advert(
            mac = "AA:BB:CC:01:02:03", source = Advert.Source.BLE, rssi = -61, name = "Axon Body 3",
            manufacturerData = mapOf(0x034D to byteArrayOf(1, 2, 3)), serviceUuids = listOf(uuid),
            serviceData = mapOf(uuid to byteArrayOf(9, 8)), rawBytes = byteArrayOf(2, 1, 6), txPower = -4,
            addressType = AddressType.RANDOM_STATIC, connectable = true, phy = "LE 1M", timestamp = 1_000_000L - 1_500L
        )
        val wifi = Advert(
            mac = "11:22:33:44:55:66", source = Advert.Source.WIFI, rssi = -70, name = null,
            wifi = Advert.WifiInfo(5180, "[WPA2-PSK-CCMP]", "802.11ac", listOf(221 to byteArrayOf(0x50, 0x6F)), client = true,
                wildcardProbe = true, flockProbeIe = false, probeSkeleton = 0x1234L),
            timestamp = 1_000_000L
        )
        val hit = Hit(Category.BODY_CAM, "Axon body camera", 88, "Axon company id", "ESP32")
        val b = LinkCodec.Batch(42L, 7, 1, 1_000_000L, 10.5, 20.25,
            listOf(LinkCodec.Sighting(LinkCodec.KIND_PHONE, ble),
                LinkCodec.Sighting(LinkCodec.KIND_USB, wifi, listOf(hit), listOf("HomeNet"), "fp1", hidden = true)),
            listOf(LinkCodec.Radio(154_430_000L, 33, "VHF high", 150_000_000L, 174_000_000L, "SHARED", 60, "Dispatch", false, true, 999_000L)))

        val bytes = LinkCodec.batch(b)
        assertEquals(LinkCodec.BATCH, bytes[0].toInt())
        val r = LinkCodec.readBatch(bytes)!!
        assertEquals(42L, r.origin); assertEquals(7, r.seq); assertEquals(1, r.hops); assertEquals(10.5, r.lat, 0.0)
        val a = r.sightings[0].advert
        assertEquals("AA:BB:CC:01:02:03", a.mac)
        assertEquals(-61, a.rssi); assertEquals("Axon Body 3", a.name); assertEquals(998_500L, a.timestamp)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.manufacturerData[0x034D])
        assertEquals(listOf(uuid), a.serviceUuids)
        assertArrayEquals(byteArrayOf(9, 8), a.serviceData[uuid])
        assertEquals(-4, a.txPower); assertEquals(AddressType.RANDOM_STATIC, a.addressType); assertEquals(true, a.connectable)
        val s2 = r.sightings[1]
        assertEquals(LinkCodec.KIND_USB, s2.kind)
        assertEquals(listOf(hit), s2.hits)
        assertEquals(listOf("HomeNet"), s2.probed)
        assertEquals("fp1", s2.fingerprint); assertTrue(s2.hidden)
        val w = s2.advert.wifi!!
        assertEquals(5180, w.frequencyMhz); assertTrue(w.client); assertTrue(w.wildcardProbe); assertEquals(0x1234L, w.probeSkeleton)
        assertEquals(221, w.infoElements[0].first)
        assertEquals(LinkCodec.Radio(154_430_000L, 33, "VHF high", 150_000_000L, 174_000_000L, "SHARED", 60, "Dispatch", false, true, 999_000L),
            r.radios[0])
        assertNull(LinkCodec.readBatch(byteArrayOf(LinkCodec.BATCH.toByte(), 1, 2, 3)))
    }

    @Test
    fun smallMessagesRoundTrip() {
        val me = LinkCodec.Member(5L, "Pixel 7")
        val (who, known) = LinkCodec.readInfo(LinkCodec.info(me, listOf(LinkCodec.Member(9L, "Tablet"))))
        assertEquals(me, who); assertEquals(listOf(LinkCodec.Member(9L, "Tablet")), known)
        val key = LinkCrypto.random(32)
        val (k, members) = LinkCodec.readGroup(LinkCodec.group(key, listOf(me)))
        assertArrayEquals(key, k); assertEquals(listOf(me), members)
        val (counter, data) = LinkCodec.readSealed(LinkCodec.sealed(77L, byteArrayOf(4, 5)))
        assertEquals(77L, counter); assertArrayEquals(byteArrayOf(4, 5), data)
    }

    @Test
    fun framesReassembleFromAnyPieces() {
        val a = ByteArray(1000) { it.toByte() }
        val b = byteArrayOf(1, 2, 3)
        val stream = LinkFrames.frame(a) + LinkFrames.frame(b)
        val r = LinkFrames.Reassembler()
        val out = LinkFrames.chunks(stream, 37).flatMap { r.feed(it) }
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0]); assertArrayEquals(b, out[1])
        val bad = LinkFrames.Reassembler()
        assertTrue(runCatching { bad.feed(byteArrayOf(0x7F, 0, 0, 0)) }.isFailure)
    }

    @Test
    fun outboxSendsEachDeviceOnceUntilItChanges() {
        val box = LinkOutbox(1L)
        fun adv(rssi: Int, t: Long) = Advert("AA:00:00:00:00:01", Advert.Source.BLE, rssi, timestamp = t)
        box.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, adv(-60, 1_000)))
        box.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, adv(-61, 1_500))) // same device, nothing new
        val first = box.drain(2_000, Double.NaN, Double.NaN)
        assertNotNull(first)
        assertEquals(1, first!!.sightings.size)
        assertNull(box.drain(2_100, Double.NaN, Double.NaN))
        box.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, adv(-45, 2_200))) // much stronger
        box.add(LinkCodec.Sighting(LinkCodec.KIND_ESP, adv(-80, 2_200))) // another radio heard it
        val second = box.drain(2_300, Double.NaN, Double.NaN)!!
        assertEquals(2, second.sightings.size)
        assertTrue(second.seq > first.seq)
        box.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, adv(-45, 2_200 + LinkOutbox.REPEAT_MS))) // time to repeat
        assertEquals(1, box.drain(9_000, Double.NaN, Double.NaN)!!.sightings.size)
    }

    @Test
    fun seenBatchesPassEachOnlyOnce() {
        val seen = SeenBatches(capacity = 3)
        assertTrue(seen.first(1L, 1)); assertFalse(seen.first(1L, 1)); assertTrue(seen.first(2L, 1))
    }
}
