package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAheadTest {
    /** Read k fills the buffer with k, and every third read fails. */
    private class Counting : SdrDevice {
        override val label = "count"; override val sampleRate = 0; override val ctlErrors = 0
        override val lost = false; override val tunerWriteFails = 0; override val settleReads = 0
        @Volatile var reads = 0
        override fun open() = true
        override fun close() {}
        override fun setSampleRate(rate: Int) {}
        override fun tune(hz: Long) = true
        override fun setGain(tenthsDb: Int) {}
        override fun resetBuffer() {}
        override fun read(buf: ByteArray, len: Int): Int {
            val k = ++reads
            if (k % 3 == 0) return -1
            buf.fill(k.toByte(), 0, len)
            return len - k % 2 // short reads too
        }
    }

    @Test fun `reads come back in order with their counts, failures included`() {
        val sdr = Counting()
        val into = ByteArray(16)
        ReadAhead(sdr, 16, slots = 3).use { ahead ->
            for (k in 1..30) {
                val n = ahead.read(into)
                if (k % 3 == 0) assertEquals(-1, n)
                else { assertEquals(16 - k % 2, n); assertEquals(k.toByte(), into[n - 1]) }
            }
        }
        val after = sdr.reads
        Thread.sleep(50)
        assertEquals("the reader stopped on close", after, sdr.reads)
        assertTrue("it stays only a few reads ahead", after <= 30 + 3 + 1)
    }
}
