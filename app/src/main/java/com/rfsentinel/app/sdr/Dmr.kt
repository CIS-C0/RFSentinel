package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * DMR (ETSI TS 102 361, Motorola MOTOTRBO, Hytera and most business radios) digital voice: FM discriminator samples at
 * [rate] (about ±1, any scale) to AMBE+2 speech. DMR sends 4800 symbols a second in two 30 ms time slots; a call uses
 * one of them. A voice superframe is six bursts (A-F), one every 60 ms on its slot: burst A carries a voice sync
 * (it gives the timing, DC and level), B-F carry an EMB instead (colour code, the privacy flag PI, which part of the
 * embedded call info follows; QR(16,7) coded). Each burst holds three AMBE frames around its middle field.
 * B-E carry the call's link control (BPTC(128,72), CRC-5): source, destination and the service options, whose privacy
 * bit (like PI) says the call is encrypted ([cipher]). Clear voice goes to [onVoice] at [outRate] (8 kHz,
 * upsampled by a whole factor); encrypted voice is not decoded, and nothing is played before the call said which.
 *
 * Ported from dsd-neo (arancormonk; DSD-FME, LWVMOBILE; DSD: burst layout, sync patterns, AMBE interleave, embedded
 * LC; ISC licence) and its FEC code (Hamming(16,11,4), QR(16,7,6) parity-check matrices: Edouard Griffiths F4EXB,
 * GPL-3.0-or-later). Its polarity rule too: a voice sync on four bursts in a row is a data channel the other way up;
 * and a data sync every 360 ms only is a mobile's call the other way up.
 * shortcut: voice LC headers, PI headers and terminators (BPTC(196,96)) aren't decoded, only their sync ends a call;
 * a radio that sets neither PI nor the privacy bit for its encryption plays as noise until it stops.
 * Pure (no Android): unit-tested on a synthetic signal.
 */
class Dmr(private val rate: Int, outRate: Int = MbeSynth.RATE, private val onVoice: (ShortArray) -> Unit) {

    private val sps = rate.toDouble() / BAUD
    // A short moving average against noise (the symbol pulse is wider).
    private val boxN = maxOf(1, (sps * 0.6).toInt())
    private val box = DoubleArray(boxN); private var boxAt = 0; private var boxSum = 0.0
    private val hist = DoubleArray(HIST)
    private val xs = DoubleArray(SYNC_LEN)
    private var n = 0L
    private var best = 0.0; private var bestAt = -1L; private var bestPat = 0; private var lastSync = Long.MIN_VALUE / 2

    /** The call's next burst: the sample its middle field ends at, which of the superframe's six (0 = A), its sync found. */
    private class Next(val end: Double, val vc: Int, val pat: Int, val found: Boolean)
    private var next: Next? = null
    private var level = 0.0; private var dc = 0.0; private var misses = 0
    private var inverted = false
    private var embOk = 0; private var embBad = 0
    private var lastVoiceSync = -1.0; private var adjacent = 0
    private var lastDataSync = -1.0; private var superframes = 0
    private val frag = IntArray(128); private var fragNext = 0

    /** Bursts read whose sync or EMB checked; bursts whose EMB didn't. */
    var bursts = 0; private set
    var badBursts = 0; private set
    /** Data and control bursts heard (headers, terminators, a repeater's idle slot). */
    var dataSyncs = 0; private set
    /** AMBE frames decoded; skipped as encrypted; skipped before the call said clear or encrypted. */
    var voiceFrames = 0; private set
    var encryptedFrames = 0; private set
    var heldFrames = 0; private set
    /** Bits the AMBE error correction fixed, over all [voiceFrames]: the signal's quality. */
    var ambeErrors = 0; private set
    /** Link control messages whose CRC checked. */
    var linkControls = 0; private set
    /** The call's privacy: -1 not known yet, 0 clear, 1 encrypted. */
    var cipher = -1; private set
    /** The call's source and destination (unit, group) IDs, -1 not known yet. */
    var source = -1; private set
    var destination = -1; private set
    /** Times the signal was found to be the other way up (a receiver that inverts the spectrum). */
    var polarityFlips = 0; private set
    /** True for half a second after the last good burst: the FM audio is the DMR signal, not speech. */
    val active get() = bursts > 0 && n - lastBurst < rate / 2
    private var lastBurst = Long.MIN_VALUE / 2
    /** The longest time between two good bursts, in seconds: lost sync (a fade, an overloaded dongle). */
    var longestGap = 0.0; private set

