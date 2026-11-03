package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class P25VoiceTest {

    /** A codeword as a transmitter builds it from 88 info bits: coded, scrambled, interleaved into 72 dibits. */
    private fun codeword(bits: ByteArray): IntArray {
        var b = 0
        val u = IntArray(8) { c -> var x = 0; repeat(P25Voice.INFO[c]) { x = x shl 1 or bits[b++].toInt() }; x }
        val w = IntArray(8)
        for (c in 0..3) w[c] = u[c] shl 11 or P25Voice.golayParity(u[c])
        for (c in 4..6) w[c] = (0 until 16).map { u[c] shl 4 or it }.first { P25Voice.hammingSyndrome(it) == 0 }
        w[7] = u[7]
        val pn = P25Voice.pn(u[0]); var k = 0
        for (c in 1..6) for (j in P25Voice.SIZE[c] - 1 downTo 0) { if (pn[k++] == 1) w[c] = w[c] xor (1 shl j) }
        return IntArray(72) { i ->
            (w[P25Voice.IW[i]] shr P25Voice.IX[i] and 1) shl 1 or (w[P25Voice.IY[i]] shr P25Voice.IZ[i] and 1)
        }
    }

    private fun hexWord(v: Int): IntArray = P25TestSignal.dibitsOf((v shl 4 or P25Voice.hexParity(v)).toLong(), 5)

    /** An LDU after its sync, status symbols in: NID, the nine codewords, the algorithm ID for an LDU2. */
    private fun ldu(duid: Int, voice: List<ByteArray>, algid: Int = P25Voice.ALGID_CLEAR, hit: (IntArray) -> Unit = {}): IntArray {
        val d = IntArray(816)
        P25TestSignal.dibitsOf(P25Frame.nidCodeword(0x293 shl 4 or duid) shl 1, 32).copyInto(d)
        for (i in 0 until 9) codeword(voice[i]).copyInto(d, P25Voice.VOICE_AT[i])
        hexWord(algid shr 2).copyInto(d, 452); hexWord(algid and 3 shl 4).copyInto(d, 457)
        hit(d)
        return withStatus(d)
    }

    /** A call's terminator after its sync, padded to an LDU's length. */
    private fun tdu(): IntArray {
        val d = IntArray(816)
        P25TestSignal.dibitsOf(P25Frame.nidCodeword(0x293 shl 4 or 0x3) shl 1, 32).copyInto(d)
        return withStatus(d)
    }

    private fun withStatus(d: IntArray): IntArray {
        val data = ArrayDeque(d.toList())
        return IntArray(P25Voice.LDU_DIBITS) { i -> if ((i + 25) % 36 == 0) 2 else data.removeFirst() }
    }

    /** [dibits] on the air as the dongle hears them: 250 kHz up, plus 700 Hz of crystal error, 8-bit I/Q. */
    private fun iq(dibits: IntArray): ByteArray {
        val rate = P25Demod.SAMPLE_RATE
        val perSymbol = rate / P25Demod.SYMBOL_RATE
        val iq = ByteArray(dibits.size * perSymbol * 2)
        val rnd = java.util.Random(9)
        var phase = 0.0; var k = 0
        for (d in dibits) repeat(perSymbol) {
            phase += 2 * PI * (250_700.0 + P25TestSignal.hz(d)) / rate
            iq[k++] = (127.5 + 50 * cos(phase) + rnd.nextGaussian() * 4).roundToInt().coerceIn(0, 255).toByte()
            iq[k++] = (127.5 + 50 * sin(phase) + rnd.nextGaussian() * 4).roundToInt().coerceIn(0, 255).toByte()
        }
        return iq
    }

    private fun chunks(iq: ByteArray, feed: (ByteArray, Int) -> Unit) {
        var at = 0
        while (at < iq.size) { val len = minOf(16_384, iq.size - at); feed(iq.copyOfRange(at, at + len), len / 2); at += len }
    }

    private fun voice(seed: Long): List<ByteArray> {
        val rnd = java.util.Random(seed)
        return List(9) { ByteArray(88) { rnd.nextInt(2).toByte() } }
    }

    @Test
    fun theInterleaveCoversEveryBitOnce() {
        val seen = HashSet<Int>()
        for (k in 0 until 72) { seen += P25Voice.IW[k] * 32 + P25Voice.IX[k]; seen += P25Voice.IY[k] * 32 + P25Voice.IZ[k] }
        assertEquals(144, seen.size)
        for (c in 0 until 8) for (j in 0 until P25Voice.SIZE[c]) assertTrue("word $c bit $j", c * 32 + j in seen)
    }

    @Test
    fun codewordsComeBackAndErrorsAreCorrected() {
        val v = voice(1)
        val f = P25Voice.decode(ldu(P25Voice.DUID_LDU2, v))!!
        assertEquals(0x293, f.nid.nac)
        assertEquals(9, f.voice.size)
        for (i in 0 until 9) { assertArrayEquals(v[i], f.voice[i].bits); assertEquals(0, f.voice[i].errors) }
        assertEquals(P25Voice.ALGID_CLEAR, f.algid)
        assertFalse(f.encrypted)

        // A few dibits hit in the third codeword: Golay and Hamming put it right.
        val hit = ldu(P25Voice.DUID_LDU1, v) { d -> for (k in listOf(2, 30, 61)) d[P25Voice.VOICE_AT[2] + k] = d[P25Voice.VOICE_AT[2] + k] xor 1 }
        val g = P25Voice.decode(hit)!!
        assertNull(g.algid)
        assertArrayEquals(v[2], g.voice[2].bits)
        assertTrue(g.voice[2].errors > 0)
    }

    @Test
    fun anEncryptedCallIsTold() {
        assertTrue(P25Voice.decode(ldu(P25Voice.DUID_LDU2, voice(2), algid = 0x84))!!.encrypted)
    }

    @Test
    fun aRecordedCallIsDecoded() {
        // Two LDUs on the air, recorded as P25Record does.
        val v1 = voice(3); val v2 = voice(4)
        val dibits = IntArray(60) { (it * 5 + 1) % 4 } + P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU1, v1) +
            P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU2, v2) + IntArray(60) { it % 4 }
        val rec = P25Record.Baseband(250_000L)
        chunks(iq(dibits), rec::feed)

        val out = P25Voice.decodeRecording(rec.wav())
        assertEquals(2, out.frames.size)
        for (i in 0 until 9) {
            assertArrayEquals(v1[i], out.frames[0].voice[i].bits)
            assertArrayEquals(v2[i], out.frames[1].voice[i].bits)
        }
        assertEquals(P25Voice.ALGID_CLEAR, out.frames[1].algid)
        // 18 codewords of 20 ms each, as 8 kHz mono.
        assertEquals(44 + 18 * ImbeDecoder.N * 2, P25Voice.speechWav(out.frames).size)
        assertEquals(0, P25Voice.speechWav(listOf(P25Voice.decode(ldu(P25Voice.DUID_LDU2, v1, algid = 0x84))!!)).size)
    }

    @Test
    fun aCallIsFollowedLiveUntilItsTerminator() {
        val dibits = IntArray(60) { (it * 5 + 1) % 4 } + P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU1, voice(5)) +
            P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU2, voice(6)) + P25TestSignal.SYNC + tdu() +
            // After the terminator: more voice, which isn't played.
            P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU1, voice(7)) + IntArray(60) { it % 4 }
        var played = 0
        val follow = P25Follow(250_000.0) { assertEquals(ImbeDecoder.N, it.size); played++ }
        chunks(iq(dibits), follow::feed)
        assertEquals(2, follow.frames)
        assertEquals(18, played)
        assertTrue(follow.ended)

        // An encrypted call ends it before anything more is played.
        var later = 0
        val enc = P25Follow(250_000.0) { later++ }
        chunks(iq(IntArray(60) { it % 4 } + P25TestSignal.SYNC + ldu(P25Voice.DUID_LDU2, voice(8), algid = 0x84) + IntArray(60) { it % 4 }), enc::feed)
        assertTrue(enc.ended)
        assertEquals(0, later)
    }
}
