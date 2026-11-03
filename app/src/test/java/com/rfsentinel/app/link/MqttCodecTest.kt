package com.rfsentinel.app.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class MqttCodecTest {

    private fun read(bytes: ByteArray) = MqttCodec.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun connectWithLogin() {
        val p = MqttCodec.connect("rfs-1", "u", "pw", 60)
        // 0x10, length, "MQTT", level 4, flags user+password+clean, keepalive 60, then the strings.
        val expected = byteArrayOf(0x10, 24, 0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4, 0xC2.toByte(), 0, 60,
            0, 5, 'r'.code.toByte(), 'f'.code.toByte(), 's'.code.toByte(), '-'.code.toByte(), '1'.code.toByte(),
            0, 1, 'u'.code.toByte(), 0, 2, 'p'.code.toByte(), 'w'.code.toByte())
        assertArrayEquals(expected, p)
        assertEquals(0x02, MqttCodec.connect("x", null, "ignored", 60)[9].toInt()) // no user: no password either
    }

    @Test
    fun publishRoundTripsWithLongLength() {
        val payload = ByteArray(300) { it.toByte() }
        val p = read(MqttCodec.publish("rfsentinel/ab", payload))
        assertEquals(MqttCodec.PUBLISH, p.type)
        val (topic, data) = MqttCodec.payload(p)!!
        assertEquals("rfsentinel/ab", topic)
        assertArrayEquals(payload, data)
    }

    @Test
    fun qos1PublishSkipsItsPacketId() {
        // Topic "t", packet id 0x0001, payload 0xAA.
        val p = read(byteArrayOf(0x32, 6, 0, 1, 't'.code.toByte(), 0, 1, 0xAA.toByte()))
        assertArrayEquals(byteArrayOf(0xAA.toByte()), MqttCodec.payload(p)!!.second)
    }

    @Test
    fun brokerAddresses() {
        assertEquals(MqttRelay.Broker(true, "example.org", 8883), MqttRelay.parse("example.org"))
        assertEquals(MqttRelay.Broker(false, "10.0.0.5", 1883), MqttRelay.parse(" mqtt://10.0.0.5 "))
        assertEquals(MqttRelay.Broker(true, "h.net", 9000), MqttRelay.parse("mqtts://h.net:9000"))
        assertEquals(null, MqttRelay.parse("http://h.net"))
        assertEquals(null, MqttRelay.parse(""))
    }

    @Test
    fun topicIsStableAndGroupSpecific() {
        val a = MqttRelay.topic(ByteArray(32) { 1 })
        assertEquals(a, MqttRelay.topic(ByteArray(32) { 1 }))
        assertEquals(27, a.length)
        assert(a != MqttRelay.topic(ByteArray(32) { 2 }))
    }
}
