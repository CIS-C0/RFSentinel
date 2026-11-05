package com.rfsentinel.app.sdr

import java.lang.Integer.bitCount
import java.lang.Integer.reverse
import kotlin.math.abs

/** Follows a stream's symbol edges by its zero crossings. */
internal class SymbolClock(private val sampleRate: Int, var baud: Int) {
    private var phase = 0.0   // through the symbol, 0 to 1
    private var last = 0.0
    /** In the middle of a symbol, clear of its edges. */
    val mid get() = phase > 0.1 && phase < 0.9

    /** [x] crossed zero: pulls the edge towards it by [rate]. */
    fun crossed(x: Double, rate: Double): Boolean {
        val crossed = (last < 0 && x >= 0) || (last >= 0 && x < 0)
        last = x
        if (crossed) phase -= (if (phase < 0.5) phase else phase - 1) * rate
        return crossed
    }

    /** One sample on: true when that ends a symbol. */
    fun advance(): Boolean {
        phase += baud.toDouble() / sampleRate
        if (phase <= 1) return false
        phase -= 1
        return true
    }
}

/**
 * FLEX pager messages from the FM-demodulated signal (any scale), a port of multimon-ng's demod_flex.c (see NOTICE.md).
 * A frame is 1.875 s: a 1600 baud two-level header (sync with the speed in it, then the frame information word), and
 * 1.76 s of data at 1600 or 3200 baud, two or four levels, carrying one to four interleaved phases of 88 words.
 * Each phase is a block information word, addresses, a vector per address, and the messages they point to.
 *
 * shortcut: group messages show under the group's address, fragments of a long page as separate partial rows, binary pages
 * and the digits in tone pages are dropped, long addresses read as short ones (as upstream); upgrade if real pages need them.
 * Pure (no Android): unit-tested.
 */
class Flex(sampleRate: Int, private val onPage: (PagerLog.Page) -> Unit) {

    private enum class State { SYNC1, FIW, SYNC2, DATA }

    /** Frame headers that passed their checks: a real FLEX signal, even with no page in it. */
    var frames = 0; private set

    private val clock = SymbolClock(sampleRate, HEADER_BAUD)
    private val zeroSamples = sampleRate * ZERO_S
    private var zero = 0.0
    private var envelope = 0.0; private var envelopeSum = 0.0; private var envelopeN = 0
    private var locked = false
    private var lockBuf = 0L
    private val symbols = IntArray(4)
    private var timeout = 0
    private var nonConsecutive = 0

    private var state = State.SYNC1
    private var syncBuf = 0L
    private var inverted = false
    private var baud = HEADER_BAUD
    private var levels = 2
    private var fiw = 0; private var fiwCount = 0
    private var sync2Count = 0
    private var dataCount = 0
    private val phases = Array(4) { IntArray(WORDS) }
    private var toggle = false
    private var dataBits = 0

    fun sample(raw: Double) {
        if (state == State.SYNC1) zero = (zero * zeroSamples + raw) / (zeroSamples + 1)
        val x = raw - zero
        if (locked) {
            if (state == State.SYNC1) { envelopeSum += abs(x); envelope = envelopeSum / ++envelopeN }
        } else {
            envelope = 0.0; envelopeSum = 0.0; envelopeN = 0
            clock.baud = HEADER_BAUD; timeout = 0; nonConsecutive = 0
            state = State.SYNC1
        }
        val mid = clock.mid
        if (mid) symbols[if (x > 0) (if (x > envelope * SLICE) 3 else 2) else (if (x < -envelope * SLICE) 0 else 1)]++
        if (clock.crossed(x, if (locked) LOCKED_RATE else UNLOCKED_RATE)) {
            // Crossings in mid-symbol: the clock isn't on this signal any more.
            if (!mid) nonConsecutive = 0 else if (++nonConsecutive > MAX_NON_CONSECUTIVE) locked = false
            timeout = 0
        }
        if (clock.advance()) symbolEnd()
    }

    private fun symbolEnd() {
        nonConsecutive = 0
        var modal = 0
        for (j in 1..3) if (symbols[j] > symbols[modal]) modal = j
        symbols.fill(0)
        if (locked) symbol(modal)
        else {
            // The bit sync: outer levels, alternating.
            lockBuf = lockBuf shl 2 or (modal xor 1).toLong()
            val pattern = lockBuf xor LOCK_PATTERN
            if (pattern and LOCK_MASK == 0L || pattern.inv() and LOCK_MASK == 0L) { locked = true; lockBuf = 0 }
        }
        if (++timeout > MAX_TIMEOUT) locked = false
    }

