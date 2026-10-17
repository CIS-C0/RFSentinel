package com.rfsentinel.app.service

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.RemoteId
import com.rfsentinel.app.detect.SignatureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanRemoteIdTest {

    /** A Basic ID message: type 0, serial-number ID type, multirotor, then the ID text. */
    private fun basicId(id: String): ByteArray {
        val m = ByteArray(RemoteId.MESSAGE_SIZE)
        m[0] = 0x02 // message type 0 (Basic ID), protocol version 2
        m[1] = ((1 shl 4) or 2).toByte() // ID type 1 (serial), UA type 2 (helicopter / multirotor)
        id.toByteArray().copyInto(m, 2)
        return m
    }

    @Test
    fun aNanDiscoveryGoesThroughTheWifiRemoteIdPath() {
        // Service info: [counter][message pack: 0xF2, message size 25, count 1, message]
        val info = byteArrayOf(7, 0xF2.toByte(), 25, 1) + basicId("1581F5FJD123")
        val a = NanRemoteId.advert(NanRemoteId.madeUpAddress(0x12345678), info, 0L)
        assertEquals("0E:4E:12:34:56:78", a.mac)
        val hit = SignatureEngine.classify(a).first()
        assertEquals(Category.DRONE, hit.category)
        assertTrue(hit.label.contains("Wi-Fi Aware"))
        val ie = a.wifi!!.infoElements.first().second
        assertEquals("1581F5FJD123", RemoteId.decodeWifiIe(ie, null)?.uasId)
        assertEquals("Drone Remote ID over Wi-Fi Aware", DeviceIntel.identify(a, null).type)
    }
}
