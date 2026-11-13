package com.rfsentinel.app.sdr

/**
 * P25 Phase 1 frames, second stage: the dibits after a frame sync ([P25Demod]) to the network ID and
 * the control channel's trunking blocks (TSBKs). Status symbols out, the NID's BCH(63,16) code corrected,
 * then each 98-dibit block de-interleaved, rate-1/2 trellis decoded (Viterbi, on the symbols' soft values when the
 * demodulator gives them) and CRC checked.
 * Constants from TIA-102.BAAA (the trellis table, interleave and CRC as OP25 implements them).
 * Pure: unit-tested.
 */
object P25Frame {

    /** Trunking signalling data unit: the control channel's frames. */
    const val DUID_TSDU = 0x7

    /** [nac] network access code, [duid] what kind of frame, [errors] bits the BCH code corrected. */
    data class Nid(val nac: Int, val duid: Int, val errors: Int)

    /** One error-free trunking block: [bytes] its 10 bytes (CRC off), [last] the frame's last block. */
    class Tsbk(val bytes: ByteArray) {
        val last get() = bytes[0].toInt() and 0x80 != 0
        val protected get() = bytes[0].toInt() and 0x40 != 0
        val opcode get() = bytes[0].toInt() and 0x3f
        val mfid get() = bytes[1].toInt() and 0xff
    }

    /** What a frame held: its NID (null when uncorrectable) and the blocks that passed their CRC. */
    class Decoded(val nid: Nid?, val tsbks: List<Tsbk>, val badBlocks: Int)

    /**
     * [soft]: the frame's symbol values (±1 for ±3, as [P25Demod.soft]), same length as [frame]; null for hard decisions.
     * [nac]: the channel's known NAC, for a NID too damaged otherwise ([nid]).
     */
    fun decode(frame: IntArray, soft: DoubleArray? = null, nac: Int? = null): Decoded {
        val d = stripStatus(frame)
        val sd = soft?.takeIf { it.size == frame.size }?.let { stripStatus(it) }
        val nid = nid(d, sd, nac) ?: return Decoded(null, emptyList(), 0)
        if (nid.duid != DUID_TSDU) return Decoded(nid, emptyList(), 0)
        val out = ArrayList<Tsbk>(); var bad = 0
        var at = NID_DIBITS
        while (out.size + bad < 3 && at + BLOCK_DIBITS <= d.size) {
            val b = trellis(deinterleave(d, at), sd?.let { deinterleaveSoft(it, at) })
            at += BLOCK_DIBITS
            if (b == null || !crcOk(b)) { bad++; continue }
            val t = Tsbk(b.copyOf(10))
            out += t
            if (t.last) break
        }
        return Decoded(nid, out, bad)
    }

    /** Takes out the status symbols: every 36th dibit counting from the sync's first ([frame] starts after the sync). */
    fun stripStatus(frame: IntArray): IntArray =
        frame.filterIndexed { i, _ -> (i + SYNC_DIBITS + 1) % 36 != 0 }.toIntArray()
    fun stripStatus(frame: DoubleArray): DoubleArray =
        frame.filterIndexed { i, _ -> (i + SYNC_DIBITS + 1) % 36 != 0 }.toDoubleArray()

    // ---- NID: 16 bits (NAC, DUID), 47 BCH parity bits, 1 parity bit ----

    /** BCH(63,16,23) generator, octal 6331141367235453 (GF(64) from x^6+x+1; it corrects 11 errors). */
    const val BCH_GENERATOR = 0xCD930BDD3B2BL // = 0o6331141367235453

    /** The 63-bit codeword for [info] (NAC << 4 | DUID): [info] then its remainder mod the generator. */
    fun nidCodeword(info: Int): Long {
        var r = info.toLong() shl 47
        for (bit in 62 downTo 47) if (r shr bit and 1L == 1L) r = r xor (BCH_GENERATOR shl (bit - 47))
        return (info.toLong() shl 47) or r
    }

