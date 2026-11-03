package com.rfsentinel.app.sdr

/**
 * P25 Phase 1 voice, second step: a voice frame (LDU1 / LDU2, after its sync: [P25Demod] with [LDU_DIBITS]) to its
 * nine IMBE voice codewords, error-corrected. Each 144-bit codeword is de-interleaved into eight words; the first
 * (Golay 23,12) is corrected and seeds a PN sequence that unscrambles the next six; those are corrected (three
 * Golay 23,12, three Hamming 15,11) and the last 7 bits are taken as they are: 88 bits for the IMBE decoder.
 * Link control / encryption sync sit between the codewords; from LDU2 the algorithm ID says whether the call is clear.
 * Layout and tables as in DSD and mbelib (ISC licence), after TIA-102.BAAA and BABA. Pure: unit-tested.
 */
object P25Voice {

    const val DUID_LDU1 = 0x5
    const val DUID_LDU2 = 0xA
    /** An LDU after its sync: NID, nine codewords, LC / ES, low speed data, status symbols. */
    const val LDU_DIBITS = 840
    /** The algorithm ID of a clear (not encrypted) call. */
    const val ALGID_CLEAR = 0x80

    /** One codeword: [bits] its 88 info bits (0 / 1), [errors] bits corrected, [first] of them in the first word. */
    class Imbe(val bits: ByteArray, val errors: Int, val first: Int)

    /** A voice frame: its codewords, and [algid] from an LDU2 (null from an LDU1, or when damaged). */
    class Frame(val nid: P25Frame.Nid, val voice: List<Imbe>, val algid: Int?) {
        val encrypted get() = algid != null && algid != ALGID_CLEAR
    }

    /** Where the nine codewords start, in dibits after the NID's first (status symbols out). */
    internal val VOICE_AT = intArrayOf(32, 104, 196, 288, 380, 472, 564, 656, 744)
    /** The LDU2's 4th group of hex words, after codeword 5: the algorithm ID is in its first two. */
    private const val ALGID_AT = 452

    /** A voice frame, or null when it isn't one (or its NID is beyond repair). */
    fun decode(frame: IntArray): Frame? {
        val d = P25Frame.stripStatus(frame)
        val nid = P25Frame.nid(d) ?: return null
        if (nid.duid != DUID_LDU1 && nid.duid != DUID_LDU2 || d.size < VOICE_AT.last() + 72) return null
        val algid = if (nid.duid != DUID_LDU2) null else {
            val a = hex(d, ALGID_AT); val b = hex(d, ALGID_AT + 5)
            if (a == null || b == null) null else a shl 2 or (b shr 4)
        }
        return Frame(nid, VOICE_AT.map { imbe(d, it) }, algid)
    }

    /** A hex word (6 bits + 4 Hamming 10,6 parity, 5 dibits) at [at], corrected; null with more than one error. */
    internal fun hex(d: IntArray, at: Int): Int? {
        var v = 0
        for (i in 0 until 5) v = v shl 2 or d[at + i]
        val syn = hexParity(v shr 4) xor (v and 0xf)
        if (syn == 0) return v shr 4
        val bit = HEX_SYNDROME[syn]
        return if (bit < 0) null else (v xor (1 shl bit)) shr 4
    }

    /** Hamming(10,6,3) parity of 6 data bits (TIA-102.BAAA; rows of G after its identity part). */
    internal fun hexParity(data: Int): Int {
        var p = 0
        for (i in 0 until 6) if (data shr (5 - i) and 1 == 1) p = p xor HEX_G[i]
        return p
    }
    private val HEX_G = intArrayOf(0b1110, 0b1101, 0b1011, 0b0111, 0b0011, 0b1100)
    private val HEX_SYNDROME = IntArray(16) { -1 }.also { t ->
        for (bit in 0 until 10) { val e = 1 shl bit; t[hexParity(e shr 4) xor (e and 0xf)] = bit }
    }

    // ---- IMBE codewords ----

    /** The 88 info bits of the codeword at dibit [at]. */
    internal fun imbe(d: IntArray, at: Int): Imbe {
        val fr = Array(8) { IntArray(23) }
        for (k in 0 until 72) {
            val v = d[at + k]
            fr[IW[k]][IX[k]] = v shr 1 and 1
            fr[IY[k]][IZ[k]] = v and 1
        }
        val w = IntArray(8) { c -> var x = 0; for (j in SIZE[c] - 1 downTo 0) x = x shl 1 or fr[c][j]; x }
        val (u0, e0) = golay(w[0])
        val pn = pn(u0)
        var k = 0
        for (c in 1..6) for (j in SIZE[c] - 1 downTo 0) { if (pn[k++] == 1) w[c] = w[c] xor (1 shl j) }
        val u = IntArray(8); u[0] = u0
        var errors = e0
        for (c in 1..3) golay(w[c]).let { (v, e) -> u[c] = v; errors += e }
        for (c in 4..6) hamming(w[c]).let { (v, e) -> u[c] = v; errors += e }
        u[7] = w[7]
        val bits = ByteArray(88)
        var b = 0
        for (c in 0 until 8) for (j in INFO[c] - 1 downTo 0) bits[b++] = (u[c] shr j and 1).toByte()
        return Imbe(bits, errors, e0)
    }

    /** The scrambling sequence for words 1-6, from word 0's 12 info bits (114 bits, 0 / 1). */
    internal fun pn(u0: Int): IntArray {
        var p = 16 * u0
        return IntArray(114) { p = (173 * p + 13849) and 0xffff; p shr 15 }
    }

    /** Word sizes in bits, and their info bits. */
    internal val SIZE = intArrayOf(23, 23, 23, 23, 15, 15, 15, 7)
    internal val INFO = intArrayOf(12, 12, 12, 12, 11, 11, 11, 7)

