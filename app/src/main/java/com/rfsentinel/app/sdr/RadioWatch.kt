package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Two-way radio activity on the North American public-safety bands, from an RTL-SDR's
 * power spectrum only: how much energy each 12.5 kHz channel carries. Nothing is
 * demodulated, decoded, recorded or decrypted - it can't tell what is said or who says
 * it, only that a radio is transmitting, and roughly how close.
 *
 * Towers, repeaters and trunking control channels are on the air all the time and
 * everywhere; a handheld or car radio is only strong close by. So it learns which
 * channels are busy most of the time and ignores them, and reports a strong burst on
 * a quiet channel. The 700 / 800 MHz bands it watches are where public-safety radios
 * (not towers) transmit; on the shared VHF / UHF bands businesses, schools and transit
 * use the same kind of radios, so those count as weak signs unless very strong.
 */
class RadioWatch {

    enum class Kind { PUBLIC_SAFETY_MOBILE, SHARED, CUSTOM, TARGET, CELLULAR_UPLINK }

    data class Band(val label: String, val startHz: Long, val endHz: Long, val kind: Kind)

    /** A frequency the user asked to watch (Settings > RTL-SDR radio): reported whenever it's active. */
    data class Target(val freqHz: Long, val label: String)

    /** What to watch and how sensitive (Settings > RTL-SDR radio); read at the start of every sweep. */
    data class Config(
        val bands: List<Band> = BANDS,
        /** The user's own excluded ranges (e.g. a business band), on top of the built-in ones. */
        val excluded: List<LongRange> = emptyList(),
        val targets: List<Target> = emptyList(),
        /** A transmission counts from this many dB above the noise (lower = more sensitive). */
        val minSnrDb: Int = DEFAULT_MIN_SNR
    )

    /**
     * A strong transmission on a normally quiet channel. [repeat]: the same channel again within
     * [REPEAT_MS] - not a new alert, but it keeps the live list and the closer / farther trend current.
     */
    data class Event(val freqHz: Long, val snrDb: Int, val band: Band, val confidence: Int,
                     val target: Target? = null, val repeat: Boolean = false) {
        val mhz: String get() = "%.4f".format(java.util.Locale.US, freqHz / 1e6)
    }

    @Volatile var config = Config()

    private class Channel {
        var visits = 0
        /** The last 32 visits, 1 = energy on the channel. */
        var recent = 0L
        var lastEvent = Long.MIN_VALUE / 2 // never reported
        fun busyRatio(): Double {
            val n = minOf(visits, 32)
            return if (n == 0) 0.0 else java.lang.Long.bitCount(recent and ((1L shl n) - 1)).toDouble() / n
        }
    }

    private val channels = HashMap<Long, Channel>()
    var sweeps = 0; private set

    val learnedChannels: Int get() = channels.size
    /** Channels busy most of the time (towers, control channels, pagers): never reported. */
    val busyChannels: Int get() = channels.values.count { it.visits >= MIN_VISITS_BUSY && it.busyRatio() >= BUSY_RATIO }

    fun sweepDone() { sweeps++ }

