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
    fun noiseIsNoNid() {
        val r = java.util.Random(3)
        assertNull(P25Frame.nid(IntArray(32) { r.nextInt(4) }))
    }
}