    private fun symbol(raw: Int) {
        val sym = if (inverted) 3 - raw else raw
        when (state) {
            State.SYNC1 -> {
                val code = sync(raw)
                if (code != 0 && mode(code)) state = State.FIW
                fiwCount = 0; fiw = 0
            }
            State.FIW -> {
                // 16 bits of dotting, then the frame information word.
                if (++fiwCount >= 16) fiw = (fiw ushr 1) or (if (sym > 1) Int.MIN_VALUE else 0)
                if (fiwCount == 48) {
                    if (fiwGood()) { frames++; sync2Count = 0; clock.baud = baud; state = State.SYNC2 }
                    else state = State.SYNC1
                }
            }
            State.SYNC2 -> if (++sync2Count == baud * 25 / 1000) {
                dataCount = 0; toggle = false; dataBits = 0
                phases.forEach { it.fill(0) }
                state = State.DATA
            }
            State.DATA -> {
                readData(sym)
                if (++dataCount == baud * 1760 / 1000) {
                    decodeData()
                    clock.baud = HEADER_BAUD
                    state = State.SYNC1
                }
            }
        }
    }

    /** The sync's speed code, either polarity; 0 until one is complete. */
    private fun sync(raw: Int): Int {
        syncBuf = syncBuf shl 1 or (if (raw < 2) 1L else 0L)
        val code = syncCode(syncBuf)
        if (code != 0) { inverted = false; return code }
        return syncCode(syncBuf.inv()).also { if (it != 0) inverted = true }
    }

    /** AAAA:A6C6AAAA:~AAAA, a few bits off allowed in each part. */
    private fun syncCode(buf: Long): Int {
        val marker = (buf ushr 16).toInt()
        val high = (buf ushr 48).toInt() and 0xFFFF
        val low = buf.toInt().inv() and 0xFFFF
        return if (bitCount(marker xor MARKER) < 4 && bitCount(low xor high) < 4) high else 0
    }

    private fun mode(code: Int): Boolean {
        val m = MODES.firstOrNull { bitCount(it[0] xor code) < 4 } ?: return false
        baud = m[1]; levels = m[2]
        return true
    }

    private fun fix(word: Int): Int? = Bch3121.correct(reverse(word))?.let { reverse(it) }

    private fun fiwGood(): Boolean {
        val w = fix(fiw) ?: return false
        val sum = (w and 0xF) + ((w ushr 4) and 0xF) + ((w ushr 8) and 0xF) + ((w ushr 12) and 0xF) + ((w ushr 16) and 0xF) + ((w ushr 20) and 1)
        return sum and 0xF == 0xF
    }

    private fun readData(sym: Int) {
        val a = sym > 1
        val b = levels == 4 && (sym == 1 || sym == 2)
        if (baud == HEADER_BAUD) toggle = false
        // Deinterleaves: bits of eight words at a time, one bit of each in turn.
        val word = ((dataBits ushr 5) and 0xFFF8) or (dataBits and 7)
        val first = if (toggle) 2 else 0
        phases[first][word] = (phases[first][word] ushr 1) or (if (a) Int.MIN_VALUE else 0)
        phases[first + 1][word] = (phases[first + 1][word] ushr 1) or (if (b) Int.MIN_VALUE else 0)
        toggle = !toggle
        if (baud == HEADER_BAUD || !toggle) dataBits++
    }

    private fun decodeData() {
        decodePhase(phases[0])
        if (levels == 4) decodePhase(phases[1])
        if (baud != HEADER_BAUD) {
            decodePhase(phases[2])
            if (levels == 4) decodePhase(phases[3])
        }
    }

