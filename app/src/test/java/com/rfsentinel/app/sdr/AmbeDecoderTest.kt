package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** AMBE+2 frame handling after mbelib-neo: error counts, lost frames, mutes, tones. */
class AmbeDecoderTest {

    private fun rms(s: ShortArray, from: Int = 0, to: Int = s.size) =
        sqrt((from until to).sumOf { s[it].toDouble() * s[it] } / (to - from))

    /** A voice frame, pitch code 40, random spectrum. */
    private fun voice(seed: Long): IntArray {
        val r = java.util.Random(seed)
        val d = IntArray(49) { r.nextInt(2) }
        val b0 = 40
        intArrayOf(0, 1, 2, 3, 37, 38, 39).forEachIndexed { i, x -> d[x] = b0 shr (6 - i) and 1 }
        return d
    }

    /** A tone frame: tone [id], amplitude [ad], its redundant ID copy filled in. */
    private fun tone(id: Int, ad: Int): IntArray {
        val d = IntArray(49)
        for (i in 0..5) d[i] = 1
        for (i in 0..5) d[6 + i] = ad shr (6 - i) and 1
        d[44] = ad and 1
        for (i in 0..7) d[12 + i] = id shr (7 - i) and 1
        for (i in 0..3) d[20 + i] = d[12 + i]
        return d
    }

    @Test
    fun c0BeyondRepairIsLostThenMuted() {
        val dec = AmbeDecoder()
        val v = voice(3)
        repeat(4) { dec.decode(ambeFrame(v)) }
        val heard = rms(dec.decode(ambeFrame(v)))
        assertEquals(0, dec.c0Errors)
        fun broken() = ambeFrame(v).also { for (j in 1..4) it[0][j] = it[0][j] xor 1 }
        repeat(3) {
            val again = dec.decode(broken())
            assertEquals(4, dec.c0Errors)
            assertTrue("a lost frame says the last one again", rms(again) > heard / 4)
        }
        val muted = dec.decode(broken())
        assertTrue("the 4th mutes: noise of one step after the 10 ms fade", (80 until 160).all { muted[it] in -1..1 })
        assertTrue("the next good frame is heard", rms(dec.decode(ambeFrame(v))) > 10)
    }

    @Test
    fun toneFramesPlayTheTone() {
        val dec = AmbeDecoder()
        val first = dec.decode(ambeFrame(tone(128, 100)))
        assertTrue("it fades in", rms(first, 0, 40) < rms(first, 80, 160) / 2)
        val steady = rms(dec.decode(ambeFrame(tone(128, 100))))
        // AD 100: 27 steps of 0.711 dB under DVSI's -2.52 dB, each of two components.
        val want = 32768 * Math.pow(10.0, (-2.523 - 27 * (3.17 + 87.13) / 127) / 20) * sqrt(2.0)
        assertEquals(want, steady, want * 0.05)
        // ID 255 plays no tone: a lost frame, so the tone goes on.
        assertEquals(steady, rms(dec.decode(ambeFrame(tone(255, 100)))), want * 0.05)
        // The next frame rises from silence as a call's first frame does, the tone fading out on top over 10 ms.
        val after = dec.decode(ambeFrame(voice(4)))
        val alone = AmbeDecoder().decode(ambeFrame(voice(4)))
        val tail = ShortArray(160) { (after[it] - alone[it]).toShort() }
        assertTrue(rms(tail, 0, 20) > want / 2)
        assertTrue((80 until 160).all { tail[it] in -1..1 })
    }

    @Test
    fun toneTable() {
        assertEquals(1336.0 to 941.0, AmbeDecoder.toneFreqs(128))
        assertEquals(490.0 to 350.0, AmbeDecoder.toneFreqs(163))
        assertEquals(31.25 * 32 to 31.25 * 32, AmbeDecoder.toneFreqs(32))
        assertNull(AmbeDecoder.toneFreqs(123))
        assertNull(AmbeDecoder.toneFreqs(200))
        assertNull(AmbeDecoder.toneFreqs(255))
    }

    companion object {
        /** A 72-bit AMBE frame (mbelib rows) holding the 49 data bits [d], C0's parity bit set: the inverse of [AmbeDecoder.data]. */
        fun ambeFrame(d: IntArray): Array<IntArray> {
            val fr = arrayOf(IntArray(24), IntArray(23), IntArray(11), IntArray(14))
            val c0 = Nxdn.value(d, 0, 12); val c1 = Nxdn.value(d, 12, 12)
            val w0 = c0 shl 11 or P25Voice.golayParity(c0)
            val w1 = c1 shl 11 or P25Voice.golayParity(c1)
            for (j in 0 until 23) fr[0][j + 1] = w0 shr j and 1
            fr[0][0] = Integer.bitCount(w0) and 1
            var pr = 16 * c0
            for (j in 22 downTo 0) { pr = (173 * pr + 13849) and 0xffff; fr[1][j] = (w1 shr j and 1) xor (pr shr 15) }
            for (i in 0 until 11) fr[2][10 - i] = d[24 + i]
            for (i in 0 until 14) fr[3][13 - i] = d[35 + i]
            return fr
        }
    }
}
