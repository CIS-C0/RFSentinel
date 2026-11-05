package com.rfsentinel.app.esp

import com.rfsentinel.app.link.MeshProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Meshtastic and MeshCore radios: framing, and which nodes count as heard. */
class MeshRadioTest {

    private fun node(num: Long, name: String, lastHeard: Long, mqtt: Boolean = false): ByteArray = MeshProto.W().apply {
        msg(4) {
            uint(1, num)
            msg(2) { string(2, name) }
            msg(3) { fixed32(1, 105_000_000L); fixed32(2, (-205_000_000L) and 0xFFFFFFFFL) }
            fixed32(5, lastHeard)
            bool(8, mqtt)
        }
    }.bytes()

    private fun contact(code: Int, name: String, lat: Int = 0, lon: Int = 0): ByteArray {
        val f = ByteArray(148)
        f[0] = code.toByte()
        for (i in 1..32) f[i] = i.toByte()
        name.toByteArray().copyInto(f, 100)
        ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).putInt(136, lat).putInt(140, lon)
        return f
    }

    @Test
    fun meshtasticNodeList() {
        val log = MeshtasticLog { 100_000 }
        log.feed(MeshProto.From.MyInfo(0x11))
        assertNull("itself", log.feed(MeshProto.readFromRadio(node(0x11, "Me", 99_999))))
        assertNull("over MQTT", log.feed(MeshProto.readFromRadio(node(0x22, "Far", 99_999, mqtt = true))))
        assertNull("old", log.feed(MeshProto.readFromRadio(node(0x33, "Old", 100_000 - 3 * 3600))))
        val s = log.feed(MeshProto.readFromRadio(node(0xA1B2C3D4, "Hilltop", 99_900)))!!
        assertEquals("06:4D:A1:B2:C3:D4", s.mac)
        assertEquals("Meshtastic: Hilltop", s.name)
        assertEquals(10.5, s.position!!.first, 1e-6)
        assertEquals(-20.5, s.position!!.second, 1e-6)
        assertEquals(setOf(0xA1B2C3D4), log.heard)
    }

    @Test
    fun meshtasticPacketsSkipMqttAndUseRssi() {
        val log = MeshtasticLog { 0 }
        assertEquals(-97, log.feed(MeshProto.From.Packet(0x55, 0xFFFFFFFFL, 0, 1, ByteArray(0), -97))!!.rssi)
        assertNull(log.feed(MeshProto.From.Packet(0x66, 0xFFFFFFFFL, 0, 1, ByteArray(0), -97, viaMqtt = true)))
    }

    @Test
    fun meshCoreLogsAdvertsNotContacts() {
        var now = 1_000L
        val log = MeshCoreLog { now }
        assertNull("the contact list is history", log.feed(contact(3, "Repeater", 10_500_000, -20_500_000)))
        assertNull(log.feed(byteArrayOf(0x88.toByte(), 8, (-88).toByte(), 1, 2)))
        val s = log.feed(contact(3, "x").copyOf(33).also { it[0] = 0x80.toByte() })!!
        assertEquals("06:43:01:02:03:04", s.mac)
        assertEquals("MeshCore: Repeater", s.name)
        assertEquals(-88, s.rssi)
        assertEquals(10.5, s.position!!.first, 1e-6)
        now += 10_000
        assertEquals("the level was for another packet", -120, log.feed(contact(0x8A, "New"))!!.rssi)
    }

    @Test
    fun framesBetweenLogTextAndAcrossReads() {
        val f = MeshFrames(meshtastic = true)
        val wire = "INFO boot\n".toByteArray() + MeshFrames.meshtastic(byteArrayOf(1, 2, 3)) + "log".toByteArray() + MeshFrames.meshtastic(byteArrayOf(4))
        val out = f.feed(wire, 13) + f.feed(wire.copyOfRange(13, wire.size), wire.size - 13)
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4)), out.map { it.toList() })

        val c = MeshFrames(meshtastic = false)
        val reply = byteArrayOf('>'.code.toByte(), 2, 0, 5, 9)
        assertEquals(listOf<Byte>(5, 9), c.feed(reply, reply.size).single().toList())
    }

    @Test
    fun selfInfoAndStartCommand() {
        assertTrue(MeshCoreLog.isSelfInfo(ByteArray(58).also { it[0] = 5 }))
        assertEquals(8 + 11, MeshCoreLog.APP_START.size)
        assertNotNull(MeshProto.readFromRadio(node(1, "a", 1)) as? MeshProto.From.NodeInfo)
    }
}
