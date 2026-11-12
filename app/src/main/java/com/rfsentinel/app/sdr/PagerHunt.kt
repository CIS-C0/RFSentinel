package com.rfsentinel.app.sdr

/**
 * Pager messages, second stage: finds a paging channel and stays on it. [hunt] looks over the paging bands the way
 * [P25Hunt] does and listens to the strongest carriers in turn; the first that decodes is the channel. [listen] then
 * stays until nothing has decoded for [QUIET_S]. Paging transmitters can be silent between pages, so a channel missed now
 * shows up on a later look. Frequencies, capcodes and text stay off the log: counts and levels only, unless [logAll]
 * (then frequencies too; text never).
 */
class PagerHunt(
    private val sdr: SdrDevice,
    private val speeds: () -> Set<Int> = { PagerDemod.SPEEDS },
    xfer: Int = XFER,
    private val onStall: (Boolean) -> Unit = {},
    /** "Log everything the RTL-SDR hears": frequencies in the log too. */
    private val logAll: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {

    /** Channels listened to in the last hunt, for the log. */
    var tried = 0; private set

    private val buf = ByteArray(xfer)

    /** The first paging channel that decodes, strongest first, or null. Pages heard on the way go to [onPage]. */
    fun hunt(stop: () -> Boolean, onPage: (PagerLog.Page, Long) -> Unit): Long? {
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        val plan = P25Hunt.plan(BANDS)
        val scan = P25Hunt.scan(sdr, plan, stop, halfHz = CHANNEL_HALF_HZ) ?: return null
        val ranked = P25Hunt.strongest(scan.found, MERGE_HZ)
        val tries = ranked.take(MAX_TRIES)
        log("Pager scan: ${plan.size} steps, tuner didn't lock ${scan.unlocked}, no samples ${scan.short}, ${ranked.size} carriers, " +
            "strongest ${P25Hunt.levels(tries)}, highest point %.0f dB".format(scan.peakDb))
        if (logAll()) log("Pager scan heard: ${P25Hunt.heard(ranked)}")
        tried = 0
        for (c in tries) {
            if (stop()) return null
            tried++
            val heard = receive(c.hz, TRIAL_S, true, stop, onPage)
            val at = if (logAll()) "%.4f MHz ".format(java.util.Locale.US, c.hz / 1e6) else ""
            log("Pager try $tried/${tries.size}: $at%.0f dB: ${if (heard) "paging channel" else "nothing decoded"}".format(c.snrDb))
            if (heard) return c.hz
        }
        return null
    }

    /** Stays on [hz], pages to [onPage]: false when [stop] said so, true when it went quiet (hunt again). */
    fun listen(hz: Long, stop: () -> Boolean, onPage: (PagerLog.Page, Long) -> Unit): Boolean {
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        receive(hz, QUIET_S, false, stop, onPage)
        return !stop()
    }

    /** Decodes [hz] until [stop] or [quietS] without a good frame ([once]: or the first one). True when a frame was heard. */
    private fun receive(hz: Long, quietS: Double, once: Boolean, stop: () -> Boolean, onPage: (PagerLog.Page, Long) -> Unit): Boolean {
        if (!sdr.tune(hz - OFFSET_HZ)) { log("Pager: the tuner didn't lock"); return false }
        sdr.resetBuffer()
        repeat(sdr.settleReads) { sdr.read(buf) }
        val demod = PagerDemod(OFFSET_HZ, speeds()) { onPage(it, hz) }
        var samples = 0L; var lastGood = 0L; var seen = 0; var fails = 0; var lastRow = 0L
        val began = System.nanoTime()
        val ahead = ReadAhead(sdr, buf.size)
        try { while (!stop()) {
            val n = ahead.read(buf)
            if (n <= 0) {
                if (++fails % STALL_READS == 0) {
                    if (fails <= STALL_READS * 3) log("Pager: no samples for $fails reads in a row - restarting the stream")
                    onStall(true)
                    sdr.resetBuffer()
                }
                if (fails >= (if (once) STALL_READS else STALL_READS * 4)) break // a dead stream never reaches quietS
                continue
            }
            if (fails >= STALL_READS) onStall(false)
            fails = 0
            demod.feed(buf, n / 2); samples += n / 2
            // The waterfall screen open: a row of the channel now and then (one small FFT, well under a read's time).
            SdrWaterfall.listener?.let { l ->
                val now = System.currentTimeMillis()
                if (now - lastRow >= ROW_MS && n >= 2 * 1024) {
                    lastRow = now
                    val rate = P25Demod.SAMPLE_RATE.toInt()
                    l(SdrWaterfall.Row(listOf(SdrWaterfall.segment(hz - OFFSET_HZ, RadioWatch.powerSpectrum(buf, n / 2), rate, rate * 0.45, null)), tuned = true, at = now))
                }
            }
            if (demod.frames > seen) {
                seen = demod.frames; lastGood = samples
                if (once) break
            }
            if (samples - lastGood > quietS * P25Demod.SAMPLE_RATE) break
        } } finally { ahead.close() }
        // Counts and levels only. Under 100%: the dongle dropped samples. A rise with no syncs: a signal that isn't paging, or won't decode.
        val secs = (System.nanoTime() - began) / 1e9
        if (samples > 0) log("Pager listen: %.0f s, %.0f%% of the samples, channel rose %.0f dB, %d syncs, %d frames".format(
            java.util.Locale.US, secs, samples * 100 / (secs * P25Demod.SAMPLE_RATE), demod.riseDb, demod.syncs, demod.frames))
        return seen > 0
    }

    companion object {
        /** Paging channels around the world, base-station side. */
        val BANDS = listOf(152_000_000L..153_000_000L, 157_400_000L..158_700_000L, 169_400_000L..169_800_000L,
            465_900_000L..466_300_000L, 929_000_000L..932_000_000L)
        private const val XFER = 16_384
        private const val OFFSET_HZ = 250_000L     // clear of the receiver's DC spike
        private const val MAX_TRIES = 6
        /** Wide enough to hold both tones of a ±4.8 kHz FSK pager, so a channel is found at its centre, not at one tone
         *  (P25's narrower window put it ~4 kHz off and one tone outside the demodulator's filter). */
        internal const val CHANNEL_HALF_HZ = 10_000.0
        /** The two tones of one pager are at most 9.6 kHz apart: closer peaks are one channel. */
        private const val MERGE_HZ = 10_000L
        private const val TRIAL_S = 3.0            // a FLEX frame is 1.875 s; a busy POCSAG channel sends within this
        /** Under the shortest turn: a channel gone silent must be noticed within a turn, not start over each time. */
        const val QUIET_S = 25.0
        private const val STALL_READS = 8
        private const val ROW_MS = 100L
    }
}