    private val ambe = AmbeDecoder()
    private val up = Upsampler(outRate)

    /** One discriminator sample. */
    fun sample(v: Double) {
        boxSum += v - box[boxAt]; box[boxAt] = v; if (++boxAt == boxN) boxAt = 0
        hist[(n and MASK).toInt()] = boxSum / boxN
        if (n > SYNC_LEN * sps + boxN) {
            var sum = 0.0; var sq = 0.0
            for (k in 0 until SYNC_LEN) { val x = at(n - (SYNC_LEN - 1 - k) * sps); xs[k] = x; sum += x; sq += x * x }
            val ex = sq - sum * sum / SYNC_LEN
            if (ex > 0) for (p in VOICE.indices) {
                val corr = corr(xs, sum, ex, p)
                if (abs(corr) > SYNC_CORR && abs(corr) > abs(best)) { best = corr; bestAt = n; bestPat = p }
            }
            if (bestAt >= 0 && n - bestAt >= sps / 2) {
                if (bestAt - lastSync > SYNC_LEN * sps) { lastSync = bestAt; sync(bestAt.toDouble(), (best > 0) != inverted, bestPat) }
                best = 0.0; bestAt = -1
            }
        }
        // A burst is read once all of it is in.
        next?.let { x -> if (n >= x.end + (HALF + 1) * sps + 2) { next = null; burst(x) } }
        n++
    }

    private fun at(t: Double): Double {
        val i = floor(t).toLong(); val f = t - i
        return hist[(i and MASK).toInt()] * (1 - f) + hist[((i + 1) and MASK).toInt()] * f
    }

    /** Pearson correlation of the last [SYNC_LEN] symbols with voice sync [p]. */
    private fun corr(x: DoubleArray, sum: Double, ex: Double, p: Int): Double {
        val s = VOICE[p]; var dot = 0.0
        for (k in 0 until SYNC_LEN) dot += x[k] * s[k]
        return (dot - sum * S_SUM[p] / SYNC_LEN) / sqrt(ex * S_E[p])
    }

    /** A sync ending at sample [at]: a voice burst's ([voice]) or a data burst's (the same pattern the other way up). */
    private fun sync(at: Double, voice: Boolean, pat: Int) {
        val x = next
        val mine = x != null && abs(at - x.end) < 2 * sps
        if (!voice) {
            dataSyncs++
            // A terminator (or a header) on the call's slot: the call is over.
            if (mine) { next = null; endCall() }
            // Only "data" syncs, one every 360 ms: a mobile's voice superframes the other way up (data comes every 30-60 ms).
            if (lastDataSync >= 0 && abs(at - lastDataSync - 6 * FRAME * sps) < 3 * sps) superframes++ else superframes = 0
            lastDataSync = at
            if (superframes >= 2 && embOk == 0) flip()
            return
        }
        if (lastVoiceSync >= 0 && abs(at - lastVoiceSync - SLOT * sps) < 3 * sps) adjacent++ else adjacent = 0
        lastVoiceSync = at
        if (adjacent >= 3 && embOk == 0) { flip(); return }
        // A call starts, or its next superframe's sync came in time (the other slot's, 30 ms off, is another call).
        if (x == null || mine) next = Next(at, 0, pat, true)
    }

