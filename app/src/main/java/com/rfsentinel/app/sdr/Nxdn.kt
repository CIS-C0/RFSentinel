package com.rfsentinel.app.sdr

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * NXDN48 digital voice (Kenwood NEXEDGE, Icom IDAS conventional): FM discriminator samples at [rate] (about ±1, any
 * scale) to the frames' messages and AMBE+2 speech. Each 80 ms frame starts with the frame sync (FSW, 10 symbols at
 * 2400 baud); the sync gives the timing, DC and level, the 182 dibits after it are sliced, de-scrambled (PN9) and split:
 * LICH (what the frame holds), SACCH (a slow message, one quarter a frame), and two halves that are voice (two AMBE
 * frames each) or a FACCH1 message. VCALL messages say whether the call is encrypted ([cipher]).
 * Clear voice goes to [onVoice] at [outRate] (8 kHz, upsampled by a whole factor); encrypted voice is not decoded.
 * Ported from DSD-FME (ISC licence; its NXDN code reworked from OP25, GPL-3.0) and mbelib ([AmbeDecoder]).
 * shortcut: NXDN96 (4800 baud, 12.5 kHz trunked systems) and type-C/D trunking are not decoded; add a second symbol
 * clock and their LICH types when one is heard.
 * Pure (no Android): unit-tested on a synthetic signal.
 */
class Nxdn(private val rate: Int, private val outRate: Int = MbeSynth.RATE, private val onVoice: (ShortArray) -> Unit) {

    private val sps = rate.toDouble() / BAUD
    // A short moving average against noise (the symbol pulse is wider).
    private val boxN = maxOf(1, (sps * 0.6).toInt())
    private val box = DoubleArray(boxN); private var boxAt = 0; private var boxSum = 0.0
    private val hist = DoubleArray(HIST)
    private var n = 0L
    private var best = 0.0; private var bestAt = -1L; private var lastSync = Long.MIN_VALUE / 2
    /** Frames waiting for all their samples: the sync's last sample, the signal's sign, found by the flywheel. */
    private class Pending(val end: Double, val pol: Int, val coast: Boolean)
    private val pending = ArrayDeque<Pending>()
    // Flywheel: where the next frame's sync should end, after a good one; a weak sync there is still read (a few times).
    private var expect = -1.0; private var expectPol = 1; private var coasts = 0

    /** Frames whose LICH checked; frames found by sync whose LICH didn't. */
    var frames = 0; private set
    var badFrames = 0; private set
    /** AMBE frames decoded, and skipped as encrypted. */
    var voiceFrames = 0; private set
    var encryptedFrames = 0; private set
    /** Bits the AMBE error correction fixed, over all [voiceFrames]: the signal's quality. */
    var ambeErrors = 0; private set
    /** The call's cipher from its VCALL message: -1 not known yet, 0 clear, 1 scrambler, 2 DES, 3 AES. */
    var cipher = -1; private set
    /** The call's source and destination (unit, group) IDs, -1 not known yet. */
    var source = -1; private set
    var destination = -1; private set
    /** True for half a second after the last good frame: the FM audio is the NXDN signal, not speech. */
    val active get() = frames > 0 && n - lastFrame < rate / 2
    private var lastFrame = Long.MIN_VALUE / 2

    private val ambe = AmbeDecoder()
    private var lastOut = 0.0
    // The superframe SACCH: four 18-bit quarters, in order.
    private val sacch = IntArray(72); private var sacchPart = -1