    /**
     * One tuned chunk: [psdDb] is the averaged power spectrum (FFT-shifted, DC in the middle)
     * at [centerHz] and [sampleRate]. Returns the transmissions worth reporting.
     */
    fun analyze(centerHz: Long, psdDb: FloatArray, sampleRate: Int, now: Long): List<Event> {
        val n = psdDb.size
        val binHz = sampleRate.toDouble() / n
        val usableBins = (USABLE_HZ / binHz).toInt()
        val floor = median(FloatArray(2 * usableBins) { psdDb[(n / 2 - usableBins + it).coerceIn(0, n - 1)] })
        val half = maxOf(1, (CHANNEL_HALF_HZ / binHz).roundToInt())
        val found = ArrayList<Event>()
        val cfg = config
        for (band in cfg.bands) {
            if (band.kind == Kind.CELLULAR_UPLINK) { widebandScan(band, centerHz, psdDb, sampleRate, now, cfg, found); continue }
            var f = ((maxOf(band.startHz, centerHz - USABLE_HZ.toLong()) + STEP_HZ - 1) / STEP_HZ) * STEP_HZ
            val last = minOf(band.endHz, centerHz + USABLE_HZ.toLong())
            while (f <= last) {
                val off = f - centerHz
                if (kotlin.math.abs(off) > DC_GUARD_HZ && !excluded(f, cfg)) {
                    val mid = n / 2 + (off / binHz).roundToInt()
                    var p = -200f
                    for (k in mid - half..mid + half) if (k in 0 until n && psdDb[k] > p) p = psdDb[k]
                    val snr = (p - floor).roundToInt()
                    val ch = channels.getOrPut(f) { Channel() }
                    val active = snr >= ACTIVE_DB
                    ch.recent = (ch.recent shl 1) or (if (active) 1L else 0L)
                    ch.visits++
                    if (active) eventFor(f, snr, band, ch, now, cfg.minSnrDb)?.let { found += it }
                }
                f += STEP_HZ
            }
        }
        // Watched frequencies: any burst counts (no learning, no busy filter - the user asked for them).
        for (t in cfg.targets) {
            val off = t.freqHz - centerHz
            if (kotlin.math.abs(off) > USABLE_HZ || kotlin.math.abs(off) <= DC_GUARD_HZ) continue
            val mid = n / 2 + (off / binHz).roundToInt()
            var p = -200f
            for (k in mid - half..mid + half) if (k in 0 until n && psdDb[k] > p) p = psdDb[k]
            val snr = (p - floor).roundToInt()
            val st = targetState.getOrPut(t.freqHz) { Channel() }
            if (snr < maxOf(ACTIVE_DB, cfg.minSnrDb - 10)) continue
            found += Event(t.freqHz, snr, Band(t.label, t.freqHz, t.freqHz, Kind.TARGET), TARGET_CONFIDENCE, t,
                repeat = now - st.lastEvent < REPEAT_MS)
        }
        // A strong signal spills into the channels next to it: keep the strongest of a cluster
        // (a watched frequency wins a tie).
        val kept = found.sortedWith(compareByDescending<Event> { it.snrDb }.thenByDescending { it.target != null })
            .fold(ArrayList<Event>()) { acc, e ->
                if (acc.none { kotlin.math.abs(it.freqHz - e.freqHz) <= SPLATTER_HZ }) acc += e
                acc
            }
        for (e in kept) if (!e.repeat) {
            (if (e.target != null) targetState else channels)[e.freqHz]?.lastEvent = now
            cells[e.freqHz]?.lastEvent = now
        }
        return kept
    }

    private val targetState = HashMap<Long, Channel>()
    private val cells = HashMap<Long, Cell>()

    private class Cell {
        var visits = 0
        /** Slowly-learned quiet level of this slice (dB); a transmitter shows as a rise above it. */
        var baseline = Float.NaN
        var lastEvent = Long.MIN_VALUE / 2
    }

    /** Cellular uplink slices learned so far (for the log). */
    val cellSlices: Int get() = cells.size

    /**
     * Wideband energy in one ~1.6 MHz slice of a cellular UPLINK band - the side the phone, modem or
     * router transmits on, not the tower. A device transmitting nearby lifts the whole slice above its
     * learned quiet level. Signal strength only: nothing is demodulated, and it can't tell one device
     * or carrier from another - only that something cellular is transmitting close by, and roughly how
     * close. Towers transmit on the downlink bands, which this never looks at, so a steady uplink rise
     * that travels with you is a device travelling with you.
     */
    private fun widebandScan(band: Band, centerHz: Long, psdDb: FloatArray, sampleRate: Int, now: Long,
                             cfg: Config, found: ArrayList<Event>) {
        val nn = psdDb.size
        val binHz = sampleRate.toDouble() / nn
        val usableBins = (USABLE_HZ / binHz).toInt()
        val vals = ArrayList<Float>(2 * usableBins)
        for (i in nn / 2 - usableBins..nn / 2 + usableBins) {
            if (i !in 0 until nn) continue
            val f = centerHz + (((i - nn / 2) * binHz).roundToInt()).toLong()
            if (kotlin.math.abs(f - centerHz) <= DC_GUARD_HZ) continue
            if (f < band.startHz || f > band.endHz || excluded(f, cfg)) continue
            vals += psdDb[i]
        }
        if (vals.size < 16) return
        vals.sort()
        val floor = vals[vals.size / 2]                 // slice noise floor (median)
        val level = vals[(vals.size * 7) / 10]          // how high the busier 30 % of the slice sits
        val cell = cells.getOrPut(centerHz) { Cell() }
        cell.visits++
        @Suppress("UNUSED_VARIABLE") val slice = floor  // in-slice noise floor, kept for the log/debug
        if (cell.baseline.isNaN()) cell.baseline = level
        val rise = (level - cell.baseline).roundToInt()
        val active = rise >= CELL_RISE_DB
        // Learn the quiet level only while nothing is transmitting, so a passing device isn't learned away.
        if (!active) cell.baseline = cell.baseline * (1 - CELL_ALPHA) + level * CELL_ALPHA
        if (cell.visits < LEARN_SWEEPS || !active) return
        val confidence = if (rise >= CELL_RISE_DB * 3) 55 else 40
        found += Event(centerHz, rise, band, confidence, repeat = now - cell.lastEvent < REPEAT_MS)
    }