    private val codewords: LongArray by lazy { LongArray(1 shl 16) { nidCodeword(it) } }

    /** The NID from the first 32 dibits; null when more than 11 bits are wrong. */
    fun nid(d: IntArray): Nid? {
        if (d.size < NID_DIBITS) return null
        var v = 0L
        for (i in 0 until NID_DIBITS) v = v shl 2 or d[i].toLong()
        val received = v ushr 1 // the 64th bit is a plain parity bit
        var best = -1; var bestErr = 64
        val cw = codewords
        for (info in cw.indices) {
            val e = java.lang.Long.bitCount(cw[info] xor received)
            if (e < bestErr) { bestErr = e; best = info; if (e == 0) break }
        }
        return if (bestErr <= 11) Nid(best shr 4, best and 0xf, bestErr) else null
    }

    /**
     * [nid], and when that fails with the channel's [nac] known: the closest of that NAC's NIDs to the [soft] symbol
     * values (status symbols out), when it is the only closest and no more than 15 bits off. SDRTrunk's
     * P25P1MessageFramer.softDecodeNID: 7 candidates make a random word pass about once in 7,700.
     */
    fun nid(d: IntArray, soft: DoubleArray?, nac: Int?): Nid? {
        nid(d)?.let { return it }
        if (nac == null || soft == null || soft.size < NID_DIBITS || d.size < NID_DIBITS) return null
        var best = -1; var bestDist = Double.MAX_VALUE; var second = Double.MAX_VALUE; var bestErr = 0
        for (duid in SOFT_DUIDS) {
            val cw = nidCodeword(nac shl 4 or duid) shl 1 // the 64th bit, a plain parity bit, either way
            var dist = 0.0; var err = 0
            for (k in 0 until NID_DIBITS) {
                val sent = (cw ushr (62 - 2 * k) and 3L).toInt()
                dist += if (k < NID_DIBITS - 1) (soft[k] - LEVEL[sent]).let { it * it }
                    else minOf((soft[k] - LEVEL[sent and 2]).let { it * it }, (soft[k] - LEVEL[sent or 1]).let { it * it })
                err += Integer.bitCount((sent xor d[k]) and (if (k < NID_DIBITS - 1) 3 else 2))
            }
            if (dist < bestDist) { second = bestDist; bestDist = dist; best = duid; bestErr = err } else if (dist < second) second = dist
        }
        return if (best >= 0 && bestDist < second && bestErr <= 15) Nid(nac, best, bestErr) else null
    }

    /** The data unit IDs P25 phase 1 uses: header, terminator, LDU1, trunking, LDU2, data packet, terminator with LC. */
    private val SOFT_DUIDS = intArrayOf(0x0, 0x3, 0x5, 0x7, 0xA, 0xC, 0xF)

    // ---- Trunking blocks ----

    /** 4-bit symbol [k] of a block comes in at dibit pair [SYMBOL_AT][k] (the 49 symbols go out in four groups). */
    internal val SYMBOL_AT = IntArray(49) { k -> 2 * (k / 4 + intArrayOf(0, 13, 25, 37)[k % 4]) }

    /** The block's 49 four-bit trellis symbols, in order, from the 98 dibits at [from]. */
    fun deinterleave(d: IntArray, from: Int): IntArray =
        IntArray(49) { k -> d[from + SYMBOL_AT[k]] shl 2 or d[from + SYMBOL_AT[k] + 1] }

    /** The block's soft values in trellis order: symbol k's two dibits at [2k] and [2k + 1]. */
    fun deinterleaveSoft(d: DoubleArray, from: Int): DoubleArray =
        DoubleArray(98) { i -> d[from + SYMBOL_AT[i / 2] + i % 2] }

    /** Each dibit's symbol value (±1 for ±3): 01 → +1, 00 → +1/3, 10 → -1/3, 11 → -1. */
    private val LEVEL = doubleArrayOf(1.0 / 3, 1.0, -1.0 / 3, -1.0)

