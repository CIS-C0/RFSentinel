package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P25ReedSolomonTest {

    @Test
    fun correctsUpToHalfTheParity() {
        val rnd = java.util.Random(11)
        for ((k, parity) in listOf(12 to 12, 16 to 8)) repeat(300) {
            val data = IntArray(k) { rnd.nextInt(64) }
            val sent = data + P25ReedSolomon.encode(data, parity)
            val got = sent.copyOf()
            assertEquals(0, P25ReedSolomon.decode(got, parity))
            val errors = rnd.nextInt(parity / 2 + 1)
            (0 until 24).shuffled(rnd).take(errors).forEach { got[it] = got[it] xor (1 + rnd.nextInt(63)) }
            assertEquals(errors, P25ReedSolomon.decode(got, parity))
            assertArrayEquals(sent, got)
        }
    }

    @Test
    fun tooManyErrorsAreMostlyCaught() {
        // One more than it can correct: refused, or (rarely) a wrong codeword, which a real code can't avoid.
        val rnd = java.util.Random(12)
        var refused = 0
        repeat(1000) {
            val data = IntArray(16) { rnd.nextInt(64) }
            val got = data + P25ReedSolomon.encode(data, 8)
            (0 until 24).shuffled(rnd).take(5).forEach { got[it] = got[it] xor (1 + rnd.nextInt(63)) }
            if (P25ReedSolomon.decode(got, 8) < 0) refused++
        }
        assertTrue("$refused", refused > 990)
    }
}