    private fun burst(x: Next) {
        val pol = if (inverted) -1.0 else 1.0
        if (x.vc == 0) {
            // Level and DC from the sync, least squares x = dc + a·S. A burst A the sync hunt missed must still look like one.
            val s = VOICE[x.pat]
            var sx = 0.0; var sxx = 0.0; var dot = 0.0
            for (k in 0 until SYNC_LEN) { val v = pol * at(x.end - (SYNC_LEN - 1 - k) * sps); xs[k] = v; sx += v; sxx += v * v; dot += v * s[k] }
            val ex = sxx - sx * sx / SYNC_LEN
            val ok = x.found || (ex > 0 && corr(xs, sx, ex, x.pat) > COAST_CORR)
            val a = (dot - sx * S_SUM[x.pat] / SYNC_LEN) / S_E[x.pat]
            if (!ok || a <= 0) { miss(x); return }
            level = a; dc = (sx - a * S_SUM[x.pat]) / SYNC_LEN
        }
        if (level <= 0) { next = null; return }
        val d = IntArray(BURST) { j -> P25Demod.slice((pol * at(x.end + (j - SYNC_END) * sps) - dc) / (3 * level)) }
        if (x.vc > 0) {
            val emb = IntArray(16) { i -> val k = if (i < 8) 54 + i / 2 else 74 + (i - 8) / 2; (d[k] shr (1 - i % 2)) and 1 }
            val e = decode(Nxdn.value(emb, 0, 16), QR, 2)
            if (e < 0) { embBad++; badBursts++; miss(x); return }
            embOk++
            val pi = (e shr 2) and 1; val lcss = e and 3
            if (pi == 1) cipher = 1 else if (cipher < 0) cipher = 0
            embedded(x.vc, lcss, d)
        }
        misses = 0
        if (bursts > 0) longestGap = maxOf(longestGap, (n - lastBurst).toDouble() / rate)
        bursts++; lastBurst = n
        when {
            cipher > 0 -> encryptedFrames += 3
            cipher < 0 -> heldFrames += 3
            else -> for (f in 0 until 3) {
                val pcm = ambe.decode(AmbeDecoder.frame { i -> d[voiceDibit(f, i)] })
                voiceFrames++; ambeErrors += ambe.errors
                onVoice(up.apply(pcm))
            }
        }
        next = Next(x.end + FRAME * sps, (x.vc + 1) % 6, x.pat, false)
    }

    /** A burst that didn't check: the call is followed a little longer (a fade), then dropped. */
    private fun miss(x: Next) {
        if (++misses > MAX_MISSES) {
            next = null; endCall()
            if (embBad >= 8 && embOk == 0) flip()
        } else next = Next(x.end + FRAME * sps, (x.vc + 1) % 6, x.pat, false)
    }

    /** Bursts B-E's embedded fields in order (LCSS first, continue, continue, last): the call's link control. */
    private fun embedded(vc: Int, lcss: Int, d: IntArray) {
        if (vc > 4 || lcss != LCSS[vc - 1] || (vc > 1 && fragNext != vc - 1)) { fragNext = 0; return }
        for (i in 0 until 32) frag[(vc - 1) * 32 + i] = (d[58 + i / 2] shr (1 - i % 2)) and 1
        fragNext = vc
        if (vc == 4) { fragNext = 0; linkControl(frag) }
    }

    /** BPTC(128,72): an 8 × 16 matrix sent column by column, rows 0-6 Hamming(16,11), row 7 column parity; CRC-5. */
    internal fun linkControl(b: IntArray): Boolean {
        val m = Array(8) { r -> IntArray(16) { c -> b[c * 8 + r] } }
        for (r in 0 until 7) {
            val v = decode(Nxdn.value(m[r], 0, 16), HAMMING, 1)
            if (v < 0) return false
            for (c in 0 until 11) m[r][c] = (v shr (10 - c)) and 1
        }
        val data = IntArray(72); var k = 0
        for (r in 0 until 2) for (c in 0 until 11) data[k++] = m[r][c]
        for (r in 2 until 7) for (c in 0 until 10) data[k++] = m[r][c]
        var crc = 0; for (r in 2 until 7) crc = crc shl 1 or m[r][10]
        var sum = 0; for (i in 0 until 9) sum += Nxdn.value(data, i * 8, 8)
        if (sum % 31 != crc) return false
        linkControls++
        val flco = Nxdn.value(data, 2, 6); val options = Nxdn.value(data, 16, 8)
        if (options and PRIVACY != 0) cipher = 1
        if (flco == GROUP_VOICE || flco == UNIT_VOICE) { destination = Nxdn.value(data, 24, 24); source = Nxdn.value(data, 48, 24) }
        return true
    }

