package com.rfsentinel.app.sdr

/**
 * The TETRA handset band (380-385 MHz) watched nonstop on one dongle, instead of a 64 ms look per sweep. An idle TETRA
 * radio only sends a short burst every few seconds; a sweep passing by catches few of them.
 *
 * At [RATE] one dongle sees half the band ([LOW] or [HIGH]); two dongles see all of it at once. One dongle watching both
 * halves swaps every [SWAP_MS]. Each look is [READS] USB reads (~64 ms, a whole TETRA frame) through [RadioWatch]'s
 * time-slot check; only bursts with TETRA's rhythm are reported (the sweep's plain-energy alerts are left to the sweep).
 * Energy only: nothing is decoded.
 *
 * [centers] is read before every look: which halves this dongle watches can change as dongles come and go.
 */
class TetraWatch(
    private val sdr: SdrDevice,
    private val centers: () -> List<Long>,
    private val label: String,
    private val config: () -> RadioWatch.Config,
    private val stop: () -> Boolean,
    private val onEvent: (RadioWatch.Event) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {

    var looks = 0; private set
    var reported = 0; private set
    @Volatile var note = ""; private set

    /** Watches until [stop] or the dongle goes away. */
    fun run() {
        sdr.setSampleRate(RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        val watch = RadioWatch(learnEveryMs = CHANNEL_LEARN_MS)
        val buf = ByteArray(XFER)
        val iq = ByteArray(XFER * READS)
        val sent = HashMap<Long, Long>()
        var center = 0L; var swapAt = 0L; var turn = 0; var learnAt = clock(); var logAt = clock()
        var failed = 0; var unlocked = 0
        log("$label: TETRA watch, ${sdr.sampleRate} S/s, gain ${GAIN_TENTHS_DB / 10.0} dB")
        while (!stop() && !sdr.lost) {
            val mine = centers()
            if (mine.isEmpty()) { note = "$label idle"; Thread.sleep(500); center = 0L; continue }
            val now0 = clock()
            if (center !in mine || (mine.size > 1 && now0 - swapAt >= SWAP_MS)) {
                center = mine[turn++ % mine.size]; swapAt = now0
                if (!sdr.tune(center)) { unlocked++; center = 0L; Thread.sleep(50); continue }
                repeat(sdr.settleReads) { sdr.read(buf) }
                note = "$label " + mine.joinToString(" + ") { half(it) }
            }
            var got = 0
            for (i in 0 until READS) {
                val n = sdr.read(buf)
                if (n <= 0) break
                System.arraycopy(buf, 0, iq, got, n); got += n
            }
            if (got < 2 * 1024) { if (++failed % 8 == 0) sdr.resetBuffer(); continue }
            failed = 0
            looks++
            val bursts = RadioWatch.burstSpectrum(iq, got / 2, sdr.sampleRate)
            watch.config = config().copy(bands = listOf(RadioWatch.TETRA_BAND), targets = emptyList())
            val now = clock()
            for (e in watch.analyze(center, bursts.psd, sdr.sampleRate, now, bursts, USABLE_HZ)) {
                if (!e.tdma) continue
                // The same radio many times a second: a few updates a second keep the bar and the list live.
                if (e.repeat && now - (sent[e.freqHz] ?: Long.MIN_VALUE / 2) < REPEAT_EVERY_MS) continue
                sent[e.freqHz] = now
                reported++
                if (!e.repeat) log("$label: TETRA bursts ${e.mhz} MHz, ${e.snrDb} dB above the noise")
                onEvent(e)
            }
            if (now - learnAt >= LEARN_MS) { watch.sweepDone(); learnAt = now }
            if (now - logAt >= 60_000L) {
                logAt = now
                log("$label: TETRA watch: $looks looks, $reported reports, ${watch.busyChannels} always-busy channels, PLL unlocked $unlocked, USB errors ${sdr.ctlErrors}")
            }
        }
        note = ""
        log("$label: TETRA watch stopped after $looks looks, $reported reports")
    }

    companion object {
        /** Each dongle sees ±[USABLE_HZ] at this rate: two cover the band. */
        const val RATE = 2_560_000
        /** shortcut: 380.0-382.4 and 382.6-385.0 MHz leave 382.4-382.6 unwatched; a third half would close it if radios show up there. */
        const val USABLE_HZ = 1_200_000.0
        const val LOW = 381_200_000L
        const val HIGH = 383_800_000L
        private const val XFER = 16_384
        /** 20 × 8192 samples at [RATE]: 64 ms, more than one 56.7 ms TETRA frame. */
        private const val READS = 20
        /** One dongle for both halves: each this long (a radio sends every few seconds, so both get a chance). */
        const val SWAP_MS = 2_000L
        /** Warm-up steps: alerts start after 6 of these. */
        private const val LEARN_MS = 3_000L
        /** A channel's busy history steps this slowly so a radio's long call (a minute or more) isn't learned as always busy.
         *  Spurs and carriers have no time slots, so they're never reported here anyway. */
        private const val CHANNEL_LEARN_MS = 10_000L
        private const val REPEAT_EVERY_MS = 1_000L
        private const val GAIN_TENTHS_DB = 297

        /** The halves each of [n] dongles watches: one alone takes both in turn, two take one each, more stay idle. */
        fun split(n: Int): List<List<Long>> = List(n) { i ->
            when { n == 1 -> listOf(LOW, HIGH); i == 0 -> listOf(LOW); i == 1 -> listOf(HIGH); else -> emptyList() }
        }

        fun half(center: Long) = "%.1f-%.1f MHz".format(java.util.Locale.US, (center - USABLE_HZ) / 1e6, (center + USABLE_HZ) / 1e6)

        /** The last TETRA radio heard nearby (any dongle, sweep included), for the main screen's bar and the beeps. */
        data class Heard(val hz: Long, val snr: Int, val at: Long, val trend: Int)
        @Volatile var heard: Heard? = null; private set

        /** A TETRA burst [e] at [now]: the bar follows the strongest radio of the last few seconds. */
        fun heard(e: RadioWatch.Event, now: Long) {
            if (!e.tdma || e.band.kind != RadioWatch.Kind.TETRA) return
            val h = heard
            if (h != null && now - h.at < HOLD_MS && e.freqHz != h.hz && e.snrDb < h.snr) return
            val trend = if (h == null || h.hz != e.freqHz || now - h.at >= HOLD_MS) 0
                else if (e.snrDb - h.snr >= 3) 1 else if (h.snr - e.snrDb >= 3) -1 else h.trend
            heard = Heard(e.freqHz, e.snrDb, now, trend)
        }

        /** The bar shows this long after the last burst. */
        const val HOLD_MS = 8_000L

        /** The bar's fill (0..1) for [snr] dB above the noise: 15 dB empty, 50 dB full. */
        fun level(snr: Int) = ((snr - 15) / 35f).coerceIn(0f, 1f)

        /** The beep rate for [snr], on the radar beeps' curve: 15 dB -> 1.3 s, 40 dB -> 0.4 s, 55 dB -> 0.06 s. */
        fun beepRssi(snr: Int) = -95 + (snr - 15) * 5 / 4
    }
}
