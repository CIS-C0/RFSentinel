package com.rfsentinel.app.sdr

import java.lang.Integer.bitCount

/**
 * The BCH(31,21) code of POCSAG and FLEX, generator 0x769, plus even parity. Works on POCSAG's word layout: codeword in
 * bits 31..1, first bit sent highest, parity in bit 0. A FLEX word is the same layout bit-reversed.
 */
internal object Bch3121 {
    private const val POLY = 0x769

    /** Remainder of the codeword by the generator: 0 when it is clean. */
    fun syndrome(word: Int): Int {
        var r = word ushr 1
        for (i in 20 downTo 0) if ((r ushr (i + 10)) and 1 != 0) r = r xor (POLY shl i)
        return r
    }

    /** Syndrome to the one or two codeword bits that cause it; 0 when no such error does. */
    private val ERRORS = IntArray(1024).also { t ->
        for (i in 1..31) t[syndrome(1 shl i)] = 1 shl i
        for (i in 1..31) for (j in i + 1..31) {
            val s = syndrome(1 shl i) xor syndrome(1 shl j)
            if (t[s] == 0) t[s] = (1 shl i) or (1 shl j)
        }
    }

    /**
     * [word] with up to two bit errors in the codeword fixed, or null. The parity bit rejects a fix that three or more errors
     * made look valid: without it about half of random words would pass.
     */
    fun correct(word: Int): Int? {
        val s = syndrome(word)
        if (s == 0) return if (bitCount(word) and 1 == 0) word else word xor 1
        val error = ERRORS[s]
        if (error == 0) return null
        return (word xor error).takeIf { bitCount(it) and 1 == 0 }
    }
}

/** Text from 7-bit characters: printable ones kept, line breaks as spaces, other control codes dropped. */
internal fun pageText(codes: List<Int>): String = buildString {
    for (c in codes) when (c) {
        in 0x20..0x7e -> append(c.toChar())
        0x0a, 0x0d -> append(' ')
    }
}.trim()

/**
 * POCSAG pager messages from a stream of bits, at one speed ([baud]): frame sync (either polarity, so it doesn't matter
 * which way up the receiver has the signal), then batches of 16 codewords with the sync between them. An address word
 * starts a page, message words add to it, and an idle word or the next address ends it. A page's frame in the batch is
 * the low three bits of its capcode.
 *
 * Pages wait for the end of their batch: a false sync on noise gets past the error correction often enough to
 * make up a page, but not for a whole batch. Pure (no Android): unit-tested. Follows multimon-ng's pocsag.c (see NOTICE.md).
 */
class Pocsag(private val baud: Int, private val onPage: (PagerLog.Page) -> Unit) {

    /** Batches that ended in a sync or held a page: a real POCSAG signal. */
    var batches = 0; private set
    /** Frame syncs found, whatever followed them. */
    var syncs = 0; private set

    private var reg = 0
    private var synced = false
    private var inverted = false
    private var bits = 0
    private var at = 0                  // next codeword in the batch; BATCH: the sync is next
    private var bad = 0                 // words past correcting in this batch
    private var capcode = -1L           // the page being read
    private var function = 0
    private val words = ArrayList<Int>()
    private val pending = ArrayList<PagerLog.Page>()

    fun bit(one: Boolean) {
        reg = reg shl 1 or (if (one) 1 else 0)
        if (!synced) {
            val d = bitCount(reg xor SYNC)
            if (d <= SYNC_ERRORS || d >= 32 - SYNC_ERRORS) {
                synced = true; inverted = d > SYNC_ERRORS; syncs++
                bits = 0; at = 0; bad = 0
            }
            return
        }
        if (++bits < 32) return
        bits = 0
        word(if (inverted) reg.inv() else reg)
    }

    private fun word(w: Int) {
        if (at == BATCH) {
            val sync = bitCount(w xor SYNC) <= SYNC_ERRORS
            if (sync) { at = 0; bad = 0 } else { end(false); synced = false }
            // A short page is one batch with no sync after it, but it passed the same checks as any page shown.
            if (sync || pending.isNotEmpty()) batches++
            pending.forEach(onPage); pending.clear()
            return
        }
        val index = at++
        val c = Bch3121.correct(w)
        when {
            c == null -> {
                end(true)
                // Noise, or the signal is gone: nothing from this batch can be trusted.
                if (++bad >= MAX_BAD) { pending.clear(); synced = false }
            }
            c == IDLE -> end(false)
            c >= 0 -> {
                end(false)
                capcode = (((c ushr 13) and 0x3FFFF).toLong() shl 3) or (index / 2).toLong()
                function = (c ushr 11) and 3
            }
            capcode >= 0 && words.size < MAX_WORDS -> words += (c ushr 11) and 0xFFFFF
        }
    }

    /** The page being read is complete, or cut off ([partial]). */
    private fun end(partial: Boolean) {
        if (capcode >= 0) {
            val (kind, text) = decode(function, words)
            pending += PagerLog.Page("POCSAG $baud", capcode, kind, text, partial)
        }
        capcode = -1; words.clear()
    }

    companion object {
        internal const val SYNC = 0x7CD215D8
        internal const val IDLE = 0x7A89C197
        internal const val BATCH = 16
        private const val SYNC_ERRORS = 2
        private const val MAX_BAD = 6
        private const val MAX_WORDS = 256
        private const val NUMERIC = "0123456789*U -)("
        private const val DIGITS = "0123456789 -"

        /** Function 3 is text by convention. The rest is up to the network: numbers unless they read as noise. */
        internal fun decode(function: Int, words: List<Int>): Pair<PagerLog.Kind, String> {
            if (words.isEmpty()) return PagerLog.Kind.TONE to ""
            val digits = numeric(words)
            val isNumeric = function != 3 && digits.count { it !in DIGITS } * 4 <= digits.length
            return if (isNumeric) PagerLog.Kind.NUMERIC to digits else PagerLog.Kind.ALPHA to alpha(words)
        }

        /** Five digits a word, each sent lowest bit first. */
        private fun numeric(words: List<Int>) = buildString {
            for (w in words) for (shift in 16 downTo 0 step 4)
                append(NUMERIC[Integer.reverse((w ushr shift) and 0xF) ushr 28])
        }.trim()

        /** 7-bit characters running across the 20-bit words, each sent lowest bit first. */
        private fun alpha(words: List<Int>): String {
            val codes = ArrayList<Int>()
            var c = 0; var n = 0
            for (w in words) for (b in 19 downTo 0) {
                c = c or (((w ushr b) and 1) shl n)
                if (++n == 7) { codes += c; c = 0; n = 0 }
            }
            return pageText(codes)
        }
    }
}