    private fun endCall() { cipher = -1; fragNext = 0; misses = 0 }

    private fun flip() {
        inverted = !inverted; polarityFlips++
        embOk = 0; embBad = 0; adjacent = 0; superframes = 0; next = null; endCall()
    }

    companion object {
        const val BAUD = 4800
        /** Dibits in a burst: 54, the 24-dibit middle field (sync or EMB + embedded signalling), 54. */
        const val BURST = 132
        private const val HALF = 54
        private const val SYNC_LEN = 24
        private const val SYNC_END = HALF + SYNC_LEN - 1
        /** Symbols from one burst of a slot to the next (60 ms), and from one slot to the other (30 ms). */
        const val FRAME = 288
        private const val SLOT = 144
        private const val HIST = 8192
        private const val MASK = HIST - 1L
        private const val SYNC_CORR = 0.8
        private const val COAST_CORR = 0.5
        private const val MAX_MISSES = 3
        private const val PRIVACY = 0x40
        private const val GROUP_VOICE = 0
        private const val UNIT_VOICE = 3
        private val LCSS = intArrayOf(1, 3, 3, 2)

        /** Voice syncs (a data sync is the same the other way up): base station, mobile, direct mode slot 1, slot 2. */
        internal val VOICE: Array<IntArray> = arrayOf(
            "131111333113313313113313", "133313311131311113313331", "113111131333131311133333", "133133333111331111311133"
        ).map { p -> IntArray(SYNC_LEN) { if (p[it] == '1') 3 else -3 } }.toTypedArray()
        private val S_SUM = DoubleArray(VOICE.size) { p -> VOICE[p].sum().toDouble() }
        private val S_E = DoubleArray(VOICE.size) { p -> VOICE[p].sumOf { it * it } - S_SUM[p] * S_SUM[p] / SYNC_LEN }

        /** Burst dibit of AMBE frame [f]'s dibit [i]: the second frame is split by the middle field. */
        internal fun voiceDibit(f: Int, i: Int) = when (f) { 0 -> i; 1 -> if (i < 18) 36 + i else 60 + i; else -> 96 + i }

        // Parity-check matrices (16 columns, data bits first, identity last) of Hamming(16,11,4) and QR(16,7,6).
        // dsd-neo's Hamming matrix has its last row's columns 4 and 6 swapped (its syndrome table and ETSI have d0 d2 d5 d6 d8 d9 d10): fixed here.
        internal val HAMMING_H = intArrayOf(
            1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0,
            0, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0,
            0, 0, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0,
            1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0,
            1, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 0, 0, 0, 0, 1
        )
        internal val QR_H = intArrayOf(0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1)

        /** Every codeword (16 bits, first bit highest) of the systematic code with parity-check matrix [h], by data value. */
        internal fun codewords(h: IntArray): IntArray {
            val rows = h.size / 16; val k = 16 - rows
            return IntArray(1 shl k) { data ->
                var cw = data shl rows
                for (r in 0 until rows) {
                    var p = 0
                    for (c in 0 until k) p = p xor ((data shr (k - 1 - c)) and h[r * 16 + c])
                    cw = cw or (p shl (rows - 1 - r))
                }
                cw
            }
        }
        internal val HAMMING = codewords(HAMMING_H)
        internal val QR = codewords(QR_H)

        /** The data value of the codeword nearest [word], or -1 when more than [maxErrors] bits away. */
        internal fun decode(word: Int, code: IntArray, maxErrors: Int): Int {
            var best = -1; var bestD = maxErrors + 1
            for (v in code.indices) { val dd = Integer.bitCount(code[v] xor word); if (dd < bestD) { bestD = dd; best = v } }
            return best
        }

        /** The privacy as a user reads it. */
        fun cipherLabel(c: Int) = when (c) { 0 -> "clear"; 1 -> "encrypted"; else -> "" }
    }
}