    /** One discriminator sample. */
    fun sample(v: Double) {
        boxSum += v - box[boxAt]; box[boxAt] = v; if (++boxAt == boxN) boxAt = 0
        hist[(n and MASK).toInt()] = boxSum / boxN
        if (n > SYNC.size * sps + boxN) {
            var dot = 0.0; var sum = 0.0; var sq = 0.0
            for (k in SYNC.indices) {
                val x = at(n - (SYNC.size - 1 - k) * sps)
                dot += x * SYNC[k]; sum += x; sq += x * x
            }
            // The sync's symbols sum to zero, so only the samples' own DC needs taking out.
            val e = (sq - sum * sum / SYNC.size) * SYNC_ENERGY
            val corr = if (e > 0) dot / sqrt(e) else 0.0
            if (kotlin.math.abs(corr) > SYNC_CORR && kotlin.math.abs(corr) > kotlin.math.abs(best)) { best = corr; bestAt = n }
            if (bestAt >= 0 && n - bestAt >= sps / 2) {
                if (bestAt - lastSync > SYNC.size * sps) {
                    lastSync = bestAt
                    if (expect >= 0 && kotlin.math.abs(bestAt - expect) < 2 * sps) expect = -1.0
                    pending += Pending(bestAt.toDouble(), if (best > 0) 1 else -1, false)
                }
                best = 0.0; bestAt = -1
            }
        }
        if (expect >= 0 && n > expect + 2 * sps) {
            if (coasts < MAX_COASTS) { coasts++; pending += Pending(expect, expectPol, true) }
            expect = -1.0
        }
        // A frame is read once all of it is in.
        val first = pending.firstOrNull()
        if (first != null && n >= first.end + (FRAME + 1) * sps + 2) frame(pending.removeFirst())
        n++
    }

    private fun at(t: Double): Double {
        val i = floor(t).toLong(); val f = t - i
        return hist[(i and MASK).toInt()] * (1 - f) + hist[((i + 1) and MASK).toInt()] * f
    }

    private fun frame(p: Pending) {
        val end = p.end; val pol = p.pol
        // Level and DC from the sync: x = dc + a·S.
        var dot = 0.0; var sum = 0.0
        for (k in SYNC.indices) { val x = pol * at(end - (SYNC.size - 1 - k) * sps); dot += x * SYNC[k]; sum += x }
        val a = dot / SYNC_ENERGY; val dc = sum / SYNC.size
        val good = a > 0 && handle(IntArray(FRAME) { j -> P25Demod.slice((pol * at(end + (j + 1) * sps) - dc) / (3 * a)) xor (PN[j] shl 1) })
        if (good) {
            if (!p.coast) coasts = 0
            expect = end + (SYNC.size + FRAME) * sps; expectPol = pol
        } else if (p.coast) coasts = MAX_COASTS
    }