    /** Rate-1/2 trellis: the symbol sent for input dibit [in] from state (the previous input) [s]: NEXT[s][in]. */
    val NEXT = arrayOf(intArrayOf(0x2, 0xC, 0x1, 0xF), intArrayOf(0xE, 0x0, 0xD, 0x3),
        intArrayOf(0x9, 0x7, 0xA, 0x4), intArrayOf(0x5, 0xB, 0x6, 0x8))

    /**
     * Viterbi decode of 49 symbols into 48 dibits (12 bytes; the 49th input is the flush). Null when too damaged.
     * With [soft] (from [deinterleaveSoft]) the path is chosen by distance to the sent levels (about 2 dB better than
     * counting bits); the damage limit still counts the bits the chosen path disagrees with.
     */
    fun trellis(sym: IntArray, soft: DoubleArray? = null): ByteArray? {
        val inf = Double.MAX_VALUE / 4
        var metric = doubleArrayOf(0.0, inf, inf, inf)
        val from = Array(sym.size) { IntArray(4) }
        for (t in sym.indices) {
            val next = DoubleArray(4) { inf }
            for (s in 0 until 4) if (metric[s] < inf) for (input in 0 until 4) {
                val c = NEXT[s][input]
                val cost = if (soft == null) Integer.bitCount(c xor sym[t]).toDouble()
                    else (soft[2 * t] - LEVEL[c shr 2]).let { it * it } + (soft[2 * t + 1] - LEVEL[c and 3]).let { it * it }
                val m = metric[s] + cost
                if (m < next[input]) { next[input] = m; from[t][input] = s }
            }
            metric = next
        }
        // Ends in state 0: the flush input.
        var s = 0
        if (metric[0] >= inf) return null
        val dibits = IntArray(sym.size)
        for (t in sym.indices.reversed()) { dibits[t] = s; s = from[t][s] }
        var errors = 0; var state = 0
        for (t in sym.indices) { errors += Integer.bitCount(NEXT[state][dibits[t]] xor sym[t]); state = dibits[t] }
        if (errors > MAX_TRELLIS_ERRORS) return null
        val out = ByteArray(12)
        for (i in 0 until 48) out[i / 4] = (out[i / 4].toInt() or (dibits[i] shl (6 - 2 * (i % 4)))).toByte()
        return out
    }

    /**
     * The block's 96 bits divided by x^16+x^12+x^5+1 leave all ones when it is intact (the sender appends the
     * inverted CRC-CCITT of the first 10 bytes). One wrong bit is put right in [b], as SDRTrunk's CRCP25.correctCCITT80
     * does after the trellis; two or more fail.
     */
    fun crcOk(b: ByteArray): Boolean {
        val s = remainder(b) xor 0xffff
        if (s == 0) return true
        val bit = SINGLE_BIT.indexOf(s).takeIf { it >= 0 && b.size == 12 } ?: return false
        b[bit / 8] = (b[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
        return true
    }

    /** What one wrong bit (MSB first) does to a 12-byte block's remainder: the distance-4 code makes each unique. */
    private val SINGLE_BIT = IntArray(96) { i -> remainder(ByteArray(12).also { it[i / 8] = (0x80 ushr (i % 8)).toByte() }) }

    /** Remainder of [b] (bits MSB first) divided by the CCITT polynomial. */
    fun remainder(b: ByteArray): Int {
        var r = 0
        for (byte in b) for (bit in 7 downTo 0) {
            r = r shl 1 or (byte.toInt() shr bit and 1)
            if (r and 0x10000 != 0) r = r and 0xffff xor 0x1021
        }
        return r
    }

    private const val SYNC_DIBITS = 24
    private const val NID_DIBITS = 32
    private const val BLOCK_DIBITS = 98
    /** Bit errors the trellis path may carry before the block is dropped (the CRC catches the rest). */
    private const val MAX_TRELLIS_ERRORS = 20
}
