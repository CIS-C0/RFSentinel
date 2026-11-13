package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rfsentinel.app.sdr.P25TestSignal.block
import com.rfsentinel.app.sdr.P25TestSignal.frame

class P25FrameTest {

    private val grant = byteArrayOf(0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)
    private val last = byteArrayOf(0x80.toByte(), 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09)

    @Test
    fun generatorIsTheStandardOne() {
        assertEquals(java.lang.Long.parseLong("6331141367235453", 8), P25Frame.BCH_GENERATOR)
    }

    @Test
    fun decodesATsduWithErrors() {
        val f = frame(0x293, P25Frame.DUID_TSDU, block(grant), block(last))
        // 6 wrong bits in the NID (BCH fixes up to 11) and 3 in the first block (trellis).
        for (i in listOf(0, 3, 9, 14, 20, 30)) f[i] = f[i] xor 1
        for (i in listOf(40, 60, 90)) f[i] = f[i] xor 2
        val d = P25Frame.decode(f)
        assertEquals(P25Frame.Nid(0x293, 7, 6), d.nid)
        assertEquals(2, d.tsbks.size)
        assertArrayEquals(grant, d.tsbks[0].bytes)
        assertFalse(d.tsbks[0].last)
        assertTrue(d.tsbks[1].last)
        assertEquals(0, d.tsbks[1].opcode)
    }

    @Test
    fun damagedBlockFailsItsCrc() {
        val blk = block(last)
        for (i in 0 until 98 step 3) blk[i] = blk[i] xor 3
        val d = P25Frame.decode(frame(0x293, P25Frame.DUID_TSDU, blk))
        assertEquals(0, d.tsbks.size)
        assertTrue(d.badBlocks > 0)
    }

    @Test
    fun aDamagedNidIsReadWithTheKnownNac() {
        val level = doubleArrayOf(1.0 / 3, 1.0, -1.0 / 3, -1.0)
        val sent = P25TestSignal.dibitsOf(P25Frame.nidCodeword(0x293 shl 4 or P25Frame.DUID_TSDU) shl 1, 32)
        // 14 bits wrong: past what the hard decode takes.
        val d = sent.copyOf()
        for (k in 0 until 28 step 2) d[k] = d[k] xor 1
        assertNull(P25Frame.nid(d))
        // Symbol values a little off where right, near the wrong level where wrong.
        val soft = DoubleArray(32) { k -> level[d[k]] + (if (d[k] == sent[k]) 0.05 else -0.05) }
        val n = P25Frame.nid(d, soft, 0x293)!!
        assertEquals(0x293, n.nac); assertEquals(P25Frame.DUID_TSDU, n.duid); assertEquals(14, n.errors)
        assertNull(P25Frame.nid(d, soft, null))
        assertNull(P25Frame.nid(d, soft, 0x111)) // another NAC: far off
        // Noise the hard decode refuses: rarely a NID with the NAC known either.
        val r = java.util.Random(4)
        var passed = 0
        repeat(2000) {
            val x = IntArray(32) { r.nextInt(4) }
            if (P25Frame.nid(x) == null && P25Frame.nid(x, DoubleArray(32) { level[x[it]] }, 0x293) != null) passed++
        }
        assertTrue("$passed", passed <= 3)
    }

    @Test
    fun oneWrongBitAfterTheTrellisIsPutRight() {
        val crc = P25Frame.remainder(last + byteArrayOf(0, 0)) xor 0xffff
        val good = last + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        for (bit in 0 until 96) {
            val b = good.copyOf(); b[bit / 8] = (b[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
            assertTrue(P25Frame.crcOk(b)); assertTrue(b.contentEquals(good))
        }
        val two = good.copyOf(); two[0] = (two[0].toInt() xor 0x81).toByte()
        assertTrue(!P25Frame.crcOk(two))
    }

    @Test
    fun noiseIsNoNid() {
        val r = java.util.Random(3)
        assertNull(P25Frame.nid(IntArray(32) { r.nextInt(4) }))
    }
}
