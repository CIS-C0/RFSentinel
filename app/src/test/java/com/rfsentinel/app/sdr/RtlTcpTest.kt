package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class RtlTcpTest {

    private fun cmd(c: Int, v: Int) = listOf(c, v)

    @Test
    fun opensSendsCommandsAndKeepsIqAligned() {
        val server = ServerSocket(0)
        val got = mutableListOf<List<Int>>()
        val iq = ByteArray(1000) { it.toByte() }
        val t = thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); out.flush()
                val inp = DataInputStream(s.getInputStream())
                // AGC, bias-T, rate, gain mode, gain, frequency.
                repeat(6) { got += cmd(inp.readUnsignedByte(), inp.readInt()) }
                // After the retune: the stale samples it skips, then the stream in an odd split (333 bytes, a pause, the rest).
                Thread.sleep(300)
                out.write(ByteArray(RtlTcp.STALE_BYTES) { 0x7f }); out.write(iq, 0, 333); out.flush()
                Thread.sleep(2_500) // longer than the read timeout: the first read comes back short, on an odd byte
                out.write(iq, 333, iq.size - 333); out.flush()
                Thread.sleep(500)
            }
        }
        val sdr = RtlTcp("127.0.0.1", server.localPort) {}
        assertTrue(sdr.open())
        assertEquals(-1, sdr.read(ByteArray(4), 1))
        val buf = ByteArray(1000)
        var n = 0
        while (n < iq.size) {
            val part = ByteArray(400)
            val k = sdr.read(part, part.size)
            assertTrue("reads are whole samples", k > 0 && k % 2 == 0)
            System.arraycopy(part, 0, buf, n, k); n += k
        }
        assertArrayEquals(iq, buf)
        t.join(); server.close()
        assertEquals(listOf(
            cmd(RtlTcp.AGC, 0), cmd(RtlTcp.BIAS_TEE, 0), cmd(RtlTcp.SAMPLE_RATE, 2_048_000),
            cmd(RtlTcp.GAIN_MODE, 1), cmd(RtlTcp.GAIN, 297), cmd(RtlTcp.FREQUENCY, 100_000_000)), got)
        assertEquals(2_048_000, sdr.sampleRate)
        assertFalse(sdr.tune(5_000_000_000L))
        sdr.close()
    }

    @Test
    fun biasTeeAndFrequencyCorrectionReachTheServerAndThePowerGoesOffOnClose() {
        val server = ServerSocket(0)
        val got = mutableListOf<List<Int>>()
        val t = thread {
            server.accept().use { s ->
                s.getOutputStream().apply { write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); flush() }
                val inp = DataInputStream(s.getInputStream())
                // The six on open, then bias on, the corrected tune, bias off on close.
                repeat(9) { got += cmd(inp.readUnsignedByte(), inp.readInt()) }
            }
        }
        val sdr = RtlTcp("127.0.0.1", server.localPort) {}
        assertTrue(sdr.open())
        assertTrue(sdr.setBiasTee(true))
        sdr.ppm = 25.0
        assertTrue(sdr.tune(851_012_500L))
        sdr.close()
        t.join(); server.close()
        assertEquals(listOf(cmd(RtlTcp.BIAS_TEE, 1), cmd(RtlTcp.FREQUENCY, SdrDevice.corrected(851_012_500L, 25.0).toInt()), cmd(RtlTcp.BIAS_TEE, 0)),
            got.drop(6))
        assertEquals(850_991_225L, SdrDevice.corrected(851_012_500L, 25.0))
    }

    @Test
    fun notAnRtlTcpServerIsLost() {
        val server = ServerSocket(0)
        val t = thread { server.accept().use { it.getOutputStream().write("HTTP/1.1 200".toByteArray()); Thread.sleep(200) } }
        val sdr = RtlTcp("127.0.0.1", server.localPort) {}
        assertFalse(sdr.open())
        assertTrue(sdr.lost)
        t.join(); server.close()
    }

    @Test
    fun parsesTheAddress() {
        assertEquals("1.2.3.4" to 1234, RtlTcp.parse(" 1.2.3.4 "))
        assertEquals("h" to 99, RtlTcp.parse("h:99"))
        assertNull(RtlTcp.parse("h:port"))
        assertNull(RtlTcp.parse("h:70000"))
        assertNull(RtlTcp.parse(":1234"))
        assertNull(RtlTcp.parse(""))
    }
}
