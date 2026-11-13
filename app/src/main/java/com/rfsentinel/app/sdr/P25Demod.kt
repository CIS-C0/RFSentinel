package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P25 Phase 1 control channel, first stage: the RTL-SDR's raw I/Q to dibits.
 *
 * Picks the 12.5 kHz channel [offsetHz] from the tuned centre (mix, integrate-and-dump to 48 kS/s) and turns it into
 * symbols two ways ([mod]):
 * - C4FM: low-pass, FM discriminator, a symbol-long average (the integrate-and-dump C4FM's transmit filter is built for).
 * - LSM / CQPSK (simulcast sites): a root-raised-cosine filter, then the phase moved over one symbol. The same ±45° /
 *   ±135° steps C4FM makes by frequency, but taken from two points a symbol apart, so the amplitude dips between
 *   LSM symbols (where an FM discriminator's phase jumps) don't break it.
 * Each finds the frame sync (0x5575F5FF77FF, 24 symbols of ±3); the sync gives the symbol timing, the level of ±3
 * and the DC offset, and the [FRAME_DIBITS] dibits after it are sliced and handed to [onFrame] (status symbols still
 * in: the frame decoder takes them out). While [onFrame] runs, [soft] holds that frame's symbol values (±1 for ±3) for
 * soft decoding and [lsmFrame] says which path found it.
 * A slow frequency loop keeps the channel centred while the dongle's crystal drifts.
 *
 * [frameDibits] longer than the default takes in a whole voice frame ([P25Voice]). With [onPdu] set, a frame whose NID
 * says data packet goes on being collected to [P25Pdu.PDU_DIBITS] and is handed there too (after [onFrame] had its start). Pure (no Android): unit-tested on
 * a synthetic signal.
 */
class P25Demod(offsetHz: Double, private val frameDibits: Int = FRAME_DIBITS, val mod: Mod = Mod.C4FM,
               private val onFrame: (IntArray) -> Unit) {

    /** Which demodulator(s) run: [BOTH] while it isn't known yet (a frame then comes from one path, or once from each). */
    enum class Mod { C4FM, LSM, BOTH }

    /** Where the channel is, from the tuned centre; the frequency loop moves it while it tracks. */
    var offsetHz = offsetHz; private set
    /** Frame syncs found so far (both paths). */
    val syncs get() = (fm?.syncs ?: 0) + (lsm?.syncs ?: 0)
    /** During [onFrame]: the frame's symbols, scaled so ±3 is ±1 (same length and order as the dibits). */
    var soft = DoubleArray(0); private set
    /** During [onFrame]: true when the LSM path found it. */
    var lsmFrame = false; private set

    // Mixer: a phasor turned each input sample.
    private var pr = 1.0; private var pi = 0.0
    private var stepR = 1.0; private var stepI = 0.0
    private fun setStep() { val w = -2 * PI * offsetHz / SAMPLE_RATE; stepR = cos(w); stepI = sin(w) }
    init { setStep() }

    // Integrate-and-dump to 48 kS/s.
    private var accR = 0.0; private var accI = 0.0; private var accN = 0

    // Channel low-pass at 48 kS/s (C4FM and the frequency loop).
    private val firR = DoubleArray(TAPS.size); private val firI = DoubleArray(TAPS.size); private var firAt = 0

    // Discriminator.
    private var lastR = 0.0; private var lastI = 0.0
    private var afcSum = 0.0; private var afcN = 0

    // C4FM symbol filter: a symbol-long moving average.
    private val box = DoubleArray(SPS); private var boxAt = 0; private var boxSum = 0.0

    // LSM: matched filter and the last symbol's worth of its output.
    private val rrcR = DoubleArray(RRC.size); private val rrcI = DoubleArray(RRC.size); private var rrcAt = 0
    private val backR = DoubleArray(SPS); private val backI = DoubleArray(SPS); private var backAt = 0

    private val fm = if (mod != Mod.LSM) Symbols(false) else null
    private val lsm = if (mod != Mod.C4FM) Symbols(true) else null

    /** Unsigned 8-bit I/Q as the dongle delivers it: [count] complex samples from [buf]. */
    fun feed(buf: ByteArray, count: Int) {
        var k = 0
        for (s in 0 until count) {
            val i = (buf[k++].toInt() and 0xff) - 127.5
            val q = (buf[k++].toInt() and 0xff) - 127.5
            accR += i * pr - q * pi
            accI += i * pi + q * pr
            val nr = pr * stepR - pi * stepI
            pi = pr * stepI + pi * stepR; pr = nr
            if (++accN == DECIM) {
                accN = 0
                channelSample(accR, accI)
                accR = 0.0; accI = 0.0
                // Keeps the phasor on the unit circle (rounding drifts it).
                val m = 1.5 - 0.5 * (pr * pr + pi * pi); pr *= m; pi *= m
            }
        }
    }

    /** One sample of the channel already mixed down and at [CHANNEL_RATE], as [P25Record] saves it. */
    fun feedChannel(r: Double, i: Double) = channelSample(r, i)

    private fun channelSample(r: Double, i: Double) {
        firR[firAt] = r; firI[firAt] = i
        var yr = 0.0; var yi = 0.0; var j = firAt
        for (t in TAPS) { yr += t * firR[j]; yi += t * firI[j]; if (--j < 0) j = TAPS.size - 1 }
        if (++firAt == TAPS.size) firAt = 0
        // FM discriminator: the phase step between samples, in Hz.
        val hz = atan2(yi * lastR - yr * lastI, yr * lastR + yi * lastI) * CHANNEL_RATE / (2 * PI)
        lastR = yr; lastI = yi
        afc(hz)
        if (fm != null) {
            boxSum += hz - box[boxAt]; box[boxAt] = hz; if (++boxAt == SPS) boxAt = 0
            fm.add(boxSum / SPS)
        }
        if (lsm != null) {
            rrcR[rrcAt] = r; rrcI[rrcAt] = i
            var zr = 0.0; var zi = 0.0; j = rrcAt
            for (t in RRC) { zr += t * rrcR[j]; zi += t * rrcI[j]; if (--j < 0) j = RRC.size - 1 }
            if (++rrcAt == RRC.size) rrcAt = 0
            // The phase moved since one symbol ago, on the same Hz scale as the C4FM average (±1800 Hz is ±135°).
            val br = backR[backAt]; val bi = backI[backAt]
            backR[backAt] = zr; backI[backAt] = zi; if (++backAt == SPS) backAt = 0
            lsm.add(atan2(zi * br - zr * bi, zr * br + zi * bi) * SYMBOL_RATE / (2 * PI))
        }
    }

    /** C4FM and LSM both average to zero over random data: what's left is the channel being off centre. */
    private fun afc(hz: Double) {
        afcSum += hz.coerceIn(-3000.0, 3000.0)
        if (++afcN < AFC_SAMPLES) return
        offsetHz += AFC_GAIN * afcSum / afcN
        afcSum = 0.0; afcN = 0
        setStep()
    }

    /** Data packets ([P25Pdu]): the whole frame and its symbol values, from the demodulator's thread. */
    var onPdu: ((IntArray, DoubleArray) -> Unit)? = null

    private class Collect(val start: Long, val dc: Double, val level: Double, size: Int) {
        val dibits = IntArray(size); val soft = DoubleArray(size); var got = 0; var handed = false
        fun put(v: Double) { val x = (v - dc) / level; soft[got] = x.coerceIn(-2.0, 2.0); dibits[got++] = slice(x) }
    }

    /** One demodulator's symbols at [CHANNEL_RATE]: sync search, timing and slicing. */
    private inner class Symbols(private val isLsm: Boolean) {
        var syncs = 0
        private val hist = DoubleArray(HIST); private var n = 0L
        private val collecting = ArrayList<Collect>()
        private var lastSync = Long.MIN_VALUE / 2
        private var best = 0.0; private var bestAt = -1L

        private fun at(back: Int) = hist[((n - back) % HIST).toInt().let { if (it < 0) it + HIST else it }]

        fun add(v: Double) {
            hist[(n % HIST).toInt()] = v
            // Dibits for the frames being collected: one every SPS samples after their sync.
            if (collecting.isNotEmpty()) {
                val it = collecting.iterator()
                while (it.hasNext()) {
                    val c = it.next()
                    if ((n - c.start) % SPS != 0L) continue
                    c.put(v)
                    if (c.got == frameDibits && !c.handed) {
                        c.handed = true; hand(c)
                        if (c.dibits.size == frameDibits || !P25Pdu.isPdu(c.dibits)) { it.remove(); continue }
                    }
                    if (c.got == c.dibits.size) { it.remove(); onPdu?.invoke(c.dibits, c.soft) }
                }
            }
            // Sync search: the last 24 symbols against the pattern, normalized.
            if (n >= SYNC.size * SPS) {
                var dot = 0.0; var energy = 0.0
                for (s in SYNC.indices) {
                    val x = at((SYNC.size - 1 - s) * SPS)
                    dot += x * SYNC[s]; energy += x * x
                }
                val corr = if (energy > 0) dot / sqrt(energy * SYNC_ENERGY) else 0.0
                if (corr > SYNC_CORR && corr > best) { best = corr; bestAt = n }
                // The peak is past: that sample is the sync's last symbol.
                if (bestAt >= 0 && n - bestAt >= SPS / 2) {
                    if (bestAt - lastSync > SYNC.size * SPS) found(bestAt)
                    best = 0.0; bestAt = -1
                }
            }
            n++
        }

        private fun hand(c: Collect) {
            val long = c.dibits.size > frameDibits
            soft = if (long) c.soft.copyOf(frameDibits) else c.soft; lsmFrame = isLsm
            onFrame(if (long) c.dibits.copyOf(frameDibits) else c.dibits)
        }

        private fun found(end: Long) {
            lastSync = end
            var plus = 0.0; var minus = 0.0; var np = 0; var nm = 0
            for (s in SYNC.indices) {
                val x = at((n - end).toInt() + (SYNC.size - 1 - s) * SPS)
                if (SYNC[s] > 0) { plus += x; np++ } else { minus += x; nm++ }
            }
            val hi = plus / np; val lo = minus / nm
            val level = (hi - lo) / 2
            if (level <= 0) return
            syncs++
            val c = Collect(end, (hi + lo) / 2, level, if (onPdu != null) maxOf(frameDibits, P25Pdu.PDU_DIBITS) else frameDibits)
            // Samples already past since the sync's end: catch up those dibits from the history.
            var t = end + SPS
            while (t <= n && c.got < frameDibits) { c.put(at((n - t).toInt())); t += SPS }
            collecting += c
        }
    }

    companion object {
        /** RTL-SDR sample rate for P25: 40 × 48 kS/s, so 10 samples a symbol after decimation. */
        const val SAMPLE_RATE = 1_920_000
        const val DECIM = 40
        const val CHANNEL_RATE = SAMPLE_RATE / DECIM
        const val SYMBOL_RATE = 4_800
        const val SPS = CHANNEL_RATE / SYMBOL_RATE
        /** Dibits kept after a sync: NID and up to three trellis blocks, status symbols included. */
        const val FRAME_DIBITS = 340
        private const val HIST = 1024
        /** Low enough for frames at the edge of range: a false sync is thrown out by the NID's BCH check after it. */
        private const val SYNC_CORR = 0.75
        private const val AFC_SAMPLES = 4_800      // 0.1 s
        private const val AFC_GAIN = 0.3

        /** Frame sync 0x5575F5FF77FF as symbols: dibit 01 is +3, 11 is -3. */
        val SYNC: IntArray = run {
            val bits = 0x5575F5FF77FFL
            IntArray(24) { k -> if (((bits shr (46 - 2 * k)) and 3L) == 1L) 3 else -3 }
        }
        private val SYNC_ENERGY = SYNC.sumOf { it * it }.toDouble()

        /** Symbol (scaled so ±3 is ±1) to dibit: +3 → 01, +1 → 00, -1 → 10, -3 → 11. */
        fun slice(x: Double): Int = when {
            x >= 2.0 / 3 -> 1
            x >= 0 -> 0
            x >= -2.0 / 3 -> 2
            else -> 3
        }

        /** Channel low-pass at 48 kS/s: windowed sinc, cut at [cutHz]. */
        internal fun lowPass(cutHz: Double): DoubleArray {
            val len = 31; val fc = cutHz / CHANNEL_RATE
            val t = DoubleArray(len) { k ->
                val x = k - (len - 1) / 2.0
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.54 - 0.46 * cos(2 * PI * k / (len - 1)))
            }
            val sum = t.sum(); return DoubleArray(len) { t[it] / sum }
        }
        private val TAPS = lowPass(6_250.0)

        /** Root-raised-cosine (roll-off 0.2, 8 symbols long) at 48 kS/s: LSM's receive filter, matched to its transmitter's. */
        internal fun rootRaisedCosine(beta: Double = 0.2, spanSymbols: Int = 8): DoubleArray {
            val len = spanSymbols * SPS + 1
            val t = DoubleArray(len) { k ->
                val x = (k - (len - 1) / 2.0) / SPS // in symbols
                when {
                    x == 0.0 -> 1 - beta + 4 * beta / PI
                    abs(abs(x) - 1 / (4 * beta)) < 1e-9 ->
                        beta / sqrt(2.0) * ((1 + 2 / PI) * sin(PI / (4 * beta)) + (1 - 2 / PI) * cos(PI / (4 * beta)))
                    else -> (sin(PI * x * (1 - beta)) + 4 * beta * x * cos(PI * x * (1 + beta))) / (PI * x * (1 - (4 * beta * x).let { it * it }))
                }
            }
            val sum = t.sum(); return DoubleArray(len) { t[it] / sum }
        }
        private val RRC = rootRaisedCosine()
    }
}