    /** One frame's 182 de-scrambled dibits (LICH first); false when its LICH says it isn't one. */
    internal fun handle(d: IntArray): Boolean {
        var lich = 0; var ones = 0
        for (k in 0 until 8) { lich = lich shl 1 or (d[k] shr 1); ones += d[k] and 1 }
        // The LICH's second bits are all ones; its last first bit is the parity of the first four.
        val parity = (lich shr 7 xor (lich shr 6) xor (lich shr 5) xor (lich shr 4)) and 1
        if (ones < 7 || parity != lich and 1) { badFrames++; return false }
        val type = lich shr 1
        val voice = when (type) { 0x32, 0x33, 0x52, 0x53 -> 2; 0x34, 0x35, 0x54, 0x55 -> 1; 0x36, 0x37, 0x56, 0x57 -> 3; else -> 0 }
        val facch = when (type) {
            0x32, 0x33, 0x52, 0x53 -> 1; 0x34, 0x35, 0x54, 0x55 -> 2
            0x20, 0x21, 0x30, 0x31, 0x40, 0x41, 0x50, 0x51 -> 3; else -> 0
        }
        if (voice == 0 && facch == 0 && type != 0x38 && type != 0x39) { badFrames++; return false }
        val bits = IntArray(2 * FRAME) { (d[it / 2] shr (1 - it % 2)) and 1 }
        val s = viterbi(depuncture(deinterleave(bits.copyOfRange(16, 76), 5), SACCH_PUNCTURE, 72))
        val ok = crc(s, 26, 6) == value(s, 26, 6)
        val facchMsgs = (1..2).filter { facch and it != 0 }.mapNotNull { half ->
            val from = 76 + (half - 1) * 144
            val f = viterbi(depuncture(deinterleave(bits.copyOfRange(from, from + 144), 9), FACCH_PUNCTURE, 192))
            if (crc(f, 80, 12) == value(f, 80, 12)) f.copyOf(80) else null
        }
        // Analog speech can pass the sync and LICH by chance (a real FRS call did): a call starts on a frame whose
        // SACCH or FACCH checks, the frames right after it may be weaker.
        if (!ok && facchMsgs.isEmpty() && !active) { badFrames++; return false }
        frames++; lastFrame = n

        // SACCH: in a superframe of four (the voice channel), or whole (idle and data).
        if (type !in NON_SUPERFRAME) {
            val part = 3 - value(s, 0, 2)
            if (ok && (part == 0 || part == sacchPart + 1)) {
                s.copyInto(sacch, part * 18, 8, 26); sacchPart = part
                if (part == 3) { message(sacch); sacchPart = -1 }
            } else sacchPart = -1
        }
        facchMsgs.forEach(::message)
        for (half in 1..2) {
            if (voice and half != 0) for (b in (half - 1) * 2 until half * 2) {
                if (cipher > 0) { encryptedFrames++; continue }
                val fr = Array(4) { IntArray(24) }
                for (i in 0 until 36) {
                    val x = d[38 + b * 36 + i]
                    fr[NW[i]][NX[i]] = x shr 1; fr[NY[i]][NZ[i]] = x and 1
                }
                val pcm = ambe.decode(fr)
                voiceFrames++; ambeErrors += ambe.errors
                onVoice(upsample(pcm))
            }
        }
        return true
    }

    /** A layer-3 message (FACCH1, or a whole superframe SACCH). */
    private fun message(m: IntArray) {
        when (value(m, 2, 6)) {
            VCALL -> if (m.size >= 64) { cipher = value(m, 56, 2); source = value(m, 24, 16); destination = value(m, 40, 16) }
            TX_REL, DISC -> cipher = -1 // the next call says its own
        }
    }

    private fun upsample(pcm: ShortArray): ShortArray {
        val k = outRate / MbeSynth.RATE
        if (k <= 1) return pcm
        val out = ShortArray(pcm.size * k)
        for (i in pcm.indices) {
            val x = pcm[i].toDouble()
            for (j in 1..k) out[i * k + j - 1] = (lastOut + (x - lastOut) * j / k).toInt().toShort()
            lastOut = x
        }
        return out
    }