    /** [min]: the sensitivity setting; at the default (30 dB) the thresholds are the original ones. */
    private fun eventFor(f: Long, snr: Int, band: Band, ch: Channel, now: Long, min: Int = DEFAULT_MIN_SNR): Event? {
        if (sweeps < LEARN_SWEEPS) return null                                   // still learning what's always there
        if (ch.visits >= MIN_VISITS_BUSY && ch.busyRatio() >= BUSY_RATIO) return null // tower / control channel / pager
        val confidence = when (band.kind) {
            Kind.PUBLIC_SAFETY_MOBILE -> when { snr >= min + 10 -> 75; snr >= min -> 60; else -> return null }
            else -> when { snr >= min + 15 -> 55; snr >= min + 5 -> 35; else -> return null }
        }
        return Event(f, snr, band, confidence, repeat = now - ch.lastEvent < REPEAT_MS)
    }

    companion object {
        const val STEP_HZ = 12_500L
        private const val CHANNEL_HALF_HZ = 5_000.0
        /** Only the flat middle of the spectrum is used; the edges roll off. */
        const val USABLE_HZ = 800_000.0
        private const val DC_GUARD_HZ = 15_000L
        private const val ACTIVE_DB = 12
        private const val SPLATTER_HZ = 37_500L
        private const val BUSY_RATIO = 0.4
        private const val MIN_VISITS_BUSY = 6
        private const val LEARN_SWEEPS = 6 // = MIN_VISITS_BUSY: no alert before always-on channels are known
        private const val REPEAT_MS = 120_000L
        /** Default sensitivity: 30 dB above the noise (the original public-safety threshold). */
        const val DEFAULT_MIN_SNR = 30
        private const val TARGET_CONFIDENCE = 70
        /** A cellular uplink slice must rise this many dB above its learned quiet level to count. */
        private const val CELL_RISE_DB = 8
        /** How fast the learned quiet level follows a slow ambient drift (a gentle rise is not a transmitter). */
        private const val CELL_ALPHA = 0.1f

        /**
         * North-American LTE/5G FDD UPLINK bands within the RTL-SDR's tuning range (~24-1766 MHz) - the
         * frequencies a handset, modem or router TRANSMITS on (towers use the paired downlink bands, which
         * are left out on purpose, so a tower never looks like a device). The 1850-1915 MHz PCS uplink and
         * the 5G mid-band are above the dongle's range and can't be watched. Receive-only, energy only.
         */
        val LTE_UPLINK_BANDS = listOf(
            Band("Cellular uplink - 600 MHz (Band 71)", 663_000_000L, 698_000_000L, Kind.CELLULAR_UPLINK),
            Band("Cellular uplink - 700 MHz lower (Band 12/17)", 698_000_000L, 716_000_000L, Kind.CELLULAR_UPLINK),
            Band("Cellular uplink - 700 MHz upper (Band 13)", 776_000_000L, 788_000_000L, Kind.CELLULAR_UPLINK),
            // Band 14: the public-safety broadband block (FirstNet in the US; AT&T phones may use spare capacity).
            Band("Cellular uplink - 700 MHz public-safety broadband (Band 14, FirstNet)", 788_000_000L, 798_000_000L, Kind.CELLULAR_UPLINK),
            Band("Cellular uplink - 850 MHz (Band 5/26)", 814_000_000L, 849_000_000L, Kind.CELLULAR_UPLINK),
            Band("Cellular uplink - AWS 1700 MHz (Band 4/66, partial)", 1_710_000_000L, 1_766_000_000L, Kind.CELLULAR_UPLINK)
        )

        /** North American (FCC) public-safety land-mobile bands. */
        val BANDS = listOf(
            Band("700 MHz public-safety radio band", 799_000_000L, 805_000_000L, Kind.PUBLIC_SAFETY_MOBILE),
            // 806-814 MHz: public-safety radios after the 800 MHz rebanding; LTE phones start at 814.
            Band("800 MHz public-safety radio band", 806_000_000L, 813_900_000L, Kind.PUBLIC_SAFETY_MOBILE),
            Band("VHF land-mobile band", 150_800_000L, 174_000_000L, Kind.SHARED),
            Band("UHF land-mobile band", 450_000_000L, 470_000_000L, Kind.SHARED)
        )

        /** Consumer, marine, railroad, weather and paging channels - never police radios. */
        private val EXCLUDED = listOf(
            151_815_000L..151_945_000L, 154_565_000L..154_605_000L,  // MURS
            156_000_000L..157_450_000L, 160_600_000L..162_025_000L,  // marine VHF
            159_810_000L..161_565_000L,                               // railroad
            162_400_000L..162_550_000L,                               // NOAA weather radio
            462_537_500L..462_737_500L, 467_537_500L..467_737_500L    // FRS / GMRS walkie-talkies
        ) + listOf(152_007_500L, 152_240_000L, 152_480_000L, 157_740_000L, 158_100_000L, 158_700_000L) // paging
            .map { it - 6_250L..it + 6_250L }

        fun excluded(hz: Long, cfg: Config = Config()) = EXCLUDED.any { hz in it } || cfg.excluded.any { hz in it }

        /**
         * Tuning plan: chunk centres covering every band with the usable middle of the spectrum,
         * plus a chunk for each watched frequency outside them.
         */
        fun plan(cfg: Config = Config()): List<Long> {
            val step = (2 * USABLE_HZ).toLong()
            val chunks = cfg.bands.flatMap { b ->
                generateSequence(b.startHz + step / 2) { it + step }.takeWhile { it - step / 2 < b.endHz }.toList()
            }.toMutableList()
            for (t in cfg.targets) {
                // Inside a chunk's usable middle but clear of its centre (DC): nothing to add.
                if (chunks.none { kotlin.math.abs(t.freqHz - it) in (DC_GUARD_HZ + 1)..USABLE_HZ.toLong() }) chunks += t.freqHz + step / 4
            }
            return chunks
        }

        private fun median(a: FloatArray): Float { a.sort(); return if (a.isEmpty()) 0f else a[a.size / 2] }

        /**
         * Averaged power spectrum in dB of [count] unsigned 8-bit I/Q pairs from [iq], in
         * [n]-point Hann-windowed FFTs, FFT-shifted so DC sits at index n/2.
         */
        fun powerSpectrum(iq: ByteArray, count: Int, n: Int = 1024): FloatArray {
            val acc = DoubleArray(n)
            val re = DoubleArray(n); val im = DoubleArray(n)
            val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
            var frames = 0
            var s = 0
            while (s + n <= count) {
                for (k in 0 until n) {
                    re[k] = ((iq[2 * (s + k)].toInt() and 0xff) - 127.5) / 127.5 * win[k]
                    im[k] = ((iq[2 * (s + k) + 1].toInt() and 0xff) - 127.5) / 127.5 * win[k]
                }
                fft(re, im)
                for (k in 0 until n) acc[k] += re[k] * re[k] + im[k] * im[k]
                frames++; s += n
            }
            return FloatArray(n) { k ->
                val v = acc[(k + n / 2) % n] / maxOf(1, frames)
                (10 * log10(v + 1e-20)).toFloat()
            }
        }

        /** In-place iterative radix-2 FFT. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar; im[i + k] += ai
                        val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
