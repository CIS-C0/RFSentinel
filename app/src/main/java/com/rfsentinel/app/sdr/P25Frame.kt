package com.rfsentinel.app.sdr

/**
 * P25 Phase 1 frames, second stage: the dibits after a frame sync ([P25Demod]) to the network ID and
 * the control channel's trunking blocks (TSBKs). Status symbols out, the NID's BCH(63,16) code corrected,
 * then each 98-dibit block de-interleaved, rate-1/2 trellis decoded (Viterbi) and CRC checked.
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

    fun decode(frame: IntArray): Decoded {
        val d = stripStatus(frame)
        val nid = nid(d) ?: return Decoded(null, emptyList(), 0)
        if (nid.duid != DUID_TSDU) return Decoded(nid, emptyList(), 0)
        val out = ArrayList<Tsbk>(); var bad = 0
        var at = NID_DIBITS
        while (out.size + bad < 3 && at + BLOCK_DIBITS <= d.size) {
            val b = trellis(deinterleave(d, at))
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

    // ---- Trunking blocks ----

    /** 4-bit symbol [k] of a block comes in at dibit pair [SYMBOL_AT][k] (the 49 symbols go out in four groups). */
    internal val SYMBOL_AT = IntArray(49) { k -> 2 * (k / 4 + intArrayOf(0, 13, 25, 37)[k % 4]) }

    /** The block's 49 four-bit trellis symbols, in order, from the 98 dibits at [from]. */
    fun deinterleave(d: IntArray, from: Int): IntArray =
        IntArray(49) { k -> d[from + SYMBOL_AT[k]] shl 2 or d[from + SYMBOL_AT[k] + 1] }

    /** Rate-1/2 trellis: the symbol sent for input dibit [in] from state (the previous input) [s]: NEXT[s][in]. */
    val NEXT = arrayOf(intArrayOf(0x2, 0xC, 0x1, 0xF), intArrayOf(0xE, 0x0, 0xD, 0x3),
        intArrayOf(0x9, 0x7, 0xA, 0x4), intArrayOf(0x5, 0xB, 0x6, 0x8))

    /** Viterbi decode of 49 symbols into 48 dibits (12 bytes; the 49th input is the flush). Null when too damaged. */
    fun trellis(sym: IntArray): ByteArray? {
        val inf = Int.MAX_VALUE / 2
        var metric = intArrayOf(0, inf, inf, inf)
        val from = Array(sym.size) { IntArray(4) }
        for (t in sym.indices) {
            val next = IntArray(4) { inf }
            for (s in 0 until 4) if (metric[s] < inf) for (input in 0 until 4) {
                val m = metric[s] + Integer.bitCount(NEXT[s][input] xor sym[t])
                if (m < next[input]) { next[input] = m; from[t][input] = s }
            }
            metric = next
        }
        // Ends in state 0: the flush input.
        var s = 0
        if (metric[0] > MAX_TRELLIS_ERRORS) return null
        val dibits = IntArray(sym.size)
        for (t in sym.indices.reversed()) { dibits[t] = s; s = from[t][s] }
        val out = ByteArray(12)
        for (i in 0 until 48) out[i / 4] = (out[i / 4].toInt() or (dibits[i] shl (6 - 2 * (i % 4)))).toByte()
        return out
    }

    /**
     * The block's 96 bits divided by x^16+x^12+x^5+1 leave all ones when it is intact (the sender appends the
     * inverted CRC-CCITT of the first 10 bytes).
     */
    fun crcOk(b: ByteArray): Boolean = remainder(b) == 0xffff

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
