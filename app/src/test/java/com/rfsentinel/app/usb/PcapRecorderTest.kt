package com.rfsentinel.app.usb

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** .pcap recordings of what the USB adapters hear: a file Wireshark opens, radiotap with channel and signal. */
class PcapRecorderTest {
    /** Kept in the build folder so a scapy / Wireshark check can open it. */
    private val file = File("build/test-pcap/sample.pcap")

    @After
    fun tearDown() = PcapRecorder.stop()

    /** A beacon from 02:00:00:00:00:01 named "Test", optionally with its checksum. */
    private fun beacon(withFcs: Boolean): ByteArray {
        val hdr = ByteArray(24).also {
            it[0] = 0x80.toByte()
            for (i in 4..9) it[i] = 0xFF.toByte()
            it[10] = 2; it[15] = 1; it[16] = 2; it[21] = 1
        }
        val body = ByteArray(12) + byteArrayOf(0, 4) + "Test".toByteArray()
        val frame = hdr + body
        if (!withFcs) return frame
        val c = CRC32().apply { update(frame) }.value
        return frame + byteArrayOf(c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), (c shr 24).toByte())
    }

    @Test
    fun aRecordingWiresharkCanOpen() {
        assertTrue(PcapRecorder.start(file))
        val withFcs = beacon(true)
        // Inside a bigger USB buffer, as the drivers hand them over.
        val buf = ByteArray(10) + withFcs + ByteArray(5)
        PcapRecorder.frame(buf, 10, 10 + withFcs.size, 6, -48, now = 1_700_000_000_123L)
        PcapRecorder.frame(beacon(false), 0, beacon(false).size, 36, null, now = 1_700_000_001_000L)
        assertEquals(2, PcapRecorder.frames)
        PcapRecorder.stop()

        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xa1b2c3d4.toInt(), b.int); assertEquals(2, b.short.toInt()); assertEquals(4, b.short.toInt())
        b.int; b.int; assertEquals(65535, b.int); assertEquals(127, b.int) // radiotap

        // Frame 1: 2.4 GHz channel 6, -48 dBm, checksum kept.
        assertEquals(1_700_000_000, b.int); assertEquals(123_000, b.int)
        val len1 = b.int; assertEquals(len1, b.int); assertEquals(15 + withFcs.size, len1)
        assertEquals(0, b.get().toInt()); b.get(); assertEquals(15, b.short.toInt())
        assertEquals(0x2A, b.int) // flags, channel, antenna signal
        assertEquals(0x10, b.get().toInt()) // FCS at the end
        b.get() // alignment
        assertEquals(2437, b.short.toInt()); assertEquals(0x00A0, b.short.toInt())
        assertEquals(-48, b.get().toInt())
        val f1 = ByteArray(withFcs.size).also { b.get(it) }
        assertArrayEquals(withFcs, f1)

        // Frame 2: 5 GHz channel 36, no signal reading, no checksum.
        b.int; b.int
        val len2 = b.int; b.int; assertEquals(14 + beacon(false).size, len2)
        b.get(); b.get(); assertEquals(14, b.short.toInt()); assertEquals(0x0A, b.int)
        assertEquals(0, b.get().toInt()); b.get()
        assertEquals(5180, b.short.toInt()); assertEquals(0x0140, b.short.toInt())
        assertEquals(beacon(false).size, b.remaining())
    }

    @Test
    fun nothingIsWrittenWhenNotRecording() {
        PcapRecorder.stop()
        val before = PcapRecorder.frames
        PcapRecorder.frame(beacon(false), 0, 40, 1, -60)
        assertEquals(before, PcapRecorder.frames)
        assertFalse(PcapRecorder.active)
    }

    @Test
    fun channelsAndV2xFrequencies() {
        assertEquals(2412, PcapRecorder.frequencyOf(1))
        assertEquals(2484, PcapRecorder.frequencyOf(14))
        assertEquals(5825, PcapRecorder.frequencyOf(165))
        assertEquals(5900, PcapRecorder.frequencyOf(5900))
    }

    @Test
    fun theChecksumIsOnlyClaimedWhenItMatches() {
        val f = beacon(true)
        assertTrue(PcapRecorder.hasFcs(f, 0, f.size))
        val g = beacon(false)
        assertFalse(PcapRecorder.hasFcs(g, 0, g.size))
    }
}
