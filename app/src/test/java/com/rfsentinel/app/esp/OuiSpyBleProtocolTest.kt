package com.rfsentinel.app.esp

import com.rfsentinel.app.detect.Category
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packets laid out like packDetection() in the OUI-SPY firmware (made-up values). */
class OuiSpyBleProtocolTest {

    private fun header(size: Int, engine: Int, mac: IntArray, rssi: Int, ch: Int, method: Int): ByteBuffer {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, engine.toByte())
        mac.forEachIndexed { i, v -> b.put(1 + i, v.toByte()) }
        b.put(7, rssi.toByte()); b.put(8, ch.toByte())
        b.putInt(9, 123456); b.put(13, method.toByte())
        return b
    }

    private fun str(b: ByteBuffer, at: Int, s: String) = s.toByteArray().forEachIndexed { i, v -> b.put(at + i, v) }

    private val mac = intArrayOf(0x70, 0xC9, 0x4E, 0x11, 0x22, 0x33)

    @Test
    fun flockWifiWildcardProbe() {
        val b = header(70, OuiSpyBleProtocol.ENGINE_FLOCK_WIFI, mac, -70, 6, 4)
        val s = OuiSpyBleProtocol.decode(b.array())!!
        assertEquals("70:C9:4E:11:22:33", s.mac)
        assertFalse(s.ble)
        assertEquals(2437, s.frequencyMhz)
        assertEquals(Category.ALPR, s.hits.single().category)
        assertEquals(80, s.hits.single().confidence)
    }

    @Test
    fun flockBleNameAndRaven() {
        val b = header(70, OuiSpyBleProtocol.ENGINE_FLOCK_BLE, mac, -60, 0, 1)
        str(b, 37, "Penguin-1234567890")
        val s = OuiSpyBleProtocol.decode(b.array())!!
        assertTrue(s.ble)
        assertEquals("Penguin-1234567890", s.name)
        assertEquals(Category.ALPR, s.hits.single().category)

        val r = header(70, OuiSpyBleProtocol.ENGINE_FLOCK_BLE, mac, -60, 0, 3)
        r.put(19, 1); str(r, 20, "1.3.1")
        assertEquals(Category.AUDIO_SENSOR, OuiSpyBleProtocol.decode(r.array())!!.hits.single().category)
    }

    @Test
    fun skySpyDroneWithPositions() {
        val b = header(155, OuiSpyBleProtocol.ENGINE_SKYSPY, intArrayOf(0x60, 0x60, 0x1F, 1, 2, 3), -80, 0, 0)
        str(b, 19, "1581F5FJD123")
        b.putDouble(61, 10.5010); b.putDouble(69, -20.5020)
        b.putShort(77, 130); b.putShort(79, 60)
        b.putDouble(85, 10.5000); b.putDouble(93, -20.5000)
        val s = OuiSpyBleProtocol.decode(b.array())!!
        val rid = s.remoteId!!
        assertEquals("1581F5FJD123", rid.uasId)
        assertEquals(10.501, rid.latitude!!, 1e-9)
        assertEquals(-20.5, rid.operatorLongitude!!, 1e-9)
        assertEquals(60.0, rid.heightM!!, 0.0)
        assertEquals(Category.DRONE, s.hits.single().category)
    }

    @Test
    fun detectorSignaturesAndWatchlist() {
        val axon = header(52, OuiSpyBleProtocol.ENGINE_DETECTOR, intArrayOf(0x00, 0x25, 0xDF, 1, 2, 3), -55, 0, 8)
        assertEquals(Category.BODY_CAM, OuiSpyBleProtocol.decode(axon.array())!!.hits.single().category)

        val watch = header(52, OuiSpyBleProtocol.ENGINE_DETECTOR, mac, -55, 0, 0)
        str(watch, 20, "Patrol car radio")
        assertEquals("OUI-SPY target: Patrol car radio", OuiSpyBleProtocol.decode(watch.array())!!.hits.single().label)

        val flipper = header(52, OuiSpyBleProtocol.ENGINE_DETECTOR, mac, -55, 0, 3)
        assertTrue(OuiSpyBleProtocol.decode(flipper.array())!!.hits.isEmpty()) // outside RF Sentinel's scope
    }

    @Test
    fun skipsOldUnusedAndShortFrames() {
        // Detected while no phone was connected (DET_FLAG_AWAY).
        assertNull(OuiSpyBleProtocol.decode(header(70, 0x80 or OuiSpyBleProtocol.ENGINE_FLOCK_WIFI, mac, -70, 6, 4).array()))
        // Engines RF Sentinel doesn't use (5 = UniPwn, 6 = Wardrive, 7 = PCAP).
        for (e in 5..7) assertNull(OuiSpyBleProtocol.decode(header(74, e, mac, -70, 6, 0).array()))
        assertNull(OuiSpyBleProtocol.decode(ByteArray(10)))
        assertTrue(OuiSpyBleProtocol.isSpoolHeader(byteArrayOf(0xFF.toByte(), 2, 0, 0, 0, 1, 2, 3, 4)))
        assertTrue(OuiSpyBleProtocol.isSpoolEnd(byteArrayOf(0xFE.toByte())))
    }

    @Test
    fun onlyDetectionEnginesAreEverEnabled() {
        assertEquals(listOf(1, 2, 4, 0), OuiSpyBleProtocol.ENGINES)
        assertArrayEquals(byteArrayOf(0x01, 0x02), OuiSpyBleProtocol.enable(2))
        assertArrayEquals(byteArrayOf(0x0F, 0x00), OuiSpyBleProtocol.disableAll())
    }
}
