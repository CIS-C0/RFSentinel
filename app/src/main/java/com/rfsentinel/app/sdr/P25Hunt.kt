package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * P25 control channel, fourth stage: finds a site's control channel and stays on it.
 *
 * [hunt] first tries the channels it was given (the last ones that worked, the site's own alternates), then sweeps the
 * base-station downlinks of the ticked [bands] for channels that are on and listens briefly to each with [P25Demod] +
 * [P25Frame]: the first one whose trunking blocks pass their CRC is the control channel. Order: strong before weak,
 * channels on every scan before ones that come and go, ones that already turned out not to be P25 last. Each listen
 * runs the C4FM and the LSM (simulcast) demodulators side by side, listens longer once trunking frames show up, and
 * tries again with less gain when the tuner clips, or more when a control channel was there but too weak. The
 * spectrum peak is where this dongle hears it, its crystal error included, so no frequency correction is needed (the
 * demodulator's loop follows drift).
 * [listen] then stays on it and hands each message on until the channel goes quiet, when the caller hunts again.
 * [identify] and [inbound] are short looks at other channels: is that transmission P25, and which radios are calling the site.
 *
 * Times count in samples, not the clock: unit-tested with a simulated dongle.
 */
class P25Hunt(private val sdr: SdrDevice, private val readAhead: Boolean = false, private val logAll: () -> Boolean = { false },
              private val bands: () -> List<Band> = { DEFAULT_BANDS }, private val gainTenths: Int = DEFAULT_GAIN,
              private val log: (String) -> Unit = {}) {

    /** A peak in the scan: [hits] how many tuned chunks it showed in (each channel is seen from two or three). */
    data class Candidate(val hz: Long, val snrDb: Float, val hits: Int = 1)
    /** A control channel: [hz] where this dongle hears it, [nac] the system's network access code, [lsm] it decodes as
     *  LSM / CQPSK (a simulcast site), [gainTenths] the gain it decoded at. */
    data class Lock(val hz: Long, val nac: Int, val lsm: Boolean = false, val gainTenths: Int = DEFAULT_GAIN)
    /** Where to look for control channels: [key] for Settings, [label] for the log. */
    data class Band(val key: String, val label: String, val range: LongRange)
    /** What [identify] made of a signal: P25 with network code [nac]; [trunking] control-channel frames, [voice] voice
     *  frames (a call, or a conventional channel), [lsm] it decoded better as LSM. */
    data class Heard(val nac: Int, val trunking: Boolean, val voice: Boolean, val lsm: Boolean)

    /** Channels listened to in the last hunt, for the log. */
    var tried = 0; private set
    /** Good trunking blocks heard while listening, for the log. */
    var blocks = 0L; private set
    /** While listening: the control channel's carrier as this dongle hears it, after the last good block (0: none yet). */
    @Volatile var carrierHz = 0L; private set
    /** Why the last hunt found nothing, for the status line ("a control channel too weak to decode here"); null after a lock. */
    @Volatile var hint: String? = null; private set
    /** The last hunt heard a control channel it couldn't decode yet: worth looking again soon (an antenna being moved). */
    @Volatile var weakSeen = false; private set

    private val buf = ByteArray(XFER)
    private var hunts = 0
    /** Channels that turned out not to be control channels, with the hunt that tried them: tried last for [REJECT_HUNTS] hunts. */
    private val rejected = HashMap<Long, Int>()
    /** The channels of the last few scans: a control channel is on every one of them. */
    private val recent = ArrayDeque<List<Long>>()

    private fun where(hz: Long) = if (logAll()) "%.4f MHz".format(java.util.Locale.US, hz / 1e6) else bandLabel(hz, bands())

    /** The control channel heard best here, or null (none on the air, or [stop]). [first]: channels to try before scanning. */
    fun hunt(first: List<Long> = emptyList(), stop: () -> Boolean): Lock? {
        hunts++
        hint = null; weakSeen = false
        rejected.entries.removeAll { hunts - it.value >= REJECT_HUNTS }
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        sdr.setGain(gainTenths)
        tried = 0
        var weak = 0; var voice = 0
        fun attempt(c: Candidate, which: String): Lock? {
            tried++
            val o = attempts(c, which, stop) ?: return null
            if (o.weak) weak++ else if (o.voice) voice++
            if (o.lock == null && o.answered && !o.weak) rejected[c.hz] = hunts
            return o.lock
        }
        // The channels that worked before or the site announced: no scan when one of them is still on the air.
        val known = first.distinct().take(MAX_FIRST)
        for ((i, hz) in known.withIndex()) {
            if (stop()) return null
            attempt(Candidate(hz, Float.NaN), "known ${i + 1}/${known.size}")?.let { return it }
        }
        if (stop()) return null
        val bands = bands()
        val plan = plan(bands.map { it.range })
        val scan = scan(sdr, plan, stop, WEAK_SNR_DB) ?: return null
        val all = strongest(scan.found.filter { c -> bands.any { c.hz in it.range } })
            .filter { c -> known.none { abs(it - c.hz) <= MERGE_HZ } }
        val ranked = rank(all, { hz -> rejected.keys.any { abs(it - hz) <= MERGE_HZ } }) { c -> steady(c) }
        recent.addLast(all.map { it.hz }); while (recent.size > RECENT_SCANS) recent.removeFirst()
        val tries = ranked.take(MAX_TRIES)
        val strong = all.filter { it.snrDb >= MIN_SNR_DB }
        // Frequencies and network codes stay off the log (they name the local system); bands and levels say enough.
        log("P25 scan: ${plan.size} steps, tuner didn't lock ${scan.unlocked}, no samples ${scan.short}, ${strong.size} channels " +
            "${MIN_SNR_DB.toInt()} dB or more above the noise (${bands.joinToString { b -> "${b.label} ${strong.count { it.hz in b.range }}" }}), " +
            "${all.size - strong.size} weaker ones from ${WEAK_SNR_DB.toInt()} dB, ${tries.count { c -> rejected.keys.any { abs(it - c.hz) <= MERGE_HZ } }} " +
            "of the tries not P25 last time, strongest ${levels(tries)}, highest point %.0f dB".format(scan.peakDb))
        if (logAll()) log("P25 scan heard: ${heard(all)}")
        for ((i, c) in tries.withIndex()) {
            if (stop()) return null
            attempt(c, "${i + 1}/${tries.size}")?.let { return it }
        }
        weakSeen = weak > 0
        hint = when {
            weak > 0 -> "a control channel is here but too weak to decode · try a better antenna or spot"
            all.isEmpty() -> "nothing on the air in the P25 bands · check the antenna" +
                (if (bands.any { it.range.first < 300_000_000L }) " (VHF needs a longer one than the stock antenna)" else "")
            voice > 0 -> "P25 radios heard, but no control channel (a conventional system)"
            else -> "no control channel heard here"
        }
        log("P25 hunt: $tried tried, $weak control channels too weak to decode, $voice P25 voice channels without trunking, " +
            "${tried - weak - voice} not control channels or not heard")
        return null
    }

    /** How sure a peak is to be always on: seen from two tuned chunks, and in the scans before (0-3). */
    private fun steady(c: Candidate) =
        (if (c.hits >= 2) 1 else 0) + recent.count { l -> l.any { abs(it - c.hz) <= MERGE_HZ } }.coerceAtMost(2)

    /** [answered]: the tuner locked and samples came (a verdict on the channel, not on the hardware). */
    private class Outcome(val lock: Lock?, val weak: Boolean, val voice: Boolean, val clip: Double, val answered: Boolean)

    /** One channel: a listen, and once more with less gain when the tuner clipped, or more when the control channel was too weak. */
    private fun attempts(c: Candidate, which: String, stop: () -> Boolean): Outcome? {
        val o = trial(c, which, stop, gainTenths) ?: return null
        if (o.lock != null || !o.answered) return o
        val retry = when {
            o.clip > CLIP_LIMIT -> lowerGain(gainTenths)
            o.weak && o.clip < QUIET_CLIP -> higherGain(gainTenths)
            else -> null
        }?.takeIf { it != gainTenths } ?: return o
        log("P25 try $which: " + (if (o.clip > CLIP_LIMIT) "the tuner clipped (%.1f%% of samples at full scale)".format(o.clip * 100)
            else "trunking heard but not decoded") + ", again at %.1f dB gain".format(retry / 10.0))
        val again = trial(c, "$which again", stop, retry).also { sdr.setGain(gainTenths) } ?: return null
        // Too weak once stays too weak: a worse second listen mustn't mark a real control channel "not P25" for later hunts.
        return if (o.weak && again.lock == null && !again.weak) Outcome(null, true, again.voice, again.clip, true) else again
    }

    /** A short listen on [c]: a [Lock] when trunking blocks decode there. Null when [stop] said so. */
    private fun trial(c: Candidate, which: String, stop: () -> Boolean, gain: Int): Outcome? {
        val head = "P25 try $which: ${where(c.hz)}" + (if (c.snrDb.isNaN()) " (known)" else ", %.0f dB".format(c.snrDb)) + ":"
        val center = c.hz - OFFSET_HZ
        sdr.setGain(gain)
        if (!sdr.tune(center)) { log("$head the tuner didn't lock"); return Outcome(null, false, false, 0.0, false) }
        repeat(sdr.settleReads) { sdr.read(buf) }
        val look = look(sdr, buf, TRIAL_S, LONG_TRIAL_S, stop) { it.good >= MIN_BLOCKS } ?: return null
        val lsm = look.lsm.good > look.fm.good
        val st = if (lsm) look.lsm else look.fm
        val stats = "C4FM ${look.fm}; LSM ${look.lsm}"
        if (look.stalled) { log("$head $stats - the samples stopped"); return Outcome(null, false, false, look.clip, false) }
        val drift = "frequency loop moved %+.0f Hz in %.2f s".format(look.offsetHz - OFFSET_HZ, look.seconds) +
            (if (look.clip > 0.0005) ", %.1f%% clipped".format(look.clip * 100) else "")
        val trunking = look.fm.tsdu + look.lsm.tsdu > 0
        val voice = look.fm.voice + look.lsm.voice > 0
        if (st.good < MIN_BLOCKS) {
            // Trunking NIDs but no clean block: a control channel all the same, only too weak here to read.
            log("$head $stats, $drift - " + when {
                trunking -> "a control channel, too weak to decode"
                voice -> "P25 voice (a call or a conventional channel), not a control channel"
                else -> "not a control channel"
            })
            return Outcome(null, trunking, voice, look.clip, true)
        }
        val nacs = st.blockNacs
        log("$head $stats, $drift - control channel${if (lsm) " (LSM, a simulcast site)" else ""} (${nacs.size} NAC${if (nacs.size == 1) "" else "s"} seen" +
            (if (logAll()) ": " + nacs.keys.joinToString { "%03X".format(it) } else "") + ")")
        return Outcome(Lock(center + look.offsetHz.roundToLong(), nacs.maxBy { it.value }.key, lsm, gain), false, false, look.clip, true)
    }

    /**
     * Stays on [lock], handing each message to [onMsg]: false when [stop] said so, true when the channel
     * went quiet for [LOST_S] (hunt again). Logs what it decoded every [LOG_S]. [detour] true: it tuned away for a
     * moment (an input check), so the control channel is tuned again. With [readAhead] a reader thread keeps a read
     * waiting on the dongle while a block is decoded (no samples lost in between); it is stopped around each detour,
     * which is then only asked for when [detourDue].
     */
    fun listen(lock: Lock, stop: () -> Boolean, detour: () -> Boolean = { false }, detourDue: () -> Boolean = { true },
               onMsg: (P25Tsbk.Msg) -> Unit): Boolean {
        var ahead: ReadAhead? = null
        try { return listen(lock, stop, detour, detourDue, onMsg, { ahead }, { ahead?.close(); ahead = if (it) ReadAhead(sdr, buf.size) else null }) }
        finally { ahead?.close() }
    }

    private fun listen(lock: Lock, stop: () -> Boolean, detour: () -> Boolean, detourDue: () -> Boolean, onMsg: (P25Tsbk.Msg) -> Unit,
                       ahead: () -> ReadAhead?, setAhead: (Boolean) -> Unit): Boolean {
        hint = null
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        var gain = lock.gainTenths
        sdr.setGain(gain)
        if (!sdr.tune(lock.hz - OFFSET_HZ)) { log("P25 listen: the tuner didn't lock"); return true }
        sdr.resetBuffer()
        var samples = 0L; var lastGood = 0L; var fails = 0; var lastLog = 0L
        var clipped = 0L; var clipFrom = 0L
        var st = Stats()
        val kinds = java.util.TreeMap<String, Int>()
        carrierHz = 0L
        var good = false
        lateinit var demod: P25Demod
        demod = P25Demod(OFFSET_HZ.toDouble(), mod = if (lock.lsm) P25Demod.Mod.LSM else P25Demod.Mod.C4FM) { f ->
            val d = P25Frame.decode(f, demod.soft)
            if (d.nid != null && d.nid.nac != lock.nac) { st.frames++; st.otherNac++; return@P25Demod }
            st.add(d)
            if (d.tsbks.isNotEmpty()) { lastGood = samples; good = true }
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
            if (!readAhead || ahead() == null || detourDue()) {
                setAhead(false) // the detour tunes and reads the dongle itself
                if (detour()) {
                    sdr.setGain(gain)
                    if (!sdr.tune(lock.hz - OFFSET_HZ)) { log("P25 listen: the tuner didn't lock after an input check"); return true }
                    sdr.resetBuffer()
                    repeat(sdr.settleReads) { sdr.read(buf) }
                    lastGood = samples // the time away doesn't count as the channel going quiet
                    continue
                }
                if (readAhead) setAhead(true)
            }
            val n = ahead()?.read(buf) ?: sdr.read(buf)
            if (n <= 0) {
                st.stalls++
                if (++fails % STALL_READS == 0) sdr.resetBuffer()
                if (fails >= STALL_READS * 4) { report("samples stopped"); return true } // a dead stream never reaches LOST_S
                continue
            }
            fails = 0
            clipped += clips(buf, n)
            demod.feed(buf, n / 2); samples += n / 2
            // The tuner overloaded (a strong transmitter close by): less gain, for as long as this listen lasts.
            if (samples - clipFrom >= P25Demod.SAMPLE_RATE * CLIP_CHECK_S) {
                val frac = clipped.toDouble() / (2 * (samples - clipFrom))
                val lower = lowerGain(gain)
                if (frac > CLIP_LIMIT && lower != gain) {
                    log("P25 listen: the tuner clipped (%.1f%% of samples at full scale), gain down to %.1f dB".format(frac * 100, lower / 10.0))
                    gain = lower; sdr.setGain(gain)
                }
                clipped = 0; clipFrom = samples
            }
            // The frequency loop is on the carrier only while blocks decode.
            if (good) { good = false; carrierHz = lock.hz - OFFSET_HZ + demod.offsetHz.roundToLong() }
            if (samples - lastGood > LOST_S * P25Demod.SAMPLE_RATE) {
                report("control channel quiet for ${LOST_S.toInt()} s (no good block)")
                return true
            }
            if (samples - lastLog >= LOG_S * P25Demod.SAMPLE_RATE) { lastLog = samples; report("last ${LOG_S.toInt()} s") }
        }
        report("listen stopped")
        return false
    }

    /**
     * What the decoder made of one channel's frames, for the log: how far each stage got tells a weak
     * signal (few syncs) from a wrong modulation (syncs, no NID) from a decoding fault (NIDs, no good blocks).
     */
    internal class Stats {
        var frames = 0; var nids = 0; var tsdu = 0; var voice = 0; var good = 0; var bad = 0; var otherNac = 0; var stalls = 0
        val opcodes = java.util.TreeMap<String, Int>()
        /** Every NID's network code, and the ones with good trunking blocks. */
        val nacs = HashMap<Int, Int>(); val blockNacs = HashMap<Int, Int>()
        fun add(d: P25Frame.Decoded) {
            frames++
            val nid = d.nid ?: return
            nids++
            nacs.merge(nid.nac, 1, Int::plus)
            if (nid.duid == P25Frame.DUID_TSDU) tsdu++
            if (nid.duid in VOICE_DUIDS) voice++
            good += d.tsbks.size; bad += d.badBlocks
            if (d.tsbks.isNotEmpty()) blockNacs.merge(nid.nac, d.tsbks.size, Int::plus)
            for (t in d.tsbks) opcodes.merge(if (t.mfid > 1) "vendor" else "%02X".format(t.opcode), 1, Int::plus)
        }
        override fun toString() = "$frames frame syncs, $nids NIDs ($tsdu trunking${if (voice > 0) ", $voice voice" else ""}), $good good blocks, $bad failed" +
            (if (otherNac > 0) ", $otherNac from another NAC" else "") + (if (stalls > 0) ", $stalls failed reads" else "")
    }

    companion object {
        val BAND_700 = Band("700", "700 MHz", 769_000_000L..775_000_000L)
        val BAND_800 = Band("800", "800 MHz", 851_000_000L..869_000_000L)
        /**
         * Base-station downlinks P25 runs on in North America. 700 / 800 MHz (public safety's own) by default; the rest
         * are shared with other users, so they take longer to search and are ticked in Settings.
         */
        val ALL_BANDS = listOf(BAND_700, BAND_800,
            Band("900", "900 MHz", 935_000_000L..941_000_000L),
            Band("vhf", "VHF", 136_000_000L..174_000_000L),
            Band("uhf_fed", "UHF federal", 380_000_000L..430_000_000L),
            Band("uhf", "UHF", 450_000_000L..470_000_000L),
            Band("tband", "T-band", 470_000_000L..512_000_000L))
        val DEFAULT_BANDS = listOf(BAND_700, BAND_800)
        val DEFAULT_KEYS = DEFAULT_BANDS.map { it.key }.toSet()
        fun bandsFor(keys: Set<String>) = ALL_BANDS.filter { it.key in keys }.ifEmpty { DEFAULT_BANDS }

        /** The receiver's gain for P25, in tenths of a dB. */
        const val DEFAULT_GAIN = 297
        private const val XFER = 16_384
        /** ~70 ms of samples per scan step: always-on channels stand out, short bursts average down. */
        private const val SCAN_READS = 16
        /** The channel this far above the tuned centre, clear of the receiver's DC spike. */
        private const val OFFSET_HZ = 250_000L
        /** A control channel sends a frame every ~75 ms: this long without two good blocks, it isn't one... */
        private const val TRIAL_S = 0.8
        /** ...unless trunking frames showed up: then this long, for blocks to get through a weak or fading signal. */
        private const val LONG_TRIAL_S = 3.0
        private const val MIN_BLOCKS = 2
        private const val MAX_TRIES = 16
        /** Known channels tried before a scan at most. */
        private const val MAX_FIRST = 6
        const val LOST_S = 3.0
        private const val LOG_S = 60.0
        private const val STALL_READS = 8
        private const val MIN_SNR_DB = 10.0
        /** Channels this far above the noise are tried too, after the stronger ones. */
        private const val WEAK_SNR_DB = 6.0
        /** Each "always on" sign counts as this much more signal when ranking. */
        private const val STEADY_DB = 3f
        private const val RECENT_SCANS = 3
        /** A channel that wasn't P25 goes to the back of the line for this many hunts (~10+ minutes). */
        private const val REJECT_HUNTS = 10
        private const val CHANNEL_HALF_HZ = 5_000.0
        private const val MERGE_HZ = 6_250L
        /** More samples than this at full scale: the tuner is overloaded. */
        private const val CLIP_LIMIT = 0.01
        /** Fewer than this: there's room for more gain. */
        private const val QUIET_CLIP = 0.0001
        private const val CLIP_CHECK_S = 2
        /** Frame types that carry voice: header, LDU1, LDU2, terminators. */
        private val VOICE_DUIDS = setOf(0x0, 0x3, 0x5, 0xA, 0xF)

        /** Tuned centres a little closer than the usable width: every channel is seen twice, once well away from DC. */
        private const val STEP_HZ = 750_000L

        /** "800 MHz": the ticked band [hz] is in, for the log. */
        internal fun bandLabel(hz: Long, bands: List<Band>) = (bands.firstOrNull { hz in it.range } ?: ALL_BANDS.firstOrNull { hz in it.range })
            ?.label?.let { "$it band" } ?: "another band"

        /** The edge chunks see past the bands (870 MHz and up is cell-phone downlink): only peaks inside count. */
        internal fun inBands(hz: Long, bands: List<Band> = DEFAULT_BANDS) = bands.any { hz in it.range }

        internal fun plan(bands: List<LongRange> = DEFAULT_BANDS.map { it.range }): List<Long> =
            bands.flatMap { b -> generateSequence(b.first - STEP_HZ / 2) { it + STEP_HZ }.takeWhile { it <= b.last + STEP_HZ / 2 }.toList() }

        /** Next gain step at least 10 dB down / up (the tuner's own steps), or the same when there is none. */
        internal fun lowerGain(tenths: Int) = AdsbGain.STEPS.lastOrNull { it <= tenths - 100 } ?: AdsbGain.STEPS.first()
        internal fun higherGain(tenths: Int) = AdsbGain.STEPS.firstOrNull { it >= tenths + 100 } ?: AdsbGain.STEPS.last()

        /** I and Q bytes at full scale in the first [n] bytes of [b]. */
        internal fun clips(b: ByteArray, n: Int): Int { var c = 0; for (i in 0 until n) { val v = b[i].toInt() and 0xff; if (v == 0 || v == 255) c++ }; return c }

        /**
         * Try order: channels that weren't P25 last time at the back, then the weak ones, and within each, by strength
         * with [STEADY_DB] more per sign of being always on ([steady]).
         */
        internal fun rank(all: List<Candidate>, rejected: (Long) -> Boolean, steady: (Candidate) -> Int): List<Candidate> =
            all.sortedWith(compareBy<Candidate>({ rejected(it.hz) }, { it.snrDb < MIN_SNR_DB })
                .thenByDescending { it.snrDb + STEADY_DB * steady(it) })

        /** [peakDb]: the highest bin above its chunk's noise, even under the threshold, so a log tells a dead antenna from a quiet band. */
        internal class Scan(val found: List<Candidate>, val unlocked: Int, val short: Int, val peakDb: Float = 0f)

        /** Looks at each tuned centre of [plan] for channels that are on, [minSnrDb] or more; null when [stop] said so. */
        internal fun scan(sdr: SdrDevice, plan: List<Long>, stop: () -> Boolean, minSnrDb: Double = MIN_SNR_DB,
                           centreHalfHz: Double = CHANNEL_HALF_HZ): Scan? {
            val buf = ByteArray(XFER)
            val found = ArrayList<Candidate>()
            val iq = ByteArray(XFER * SCAN_READS)
            var unlocked = 0; var short = 0; var peak = 0f
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
                if (got >= 2 * 1024) {
                    val psd = RadioWatch.powerSpectrum(iq, got / 2)
                    found += candidates(center, psd, sdr.sampleRate, minSnrDb, centreHalfHz)
                    peak = maxOf(peak, peakAboveNoise(psd, sdr.sampleRate))
                } else short++
            }
            return Scan(found, unlocked, short, peak)
        }

        /** Channel-wide peaks [minSnrDb] above the noise in one tuned chunk, at their power-weighted centre over [centreHalfHz] either side
         *  (wider than a channel for a signal like a two-tone pager, whose peaks sit at its tones). */
        internal fun candidates(center: Long, psd: FloatArray, sampleRate: Int, minSnrDb: Double = MIN_SNR_DB,
                                centreHalfHz: Double = CHANNEL_HALF_HZ): List<Candidate> {
            val n = psd.size; val mid = n / 2
            val binHz = sampleRate.toDouble() / n
            val p = DoubleArray(n) { 10.0.pow(psd[it] / 10.0) }
            val noise = p.sorted()[mid]
            val half = (CHANNEL_HALF_HZ / binHz).toInt()
            val centre = (centreHalfHz / binHz).toInt()
            val usable = (RadioWatch.USABLE_HZ / binHz).toInt()
            val guard = (RadioWatch.DC_GUARD_HZ / binHz).toInt() + half
            val win = DoubleArray(n) { k -> if (k < half || k >= n - half) 0.0 else (k - half..k + half).sumOf { p[it] } / (2 * half + 1) }
            val out = ArrayList<Candidate>()
            for (k in mid - usable + 2 * half..mid + usable - 2 * half) {
                if (abs(k - mid) <= guard) continue
                val snr = 10 * log10(win[k] / noise)
                if (snr < minSnrDb) continue
                // The highest point within a channel either side (the first of equal ones).
                if ((k - 2 * half until k).any { win[it] >= win[k] } || (k + 1..k + 2 * half).any { win[it] > win[k] }) continue
                var w = 0.0; var at = 0.0
                for (j in maxOf(0, k - centre)..minOf(n - 1, k + centre)) { val e = (p[j] - noise).coerceAtLeast(0.0); w += e; at += e * (j - mid) }
                if (w <= 0.0) continue
                out += Candidate(center + (at / w * binHz).roundToLong(), snr.toFloat())
            }
            return out
        }

        /** "17, 16 dB", or "none" when nothing stood out (it read "strongest  dB"). */
        internal fun levels(tries: List<Candidate>) = if (tries.isEmpty()) "none" else tries.joinToString { "%.0f".format(it.snrDb) } + " dB"

        /** "155.4750 MHz 24 dB, ...": the strongest [max], for "Log everything the RTL-SDR hears". */
        internal fun heard(found: List<Candidate>, max: Int = 40): String {
            val top = strongest(found)
            return if (top.isEmpty()) "nothing ${MIN_SNR_DB.toInt()} dB above the noise" else
                top.take(max).joinToString { "%.4f MHz %.0f dB".format(java.util.Locale.US, it.hz / 1e6, it.snrDb) } +
                    (if (top.size > max) " and ${top.size - max} weaker" else "")
        }

        /** The highest bin of the usable middle above the chunk's median, the receiver's own centre spike left out. */
        internal fun peakAboveNoise(psd: FloatArray, sampleRate: Int): Float {
            val n = psd.size; val mid = n / 2; val binHz = sampleRate.toDouble() / n
            val usable = (RadioWatch.USABLE_HZ / binHz).toInt(); val guard = (RadioWatch.DC_GUARD_HZ / binHz).toInt() + 1
            var m = Float.NEGATIVE_INFINITY
            for (k in maxOf(0, mid - usable) until minOf(n, mid + usable)) if (abs(k - mid) > guard && psd[k] > m) m = psd[k]
            return if (m == Float.NEGATIVE_INFINITY) 0f else m - psd.sorted()[mid]
        }

        /** Strongest first, each channel once (seen from two tuned centres: [Candidate.hits] counts them). */
        internal fun strongest(all: List<Candidate>, mergeHz: Long = MERGE_HZ): List<Candidate> {
            val out = ArrayList<Candidate>()
            for (c in all.sortedByDescending { it.snrDb }) {
                val i = out.indexOfFirst { abs(it.hz - c.hz) <= mergeHz }
                if (i < 0) out += c else out[i] = out[i].copy(hits = out[i].hits + 1)
            }
            return out
        }

        /** What [look] heard: each demodulator's frames, where the frequency loop ended, how long, the share of clipped samples. */
        private class Look(val fm: Stats, val lsm: Stats, val offsetHz: Double, val seconds: Double, val clip: Double, val stalled: Boolean)

        /**
         * Listens where [sdr] is tuned (the channel [OFFSET_HZ] above) with both demodulators, until [enough] for one of
         * them or [seconds] (stretched to [longer] once trunking frames show up). Null when [stop] said so.
         */
        private fun look(sdr: SdrDevice, buf: ByteArray, seconds: Double, longer: Double, stop: () -> Boolean, enough: (Stats) -> Boolean): Look? {
            val fm = Stats(); val lsm = Stats()
            lateinit var demod: P25Demod
            demod = P25Demod(OFFSET_HZ.toDouble(), mod = P25Demod.Mod.BOTH) { f ->
                (if (demod.lsmFrame) lsm else fm).add(P25Frame.decode(f, demod.soft))
            }
            var samples = 0L; var fails = 0; var clipped = 0L
            var limit = (seconds * P25Demod.SAMPLE_RATE).toLong()
            fun done(stalled: Boolean) = Look(fm, lsm, demod.offsetHz, samples.toDouble() / P25Demod.SAMPLE_RATE,
                if (samples > 0) clipped.toDouble() / (2 * samples) else 0.0, stalled)
            while (!enough(fm) && !enough(lsm) && samples < limit) {
                if (stop()) return null
                val n = sdr.read(buf)
                if (n <= 0) {
                    fm.stalls++
                    if (++fails >= STALL_READS) return done(true)
                    continue
                }
                fails = 0
                clipped += clips(buf, n)
                demod.feed(buf, n / 2); samples += n / 2
                if (fm.tsdu + lsm.tsdu > 0) limit = maxOf(limit, (longer * P25Demod.SAMPLE_RATE).toLong())
            }
            return done(false)
        }

        /**
         * Is the signal at [hz] P25? Listens up to [seconds] (the SDR set to [P25Demod.SAMPLE_RATE] by the caller) and
         * answers once two frame headers (NIDs) with the same network code decoded, else null. A conventional P25 radio,
         * which no control channel lists, shows here.
         */
        fun identify(sdr: SdrDevice, hz: Long, seconds: Double, stop: () -> Boolean): Heard? {
            if (!sdr.tune(hz - OFFSET_HZ)) return null
            sdr.resetBuffer()
            val buf = ByteArray(XFER)
            repeat(sdr.settleReads) { sdr.read(buf) }
            val sure = { st: Stats -> (st.nacs.values.maxOrNull() ?: 0) >= 2 }
            val look = look(sdr, buf, seconds, seconds, stop, sure) ?: return null
            val st = when { sure(look.lsm) && !sure(look.fm) -> look.lsm; sure(look.fm) -> look.fm; else -> return null }
            return Heard(st.nacs.maxBy { it.value }.key, look.fm.tsdu + look.lsm.tsdu > 0, look.fm.voice + look.lsm.voice > 0, st === look.lsm)
        }

        /**
         * Listens [seconds] on a control channel's input [hz], where only radios transmit, and hands each radio message of
         * system [nac] to [onRadio] with how far above the noise it came in (the strongest moment of the last ~0.2 s):
         * radios registering, changing talkgroup, sending status or data, with nobody talking. Messages heard, or null
         * when the tuner didn't get there. Uses a reader thread so no burst falls between two reads.
         */
        /** Listens to the control channel's input; the radio messages decoded and the highest level heard (dB over the noise). */
        fun inbound(sdr: SdrDevice, hz: Long, nac: Int, seconds: Double, stop: () -> Boolean, onRadio: (P25Tsbk.Inbound, Int) -> Unit): Pair<Int, Int>? {
            if (!sdr.tune(hz - OFFSET_HZ)) return null
            sdr.resetBuffer()
            val buf = ByteArray(XFER)
            repeat(sdr.settleReads) { sdr.read(buf) }
            val levels = IntArray(LEVEL_READS); var at = 0
            var heard = 0
            lateinit var demod: P25Demod
            demod = P25Demod(OFFSET_HZ.toDouble()) { f ->
                val d = P25Frame.decode(f, demod.soft)
                if (d.nid?.nac != nac) return@P25Demod
                for (t in d.tsbks) P25Tsbk.parseInbound(t)?.let { heard++; onRadio(it, levels.max()) }
            }
            var samples = 0L; var fails = 0; var peak = 0
            ReadAhead(sdr, buf.size).use { ahead ->
                while (samples < seconds * P25Demod.SAMPLE_RATE && !stop() && fails < STALL_READS) {
                    val n = ahead.read(buf)
                    if (n <= 0) { fails++; continue }
                    fails = 0
                    levels[at++ % LEVEL_READS] = RadioWatch.channelSnrDb(RadioWatch.powerSpectrum(buf, n / 2), sdr.sampleRate, OFFSET_HZ)
                    peak = maxOf(peak, levels[(at - 1) % LEVEL_READS])
                    demod.feed(buf, n / 2); samples += n / 2
                }
            }
            return heard to peak
        }
        /** Reads (~4 ms each) whose level a radio message is measured from: its burst and the frame decode's delay. */
        private const val LEVEL_READS = 48
    }
}
