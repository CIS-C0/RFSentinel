package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HackRfTest {
    @Test fun `pairs of signed samples average into one unsigned sample`() {
        val src = byteArrayOf(10, -20, 30, -40, 127, -128, 127, -128)
        val dst = ByteArray(4)
        assertEquals(4, HackRf.averagePairs(src, src.size, dst))
        assertArrayEquals(byteArrayOf((20 + 128).toByte(), (-30 + 128).toByte(), (127 + 128).toByte(), 0), dst)
    }

    @Test fun `a short read drops the half pair and never splits I from Q`() {
        val dst = ByteArray(4)
        assertEquals(2, HackRf.averagePairs(ByteArray(7), 7, dst))
        assertEquals(0, HackRf.averagePairs(ByteArray(3), 3, dst))
    }
}