    /** Golay(23,12) parity of 12 data bits: data bit i (MSB first) adds GOLAY_G[i]. */
    internal fun golayParity(data: Int): Int {
        var p = 0
        for (i in 0 until 12) if (data shr (11 - i) and 1 == 1) p = p xor GOLAY_G[i]
        return p
    }
    private val GOLAY_G = intArrayOf(0x63a, 0x31d, 0x7b4, 0x3da, 0x1ed, 0x6cc, 0x366, 0x1b3, 0x6e3, 0x54b, 0x49f, 0x475)
    private fun golaySyndrome(w: Int) = golayParity(w shr 11) xor (w and 0x7ff)
    /** Syndrome → the error pattern (up to 3 bits): the code is perfect, every syndrome has one. */
    private val GOLAY_ERR: IntArray by lazy {
        val t = IntArray(2048)
        for (a in 0 until 23) for (b in a until 23) for (c in b until 23) {
            val e = (1 shl a) or (1 shl b) or (1 shl c) // a == b == c is one bit, a == b < c two
            t[golaySyndrome(e)] = e
        }
        t
    }

    /** A Golay(23,12) word corrected: its 12 info bits and the bits fixed. */
    internal fun golay(w: Int): Pair<Int, Int> {
        val e = GOLAY_ERR[golaySyndrome(w)]
        return ((w xor e) shr 11) to Integer.bitCount(e)
    }

    /** Hamming(15,11) parity checks (mbelib): codeword bit 14 first, the 4 parity bits last. */
    private val HAMMING_H = intArrayOf(0x7f08, 0x78e4, 0x66d2, 0x55b1)
    internal fun hammingSyndrome(w: Int): Int { var s = 0; for (h in HAMMING_H) s = s shl 1 or (Integer.bitCount(w and h) and 1); return s }
    private val HAMMING_ERR = IntArray(16).also { t -> for (bit in 0 until 15) t[hammingSyndrome(1 shl bit)] = 1 shl bit }

    /** A Hamming(15,11) word corrected: its 11 info bits and the bits fixed. */
    internal fun hamming(w: Int): Pair<Int, Int> {
        val e = HAMMING_ERR[hammingSyndrome(w)]
        return ((w xor e) shr 4) to Integer.bitCount(e)
    }

    // The interleave: dibit k's first bit goes to word IW[k] bit IX[k], its second to IY[k] bit IZ[k] (DSD).
    internal val IW = intArrayOf(0, 2, 4, 1, 3, 5, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6,
        0, 2, 4, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 7, 0, 2, 5, 1, 3, 7, 0, 2, 5, 1, 4, 7, 0, 3, 5, 2, 4, 7)
    internal val IX = intArrayOf(22, 20, 10, 20, 18, 0, 20, 18, 8, 18, 16, 13, 18, 16, 6, 16, 14, 11, 16, 14, 4, 14, 12, 9,
        14, 12, 2, 12, 10, 7, 12, 10, 0, 10, 8, 5, 10, 8, 13, 8, 6, 3, 8, 6, 11, 6, 4, 1, 6, 4, 9, 4, 2, 6, 4, 2, 7, 2, 0, 4,
        2, 0, 5, 0, 13, 2, 0, 21, 3, 21, 11, 0)
    internal val IY = intArrayOf(1, 3, 5, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4,
        1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 7, 0, 2, 5, 1, 4, 7, 0, 3, 5, 2, 4, 7, 1, 3, 5)
    internal val IZ = intArrayOf(21, 19, 1, 21, 19, 9, 19, 17, 14, 19, 17, 7, 17, 15, 12, 17, 15, 5, 15, 13, 10, 15, 13, 3,
        13, 11, 8, 13, 11, 1, 11, 9, 6, 11, 9, 14, 9, 7, 4, 9, 7, 12, 7, 5, 2, 7, 5, 10, 5, 3, 0, 5, 3, 8, 3, 1, 5, 3, 1, 6,
        1, 14, 3, 1, 22, 4, 22, 12, 1, 22, 20, 2)

    // ---- recordings ----

    /** What a [P25Record] recording held: its voice frames, and how many frame syncs were found. */
    class Decoded(val frames: List<Frame>, val syncs: Int)

    /** Runs a recording (2-channel 16-bit WAV at [P25Record.RATE]) through the demodulator and this decoder. */
    fun decodeRecording(wav: ByteArray): Decoded {
        val b = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // The data chunk: after the 12-byte RIFF header, chunk by chunk.
        var at = 12
        while (at + 8 <= wav.size && String(wav, at, 4) != "data") at += 8 + b.getInt(at + 4)
        val end = if (at + 8 <= wav.size) minOf(wav.size, at + 8 + b.getInt(at + 4)) else at
        val frames = ArrayList<Frame>()
        val demod = P25Demod(0.0, LDU_DIBITS) { f -> decode(f)?.let { frames += it } }
        var k = at + 8
        while (k + 4 <= end) { demod.feedChannel(b.getShort(k).toDouble(), b.getShort(k + 2).toDouble()); k += 4 }
        return Decoded(frames, demod.syncs)
    }

    /** A call's speech as a WAV (8 kHz mono, 20 ms per codeword); empty when any of it is encrypted. */
    fun speechWav(frames: List<Frame>): ByteArray {
        if (frames.isEmpty() || frames.any { it.encrypted }) return ByteArray(0)
        val imbe = ImbeDecoder()
        val b = java.nio.ByteBuffer.allocate(frames.sumOf { it.voice.size } * ImbeDecoder.N * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in frames) for (v in f.voice) for (s in imbe.decode(v.bits, v.errors)) b.putShort(s)
        return P25Record.header(b.capacity(), 1, ImbeDecoder.RATE) + b.array()
    }
}