    companion object {
        const val BAUD = 2400
        /** Dibits after the sync: LICH, SACCH, two voice / FACCH1 halves. */
        const val FRAME = 182
        private const val HIST = 8192
        private const val MASK = HIST - 1L
        private const val SYNC_CORR = 0.85
        private const val MAX_COASTS = 3
        private const val VCALL = 0x01
        private const val TX_REL = 0x08
        private const val DISC = 0x11

        /** The frame sync 0xCDF59 as symbols. */
        val SYNC = intArrayOf(-3, 1, -3, 3, -3, -3, 3, 3, -1, 3)
        private val SYNC_ENERGY = SYNC.sumOf { it * it }.toDouble()
        private val NON_SUPERFRAME = setOf(0x20, 0x21, 0x40, 0x41)

        /** The PN9 scrambler over a frame's dibits: 1 flips the dibit's sign (its first bit). */
        internal val PN: IntArray = run {
            var lfsr = 0xE4
            IntArray(FRAME) { val b = lfsr and 1; val fb = (lfsr shr 4 xor lfsr) and 1; lfsr = lfsr shr 1 or (fb shl 8); b }
        }

        private val SACCH_PUNCTURE = intArrayOf(1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0)
        private val FACCH_PUNCTURE = intArrayOf(1, 0, 1, 1)

        /** Bits sent row by row of a [cols]-column block, back to column by column. */
        internal fun deinterleave(bits: IntArray, cols: Int): IntArray {
            val rows = bits.size / cols
            val d = IntArray(bits.size)
            for (i in bits.indices) d[(i % cols) * rows + i / cols] = bits[i]
            return d
        }

        /** The punctured bits back to [len], -1 where a bit was left out. */
        internal fun depuncture(bits: IntArray, pattern: IntArray, len: Int): IntArray {
            var k = 0
            return IntArray(len) { i -> if (pattern[i % pattern.size] == 1 && k < bits.size) bits[k++] else -1 }
        }

        // The rate-1/2 K=5 code: G1 = 1 + D³ + D⁴, G2 = 1 + D + D² + D⁴, G1's bit first. State: the last 4 bits, newest in bit 0.
        private fun g1(s: Int, b: Int) = (b xor (s shr 2) xor (s shr 3)) and 1
        private fun g2(s: Int, b: Int) = (b xor s xor (s shr 1) xor (s shr 3)) and 1

        /** [data] and 4 flushing zeros, coded. */
        internal fun encode(data: IntArray): IntArray {
            val out = IntArray(2 * (data.size + 4)); var s = 0
            for (t in 0 until data.size + 4) {
                val b = if (t < data.size) data[t] else 0
                out[2 * t] = g1(s, b); out[2 * t + 1] = g2(s, b)
                s = (s shl 1 or b) and 15
            }
            return out
        }

        /** Hard-decision Viterbi (-1: erased), ending in state zero: the coded bits back to the input bits, flush included. */
        internal fun viterbi(code: IntArray): IntArray {
            val steps = code.size / 2
            val big = Int.MAX_VALUE / 2
            var m = IntArray(16) { if (it == 0) 0 else big }
            val from = Array(steps) { IntArray(16) }
            for (t in 0 until steps) {
                val c0 = code[2 * t]; val c1 = code[2 * t + 1]
                val next = IntArray(16) { big }
                for (s in 0 until 16) {
                    if (m[s] >= big) continue
                    for (b in 0..1) {
                        val cost = m[s] + (if (c0 >= 0 && c0 != g1(s, b)) 1 else 0) + (if (c1 >= 0 && c1 != g2(s, b)) 1 else 0)
                        val ns = (s shl 1 or b) and 15
                        if (cost < next[ns]) { next[ns] = cost; from[t][ns] = s }
                    }
                }
                m = next
            }
            val out = IntArray(steps); var s = 0
            for (t in steps - 1 downTo 0) { out[t] = s and 1; s = from[t][s] }
            return out
        }

        /** NXDN's CRC-6 / CRC-12 over the first [len] bits, register preset to ones. */
        internal fun crc(b: IntArray, len: Int, size: Int): Int {
            val s = IntArray(size) { 1 }
            for (i in 0 until len) {
                val a = b[i] xor s[0]
                for (k in 0 until size - 1) s[k] = s[k + 1]
                s[size - 1] = a
                for (k in if (size == 6) CRC6_TAPS else CRC12_TAPS) s[k] = s[k] xor a
            }
            return value(s, 0, size)
        }

        private val CRC6_TAPS = intArrayOf(0, 3, 4)
        private val CRC12_TAPS = intArrayOf(0, 8, 9, 10)

        internal fun value(b: IntArray, from: Int, len: Int): Int { var v = 0; for (i in from until from + len) v = v shl 1 or b[i]; return v }

        // A voice half's dibits to mbelib's AMBE frame rows: dibit i's first bit to row NW bit NX, its second to NY / NZ.
        private val NW = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2)
        private val NX = intArrayOf(23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4)
        private val NY = intArrayOf(0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3)
        private val NZ = intArrayOf(5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0)

        /** A cipher number as a user reads it. */
        fun cipherLabel(c: Int) = when (c) { 0 -> "clear"; 1 -> "encrypted (scrambler)"; 2 -> "encrypted (DES)"; 3 -> "encrypted (AES)"; else -> "" }
    }
}
