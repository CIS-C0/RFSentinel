package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * P25 control channel, fourth stage: finds a site's control channel and stays on it.
 *
 * [hunt] sweeps the base-station downlinks (700 MHz 769-775, 800 MHz 851-869) for channels that are on,
 * strongest first, and listens briefly to each with [P25Demod] + [P25Frame]: the first one whose trunking
 * blocks pass their CRC is the control channel. The spectrum peak is where this dongle hears it, its
 * crystal error included, so no frequency correction is needed (the demodulator's loop follows drift).
 * [listen] then stays on it and hands each message on until the channel goes quiet, when the caller hunts again.
 *
 * Times count in samples, not the clock: unit-tested with a simulated dongle.
 */
class P25Hunt(private val sdr: SdrDevice, private val log: (String) -> Unit = {}) {

    data class Candidate(val hz: Long, val snrDb: Float)
    /** A control channel: [hz] where this dongle hears it, [nac] the system's network access code. */
    data class Lock(val hz: Long, val nac: Int)

    /** Channels listened to in the last hunt, for the log. */
    var tried = 0; private set
    /** Good trunking blocks heard while listening, for the log. */
    var blocks = 0L; private set

    private val buf = ByteArray(XFER)

    /**
     * What the decoder made of one channel's frames, for the log: how far each stage got tells a weak
     * signal (few syncs) from a wrong modulation (syncs, no NID) from a decoding fault (NIDs, no good blocks).
     */
    private class Stats {
        var frames = 0; var nids = 0; var tsdu = 0; var good = 0; var bad = 0; var otherNac = 0; var stalls = 0
        val opcodes = java.util.TreeMap<String, Int>()
        fun add(d: P25Frame.Decoded) {
            frames++
            val nid = d.nid ?: return
            nids++
            if (nid.duid == P25Frame.DUID_TSDU) tsdu++
            good += d.tsbks.size; bad += d.badBlocks
            for (t in d.tsbks) opcodes.merge(if (t.mfid > 1) "vendor" else "%02X".format(t.opcode), 1, Int::plus)
        }
        override fun toString() = "$frames frame syncs, $nids NIDs ($tsdu trunking), $good good blocks, $bad failed" +
            (if (otherNac > 0) ", $otherNac from another NAC" else "") + (if (stalls > 0) ", $stalls failed reads" else "")
    }

    private fun band(hz: Long) = if (hz < 800_000_000L) "700 MHz" else "800 MHz"

    /** The control channel heard best here, or null (none on the air, or [stop]). */
    fun hunt(stop: () -> Boolean): Lock? {
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        val plan = plan()
        val scan = scan(sdr, plan, stop) ?: return null
        val ranked = strongest(scan.found)
        val tries = ranked.take(MAX_TRIES)
        // Frequencies and network codes stay off the log (they name the local system); bands and levels say enough.
        log("P25 scan: ${plan.size} steps, tuner didn't lock ${scan.unlocked}, no samples ${scan.short}, ${ranked.size} channels " +
            "${MIN_SNR_DB.toInt()} dB or more above the noise (700 MHz ${ranked.count { it.hz < 800_000_000L }}, " +
            "800 MHz ${ranked.count { it.hz >= 800_000_000L }}), strongest ${tries.joinToString { "%.0f".format(it.snrDb) }} dB")
        tried = 0
        for (c in tries) {
            if (stop()) return null
            tried++
            trial(c, "$tried/${tries.size}", stop)?.let { return it }
        }
        return null
    }

    /** A short listen on [c]: a [Lock] when trunking blocks decode there. */
    private fun trial(c: Candidate, which: String, stop: () -> Boolean): Lock? {
        val head = "P25 try $which: ${band(c.hz)} band, %.0f dB:".format(c.snrDb)
        val center = c.hz - OFFSET_HZ
        if (!sdr.tune(center)) { log("$head the tuner didn't lock"); return null }
        repeat(sdr.settleReads) { sdr.read(buf) }
        val st = Stats()
        val nacs = HashMap<Int, Int>()
        val demod = P25Demod(OFFSET_HZ.toDouble()) { f ->
            val d = P25Frame.decode(f)
            st.add(d)
            if (d.nid != null && d.tsbks.isNotEmpty()) nacs.merge(d.nid.nac, 1, Int::plus)
        }
        var samples = 0L; var fails = 0
        while (st.good < MIN_BLOCKS && samples < TRIAL_S * P25Demod.SAMPLE_RATE) {
            if (stop()) return null
            val n = sdr.read(buf)
            if (n <= 0) {
                st.stalls++
                if (++fails >= STALL_READS) { log("$head $st - the samples stopped"); return null }
                continue
            }
            fails = 0
            demod.feed(buf, n / 2); samples += n / 2
        }
        val drift = "frequency loop moved %+.0f Hz in %.2f s".format(demod.offsetHz - OFFSET_HZ, samples.toDouble() / P25Demod.SAMPLE_RATE)
        if (st.good < MIN_BLOCKS) { log("$head $st, $drift - not a control channel"); return null }
        log("$head $st, $drift - control channel (${nacs.size} NAC${if (nacs.size == 1) "" else "s"} seen)")
        return Lock(center + demod.offsetHz.roundToLong(), nacs.maxBy { it.value }.key)
    }

    /**
     * Stays on [lock], handing each message to [onMsg]: false when [stop] said so, true when the channel
     * went quiet for [LOST_S] (hunt again). Logs what it decoded every [LOG_S]. [detour] true: it tuned away for a
     * moment (an input check), so the control channel is tuned again.
     */
    fun listen(lock: Lock, stop: () -> Boolean, detour: () -> Boolean = { false }, onMsg: (P25Tsbk.Msg) -> Unit): Boolean {
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        if (!sdr.tune(lock.hz - OFFSET_HZ)) { log("P25 listen: the tuner didn't lock"); return true }
        sdr.resetBuffer()
        var samples = 0L; var lastGood = 0L; var fails = 0; var lastLog = 0L
        var st = Stats()
        val kinds = java.util.TreeMap<String, Int>()
        val demod = P25Demod(OFFSET_HZ.toDouble()) { f ->
            val d = P25Frame.decode(f)
            if (d.nid != null && d.nid.nac != lock.nac) { st.frames++; st.otherNac++; return@P25Demod }
            st.add(d)
            if (d.tsbks.isNotEmpty()) lastGood = samples
            blocks += d.tsbks.size
            for (t in d.tsbks) P25Tsbk.parse(t)?.let { kinds.merge(it.javaClass.simpleName, 1, Int::plus); onMsg(it) }
        }
        fun report(why: String) {
            log("P25 $why: $st, block types ${st.opcodes.entries.joinToString(" ") { "${it.key}x${it.value}" }.ifEmpty { "none" }}, " +
                "messages ${kinds.entries.joinToString(" ") { "${it.key}x${it.value}" }.ifEmpty { "none" }}, " +
                "frequency loop at %+.0f Hz".format(demod.offsetHz - OFFSET_HZ))
            st = Stats(); kinds.clear()
        }
        while (!stop()) {
            if (detour()) {
                if (!sdr.tune(lock.hz - OFFSET_HZ)) { log("P25 listen: the tuner didn't lock after an input check"); return true }
                sdr.resetBuffer()
                repeat(sdr.settleReads) { sdr.read(buf) }
                lastGood = samples // the time away doesn't count as the channel going quiet
                continue
            }
            val n = sdr.read(buf)
            if (n <= 0) { st.stalls++; if (++fails % STALL_READS == 0) sdr.resetBuffer(); continue }
            fails = 0
            demod.feed(buf, n / 2); samples += n / 2
            if (samples - lastGood > LOST_S * P25Demod.SAMPLE_RATE) {
                report("control channel quiet for ${LOST_S.toInt()} s (no good block)")
                return true
            }
            if (samples - lastLog >= LOG_S * P25Demod.SAMPLE_RATE) { lastLog = samples; report("last ${LOG_S.toInt()} s") }
        }
        report("listen stopped")
        return false
    }

    companion object {
        /** Base-station downlinks: 700 MHz narrowband and 800 MHz public safety. */
        val BANDS = listOf(769_000_000L..775_000_000L, 851_000_000L..869_000_000L)
        private const val XFER = 16_384
        /** ~70 ms of samples per scan step: always-on channels stand out, short bursts average down. */
        private const val SCAN_READS = 16
        /** The channel this far above the tuned centre, clear of the receiver's DC spike. */
        private const val OFFSET_HZ = 250_000L
        /** A control channel sends a frame every ~75 ms: this long without two good blocks, it isn't one. */
        private const val TRIAL_S = 0.8
        private const val MIN_BLOCKS = 2
        private const val MAX_TRIES = 12
        const val LOST_S = 3.0
        private const val LOG_S = 60.0
        private const val STALL_READS = 8
        private const val MIN_SNR_DB = 10.0
        private const val CHANNEL_HALF_HZ = 5_000.0
        private const val MERGE_HZ = 6_250L

        /** Tuned centres a little closer than the usable width: every channel is seen twice, once well away from DC. */
        private const val STEP_HZ = 750_000L

        internal fun plan(bands: List<LongRange> = BANDS): List<Long> =
            bands.flatMap { b -> generateSequence(b.first - STEP_HZ / 2) { it + STEP_HZ }.takeWhile { it <= b.last + STEP_HZ / 2 }.toList() }

        internal class Scan(val found: List<Candidate>, val unlocked: Int, val short: Int)

        /** Looks at each tuned centre of [plan] for channels that are on; null when [stop] said so. */
        internal fun scan(sdr: SdrDevice, plan: List<Long>, stop: () -> Boolean): Scan? {
            val buf = ByteArray(XFER)
            val found = ArrayList<Candidate>()
            val iq = ByteArray(XFER * SCAN_READS)
            var unlocked = 0; var short = 0
            for (center in plan) {
                if (stop()) return null
                if (!sdr.tune(center)) { unlocked++; continue }
                repeat(sdr.settleReads) { sdr.read(buf) }
                var got = 0
                for (i in 0 until SCAN_READS) {
                    val n = sdr.read(buf)
                    if (n <= 0) break
                    System.arraycopy(buf, 0, iq, got, n); got += n
                }
                if (got >= 2 * 1024) found += candidates(center, RadioWatch.powerSpectrum(iq, got / 2), sdr.sampleRate) else short++
            }
            return Scan(found, unlocked, short)
        }

        /** Channel-wide peaks [MIN_SNR_DB] above the noise in one tuned chunk, at their power-weighted centre. */
        internal fun candidates(center: Long, psd: FloatArray, sampleRate: Int): List<Candidate> {
            val n = psd.size; val mid = n / 2
            val binHz = sampleRate.toDouble() / n
            val p = DoubleArray(n) { 10.0.pow(psd[it] / 10.0) }
            val noise = p.sorted()[mid]
            val half = (CHANNEL_HALF_HZ / binHz).toInt()
            val usable = (RadioWatch.USABLE_HZ / binHz).toInt()
            val guard = (RadioWatch.DC_GUARD_HZ / binHz).toInt() + half
            val win = DoubleArray(n) { k -> if (k < half || k >= n - half) 0.0 else (k - half..k + half).sumOf { p[it] } / (2 * half + 1) }
            val out = ArrayList<Candidate>()
            for (k in mid - usable + 2 * half..mid + usable - 2 * half) {
                if (abs(k - mid) <= guard) continue
                val snr = 10 * log10(win[k] / noise)
                if (snr < MIN_SNR_DB) continue
                // The highest point within a channel either side (the first of equal ones).
                if ((k - 2 * half until k).any { win[it] >= win[k] } || (k + 1..k + 2 * half).any { win[it] > win[k] }) continue
                var w = 0.0; var at = 0.0
                for (j in k - half..k + half) { val e = (p[j] - noise).coerceAtLeast(0.0); w += e; at += e * (j - mid) }
                out += Candidate(center + (at / w * binHz).roundToLong(), snr.toFloat())
            }
            return out
        }

        /** Strongest first, each channel once (seen from two tuned centres). */
        internal fun strongest(all: List<Candidate>): List<Candidate> {
            val out = ArrayList<Candidate>()
            for (c in all.sortedByDescending { it.snrDb }) if (out.none { abs(it.hz - c.hz) <= MERGE_HZ }) out += c
            return out
        }
    }
}