    private fun decodePhase(p: IntArray) {
        for (i in p.indices) p[i] = (fix(p[i]) ?: return) and DATA_MASK
        val biw = p[0]
        if (biw == 0 || biw == DATA_MASK) return
        val vectors = (biw ushr 10) and 0x3f
        val addresses = ((biw ushr 8) and 3) + 1
        if (vectors < addresses || vectors + (vectors - addresses) > WORDS) return
        for (i in addresses until vectors) {
            val address = p[i]
            if (address == 0 || address == DATA_MASK) continue
            val capcode = address - 0x8000L
            if (capcode < 0) continue
            val long = address < 0x8001 || address > 0x1E0000
            val j = vectors + i - addresses
            val vector = p[j]
            val type = (vector ushr 4) and 7
            val start = (vector ushr 7) and 0x7f
            val end = start + ((vector ushr 14) and 0x7f) - 1
            if (type == TYPE_SHORT_INSTRUCTION || (start == 0 && end == 0)) continue
            val protocol = "FLEX $baud/$levels"
            when (type) {
                TYPE_SECURE, TYPE_ALPHA -> if (start < WORDS && end < WORDS) alpha(p, start, end, protocol, capcode)
                TYPE_NUMERIC, TYPE_SPECIAL_NUMERIC, TYPE_NUMBERED_NUMERIC ->
                    numeric(p, j, type, long)?.let { onPage(PagerLog.Page(protocol, capcode, PagerLog.Kind.NUMERIC, it)) }
                TYPE_TONE -> onPage(PagerLog.Page(protocol, capcode, PagerLog.Kind.TONE, ""))
            }
        }
    }

    /** Three 7-bit characters a word after a header word; a whole page's first character is a signature, not text. */
    private fun alpha(p: IntArray, start: Int, end: Int, protocol: String, capcode: Long) {
        val fragment = (p[start] ushr 11) and 3
        val continued = (p[start] ushr 10) and 1 == 1
        val codes = ArrayList<Int>()
        for (i in start + 1..end) {
            if (i > start + 1 || fragment != 3) codes += p[i] and 0x7F
            codes += (p[i] ushr 7) and 0x7F
            codes += (p[i] ushr 14) and 0x7F
        }
        onPage(PagerLog.Page(protocol, capcode, PagerLog.Kind.ALPHA, pageText(codes), continued || fragment != 3))
    }

    /** Digits run through 21-bit words, after a few header bits; null when the vector points outside the phase. */
    private fun numeric(p: IntArray, j: Int, type: Int, long: Boolean): String? {
        var first = (p[j] ushr 7) and 0x7f
        var last = ((p[j] ushr 14) and 7) + first
        if (first >= WORDS || last >= WORDS || (long && j + 1 >= WORDS)) return null
        // A long address has its first digits in the second vector word.
        var dw = if (long) p[j + 1] else p[first++].also { last++ }
        var digit = 0
        var count = if (type == TYPE_NUMBERED_NUMERIC) 14 else 6
        return buildString {
            for (i in first..last) {
                repeat(21) {
                    digit = (digit ushr 1) or ((dw and 1) shl 3)
                    dw = dw ushr 1
                    if (--count == 0) {
                        if (digit != FILL) append(BCD[digit])
                        count = 4
                    }
                }
                if (i < last) dw = p[i]
            }
        }
    }

    companion object {
        private const val HEADER_BAUD = 1600
        private const val WORDS = 88
        private const val DATA_MASK = 0x1FFFFF
        private const val MARKER = 0xA6C6AAAA.toInt()
        private const val SLICE = 0.667            // four levels: the outer ones are three times the inner
        private const val ZERO_S = 0.010
        private const val LOCKED_RATE = 0.045
        private const val UNLOCKED_RATE = 0.050
        private const val LOCK_PATTERN = 0x6666666666666666L
        private const val LOCK_MASK = (1L shl 48) - 1  // 24 symbols
        private const val MAX_TIMEOUT = 100        // symbols with no zero crossing
        private const val MAX_NON_CONSECUTIVE = 20
        private const val FILL = 0xC
        private const val BCD = "0123456789 U -]["
        private const val TYPE_SECURE = 0
        private const val TYPE_SHORT_INSTRUCTION = 1
        private const val TYPE_TONE = 2
        private const val TYPE_NUMERIC = 3
        private const val TYPE_SPECIAL_NUMERIC = 4
        private const val TYPE_ALPHA = 5
        private const val TYPE_NUMBERED_NUMERIC = 7
        /** Speed code, baud, levels. */
        private val MODES = listOf(
            intArrayOf(0x870C, 1600, 2), intArrayOf(0xB068, 1600, 4),
            intArrayOf(0x7B18, 3200, 2), intArrayOf(0xDEA0, 3200, 4), intArrayOf(0x4C7C, 3200, 4)
        )
    }
}
