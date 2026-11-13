package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P25PduTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u32(v: Long) = b((v shr 24).toInt(), (v shr 16).toInt(), (v shr 8).toInt(), v.toInt())

    /** An LRRP immediate location response: a request ID, then a circle (lat, lon, radius 12.34 m) and a speed. */
    private fun lrrp(lat: Double, lon: Double): ByteArray {
        val latRaw = (Math.abs(lat) / 180.0 * 4294967295.0).toLong() or (if (lat < 0) 0x80000000L else 0L)
        val lonRaw = (lon / 360.0 * 4294967295.0).toLong() and 0xFFFFFFFFL
        val tokens = b(0x22, 0x04, 1, 2, 3, 4) + b(0x51) + u32(latRaw) + u32(lonRaw) + b(0x04, 0xD2) + b(0x6C, 0, 10)
        return b(0x07, tokens.size) + tokens
    }

    /** [payload] in UDP to port 4001, in IPv4 with its header checksum. */
    private fun ipUdp(payload: ByteArray): ByteArray {
        val udp = b(0x0F, 0xA1, 0x0F, 0xA1, 0, 8 + payload.size, 0, 0) + payload
        val ip = b(0x45, 0, 0, 20 + udp.size, 0, 1, 0, 0, 64, 17, 0, 0, 10, 0, 0, 1, 10, 0, 0, 2)
        var sum = 0
        for (i in 0 until 20 step 2) sum += (ip[i].toInt() and 0xff) shl 8 or (ip[i + 1].toInt() and 0xff)
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ck = sum.inv() and 0xFFFF
        ip[10] = (ck shr 8).toByte(); ip[11] = ck.toByte()
        return ip + udp
    }

    /** A data packet frame: SNDCP header + [ip], padded, CRC-32, in confirmed (16-byte) or unconfirmed (12-byte) blocks. */
    private fun frame(ip: ByteArray, confirmed: Boolean, llid: Int = 0x123456, tamper: Boolean = false): IntArray {
        val per = if (confirmed) 16 else 12
        val packet = b(0x40, 0x00) + ip
        val blocks = (packet.size + 4 + per - 1) / per
        val pad = blocks * per - 4 - packet.size
        val data = packet + ByteArray(pad)
        val crc = P25Pdu.crc32(data, data.size)
        val all = data + u32(crc.toLong() and 0xFFFFFFFFL)
        if (tamper) all[30] = (all[30].toInt() xor 0x10).toByte()
        val header = b(0x16 or (if (confirmed) 0x40 else 0), 4, 0, llid shr 16, llid shr 8, llid, 0x80 or blocks, pad, 0, 2)
        val coded = (0 until blocks).map { i ->
            val chunk = all.copyOfRange(i * per, i * per + per)
            if (!confirmed) P25TestSignal.half(chunk)
            else {
                val blk = b(i shl 1, 0) + chunk
                val c = P25Pdu.crc9(blk)
                blk[0] = (blk[0].toInt() or (c shr 8)).toByte(); blk[1] = c.toByte()
                P25TestSignal.threeQuarter(blk)
            }
        }
        return P25TestSignal.frame(0x293, P25Pdu.DUID_PDU, P25TestSignal.block(header), *coded.toTypedArray(), size = P25Pdu.PDU_DIBITS)
    }

    @Test
    fun unconfirmedPositionDecodes() {
        val d = P25Pdu.decode(frame(ipUdp(lrrp(0.5, -30.25)), confirmed = false))!!
        assertEquals(0x293, d.nac)
        assertEquals(P25Pdu.Stage.LRRP, d.stage)
        val p = d.positions.single()
        assertEquals(0x123456, p.radio)
        assertTrue(!p.outbound)
        assertEquals(0.5, p.lat, 1e-6); assertEquals(-30.25, p.lon, 1e-6)
        assertEquals(12.34, p.radiusM!!, 1e-9)
        assertNull(p.altitudeM)
    }

    @Test
    fun confirmedPositionDecodesSouthernHemisphere() {
        val d = P25Pdu.decode(frame(ipUdp(lrrp(-0.75, 30.5)), confirmed = true))!!
        assertEquals(P25Pdu.Stage.LRRP, d.stage)
        assertEquals(-0.75, d.positions.single().lat, 1e-6); assertEquals(30.5, d.positions.single().lon, 1e-6)
    }

    @Test
    fun damagedOrOtherPacketsGiveNoPosition() {
        // A byte changed after the CRC was made: the packet CRC catches it.
        val bad = P25Pdu.decode(frame(ipUdp(lrrp(0.5, -30.25)), confirmed = false, tamper = true))!!
        assertEquals(P25Pdu.Stage.HEADER, bad.stage); assertTrue(bad.positions.isEmpty())
        // Another UDP port: a good packet, no LRRP.
        val other = ipUdp(lrrp(0.5, -30.25)).also { it[22] = 0x10 }
        assertEquals(P25Pdu.Stage.PACKET, P25Pdu.decode(frame(other, confirmed = false))!!.stage)
        // A trunking frame is no data packet.
        assertNull(P25Pdu.decode(P25TestSignal.frame(0x293, P25Frame.DUID_TSDU, P25TestSignal.block(ByteArray(10)))))
        // Noise never makes one.
        val rnd = java.util.Random(5)
        repeat(300) { assertTrue(P25Pdu.decode(P25TestSignal.frame(0x293, P25Pdu.DUID_PDU, IntArray(1300) { rnd.nextInt(4) }, size = P25Pdu.PDU_DIBITS))?.positions.isNullOrEmpty()) }
    }

    @Test
    fun crcsMatchTheirDefinitions() {
        // CRC-32/BZIP2 ("123456789" -> 0xFC891918) is the same CRC started at all ones; OP25's starts at 0, the same
        // as all ones XORed into the first 32 message bits.
        val msg = "123456789".toByteArray()
        for (i in 0 until 4) msg[i] = (msg[i].toInt() xor 0xFF).toByte()
        assertEquals(0xFC891918L.toInt(), P25Pdu.crc32(msg, 9))
        // Rate 3/4: a clean block decodes, and so does one with a bit error in any symbol.
        val blk = ByteArray(18) { (it * 37 + 5).toByte() }
        val sym = P25Pdu.encode34(blk)
        assertTrue(P25Pdu.trellis34(sym)!!.contentEquals(blk))
        for (i in 0 until 49) {
            val s = sym.copyOf(); s[i] = s[i] xor (1 shl (i % 4))
            assertTrue("symbol $i", P25Pdu.trellis34(s)!!.contentEquals(blk))
        }
    }

    @Test
    fun demodulatorHandsWholeDataPackets() {
        val pdu = frame(ipUdp(lrrp(0.5, -30.25)), confirmed = false)
        val tsdu = P25TestSignal.frame(0x293, P25Frame.DUID_TSDU, P25TestSignal.block(ByteArray(10)))
        val frames = ArrayList<IntArray>(); val pdus = ArrayList<P25Pdu.Decoded>()
        val demod = P25Demod(200_000.0) { frames += it.copyOf() }
        demod.onPdu = { f, s -> P25Pdu.decode(f, s)?.let { pdus += it } }
        val iq = P25TestSignal.iq(listOf(tsdu, pdu, tsdu), 200_300.0)
        var at = 0
        while (at < iq.size) { val len = minOf(16_384, iq.size - at); demod.feed(iq.copyOfRange(at, at + len), len / 2); at += len }
        // Every frame still handed at its usual length, the data packet once more whole.
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == P25Demod.FRAME_DIBITS })
        assertEquals(1, pdus.size)
        assertEquals(0.5, pdus[0].positions.single().lat, 1e-6)
    }
}
