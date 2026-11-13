package com.rfsentinel.app.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * An RTL-SDR dongle or a HackRF ([SdrDevice]) on an OTG cable, used while scanning as a receive-only radio-activity
 * detector ([RadioWatch]): it sweeps the public-safety bands and reports strong
 * transmissions nearby. Only signal strength per channel is measured. It has its own
 * log ([com.rfsentinel.app.util.HwLogs.sdr], Settings → External hardware → Export a hardware log).
 *
 * Or an ADS-B receiver on 1090 MHz ([ModeS], [AdsbTracker]): aircraft heard directly, no internet
 * needed, handed to [onAircraft] every few seconds. Or it finds and follows a P25 trunked-radio
 * control channel ([P25Hunt]). Or it decodes pager messages ([PagerHunt], [PagerLog]). Any mix of them ([modes]): it takes
 * turns, each of its own [turnMs].
 *
 * The radio waterfall screen ([SdrWaterfall]) gets a row per sweep, or, zoomed in with [tuneHz],
 * holds one frequency for a fast live view (the sweep and ADS-B pause meanwhile).
 */
object SdrRadio {

    private const val ACTION_PERMISSION = "com.rfsentinel.app.SDR_PERMISSION"
    private const val SAMPLE_RATE = 2_048_000
    private const val GAIN_TENTHS_DB = 297
    private const val NET = "net:"             // runningDevice of a network SDR
    private const val NET_RETRY_MS = 10_000L
    private const val ERROR_RETRY_MS = 30_000L
    /** A dongle that drops off USB and is back within this reset itself (seen: ~0.4 s, a walkie-talkie next to the antenna). */
    private const val RESET_MS = 5_000L
    /** A second reset within this: advice on the status line. */
    private const val RESET_ADVICE_MS = 5 * 60_000L
    /** Stream restarts in a row before the status line says what to try. */
    private const val STALL_ADVICE = 3
    private const val XFER = 16_384           // bytes per USB read (8192 samples)
    /** For continuous decoding: one read is ~33 ms of samples; Android before 9 caps a transfer at 16 KB. */
    private val STREAM_XFER = if (android.os.Build.VERSION.SDK_INT >= 28) 131_072 else XFER
    private const val READS_PER_CHUNK = 8     // ~32 ms of samples per tuned chunk
    private const val SLOT_READS = 16         // ~64 ms: more than one TETRA or DMR frame, so a radio's slot is always in it
    private const val STALL_CHUNKS = 8        // chunks in a row without samples before the stream is restarted
    private const val ADSB_PUSH_MS = 2_000L
    private const val TUNED_READS = 4          // ~16 ms of samples per waterfall row
    private const val TUNED_ROW_MS = 40L       // at most ~25 rows a second
    private const val TUNED_LOG_MS = 5_000L    // "log everything": what the waterfall hears, every 5 s
    /** Frequency corrections learned per session at most: a warming crystal drifts a little, a wrong reading must not loop. */
    private const val MAX_FREQ_FIXES = 3
    private const val P25_RETRY_MS = 30_000L   // no control channel found: look again after this
    private const val P25_WEAK_RETRY_MS = 5_000L // one heard but too weak to decode: sooner, the antenna may be moving
    internal const val STAY_MS = 30_000L       // Stay on activity: the turn runs on this long after the last activity
    internal const val MAX_STAY_MS = 10 * 60_000L // ...but at most this long in all, so the other scans still get turns
    private const val PAGER_RETRY_MS = 5_000L  // no paging channel decoded: look again after this (they can be silent between pages)
    private const val INPUT_DELAY_MS = 400L    // after a grant: the radio is on the air by then
    private const val INPUT_GAP_MS = 5_000L    // at most one input check this often: each costs ~0.2 s of control channel
    private const val INPUT_READS = 16         // ~70 ms of the input
    private const val INBOUND_GAP_MS = 20_000L // the control channel's input: one listen this often (radios checking in nearby)
    private const val INBOUND_S = 1.5          // each that long: the control channel misses ~7% of its messages
    private const val IDENTIFY_S = 0.6         // sweep: listening this long to a transmission tells P25 from other radios
    private const val IDENTIFY_GAP_MS = 3_000L // at most one such check this often, so the sweep keeps its pace
    private const val P25_BONUS = 15           // decoded as P25: that much surer it's a public-safety radio
    private const val P25_MAX_CONFIDENCE = 85
    private const val KNOWN_PPM = 100e-6       // a heard / announced frequency pair further apart than this is a different channel
    /** Tire sensors: tuned this far below the channel, a multiple of [Tpms.RATE] so the centre spike lands on a null. */
    private const val TPMS_OFFSET_HZ = 512_000L
    private const val TPMS_DWELL_MS = 10_000L   // each band this long: a sensor sends about every 15-60 s while driving
    /** An FM station fills at least this much. */
    private const val FM_WIDTH_HZ = 100_000L
    private const val INPUT_OFFSET_HZ = 250_000L // the input this far above the tuned centre, clear of the DC spike
    private const val RECORD_GAP_MS = 20_000L  // at most one call recording this often: each costs P25Record.SECONDS of control channel
    private const val FOLLOW_START_S = 1.5     // no voice this long after tuning to a call: it isn't there (or already over)
    private const val FOLLOW_QUIET_S = 1.0     // voice gone this long: the call is over
    private const val FOLLOW_MAX_MS = 120_000L // then back to the control channel regardless
    private const val PRIORITY_WAIT_MS = 5_000L // a priority call that cut another: looked for this long on the control channel
    private const val PLAY_START_MS = 1_500L    // Play voice transmissions: no voice this long after tuning, it's over (or wasn't one)
    private const val PLAY_QUIET_MS = 6_000L    // the squelch shut this long after voice, back to the sweep (a reply often comes in this time)
    private const val HOT_MS = 120_000L         // a walkie-talkie channel played is checked often this long after: a reply plays at once
    private const val HOT_CHECK_MS = 400L       // how often, between sweep chunks (~70 ms a look)
    private const val PLAY_MAX_MS = 60_000L     // ...and at most this long, so a stuck carrier doesn't hold the sweep
    private const val STUCK_MS = 10 * 60_000L   // a channel played that long isn't played again for this long

    /** The hardware status line, the TETRA watch's dongles after it. */
    @Volatile var status: String = ""
        get() = tetraNote.let { t -> if (t.isEmpty()) field else if (field.isBlank()) t else "$field · $t" }
        private set
    /** Dongles watching the TETRA band ([TetraWatch]) by USB device name, [MAIN] for the first one in TETRA only. */
    private val tetraWatches = java.util.concurrent.ConcurrentHashMap<String, TetraWatch>()
    private val tetraNote get() = tetraWatches.values.map { it.note }.filter { it.isNotEmpty() }.sorted()
        .let { if (it.isEmpty()) "" else "TETRA watch · " + it.joinToString(" · ") }
    /** Extra SDRs (beyond the first) and their threads: each watches the TETRA band. */
    private val extras = java.util.concurrent.ConcurrentHashMap<String, Thread>()
    private const val MAIN = ""
    private val tetraOn get() = appContext?.let { com.rfsentinel.app.util.Prefs.radioTetraOn(it) } == true
    /** Settings › TETRA only: every dongle on the TETRA band, the other scans paused. */
    private val tetraOnly get() = tetraOn && appContext?.let { com.rfsentinel.app.util.Prefs.radioTetraOnly(it) } == true
    /** Extra dongles cover the TETRA band: the sweep leaves it to them. */
    val tetraWatched get() = extras.isNotEmpty()
    /** ADS-B messages decoded since the app started, for the main screen's messages-per-second line. */
    @Volatile var adsbMessages = 0L; private set
    /** What to watch (Settings > RTL-SDR radio); picked up at the next sweep. */
    @Volatile var config = RadioWatch.Config()
    private var receiver: BroadcastReceiver? = null
    private var onEvent: ((RadioWatch.Event) -> Unit)? = null
    @Volatile private var runningDevice: String? = null
    @Volatile private var stop = false
    /** The thread reading the dongle now; an older one still winding down stops on its own. */
    @Volatile private var worker: Thread? = null
    /** The last thread started, kept after [halt] so the next one can wait for it to let go of the dongle. */
    private var lastWorker: Thread? = null
    @Volatile private var usb: UsbManager? = null
    private var appContext: Context? = null
    /** Settings › "Log everything the RTL-SDR hears": frequencies, aircraft and capcodes in the log too (page text never). */
    private val logAll get() = appContext?.let { com.rfsentinel.app.util.Prefs.sdrLogAll(it) } == true
    private fun logHeard(text: String) = com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: heard: $text")
    private val main =android.os.Handler(android.os.Looper.getMainLooper())
    /** "RTL-SDR" or "HackRF": the plugged-in device, for the status line. */
    @Volatile private var deviceLabel = "RTL-SDR"
    private val UNPLUGGED get() = "$deviceLabel unplugged"
    /** For the reading thread: stop now (scan stopped, unplugged, or a newer thread took over). */
    private val halted get() = stop || Thread.currentThread() !== worker
    /** [short] and [color]: the hardware card's graph, coloured by the scan whose turn it was. */
    enum class Mode(val label: String, val short: String, val color: Int) {
        SWEEP("radio sweep", "Sweep", 0xFF3D7BD9.toInt()), ADSB("ADS-B", "ADS-B", 0xFFE0A33A.toInt()),
        P25("P25", "P25", 0xFF9C5BD0.toInt()), PAGER("pagers", "Pagers", 0xFF2EA6A0.toInt()),
        TPMS("tire sensors", "TPMS", 0xFFD06BA0.toInt())
    }
    /** What the SDR does (Settings > RTL-SDR radio); with more than one it takes turns ([turnMs]). */
    @Volatile var modes: Set<Mode> = setOf(Mode.SWEEP)
    /** How long each scan's turn lasts (Settings › RTL-SDR radio); 60 s when not set. */
    @Volatile var turnMs: Map<Mode, Long> = emptyMap()
    /** The scan whose turn it is now; null when none is ticked. */
    @Volatile var mode: Mode? = null; private set
    @Volatile private var turnEnd = 0L
    @Volatile private var turnStart = 0L
    /** Settings › Stay on activity: a scan that hears something keeps the SDR ([activity]). */
    @Volatile var stayOnActivity = false
    /** This turn was lengthened by [activity]. */
    @Volatile private var stayed = false
    /** A turn starts and [activity] lengthens it under this lock (activity can come from the service's thread). */
    private val turnLock = Any()
    /** Numbers each sweep's waterfall line, unique across turns (the screen redraws a line while its number repeats). */
    private var sweepLines = 0L
    /** The scans that get turns: P25 alone while a talkgroup is held ([p25Hold]). */
    private val active get() = if (p25Hold != null) setOf(Mode.P25) else modes
    /** The waterfall screen is open: the sweep keeps the SDR so it always draws (a held P25 talkgroup still wins). */
    private val sweepHeld get() = SdrWaterfall.listening && p25Hold == null
    /** [m] keeps the SDR: its turn, still ticked (or the waterfall holds the sweep), and the waterfall not zoomed in. */
    private fun keeps(m: Mode) = mode == m && tuneHz == null && antennaCheck == null && finder == null && !reapplyNow &&
        if (sweepHeld) m == Mode.SWEEP else m in active && (active.size == 1 || System.currentTimeMillis() < turnEnd)

    /** Bias-tee or frequency correction changed: applied by the worker between turns ([reapplyNow]: cut this one short). */
    @Volatile private var reapply = false
    @Volatile private var reapplyNow = false
    /** "vid:pid/serial" or "host:port": whose learned frequency correction to use. */
    @Volatile private var deviceKey = ""
    /** The USB dongle that last dropped off, "vid:pid", and when: back within [RESET_MS] means it reset itself. */
    @Volatile private var goneId: String? = null
    @Volatile private var goneAt = 0L
    /** When the dongle came back from a reset: its next statuses say so until it runs again. */
    @Volatile private var resetAt = 0L
    private val resetting get() = System.currentTimeMillis() - resetAt < 60_000L
    /** When the last resets happened, for advice once it keeps resetting. Main thread only. */
    private val resetTimes = ArrayDeque<Long>()
    /** The status line's start after a reset: plain, or with what to do once it keeps happening. */
    @Volatile private var resetSays = ""
    /** Where the waterfall was zoomed in (and listening) when the dongle dropped off: given back if it was only a reset. */
    @Volatile private var resetTune: Pair<Long, Boolean>? = null
    private var freqFix: FreqCorrection? = null
    private var freqFixes = 0
    private var biasOn = false
    /** The ADS-B gain, kept from one ADS-B turn to the next while the dongle stays open. */
    private var adsbGain = AdsbGain()

    /** Settings › bias-tee or frequency correction changed; [afterTyping]: a moment after the typing stops, not per key. */
    fun deviceSettingsChanged(afterTyping: Boolean = false) {
        main.removeCallbacks(reapplyLater)
        if (afterTyping) main.postDelayed(reapplyLater, 2_000L) else reapplyLater.run()
    }
    private val reapplyLater = Runnable { reapply = true; reapplyNow = true }

    /** On the worker thread: the bias-tee and frequency correction from Settings. [start]: the session's first time. */
    private fun applyDeviceSettings(sdr: SdrDevice, start: Boolean) {
        reapply = false; reapplyNow = false
        val c = appContext ?: return
        val bias = com.rfsentinel.app.util.Prefs.sdrBiasTee(c)
        // Never touched unless asked: on some generic dongles GPIO 0 may be wired to something else.
        if (bias || biasOn) {
            val ok = sdr.setBiasTee(bias)
            biasOn = bias && ok
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: bias-tee ${if (!ok) "not supported by this SDR" else if (bias) "on" else "off"}")
        }
        val ppm = com.rfsentinel.app.util.Prefs.sdrPpm(c, deviceKey)
        if (start || kotlin.math.abs(ppm - sdr.ppm) >= 0.01) {
            val changed = !start && kotlin.math.abs(ppm - sdr.ppm) >= 0.01
            sdr.ppm = ppm
            if (start && ppm != 0.0 || changed) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: frequency correction " + "%+.1f ppm".format(java.util.Locale.US, ppm))
            // Everything learned so far was heard through the old correction: learn it again.
            if (changed) { sweepWatch = null; p25Lock = null; pagerHz = null }
        }
        freqFix = if (com.rfsentinel.app.util.Prefs.sdrPpmAuto(c) && freqFixes < MAX_FREQ_FIXES) FreqCorrection(sdr.ppm) else null
    }

    /** A P25 site said where its control channel is: one reading towards the frequency correction. */
    private fun learnFrequency(heardHz: Long, announcedHz: Long?) {
        val fix = freqFix ?: return
        val c = appContext ?: return
        val ppm = fix.add(heardHz, announcedHz ?: return) ?: return
        freqFix = null
        freqFixes++
        com.rfsentinel.app.util.Prefs.setSdrPpm(c, ppm.toFloat(), deviceKey)
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: frequency correction learned from the P25 control channel: " +
            "%+.1f ppm (was %+.1f)".format(java.util.Locale.US, ppm, fix.appliedPpm))
        deviceSettingsChanged()
    }
    /** The scan after the current one, round the ticked ones. */
    private fun next(): Mode? = nextAfter(mode, active)
    private fun nextAfter(m: Mode?, active: Set<Mode>): Mode? =
        Mode.entries.filter { it in active }.let { l -> l.firstOrNull { it.ordinal > (m?.ordinal ?: -1) } ?: l.firstOrNull() }
    /**
     * Whose turn it is now, and the scan to give it back to later: [held] (the waterfall open) keeps the sweep and
     * remembers the scan it cut short; back from the waterfall ([resume], or [tuned]: zoomed in), that scan carries on.
     */
    /** The dongle [backId] ("vid:pid") just plugged in is the one [goneId] that dropped off at [goneAt], moments ago. */
    /** The status line's start after a reset; from the second in a few minutes ([resets]), what to do about it. */
    internal fun resetLine(label: String, resets: Int, biasTee: Boolean) =
        if (resets < 2) "$label reset itself"
        else "$label keeps resetting: keep transmitters a few metres from the antenna" + (if (biasTee) ", and turn off the bias-tee if there's no LNA" else "")

    /** The status while the sample stream stalls; after [restarts] in a row, what to try. */
    internal fun stalledLine(restarts: Int, network: Boolean) = "no samples from the SDR · restarting it" +
        if (restarts < STALL_ADVICE) "" else if (network) " · check the network to the SDR" else " · try another USB cable or port, or a powered hub"

    internal fun resetBack(goneId: String?, goneAt: Long, backId: String, now: Long) = goneId == backId && now - goneAt in 0..RESET_MS

    internal fun pickTurn(held: Boolean, mode: Mode?, tuned: Boolean, resume: Mode?, active: Set<Mode>): Pair<Mode?, Mode?> =
        if (held) Mode.SWEEP to (if (mode != Mode.SWEEP) mode else resume)
        else ((resume ?: mode?.takeIf { tuned })?.takeIf { it in active } ?: nextAfter(mode, active)) to null
    /**
     * [m] heard something worth staying for (a P25 call, a police aircraft, a page, a following car): with
     * [stayOnActivity], its turn runs on [STAY_MS] past now, up to [MAX_STAY_MS] from its start. Any thread.
     */
    fun activity(m: Mode) {
        synchronized(turnLock) {
            if (!stayOnActivity || mode != m || active.size < 2 || sweepHeld) return
            val end = stayUntil(turnEnd, turnStart, System.currentTimeMillis())
            if (end == turnEnd) return
            turnEnd = end
            if (stayed) return
            stayed = true
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: staying on ${m.label}: activity heard")
    }
    internal fun stayUntil(turnEnd: Long, turnStart: Long, now: Long) = maxOf(turnEnd, minOf(now + STAY_MS, turnStart + MAX_STAY_MS))
    /** " · ADS-B in 40 s" while taking turns, or " · holding talkgroup 1234", for the status line. */
    private fun turnNote(): String {
        p25Hold?.let { return " · holding talkgroup $it" }
        if (sweepHeld) return if (modes.any { it != Mode.SWEEP }) " · other scans wait while the waterfall is open" else ""
        if (modes.size < 2) return ""
        val left = ((turnEnd - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return (if (stayed) " · staying for activity" else "") + " · ${next()?.label} in $left s"
    }
    /** P25: the control channel to go back to on its next turn, and the system the talkgroup list is for. */
    private var p25Hunt: P25Hunt? = null
    private var p25Lock: P25Hunt.Lock? = null
    private var p25Nac: Int? = null
    /** Control-channel messages decoded since the app started. */
    @Volatile var p25Messages = 0L; private set
    /** Talkgroups and calls heard on the current control channel (the P25 screen). */
    val p25Log = P25Log()
    /** Pages heard, in memory only (the Pager messages screen). */
    val pagerLog = PagerLog()
    /** The paging channel to go back to on its next turn. */
    private var pagerHz: Long? = null
    /** The Pager messages screen's chosen channel in Hz; 0 for all of them. */
    @Volatile var pagerChannel = 0L
    /** A page that wasn't ignored, for the watch alerts. */
    @Volatile var onPagerPage: ((PagerLog.Page) -> Unit)? = null
    /** A tire-pressure sensor heard (Settings › RTL-SDR radio › Tire sensors): kept in memory only, for the follow check. */
    @Volatile var onTpms: ((Tpms.Reading) -> Unit)? = null
    /** Tire-sensor messages decoded since the app started. */
    @Volatile var tpmsMessages = 0L; private set
    /** A call started on this talkgroup (the service alerts on watched ones). */
    @Volatile var onP25Call: ((Int) -> Unit)? = null
    /** Settings › RTL-SDR radio: after a call starts, briefly listen where its radio transmits (the site's input). */
    @Volatile var p25Inputs = false
    /** A call's radio heard on the input: [snrDb] above the noise, so close to you (the call, its input frequency). */
    @Volatile var onP25Input: ((P25History.Call, Long, Int) -> Unit)? = null
    /** A radio heard calling the site on the control channel's input, nobody talking: who, how strong, its talkgroup if known. */
    @Volatile var onP25Nearby: ((P25Tsbk.Inbound, Int, Int?) -> Unit)? = null
    /** Settings › RTL-SDR radio: save the radio signal of clear calls ([P25Record]). */
    @Volatile var p25Record = false
    /** Settings › RTL-SDR radio › Save played calls: each P25 call and sweep transmission played is kept ([P25Record.speechFiles]). */
    @Volatile var saveCalls = true
    /** Settings › Listen to P25 voice: which clear calls to play live ([P25Follow]). */
    enum class Listen { OFF, WATCHED, ALL }
    @Volatile var p25Listen = Listen.OFF
    /** Settings › Radio sweep › Play voice transmissions: a new transmission is tuned to and played until it goes quiet. */
    @Volatile var sweepListen = false
    /** The watched talkgroups (Settings › Watch these talkgroups). */
    @Volatile var p25Watched = P25Log.Watched()
    /** P25 talkgroups › long-press › Priority: their calls play first, and cut a call of another talkgroup that is playing. */
    @Volatile var p25Priority: Set<Int> = emptySet()
    /** P25 talkgroups › long-press › Never play: their calls are listed and alerted on, never played (unless held). */
    @Volatile var p25Lockout: Set<Int> = emptySet()
    /** A priority talkgroup whose call cut the one playing: followed when the control channel names its channel again, for a few seconds. */
    private var p25Wanted: Pair<Int, Long>? = null

    /** [tg] is watched, or is a patch carrying a watched talkgroup. */
    private fun p25IsWatched(tg: Int?) = tg in p25Watched || p25Log.members(tg).any { it in p25Watched }
    /** [tg] is a priority, or is a patch carrying one. */
    private fun p25IsPriority(tg: Int?) = tg != null && (tg in p25Priority || p25Log.members(tg).any { it in p25Priority })
    /** Never played: the talkgroup, or every talkgroup of the patch. */
    private fun p25LockedOut(tg: Int?) = tg != null && (tg in p25Lockout || p25Log.members(tg).let { m -> m.isNotEmpty() && m.all { it in p25Lockout } })

    /** P25 talkgroups › Mute: no call is played, the SDR stays on the control channel. */
    @Volatile var p25Mute = false
    /**
     * P25 talkgroups › Hold: only this talkgroup's clear calls play (whatever Listen is set to, with no length limit),
     * the SDR stays on P25 (no sweep or ADS-B turns), and recordings pause; the input check stays on for its calls.
     * Until released (null) or scanning stops.
     */
    @Volatile var p25Hold: Int? = null
        set(tg) {
            field = tg
            if (tg != null) onAircraft?.invoke(emptyList(), emptyList()) // no ADS-B while held
        }
    /** The call playing now (the P25 screen marks it), or null. */
    @Volatile var p25Playing: P25History.Call? = null; private set
    private var speaker: android.media.AudioTrack? = null
    /**
     * The waterfall screen zoomed in: hold this frequency instead of sweeping (or receiving ADS-B),
     * until set back to null. Picked up within a chunk.
     */
    @Volatile var tuneHz: Long? = null
    /** The waterfall's Listen: play [tuneHz] through the speaker ([AudioDemod]) while it's held. */
    @Volatile var listen = false
    /**
     * The Radio waterfall's "Antenna check": set to get one reading of the FM radio band (on the air everywhere) and of the
     * 7 weather-radio channels (162.400-162.550 MHz, United States only: Canada's went off the air in March 2026), then the
     * scans carry on. Called on the SDR's thread.
     */
    @Volatile var antennaCheck: ((AntennaReading) -> Unit)? = null
    /**
     * The Radio waterfall's "Find a remote's frequency" (like a Flipper Zero's frequency analyzer): while set, the SDR hops
     * the bands remotes use ([FINDER_CENTERS]) and hands it each signal that stands out: where this dongle hears it (Hz)
     * and how far above the noise (dB); (0, 0) once, when it's ready for the button to be pressed. Called on the SDR's thread.
     */
    @Volatile var finder: ((Long, Int) -> Unit)? = null
    /**
     * The strongest FM station and weather channel, and how far each stands above the noise; [ok] false when the tuner
     * or the samples failed.
     * shortcut: the 20 / 8 dB steps are not calibrated on real antennas, adjust them from testers' hardware logs.
     */
    data class AntennaReading(val fmHz: Long, val fmDb: Int, val weatherHz: Long, val weatherDb: Int, val ok: Boolean = true) {
        /** What it says about the antenna, in words. */
        val verdict: String get() {
            if (!ok) return "The SDR gave no reading. Try again."
            val fm = "FM radio %.1f MHz: %d dB above the noise.".format(java.util.Locale.US, fmHz / 1e6, fmDb)
            val weather = if (weatherDb >= 8) "\nWeather radio %.3f MHz: %d dB above the noise.".format(java.util.Locale.US, weatherHz / 1e6, weatherDb) else ""
            return when {
                maxOf(fmDb, weatherDb) >= 20 -> "The antenna and SDR receive well.\n\n$fm$weather"
                maxOf(fmDb, weatherDb) >= 8 -> "Stations are heard, but weak. Pull the antenna out fully, or try near a window or outside.\n\n$fm$weather"
                else -> "No station heard. Check that the antenna is screwed on and its cable is plugged into the SDR. " +
                    "A filter that blocks FM radio also hides these stations."
            }
        }
    }
    internal val WEATHER_HZ = (0 until 7).map { 162_400_000L + it * 25_000L }
    /** Chunk centres covering FM radio, 87.5 to 108 MHz.
     *  shortcut: not Japan's 76-95 MHz band, add its chunks if a tester there asks. */
    private val FM_CENTERS = (0 until 13).map { 88_300_000L + it * 1_600_000L }
    /**
     * Where remotes send (car keys, garage openers, sensors), each tuned a little off so the usual channels clear the
     * centre spike: 300 / 303.875 / 304.25, 310, 315, 318, 390, 418, 433.92 / 434.42, 868.35 and 915 MHz.
     */
    private val FINDER_CENTERS = listOf(300_400_000L, 303_600_000L, 310_400_000L, 315_400_000L, 318_400_000L,
        390_400_000L, 418_400_000L, 434_200_000L, 868_000_000L, 915_400_000L)
    private const val FINDER_READS = 15         // ~60 ms a look, in 4 ms spectra: all of them in under a second
    private const val FINDER_MIN_DB = 15.0      // a remote pressed near the antenna stands far above this

    /** Learned channels for the sweep, kept while the waterfall zooms in and out (new per device). */
    private var sweepWatch: RadioWatch? = null
    /** An SDR is plugged in and being read. */
    val running: Boolean get() = runningDevice != null
    /** Where you are (latitude, longitude), to drop impossible aircraft positions. */
    @Volatile var location: (() -> Pair<Double, Double>?)? = null
    /** Aircraft with a position, and addresses heard without one, every couple of seconds in ADS-B mode. */
    @Volatile var onAircraft: ((List<com.rfsentinel.app.online.PoliceAircraft.Plane>, List<String>) -> Unit)? = null

    fun isSdr(d: UsbDevice) = RtlSdr.isRtlSdr(d.vendorId, d.productId) || HackRf.isHackRf(d.vendorId, d.productId)
    fun labelOf(d: UsbDevice) = if (HackRf.isHackRf(d.vendorId, d.productId)) "HackRF" else "RTL-SDR"

    @Synchronized
    fun start(context: Context, events: (RadioWatch.Event) -> Unit) {
        val app = context.applicationContext
        appContext = app
        onEvent = events
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        // Just plugged in: Android's "Open RF Sentinel?" prompt comes first; ask ourselves only if it didn't help.
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            deviceOf(intent)?.let { d ->
                                if (resetBack(goneId, goneAt, "%04x:%04x".format(d.vendorId, d.productId), System.currentTimeMillis())) {
                                    resetAt = System.currentTimeMillis(); goneId = null
                                    resetTimes.addLast(resetAt)
                                    while (resetAt - resetTimes.first() > RESET_ADVICE_MS) resetTimes.removeFirst()
                                    resetSays = resetLine(deviceLabel, resetTimes.size, appContext?.let { com.rfsentinel.app.util.Prefs.sdrBiasTee(it) } == true)
                                    // Still zoomed in (the screen stayed open): the run picks it up again, nothing to give back.
                                    if (tuneHz != null) resetTune = null
                                    com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel dropped off USB and came back: it reset itself (a strong signal close to the antenna, or a power dip) - reconnecting")
                                    if (resetTimes.size > 1) com.rfsentinel.app.util.HwLogs.sdr.log("$resetSays (${resetTimes.size} resets in a few minutes)")
                                    status = "$resetSays · reconnecting…"
                                }
                            }
                            connect(app, ask = false)
                            main.postDelayed({ if (receiver != null) connect(app) }, com.rfsentinel.app.usb.UsbAttachActivity.ATTACH_GRACE_MS)
                        }
                        ACTION_PERMISSION, com.rfsentinel.app.usb.UsbAttachActivity.ACTION_READY -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && d.deviceName == runningDevice) { droppedOff(); halt(UNPLUGGED); clearUnpluggedSoon() }
                            else if (d != null && extras.remove(d.deviceName) != null) com.rfsentinel.app.util.HwLogs.sdr.log("TETRA watch: an extra SDR was unplugged")
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
                addAction(com.rfsentinel.app.usb.UsbAttachActivity.ACTION_READY)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        connect(app)
    }

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        halt("")
        onEvent = null
        tuneHz = null
        listen = false
        p25Hold = null
    }

    @Synchronized
    private fun halt(newStatus: String) {
        stop = true
        worker = null
        runningDevice = null
        status = newStatus
    }

    /** The running USB dongle just went away: remembered, to tell a reset (back at once) from an unplug. */
    private fun droppedOff() { goneId = deviceKey.substringBefore('/'); goneAt = System.currentTimeMillis(); resetTune = tuneHz?.let { it to listen } }

    /** The waterfall screen is closing (Android's USB prompt after a reset can close it): keep its frequency for [screenOpened]. */
    fun screenClosed() { if (resetting) tuneHz?.let { resetTune = it to listen } }

    /** The waterfall screen is open: back on the frequency it held before the dongle reset itself. */
    fun screenOpened() {
        val back = resetTune?.takeIf { resetting && tuneHz == null }
        resetTune = null
        if (back == null) return
        tuneHz = back.first; listen = back.second
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: back on " + "%.4f MHz".format(java.util.Locale.US, back.first / 1e6) + " after the reset")
    }

    /** "RTL-SDR unplugged" stays a little, then leaves the hardware status (unless it came back). */
    private fun clearUnpluggedSoon() = main.postDelayed({ if (status == UNPLUGGED) status = "" }, 15_000L)

    /** Whether the dongle [name] is no longer plugged in (no unplug notice needed). A network SDR never is: its socket says when it's lost. */
    private fun gone(name: String): Boolean = !name.startsWith(NET) &&
        runCatching { usb?.deviceList?.values?.none { it.deviceName == name } }.getOrNull() == true

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** [ask]: false right after a plug-in, while Android's own prompt may still give access. */
    @Synchronized
    private fun connect(context: Context, ask: Boolean = true) {
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        usb = mgr
        val all = runCatching { mgr.deviceList.values.toList() }.getOrDefault(emptyList())
        val d = com.rfsentinel.app.usb.UsbPick.pick(context, com.rfsentinel.app.usb.UsbPick.Kind.SDR, all)
        runningDevice?.let { name ->
            if (name.startsWith(NET)) {
                if (d == null) return
                // A plugged-in SDR takes over from the network one.
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: a USB SDR is plugged in - using it instead")
                halt("")
            } else {
                if (!gone(name)) { connectExtras(context, mgr, all, name); return }
                // Unplugged without the system saying so: let that one go so the dongle can start again.
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: $name is gone - starting over")
                halt("")
            }
        }
        if (d == null) {
            val net = RtlTcp.parse(com.rfsentinel.app.util.Prefs.networkSdr(context))
            if (net != null) connectNet(net.first, net.second)
            else com.rfsentinel.app.usb.UsbPick.waitingFor(context, com.rfsentinel.app.usb.UsbPick.Kind.SDR, all)?.let {
                status = "SDR: waiting for $it, picked in Settings"
            }
            return
        }
        deviceLabel = labelOf(d)
        if (!mgr.hasPermission(d)) {
            com.rfsentinel.app.util.HwLogs.sdr.log("%04x:%04x ($deviceLabel) plugged in - asking for USB access".format(d.vendorId, d.productId))
            status = if (resetting) "$resetSays - allow USB access on the prompt to reconnect"
                else "$deviceLabel found - allow USB access on the prompt; if it lists apps, pick RF Sentinel › Always so it reconnects by itself"
            if (!ask) return
            val pi = PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        // An extra one winding down (its TETRA watch was stopped): let it go before it becomes the first.
        extras.remove(d.deviceName)?.join(2_000)
        val conn = mgr.openDevice(d) ?: run { status = "$deviceLabel: couldn't open it"; return }
        deviceKey = "%04x:%04x/%s".format(d.vendorId, d.productId, runCatching { d.serialNumber }.getOrNull().orEmpty())
        launch(d.deviceName, "%04x:%04x".format(d.vendorId, d.productId), { conn.close() }) { device(conn, d) }
        connectExtras(context, mgr, all, d.deviceName)
    }

    private fun device(conn: android.hardware.usb.UsbDeviceConnection, d: UsbDevice): SdrDevice =
        if (HackRf.isHackRf(d.vendorId, d.productId)) HackRf(conn, d, com.rfsentinel.app.util.HwLogs.sdr::log) else RtlSdr(conn, d, com.rfsentinel.app.util.HwLogs.sdr::log)

    /**
     * The SDRs plugged in besides [first] watch the TETRA band nonstop ([TetraWatch]) while Detect TETRA radios is on. Two RTL-SDR
     * Blog V3s have the same USB ID, so they're told apart by their USB device name. Access is asked for one at a time.
     */
    private fun connectExtras(context: Context, mgr: UsbManager, all: List<UsbDevice>, first: String) {
        if (!tetraOn) return
        for (x in com.rfsentinel.app.usb.UsbPick.candidates(com.rfsentinel.app.usb.UsbPick.Kind.SDR, all)) {
            if (x.deviceName == first || extras.containsKey(x.deviceName)) continue
            if (!mgr.hasPermission(x)) {
                com.rfsentinel.app.util.HwLogs.sdr.log("TETRA watch: a second ${labelOf(x)} is plugged in - asking for USB access")
                val pi = PendingIntent.getBroadcast(context, 3, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
                runCatching { mgr.requestPermission(x, pi) }
                return
            }
            val conn = mgr.openDevice(x) ?: run { com.rfsentinel.app.util.HwLogs.sdr.log("TETRA watch: couldn't open the second ${labelOf(x)}"); null } ?: continue
            launchExtra(x, conn)
        }
    }

    private fun launchExtra(d: UsbDevice, conn: android.hardware.usb.UsbDeviceConnection) {
        val name = d.deviceName
        val rx = "RX${extras.size + 2}"
        val t = Thread({
            val me = Thread.currentThread()
            try {
                val sdr = device(conn, d)
                if (!sdr.open()) { com.rfsentinel.app.util.HwLogs.sdr.log("TETRA watch: $rx (${labelOf(d)}) couldn't start (see the USB log)"); return@Thread }
                val w = TetraWatch(sdr, { centersFor(name) }, rx, { config }, { stop || extras[name] !== me || !tetraOn || gone(name) },
                    { e -> onEvent?.invoke(e) }, com.rfsentinel.app.util.HwLogs.sdr::log)
                tetraWatches[name] = w
                w.run()
                if (!sdr.lost && !gone(name)) sdr.close()
            } catch (e: Exception) {
                com.rfsentinel.app.util.HwLogs.sdr.log("TETRA watch: $rx stopped: ${com.rfsentinel.app.util.HwLog.describe(e)}")
            } finally {
                tetraWatches.remove(name)
                runCatching { conn.close() }
                extras.remove(name, me)
            }
        }, "tetra-sdr")
        extras[name] = t
        t.start()
    }

    /** Which halves of the TETRA band the dongle [key] watches: the first joins in TETRA only, the others always. */
    private fun centersFor(key: String): List<Long> {
        val order = (if (tetraOnly && runningDevice != null) listOf(MAIN) else emptyList()) + extras.keys.sorted()
        val i = order.indexOf(key)
        return if (i < 0) emptyList() else TetraWatch.split(order.size)[i]
    }

    /** Detect TETRA radios or TETRA only changed: extra dongles start (or stop on their own). */
    fun tetraSettingsChanged() { synchronized(this) { if (receiver != null) appContext?.let { connect(it) } } }

    /** TETRA only: the first dongle watches its share of the band until that's turned off or the screen needs it. */
    private fun runTetra(sdr: SdrDevice) {
        mode = null
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: TETRA only - the other scans pause")
        status = deviceLabel
        val w = TetraWatch(sdr, { centersFor(MAIN) }, "RX1", { config },
            { halted || sdr.lost || !tetraOnly || tuneHz != null || finder != null || antennaCheck != null || reapply },
            { e -> onEvent?.invoke(e) }, com.rfsentinel.app.util.HwLogs.sdr::log)
        tetraWatches[MAIN] = w
        try { w.run() } finally { tetraWatches.remove(MAIN) }
        status = "$deviceLabel · live"
    }

    /** Settings › Network SDR: an rtl_tcp server, used while no USB SDR is plugged in. */
    private fun connectNet(host: String, port: Int) {
        deviceLabel = "Network SDR · $host"
        deviceKey = "$host:$port"
        launch("$NET$host:$port", "$host:$port", {}) { RtlTcp(host, port, com.rfsentinel.app.util.HwLogs.sdr::log) }
    }

    /** Settings › Network SDR changed: a moment after the typing stops, drop the old server and try the new one. */
    fun networkChanged() {
        main.removeCallbacks(reconnectNet)
        main.postDelayed(reconnectNet, 2_000L)
    }
    private val reconnectNet = Runnable {
        synchronized(this) {
            if (runningDevice?.startsWith(NET) == true) halt("")
            if (receiver != null) appContext?.let { connect(it) }
        }
    }

    /** Reads [name] on a new thread made by [open]; [release] frees it once the thread is done. */
    private fun launch(name: String, intro: String, release: () -> Unit, open: () -> SdrDevice) {
        runningDevice = name
        stop = false
        val net = name.startsWith(NET)
        // Only a USB dongle resets itself: a network SDR starting in the minute after says what it is.
        status = if (resetting && !net) "$resetSays · reconnecting…" else "$deviceLabel · starting…"
        val previous = lastWorker
        val t = Thread({
            // A quick stop and start: the last session puts the dongle to sleep as it ends (tuner standby, demodulator
            // off). Let it finish first, or that lands in the middle of this session's start.
            runCatching { previous?.join(3_000) }
            val sdr = open()
            val result = runCatching { run(sdr, name, intro) }
            // An antenna check still waiting (the SDR failed or was unplugged): answer it, never silence.
            antennaCheck?.let { ask -> antennaCheck = null; ask(AntennaReading(0, 0, 0, 0, ok = false)) }
            val failure = result.exceptionOrNull()
            if (net) sdr.close()
            runCatching { release() }
            synchronized(this) {
                if (worker !== Thread.currentThread()) return@synchronized
                worker = null
                runningDevice = null
                // Never leave the last "live" line behind: unplugged, failed, or just stopped answering.
                if (gone(name) || result.getOrNull() == true) {
                    com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ${if (net) "can't reach it" else "unplugged or not answering"}")
                    if (!net) droppedOff()
                    status = if (net) "$deviceLabel: " + ((sdr as? RtlTcp)?.why?.let { "can't connect, $it" } ?: "connection lost") + ", retrying" else UNPLUGGED
                    if (!net) clearUnpluggedSoon()
                    // Plugged back in (it gets a new name), only stuck, or the network server may be back: try again shortly.
                    appContext?.let { main.postDelayed({ if (receiver != null) connect(it) }, if (net) NET_RETRY_MS else 3_000L) }
                } else if (failure != null) {
                    com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel stopped: ${com.rfsentinel.app.util.HwLog.describe(failure)}")
                    status = "$deviceLabel stopped: ${failure.message ?: "unknown error"} · trying again in ${ERROR_RETRY_MS / 1000} s"
                    appContext?.let { main.postDelayed({ if (receiver != null) connect(it) }, ERROR_RETRY_MS) }
                }
            }
        }, "rtl-sdr")
        lastWorker = t
        worker = t
        t.start()
    }

    /** Reads until stopped; true when it ended because the dongle went away (unplugged or not answering). */
    private fun run(sdr: SdrDevice, name: String, intro: String): Boolean {
        com.rfsentinel.app.util.HwLogs.sdr.log("=== $deviceLabel radio activity watch ($intro) ===")
        if (!sdr.open()) {
            if (sdr.lost || gone(name)) return true
            // Control transfers failing: the dongle is stuck (seen after a strong signal), not an unknown tuner.
            status = if (sdr is HackRf) "HackRF: couldn't start it (see the USB log)"
                else if (sdr.ctlErrors > 0) "$deviceLabel isn't answering. Unplug it and plug it back in"
                else "RTL-SDR: this tuner isn't supported yet (R820T / R820T2 / R860 / R828D only)"
            return false
        }
        sweepWatch = null; p25Hunt = null; p25Lock = null; pagerHz = null
        biasOn = false; freqFixes = 0; adsbGain = AdsbGain()
        applyDeviceSettings(sdr, start = true)
        var tuned = false
        // The scan the open waterfall cut short: its turn again once the screen closes.
        var resume: Mode? = null
        while (!halted && !sdr.lost && !gone(name)) {
            // Settings changed mid-turn: apply them, then the interrupted scan carries on.
            if (reapply) { applyDeviceSettings(sdr, start = false); tuned = true }
            antennaCheck?.let { ask -> antennaCheck = null; ask(runCatching { checkAntenna(sdr) }.getOrDefault(AntennaReading(0, 0, 0, 0, ok = false))); tuned = true }
            if (finder != null) { runFinder(sdr, name); tuned = true; continue }
            if (tuneHz != null) { runTuned(sdr, name); tuned = true; continue }
            if (tetraOnly) { runTetra(sdr); tuned = true; continue }
            val held = sweepHeld
            if (held && mode != Mode.SWEEP) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: radio sweep while the waterfall is open")
            val (m, after) = pickTurn(held, mode, tuned, resume, active)
            resume = after
            tuned = false
            if (m == null) {
                mode = null
                status = "$deviceLabel · idle: no scan ticked in Settings › RTL-SDR radio"
                Thread.sleep(500); continue
            }
            val turn = turnMs[m] ?: 60_000L
            if (!held && active.size > 1) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: turn: ${m.label} for ${turn / 1000} s")
            // Held for the waterfall in another scan's place: no sweep turn left once the screen closes, the cut-short scan
            // goes on at once (it ran on for the rest of a 30 s turn).
            synchronized(turnLock) {
                stayed = false; turnStart = System.currentTimeMillis()
                mode = m; turnEnd = turnStart + if (held && after != null) 0L else turn
            }
            when (m) {
                Mode.SWEEP -> runSweep(sdr, name)
                Mode.ADSB -> runAdsb(sdr, name)
                Mode.P25 -> runP25(sdr, name)
                Mode.PAGER -> runPager(sdr, name)
                Mode.TPMS -> runTpms(sdr, name)
            }
        }
        if (sdr.lost) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the dongle stopped answering (unplugged?)")
        else if (halted) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: stopped (scanning stopped or the SDR scans switched off)")
        val away = sdr.lost || gone(name)
        if (!away) sdr.close()
        return away && !halted
    }

    /** A radio's GPS position from a P25 data packet: to the talkgroup screen only, never to the log. */
    private fun position(p: P25Tsbk.Position) { p25Log.add(p, System.currentTimeMillis()) }

    /** How many data packets a followed channel carried (counts only). */
    private fun dataNote(f: P25Follow) = if (f.dataPackets > 0) ", ${f.dataPackets} data packets" else ""

    private fun runAdsb(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(ModeS.SAMPLE_RATE)
        val gain = adsbGain
        sdr.setGain(gain.tenths)
        if (!sdr.tune(ModeS.FREQ_HZ)) {
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the tuner didn't lock on 1090 MHz")
            status = "$deviceLabel · ADS-B: the tuner didn't lock on 1090 MHz"
            var waited = 0
            while (!halted && keeps(Mode.ADSB) && waited < 50) { Thread.sleep(100); waited++ }
            return
        }
        sdr.resetBuffer()
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B receiver on 1090 MHz, ${sdr.sampleRate} S/s, gain ${gain.tenths / 10.0} dB")
        status = "$deviceLabel · ADS-B · listening on 1090 MHz…" + turnNote()
        val xfer = STREAM_XFER
        val buf = ByteArray(xfer)
        val mag = IntArray(xfer / 2 + 2 * ModeS.OVERLAP)
        var have = 0
        val demod = ModeS.Demod()
        val tracker = AdsbTracker()
        val frames = ArrayList<ModeS.Frame>()
        val loggedPlanes = HashSet<String>()
        var lastPush = 0L; var lastLog = System.currentTimeMillis(); var fails = 0; var messages = 0L; var logged = 0L
        var lastCheck = System.currentTimeMillis()
        var blockSamples = 0
        // A reader thread keeps a read waiting on the dongle while the last one is decoded: no samples lost in between.
        val ahead = ReadAhead(sdr, xfer)
        try { while (!halted && !sdr.lost && keeps(Mode.ADSB)) {
            val n = ahead.read(buf)
            // Every couple of seconds: still plugged in? (Reads alone can fail quietly for a long time.)
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            if (n <= 0) {
                if (++fails % STALL_CHUNKS == 0) {
                    if (fails <= STALL_CHUNKS * 3) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: no samples for $fails reads in a row - restarting the stream")
                    if (!halted) status = "$deviceLabel · ADS-B · " + stalledLine(fails / STALL_CHUNKS, sdr is RtlTcp) + turnNote()
                    sdr.resetBuffer()
                }
                continue
            }
            fails = 0
            ModeS.magnitudes(buf, n / 2, mag, have)
            val total = have + n / 2
            if (total < 2 * ModeS.OVERLAP) { have = total; continue }
            frames.clear()
            demod.scan(mag, total, frames, gain.quiet)
            // Once a second: the gain the noise floor asks for.
            blockSamples += n / 2
            if (blockSamples >= sdr.sampleRate) {
                blockSamples = 0
                gain.endOfBlock()?.let { c ->
                    sdr.setGain(c.tenths)
                    com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B gain ${c.tenths / 10.0} dB, ${gain.rangeDb.roundToInt()} dB above the noise: ${c.why}")
                }
            }
            val now = System.currentTimeMillis()
            val me = location?.invoke()
            for (f in frames) tracker.add(f, now, me?.first, me?.second)
            messages += frames.size
            adsbMessages += frames.size
            System.arraycopy(mag, total - ModeS.OVERLAP, mag, 0, ModeS.OVERLAP)
            have = ModeS.OVERLAP
            if (now - lastPush >= ADSB_PUSH_MS) {
                lastPush = now
                tracker.prune(now)
                val located = tracker.located(now)
                val unlocated = tracker.unlocated(now)
                // Each aircraft once per turn; no position (it would be near you).
                if (logAll) {
                    for (p in located) if (loggedPlanes.add(p.hex)) logHeard("aircraft ${p.hex}" + (p.callsign?.let { " $it" } ?: "") + (p.altitudeFt?.let { " at $it ft" } ?: ""))
                    for (hex in unlocated) if (loggedPlanes.add(hex)) logHeard("aircraft $hex, no position")
                }
                onAircraft?.invoke(located, unlocated)
                if (!halted) status = "$deviceLabel · ADS-B · ${located.size + unlocated.size} aircraft heard, ${located.size} with a position" + turnNote()
            }
            if (now - lastLog >= 60_000) {
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B ${messages - logged} messages in ${(now - lastLog) / 1000} s, " +
                    "${demod.preambles} preambles, ${demod.fixed} fixed, ${tracker.count} aircraft, gain ${gain.tenths / 10.0} dB, " +
                    "${gain.rangeDb.roundToInt()} dB above the noise, USB errors ${sdr.ctlErrors}")
                lastLog = now; logged = messages
            }
        } } finally { ahead.close() }
        // Only its turn ended: the aircraft stay listed until they go stale, and come back on the next turn.
        if (Mode.ADSB !in active) onAircraft?.invoke(emptyList(), emptyList())
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B receiver stopped after $messages messages")
    }

    private fun runSweep(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        var cfg = sweepConfig()
        var plan = RadioWatch.plan(cfg)
        val watch = sweepWatch ?: RadioWatch().also { sweepWatch = it }
        watch.config = cfg
        fun logPlan() = com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ${sdr.sampleRate} S/s, gain ${GAIN_TENTHS_DB / 10.0} dB, ${plan.size} chunks per sweep over " +
            cfg.bands.joinToString { "%.1f-%.1f MHz".format(it.startHz / 1e6, it.endHz / 1e6) } +
            (if (cfg.targets.isNotEmpty()) ", ${cfg.targets.size} watched frequencies" else "") +
            ", reporting from ${cfg.minSnrDb} dB, USB errors ${sdr.ctlErrors}")
        logPlan()
        val buf = ByteArray(XFER)
        val iq = ByteArray(XFER * maxOf(READS_PER_CHUNK, SLOT_READS))
        var lastLog = 0L; var sweepStart = System.currentTimeMillis(); var sweepMs = 0L
        var events = 0; var unlocked = 0; var readErrors = 0; var failedChunks = 0; var emptyChunks = 0
        // Each band's noise floor since the last log line (sum of chunk medians, chunks): a noisy band or a dead antenna shows here.
        val noise = java.util.TreeMap<Long, Pair<Double, Int>>()
        var lastCheck = System.currentTimeMillis()
        // The waterfall's row for this sweep, while it is on screen.
        // Drawn chunk by chunk: each new chunk redraws this sweep's line, the chunks not reached yet from the sweep before.
        var row = HashMap<Long, SdrWaterfall.Segment>()
        var lastRow = HashMap<Long, SdrWaterfall.Segment>()
        var sweepId = ++sweepLines
        fun bandOf(center: Long) = cfg.bands.firstOrNull { RadioWatch.covers(it, center) }?.label
        // Each chunk's empty segment, made once per plan (sorted by centre): show() runs for every chunk.
        val blanks = java.util.TreeMap<Long, SdrWaterfall.Segment>()
        var blanksFor: List<Long>? = null
        fun show(center: Long, seg: SdrWaterfall.Segment) {
            val l = SdrWaterfall.listener ?: return
            row[center] = seg
            if (blanksFor !== plan) {
                blanksFor = plan; blanks.clear(); plan.forEach { c -> blanks[c] = SdrWaterfall.blank(c, sdr.sampleRate, RadioWatch.USABLE_HZ, bandOf(c)) }
            }
            // Every chunk has the same span, so centre order is start order.
            l(SdrWaterfall.Row(blanks.keys.map { c -> row[c] ?: lastRow[c] ?: blanks.getValue(c) },
                tuned = false, at = System.currentTimeMillis(), sweep = sweepId))
        }
        // "Log everything": every channel heard in this sweep.
        val heard = ArrayList<P25Hunt.Candidate>()
        var lastIdentify = 0L; var identified = 0; var p25Found = 0; var played = 0
        // Play voice transmissions: a channel played for the whole PLAY_MAX_MS (a stuck carrier) is left alone a while.
        val stuck = HashMap<Long, Long>()
        // The walkie-talkie channel last played: a conversation's reply is caught between chunks instead of a sweep later.
        var hot: Pair<RadioWatch.Event, Long>? = null; var hotUntil = 0L; var hotChecked = 0L
        fun hotOn(): Boolean {
            val now = System.currentTimeMillis()
            if (hot == null || now > hotUntil || !sweepListen || SdrWaterfall.listening) return false
            hotChecked = now
            return (measureInput(sdr, hot!!.second) ?: 0) >= RadioWatch.activeDb(cfg.minSnrDb)
        }
        fun play(e: RadioWatch.Event, hz: Long) {
            val from = System.currentTimeMillis()
            val ok = playTransmission(sdr, e, hz)
            if (ok) played++
            val now = System.currentTimeMillis()
            if (now - from >= PLAY_MAX_MS) stuck[e.freqHz] = now + STUCK_MS
            // Nothing heard, or a stuck carrier: no longer checked (it would hold the sweep).
            if (ok && e.walkie && now - from < PLAY_MAX_MS) { hot = e to hz; hotUntil = now + HOT_MS }
            else if (hot?.first?.freqHz == e.freqHz) hot = null
            sdr.setSampleRate(SAMPLE_RATE); sdr.setGain(GAIN_TENTHS_DB); sdr.resetBuffer()
        }
        while (!halted && !sdr.lost && keeps(Mode.SWEEP)) {
            // Settings changed: the new bands / targets / sensitivity from this sweep on.
            if (sweepConfig() != cfg) { cfg = sweepConfig(); plan = RadioWatch.plan(cfg); watch.config = cfg; logPlan() }
            for (center in plan) {
                if (halted || sdr.lost || !keeps(Mode.SWEEP)) break
                if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
                if (System.currentTimeMillis() - hotChecked >= HOT_CHECK_MS && hotOn()) hot?.let { (e, hz) -> play(e, hz) }
                var hotLooked = false
                fun skipped() {
                    if (SdrWaterfall.listening) show(center, SdrWaterfall.blank(center, sdr.sampleRate, RadioWatch.USABLE_HZ, bandOf(center)))
                }
                if (!sdr.tune(center)) { unlocked++; skipped(); continue }
                repeat(sdr.settleReads) { sdr.read(buf) }
                // TETRA and DMR chunks also keep the power in 2 ms steps, to see a radio's time-slot bursts.
                val slotted = cfg.bands.any { cfg.slotted(it) && RadioWatch.covers(it, center) }
                var got = 0
                for (i in 0 until if (slotted) SLOT_READS else READS_PER_CHUNK) {
                    val n = sdr.read(buf)
                    if (n <= 0) { readErrors++; break }
                    System.arraycopy(buf, 0, iq, got, n); got += n
                }
                if (got < 2 * 1024) {
                    // The sample stream stalled: restart it (as librtlsdr's reset_buffer) instead of sweeping on with nothing.
                    if (++failedChunks % STALL_CHUNKS == 0) {
                        if (failedChunks <= STALL_CHUNKS * 3) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: no samples for $failedChunks chunks in a row - restarting the stream")
                        if (!halted) status = "$deviceLabel · " + stalledLine(failedChunks / STALL_CHUNKS, sdr is RtlTcp) + turnNote()
                        sdr.resetBuffer()
                    }
                    emptyChunks++
                    skipped()
                    continue
                }
                failedChunks = 0
                val bursts = if (slotted) RadioWatch.burstSpectrum(iq, got / 2, sdr.sampleRate) else null
                val psd = bursts?.psd ?: RadioWatch.powerSpectrum(iq, got / 2)
                cfg.bands.firstOrNull { RadioWatch.covers(it, center) }?.let { b ->
                    val med = psd.sorted()[psd.size / 2].toDouble()
                    noise.merge(b.startHz, med to 1) { a, c -> (a.first + c.first) to (a.second + c.second) }
                }
                if (SdrWaterfall.listening) show(center, SdrWaterfall.segment(center, psd, sdr.sampleRate, RadioWatch.USABLE_HZ, bandOf(center)))
                if (logAll) heard += P25Hunt.candidates(center, psd, sdr.sampleRate)
                // Play voice transmissions: the first new one in this chunk, once the others are reported. Not while the
                // waterfall is open (it holds the sweep to draw; Tune › Listen plays there).
                var toPlay: Pair<RadioWatch.Event, Long>? = null
                val playing = sweepListen && !SdrWaterfall.listening
                watch.walkies = playing
                val chunkAt = System.currentTimeMillis()
                fun playable(e: RadioWatch.Event) = playing && toPlay == null && (stuck[e.freqHz] ?: 0L) <= chunkAt
                for (found in watch.analyze(center, psd, sdr.sampleRate, chunkAt, bursts)) {
                    // Swept only for the open waterfall (radio sweep not ticked): nothing reported.
                    if (Mode.SWEEP !in active) continue
                    var e = found
                    // A walkie-talkie: played (a reply within minutes too: it's a conversation), never reported. Strongest first.
                    if (e.walkie) { if (playable(e)) toPlay = e to RadioWatch.walkieHz(e.freqHz, center, psd, sdr.sampleRate); continue }
                    if (!e.repeat && playing && identifies(e)) {
                        // Before a check or play of anything else (often a ghost the walkie's own strong signal makes): its reply first.
                        if (playable(e) && !hotLooked) { hotLooked = true; if (hotOn()) toPlay = hot }
                        val on = RadioWatch.activeDb(cfg.minSnrDb)
                        val hz = peakHz(e, center, psd, sdr.sampleRate)
                        // The tuner's image of a walkie-talkie out of this chunk: the walkie is played now, not a sweep later.
                        val w = sdr.imageHz(center, hz)?.let { RadioWatch.walkieChannel(it, cfg) }?.let { e.copy(freqHz = it, walkie = true) }
                        if (w != null && playable(w) && (measureInput(sdr, w.freqHz) ?: 0) >= on) {
                            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ${e.mhz} MHz is the tuner's image of a walkie-talkie (${w.mhz} MHz): played there")
                            toPlay = w to w.freqHz; continue
                        }
                        // Gone when looked at on its own (another tuning): a ghost or a short burst, not checked, played or reported.
                        if ((measureInput(sdr, hz) ?: 0) < on) {
                            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ${e.mhz} MHz gone when looked at again (a ghost or a short burst): not reported")
                            continue
                        }
                    }
                    if (!e.repeat) {
                        events++
                        // A new transmission: listen for a moment to tell a P25 radio (conventional, which no control channel lists) from the rest.
                        // Always when it's to be played: analog and P25 voice are played differently.
                        // Not once one is picked to play: the check would hold it back, and a short transmission be over.
                        if (identifies(e) && (playable(e) || System.currentTimeMillis() - lastIdentify >= IDENTIFY_GAP_MS && toPlay == null)) {
                            lastIdentify = System.currentTimeMillis()
                            identified++
                            identifyP25(sdr, e, center, psd)?.let { h ->
                                p25Found++; activity(Mode.SWEEP)
                                e = e.copy(p25Nac = h.nac, confidence = minOf(e.confidence + P25_BONUS, P25_MAX_CONFIDENCE))
                            }
                        }
                        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: transmission ${e.mhz} MHz, ${e.snrDb} dB above the noise (${e.band.label})" +
                            (e.slots?.let { ", $it time slots" } ?: "") + (if (e.p25Nac != null) ", P25" + (if (logAll) " NAC %03X".format(e.p25Nac) else "") else ""))
                        if (playable(e) && identifies(e)) toPlay = e to peakHz(e, center, psd, sdr.sampleRate)
                    }
                    onEvent?.invoke(e)
                }
                toPlay?.let { (e, hz) -> play(e, hz) }
            }
            if (!keeps(Mode.SWEEP)) break // cut short: not a whole sweep
            watch.sweepDone()
            if (logAll) logHeard("sweep ${watch.sweeps}: ${P25Hunt.heard(heard)}")
            heard.clear()
            val now = System.currentTimeMillis()
            // The next chunk starts a new line; every line has the whole plan's layout (chunks not heard as blanks).
            if (row.isNotEmpty()) lastRow = row
            row = HashMap(); sweepId = ++sweepLines
            sweepMs = now - sweepStart; sweepStart = now
            if (gone(name)) break
            if (!halted) status = (if (watch.sweeps < 6) "$deviceLabel · learning the radio bands here…"
                     else "$deviceLabel · live · sweep ${sweepMs / 100 / 10.0} s · $events transmissions nearby so far") + turnNote()
            if (now - lastLog > (if (watch.sweeps < 20) 10_000 else 60_000)) {
                lastLog = now
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: sweep ${watch.sweeps} in $sweepMs ms, ${watch.learnedChannels} channels, ${watch.busyChannels} always busy, " +
                    "$events reported ($identified checked for P25, $p25Found were, $played played), PLL unlocked $unlocked, read errors $readErrors, chunks without samples $emptyChunks, " +
                    "tuner write failures ${sdr.tunerWriteFails}, USB errors ${sdr.ctlErrors}, noise " +
                    noise.entries.joinToString { (hz, v) -> "%.0f MHz %.0f dB".format(hz / 1e6, v.first / v.second) })
                noise.clear()
            }
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: radio sweep stopped after ${watch.sweeps} sweeps")
    }

    /** What the sweep covers: the TETRA band only while no extra dongle watches it. */
    private fun sweepConfig() = config.let { c -> if (tetraWatched) c.copy(bands = c.bands - RadioWatch.TETRA_BAND) else c }

    /** Worth a P25 check: a two-way radio band (not cellular, not TETRA), and no time slots (P25 Phase 1 is continuous). */
    private fun identifies(e: RadioWatch.Event) = !e.tdma && e.band.kind != RadioWatch.Kind.CELLULAR_UPLINK && e.band.kind != RadioWatch.Kind.TETRA

    /**
     * Is [e] a P25 radio? The sweep's channel grid is 12.5 kHz, so the peak in [psd] (tuned to [center]) near it gives the
     * real centre first; then a short listen at the P25 sample rate. The sweep's rate is put back after.
     */
    private fun identifyP25(sdr: SdrDevice, e: RadioWatch.Event, center: Long, psd: FloatArray): P25Hunt.Heard? {
        val hz = peakHz(e, center, psd, sdr.sampleRate)
        sdr.setSampleRate(P25Demod.SAMPLE_RATE)
        try { return P25Hunt.identify(sdr, hz, IDENTIFY_S) { halted || sdr.lost } }
        finally { sdr.setSampleRate(SAMPLE_RATE); sdr.resetBuffer() }
    }

    /** [e]'s real centre: the peak in [psd] (tuned to [center]) nearest it, as the sweep's channel grid is 12.5 kHz. */
    private fun peakHz(e: RadioWatch.Event, center: Long, psd: FloatArray, rate: Int) =
        P25Hunt.candidates(center, psd, rate, 3.0).minByOrNull { kotlin.math.abs(it.hz - e.freqHz) }
            ?.hz?.takeIf { kotlin.math.abs(it - e.freqHz) <= RadioWatch.STEP_HZ } ?: e.freqHz

    /**
     * Play voice transmissions: [e], at [hz], played through the speaker until it goes quiet. Analog radios (walkie-talkies,
     * businesses) as narrow FM with its squelch; a P25 or NXDN radio's clear voice decoded, an encrypted one ends it. The sweep
     * goes on after. False when nothing was played.
     */
    private fun playTransmission(sdr: SdrDevice, e: RadioWatch.Event, hz: Long): Boolean {
        val p25 = e.p25Nac != null
        val mhz = "%.4f MHz".format(java.util.Locale.US, hz / 1e6)
        sdr.setSampleRate(if (p25) P25Demod.SAMPLE_RATE else SAMPLE_RATE)
        if (!sdr.tune(hz - INPUT_OFFSET_HZ)) return false
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        var out: android.media.AudioTrack? = null
        val heard = if (saveCalls) ArrayList<ShortArray>() else null
        val follow = if (!p25) null else P25Follow(INPUT_OFFSET_HZ.toDouble(), onPosition = ::position) { pcm ->
            heard?.add(pcm.copyOf()); out?.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
            com.rfsentinel.app.util.Voice.radioHeard()
        }
        var nxdn: Nxdn? = null
        var dmr: Dmr? = null
        val demod = if (p25) null else AudioDemod(AudioDemod.modeFor(hz), INPUT_OFFSET_HZ, sdr.sampleRate) { pcm, n ->
            // A digital radio's FM audio is its data's hiss: its decoded voice is played instead.
            if (nxdn?.active != true && dmr?.active != true) { heard?.add(pcm.copyOf(n)); out?.write(pcm, 0, n) }
        }
        // Narrow FM: MDC-1200 bursts name the radio (unit ID) or flag an emergency.
        var mdcUnit: Mdc1200.Packet? = null; var mdcCount = 0
        demod?.takeIf { it.mode == AudioDemod.Mode.NFM }?.let { d ->
            val mdc = Mdc1200(d.audioRate) { p ->
                mdcCount++; if (mdcUnit?.emergency != true) mdcUnit = p
                status = "$deviceLabel · playing $mhz · ${p.label}" + turnNote()
            }
            // NXDN digital voice (Kenwood NEXEDGE, Icom IDAS) on the same channel: decoded when clear.
            val nx = Nxdn(d.audioRate, d.audioRate) { pcm ->
                heard?.add(pcm); out?.write(pcm, 0, pcm.size)
                com.rfsentinel.app.util.Voice.radioHeard()
            }.also { nxdn = it }
            // DMR digital voice (MOTOTRBO, Hytera, most business radios): decoded when clear.
            val dm = Dmr(d.audioRate, d.audioRate) { pcm ->
                heard?.add(pcm); out?.write(pcm, 0, pcm.size)
                com.rfsentinel.app.util.Voice.radioHeard()
            }.also { dmr = it }
            d.tap = { v -> mdc.sample(v); nx.sample(v); dm.sample(v) }
        }
        var digital = ""; var cipher = -1
        val track = runCatching { newSpeaker(demod?.audioRate ?: ImbeDecoder.RATE).also { it.play() } }
            .onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: no audio output to play a transmission - ${com.rfsentinel.app.util.HwLog.describe(it)}") }
            .getOrNull() ?: return false
        out = track
        activity(Mode.SWEEP)
        status = "$deviceLabel · playing $mhz${if (p25) " · P25" else ""}" + turnNote()
        val start = System.currentTimeMillis()
        var heardAt = start; var heardMs = 0L; var fails = 0
        // A radio held next to the dongle overloads it: clipped samples and ghosts 350 kHz away, digital voice unreadable.
        var gain = GAIN_TENTHS_DB; var gainAt = 0L; var clipped = 0L; var bytes = 0L
        val ahead = ReadAhead(sdr, buf.size)
        try {
            while (!halted && !sdr.lost && sweepListen && keeps(Mode.SWEEP) && !SdrWaterfall.listening &&
                System.currentTimeMillis() - start < PLAY_MAX_MS) {
                val n = ahead.read(buf)
                if (n <= 0) { if (++fails >= STALL_CHUNKS) break; continue }
                fails = 0
                val now = System.currentTimeMillis()
                var clip = 0
                for (i in 0 until n) { val b = buf[i].toInt() and 0xff; if (b == 0 || b == 255) clip++ }
                clipped += clip; bytes += n
                if (clip * 200 > n && gain > AdsbGain.STEPS.first() && now - gainAt >= 300) {
                    gain = P25Hunt.lowerGain(gain); sdr.setGain(gain); gainAt = now
                }
                if (demod != null) {
                    demod.feed(buf, n / 2)
                    nxdn?.takeIf { it.frames > 0 }?.let { x ->
                        if (x.cipher >= 0) cipher = x.cipher
                        val note = "digital radio (NXDN)" + Nxdn.cipherLabel(cipher).let { if (it.isEmpty()) "" else " · $it" }
                        if (note != digital) { digital = note; status = "$deviceLabel · playing $mhz · $note" + turnNote(); RadioLog.digital(e.freqHz, note) }
                    }
                    dmr?.takeIf { it.bursts > 0 }?.let { x ->
                        if (x.cipher >= 0) cipher = x.cipher
                        val note = "digital radio (DMR)" + Dmr.cipherLabel(cipher).let { if (it.isEmpty()) "" else " · $it" }
                        if (note != digital) { digital = note; status = "$deviceLabel · playing $mhz · $note" + turnNote(); RadioLog.digital(e.freqHz, note) }
                    }
                    // Encrypted: nothing to hear, the sweep goes on (as for P25).
                    if (cipher > 0) break
                    if (demod.open || nxdn?.active == true || dmr?.active == true) { heardMs += now - heardAt; heardAt = now; com.rfsentinel.app.util.Voice.radioHeard() }
                    else if (now - heardAt >= if (heardMs == 0L) PLAY_START_MS else PLAY_QUIET_MS) break
                } else if (follow != null) {
                    follow.feed(buf, n / 2)
                    if (follow.ended || follow.quietS >= (if (follow.frames == 0) FOLLOW_START_S else FOLLOW_QUIET_S)) break
                }
            }
        } finally { ahead.close(); track.stop(); track.release() }
        val secs = if (follow != null) follow.frames * 0.18 else if (cipher > 0) 0.0 else heardMs / 1000.0
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: transmission ${if (logAll) "$mhz " else ""}(${e.band.label}) " +
            (if (secs > 0) "played (%.1f s)".format(java.util.Locale.US, secs) else "not played") +
            (if (follow != null) ", P25" + (follow.endedBy?.let { ", ended: $it" } ?: "") + dataNote(follow) else "") +
            (nxdn?.takeIf { it.frames > 0 }?.let { x -> nxdnNote(x, cipher) } ?: "") +
            (dmr?.takeIf { it.bursts > 0 || it.dataSyncs > 0 }?.let { x -> dmrNote(x) } ?: "") +
            (if (clipped > 0) ", %.2f%% samples clipped".format(java.util.Locale.US, 100.0 * clipped / maxOf(1L, bytes)) else "") +
            (if (gain != GAIN_TENTHS_DB) ", gain lowered to ${gain / 10.0} dB (overload)" else "") +
            // Unit IDs stay off the log unless "log everything" is on.
            (if (mdcCount > 0) ", $mdcCount MDC-1200 bursts" + (mdcUnit?.let { u -> if (logAll) " (${u.label})" else if (u.emergency) " (emergency)" else "" } ?: "") else "") +
            (if (logAll) follow?.talker?.let { ", radio $it" } ?: "" else ""))
        val ctx = appContext
        if (heard != null && secs > 0 && ctx != null)
            runCatching { P25Record.saveTransmission(ctx, start, hz, heard, demod?.audioRate ?: ImbeDecoder.RATE,
                mdcUnit?.let { "u" + it.unitText } ?: follow?.talker?.let { "r$it" }) }
                .onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: transmission sound not saved - ${com.rfsentinel.app.util.HwLog.describe(it)}") }
        return secs > 0
    }

    /** What the NXDN decoder made of a transmission, for the log: frames, voice, the cipher; IDs only with "log everything". */
    private fun nxdnNote(x: Nxdn, cipher: Int) = ", NXDN ${x.frames} frames (${x.badFrames} bad), ${x.voiceFrames} voice" +
        (if (x.voiceFrames > 0) " (%.1f bits fixed each)".format(java.util.Locale.US, x.ambeErrors.toDouble() / x.voiceFrames) else "") +
        (if (x.longestGap >= 0.2) ", longest gap %.1f s".format(java.util.Locale.US, x.longestGap) else "") +
        (if (x.encryptedFrames > 0) ", ${x.encryptedFrames} encrypted" else "") +
        ", " + (Nxdn.cipherLabel(cipher).ifEmpty { "cipher not heard" }) +
        (if (logAll && x.source >= 0) ", unit ${x.source} to ${x.destination}" else "")

    /** What the DMR decoder made of a transmission, for the log: bursts, voice, privacy; IDs only with "log everything". */
    private fun dmrNote(x: Dmr) = ", DMR ${x.bursts} bursts (${x.badBursts} bad), ${x.dataSyncs} data, ${x.voiceFrames} voice" +
        (if (x.voiceFrames > 0) " (%.1f bits fixed each)".format(java.util.Locale.US, x.ambeErrors.toDouble() / x.voiceFrames) else "") +
        (if (x.longestGap >= 0.2) ", longest gap %.1f s".format(java.util.Locale.US, x.longestGap) else "") +
        (if (x.encryptedFrames > 0) ", ${x.encryptedFrames} encrypted" else "") +
        (if (x.heldFrames > 0) ", ${x.heldFrames} before the privacy was known" else "") +
        ", ${x.linkControls} link controls" + (if (x.polarityFlips > 0) ", polarity flipped ${x.polarityFlips}×" else "") +
        (if (logAll && x.source >= 0) ", unit ${x.source} to ${x.destination}" else "")

    /** Finds a P25 control channel and follows it; looks again when it goes quiet or none was found. */
    private fun runP25(sdr: SdrDevice, name: String) {
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        val hunt = p25Hunt ?: P25Hunt(sdr, readAhead = true, logAll = { logAll },
            bands = { P25Hunt.bandsFor(appContext?.let { com.rfsentinel.app.util.Prefs.p25Bands(it) } ?: P25Hunt.DEFAULT_KEYS) },
            gainTenths = GAIN_TENTHS_DB) { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: $it") }.also { p25Hunt = it }
        var lastCheck = System.currentTimeMillis(); var away = false
        val stop = {
            val now = System.currentTimeMillis()
            if (now - lastCheck >= 2_000L) { lastCheck = now; away = gone(name) }
            halted || sdr.lost || away || !keeps(Mode.P25)
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 control channel receiver, gain ${GAIN_TENTHS_DB / 10.0} dB")
        while (!stop()) {
            // Its last turn's control channel first: no new search when it is still there.
            val known = p25Lock
            if (known == null) status = "$deviceLabel · P25 · looking for a control channel…" + turnNote()
            // Before a scan: the last one that decoded, the site's other control channels (and its neighbours') as this
            // dongle hears them, then the older ones found on earlier runs.
            val lock = known ?: hunt.hunt(firstTries(rememberedLocks(), alternates()), stop)
            if (lock == null) {
                if (stop()) break
                val wait = if (hunt.weakSeen) P25_WEAK_RETRY_MS else P25_RETRY_MS
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 - no control channel decoded (${hunt.tried} channels tried), looking again in ${wait / 1000} s")
                status = "$deviceLabel · P25 · ${hunt.hint ?: "no control channel heard here"} · looking again shortly" + turnNote()
                val until = System.currentTimeMillis() + wait
                while (!stop() && System.currentTimeMillis() < until) Thread.sleep(200)
                continue
            }
            // Frequency and network code stay off the log (together they name the local system), unless "log everything" is on.
            val where = "%.3f MHz · NAC %03X".format(java.util.Locale.US, lock.hz / 1e6, lock.nac)
            com.rfsentinel.app.util.HwLogs.sdr.log((if (known != null) "$deviceLabel: P25 back on the control channel" else "$deviceLabel: P25 control channel found after ${hunt.tried} tries") +
                (if (logAll) " ($where)" else ""))
            p25Lock = lock
            if (known == null) rememberLock(lock)
            if (lock.nac != p25Nac) { p25Log.clear(System.currentTimeMillis()); p25Nac = lock.nac } // another system: a new list
            status = "$deviceLabel · P25 · control channel $where" + turnNote()
            var messages = 0; var lastStatus = 0L
            var pending: Pair<P25History.Call, Long>? = null; var lastInput = 0L; var inputs = 0; var heard = 0
            var toRecord: P25History.Call? = null; var lastRecord = 0L; var recorded = 0
            var toFollow: P25History.Call? = null; var followed = 0
            var lastInbound = System.currentTimeMillis(); var inbounds = 0; val nearby = HashSet<Int>()
            // A call's input: once it's on the air, tune there for a moment and measure it (the control channel comes back after).
            // A call to play goes first, for as long as it lasts; then a call to record, its voice channel for a few seconds.
            val detour = detour@{
                toFollow?.let { call -> toFollow = null; if (followCall(sdr, call, stop)) followed++; return@detour true }
                toRecord?.let { call -> toRecord = null; if (recordCall(sdr, call)) recorded++; return@detour true }
                if (inboundDue(lastInbound)) {
                    lastInbound = System.currentTimeMillis()
                    controlInput(lock)?.let { hz ->
                        inbounds++
                        var strongest: Int? = null
                        val got = P25Hunt.inbound(sdr, hz, lock.nac, INBOUND_S, stop) { msg, snr ->
                            strongest = maxOf(strongest ?: snr, snr)
                            if (snr < RadioWatch.activeDb(config.minSnrDb)) return@inbound
                            val tg = p25Log.heardRadio(msg.radio, snr, System.currentTimeMillis())
                            if (nearby.add(msg.radio) && logAll) logHeard("P25 radio ${msg.radio} ${msg.kind} on the control channel's input, $snr dB" +
                                (tg?.let { ", talkgroup $it" } ?: ""))
                            onP25Nearby?.invoke(msg, snr, tg); activity(Mode.P25)
                        }
                        // For tuning "near you" from real drives: what the input held, decoded or not.
                        got?.let { (n, peak) -> com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 control channel input: $n radio messages" +
                            (strongest?.let { ", strongest $it dB" } ?: "") + ", peak level $peak dB, nearby from ${RadioWatch.activeDb(config.minSnrDb)} dB") }
                    }
                    return@detour true
                }
                val (call, hz) = pending ?: return@detour false
                if (System.currentTimeMillis() - call.time < INPUT_DELAY_MS) return@detour false
                pending = null
                val snr = measureInput(sdr, hz) ?: return@detour true
                inputs++
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 input check: the talking radio $snr dB above the noise, nearby from " +
                    "${RadioWatch.activeDb(config.minSnrDb)} dB" + (if (logAll) " (" + (call.tg?.let { "talkgroup $it" } ?: "private call") + (call.source?.let { ", radio $it" } ?: "") + ")" else ""))
                if (snr >= RadioWatch.activeDb(config.minSnrDb)) {
                    heard++; p25Log.heardInput(call.tg, call.target, snr, System.currentTimeMillis()); onP25Input?.invoke(call, hz, snr)
                    activity(Mode.P25)
                }
                true
            }
            val detourDue = { toFollow != null || toRecord != null || pending?.let { System.currentTimeMillis() - it.first.time >= INPUT_DELAY_MS } == true ||
                inboundDue(lastInbound) }
            val lost = hunt.listen(lock, stop, detour, detourDue) {
                messages++; p25Messages++
                val now = System.currentTimeMillis()
                for (tg in p25Log.add(it, now)) onP25Call?.invoke(tg)
                if (it is P25Tsbk.Site) learnFrequency(hunt.carrierHz, p25Log.hz(it.channel))
                val calls = p25Log.takeCalls()
                if (calls.isNotEmpty()) activity(Mode.P25)
                appContext?.let { c -> P25History.append(c, calls) }
                if (logAll) for (c in calls) logHeard("P25 call" + (c.tg?.let { " talkgroup $it" } ?: "") + (c.target?.let { " to radio $it" } ?: "") +
                    (c.source?.let { " from radio $it" } ?: "") + (c.hz?.let { " on %.4f MHz".format(java.util.Locale.US, it / 1e6) } ?: "") +
                    (if (c.encrypted) ", encrypted" else "") + (if (c.emergency) ", emergency" else "") + (if (c.phase2) ", Phase 2" else ""))
                val hold = p25Hold
                if (p25Inputs && pending == null && now - lastInput >= INPUT_GAP_MS) {
                    calls.lastOrNull { it.source != null && it.channel != null && (hold == null || it.tg == hold) }?.let { c ->
                        p25Log.inputHz(c.channel!!)?.let { hz -> pending = c to hz; lastInput = now }
                    }
                }
                val listen = p25Listen
                if ((listen != Listen.OFF || hold != null) && !p25Mute && toFollow == null) {
                    val ok = calls.filter { !it.encrypted && !it.phase2 && it.hz != null &&
                        (if (hold != null) it.tg == hold else !p25LockedOut(it.tg) && (listen == Listen.ALL || p25IsWatched(it.tg) || p25IsPriority(it.tg))) }
                    (ok.lastOrNull { p25IsPriority(it.tg) } ?: ok.lastOrNull())?.let { toFollow = it }
                    // A priority call that cut the one playing: its channel, as the site repeats it.
                    p25Wanted?.let { (tg, at) ->
                        if (toFollow != null || now - at > PRIORITY_WAIT_MS) p25Wanted = null
                        else p25Log.rows().firstOrNull { r -> r.tg == tg && r.lastAt == now && !r.encrypted && !r.phase2 && r.lastHz != null }?.let { r ->
                            toFollow = P25History.Call(now, r.tg, null, r.lastSource, r.lastChannel, r.lastHz, r.emergency, r.encrypted, null, r.phase2)
                            p25Wanted = null
                        }
                    }
                }
                if (p25Record && hold == null && toRecord == null && now - lastRecord >= RECORD_GAP_MS) {
                    calls.lastOrNull { !it.encrypted && !it.phase2 && it.hz != null && it !== toFollow }?.let { toRecord = it; lastRecord = now }
                }
                if (now - lastStatus >= 2_000L && !halted) { lastStatus = now; status = "$deviceLabel · P25 · control channel $where · $messages messages" + turnNote() }
            }
            if (lost) p25Lock = null
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 ${if (lost) "control channel went quiet" else "turn over or stopped"} after $messages messages (${hunt.blocks} blocks in all), " +
                p25Log.rows().let { r -> "${r.size} talkgroups / private calls, ${r.sumOf { it.calls }} calls" } +
                (if (inputs > 0) ", $inputs input checks, $heard radios heard nearby" else "") +
                (if (inbounds > 0) ", $inbounds control channel input checks, ${nearby.size} radios heard calling the site nearby" else "") +
                (if (recorded > 0) ", $recorded calls recorded" else "") +
                (if (followed > 0) ", $followed calls played" else ""))
        }
        speaker?.release(); speaker = null
    }

    /** Settings › Listen for radios nearby is on, nothing else is waiting, and the last listen to the control channel's input was a while ago. */
    private fun inboundDue(last: Long) = p25Inputs && p25Hold == null && System.currentTimeMillis() - last >= INBOUND_GAP_MS

    /** How this dongle's frequencies relate to the site's own: the control channel as heard over as announced (1 when unknown). */
    private fun dongleRatio(): Double {
        val lock = p25Lock ?: return 1.0
        val announced = p25Log.summary().site?.channel?.let(p25Log::hz) ?: return 1.0
        val r = lock.hz.toDouble() / announced
        return if (kotlin.math.abs(r - 1) <= KNOWN_PPM) r else 1.0
    }

    /** Where the radios transmit to the control channel [lock] (as this dongle hears it), once the site's channel table said so. */
    private fun controlInput(lock: P25Hunt.Lock): Long? {
        val site = p25Log.summary().site ?: return null
        val input = p25Log.inputHz(site.channel) ?: return null
        val down = p25Log.hz(site.channel) ?: return null
        // Only when the channel the site names is the one being listened to (not a secondary that didn't say so).
        if (kotlin.math.abs(lock.hz.toDouble() / down - 1) > KNOWN_PPM) return null
        return kotlin.math.round(input * dongleRatio()).toLong()
    }

    /** The site's other control channels and its neighbours', as this dongle hears them (empty before a site was heard). */
    private fun alternates(): List<Long> {
        val r = dongleRatio()
        return p25Log.alternates().map { kotlin.math.round(it * r).toLong() }.filter { hz -> p25Lock?.let { kotlin.math.abs(it.hz - hz) <= 6_250L } != true }
    }

    /**
     * The last control channel that decoded leads: a site announcing many alternates pushed it past the hunt's first tries.
     * One channel heard a few hundred Hz apart (announced vs measured) is tried once: a tester's log tried it twice in a row.
     */
    internal fun firstTries(remembered: List<Long>, alternates: List<Long>): List<Long> {
        val out = ArrayList<Long>()
        for (hz in remembered.take(1) + alternates + remembered.drop(1)) if (out.none { kotlin.math.abs(it - hz) <= 6_250L }) out += hz
        return out
    }

    /** Control channels this dongle found on earlier runs, newest first (kept on the phone only). */
    private fun rememberedLocks(): List<Long> = appContext?.let { c -> com.rfsentinel.app.util.Prefs.p25Known(c).map { it.hz } }.orEmpty()

    private fun rememberLock(lock: P25Hunt.Lock) { appContext?.let { com.rfsentinel.app.util.Prefs.addP25Known(it, lock) } }

    /**
     * Plays [call] live from its voice channel until it ends, goes quiet, or listening stops ([FOLLOW_MAX_MS] at most,
     * unless its talkgroup is held);
     * false when no voice was heard. Frequency kept off the log.
     */
    private fun followCall(sdr: SdrDevice, call: P25History.Call, stop: () -> Boolean): Boolean {
        val hz = call.hz ?: return false
        val out = speaker ?: runCatching { newSpeaker() }.onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 no audio output - ${com.rfsentinel.app.util.HwLog.describe(it)}") }
            .getOrNull()?.also { speaker = it } ?: return false
        if (!sdr.tune(hz - INPUT_OFFSET_HZ)) return false
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        var started = false
        val heard = if (saveCalls) ArrayList<ShortArray>() else null
        // A priority talkgroup's call announced on this voice channel (not while one is held, nor when this one is a priority).
        var priority: Int? = null
        val otherCall = { _: Int, tg: Int ->
            if (priority == null && p25Hold == null && tg != call.tg && p25IsPriority(tg) && !p25IsPriority(call.tg) && !p25LockedOut(tg)) priority = tg
        }
        val follow = P25Follow(INPUT_OFFSET_HZ.toDouble(), lsm = p25Lock?.lsm == true, grantClear = !call.encrypted, onOtherCall = otherCall,
            onPosition = ::position) { pcm ->
            heard?.add(pcm.copyOf())
            // A little silence first: voice comes in 180 ms bursts, and the buffer shouldn't run dry between them.
            if (!started) { started = true; out.write(ShortArray(ImbeDecoder.RATE / 5), 0, ImbeDecoder.RATE / 5, android.media.AudioTrack.WRITE_NON_BLOCKING) }
            out.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
            com.rfsentinel.app.util.Voice.radioHeard()
        }
        p25Playing = call
        status = "$deviceLabel · P25 · playing ${call.tg?.let { "talkgroup $it" } ?: "a private call"}" + turnNote()
        out.play()
        val start = System.currentTimeMillis()
        var fails = 0
        val ahead = ReadAhead(sdr, buf.size)
        try {
            var talker: Int? = null
            while (!stop() && !p25Mute && !follow.ended && fails < STALL_CHUNKS && priority == null &&
                follow.quietS < (if (follow.frames == 0) FOLLOW_START_S else FOLLOW_QUIET_S)) {
                val hold = p25Hold
                if (if (hold != null) hold != call.tg else p25Listen == Listen.OFF || System.currentTimeMillis() - start >= FOLLOW_MAX_MS) break
                val n = ahead.read(buf)
                if (n <= 0) { fails++; continue }
                fails = 0 // stalls in a row end it, not stalls over a long call
                follow.feed(buf, n / 2)
                if (follow.talker != talker) { talker = follow.talker
                    status = "$deviceLabel · P25 · playing ${call.tg?.let { "talkgroup $it" } ?: "a private call"} · radio $talker" + turnNote() }
            }
        } finally { ahead.close(); p25Playing = null; out.stop() } // stop: what's buffered still plays
        priority?.let { p25Wanted = it to System.currentTimeMillis() }
        val why = follow.endedBy ?: when {
            priority != null -> "priority call" + (if (logAll) " on talkgroup $priority" else "")
            stop() || p25Mute -> "stopped"
            fails >= STALL_CHUNKS -> "no samples"
            follow.frames == 0 -> "no voice heard"
            follow.quietS >= FOLLOW_QUIET_S -> "voice lost for 1 s"
            else -> "listening changed"
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 call " + (if (follow.frames > 0) "played (%.1f s)".format(java.util.Locale.US, follow.frames * 0.18) else "not played") +
            ", ended: $why" + (if (follow.garbled > 0) ", ${follow.garbled} garbled frames skipped" else "") + dataNote(follow))
        val ctx = appContext
        if (heard != null && follow.frames > 0 && ctx != null) runCatching { P25Record.saveSpeech(ctx, call, heard, follow.talker) }
            .onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 call sound not saved - ${com.rfsentinel.app.util.HwLog.describe(it)}") }
        return follow.frames > 0
    }

    /** 8 kHz mono speech out, as media (so it goes to the car or headphones like other audio). */
    private fun newSpeaker(rate: Int = ImbeDecoder.RATE): android.media.AudioTrack = android.media.AudioTrack.Builder()
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(rate) // half a second
        .build()

    /** Records [P25Record.SECONDS] of [call]'s voice channel to a file; false when nothing was saved. Frequency kept off the log. */
    private fun recordCall(sdr: SdrDevice, call: P25History.Call): Boolean {
        val ctx = appContext ?: return false
        val hz = call.hz ?: return false
        if (!sdr.tune(hz - INPUT_OFFSET_HZ)) return false
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        val rec = P25Record.Baseband(INPUT_OFFSET_HZ, sdr.sampleRate)
        var fails = 0
        ReadAhead(sdr, buf.size).use { ahead ->
            while (rec.samples < P25Record.SECONDS * P25Record.RATE && !halted && fails < STALL_CHUNKS) {
                val n = ahead.read(buf)
                if (n <= 0) { fails++; continue }
                fails = 0
                rec.feed(buf, n / 2)
            }
        }
        if (rec.samples < P25Record.RATE) return false
        val wav = rec.wav()
        runCatching { P25Record.save(ctx, call, wav) }.onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 recording not saved - ${com.rfsentinel.app.util.HwLog.describe(it)}"); return false }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 call recorded (%.1f s, %d KB)".format(java.util.Locale.US, rec.samples.toDouble() / P25Record.RATE, wav.size / 1024))
        return true
    }

    /** A call's input level in dB above the noise, or null when the tuner didn't get there. Frequency kept off the log. */
    private fun measureInput(sdr: SdrDevice, hz: Long): Int? {
        if (!sdr.tune(hz - INPUT_OFFSET_HZ)) return null
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        val iq = ByteArray(XFER * INPUT_READS)
        var got = 0
        for (i in 0 until INPUT_READS) {
            val n = sdr.read(buf)
            if (n <= 0) break
            System.arraycopy(buf, 0, iq, got, n); got += n
        }
        if (got < 2 * 1024) return null
        return RadioWatch.channelSnrDb(RadioWatch.powerSpectrum(iq, got / 2), sdr.sampleRate, INPUT_OFFSET_HZ)
    }

    /** Finds a paging channel and decodes it; looks again when it goes quiet or none decoded. */
    private fun runPager(sdr: SdrDevice, name: String) {
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        var shown = ""
        fun show(text: String) { shown = text; status = text }
        var stalls = 0
        val hunt = PagerHunt(sdr, { appContext?.let { com.rfsentinel.app.util.Prefs.pagerSpeeds(it) } ?: PagerDemod.SPEEDS }, STREAM_XFER, { stalled ->
            stalls = if (stalled) stalls + 1 else 0
            status = if (stalled) "$deviceLabel · pagers · " + stalledLine(stalls, sdr is RtlTcp) + turnNote() else shown
        }, { logAll }) {
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: $it")
        }
        var lastCheck = System.currentTimeMillis(); var away = false
        val stop = {
            val now = System.currentTimeMillis()
            if (now - lastCheck >= 2_000L) { lastCheck = now; away = gone(name) }
            halted || sdr.lost || away || !keeps(Mode.PAGER)
        }
        val onPage = { page: PagerLog.Page, hz: Long ->
            val ignored = appContext?.let { page.capcode in PagerRules.ignored(com.rfsentinel.app.util.Prefs.pagerIgnore(it)) } == true
            // Never the text: pages are never saved or exported.
            if (logAll) logHeard("%s page to capcode %d on %.4f MHz, %s, %d characters%s%s".format(java.util.Locale.US, page.protocol, page.capcode,
                hz / 1e6, page.kind.name.lowercase(), page.text.length, if (page.partial) ", partly garbled" else "", if (ignored) ", ignored" else ""))
            if (!ignored) {
                pagerLog.add(page, hz, System.currentTimeMillis())
                onPagerPage?.invoke(page); activity(Mode.PAGER)
            }
        }
        var next = 0
        while (!stop()) {
            // Frequencies typed in Settings: listen to those in turn, no scan.
            val typed = appContext?.let { c -> RadioSettings.targets(com.rfsentinel.app.util.Prefs.pagerFreqs(c)).map { it.freqHz } }.orEmpty()
            val picked = pagerChannel
            val mine = typed.filter { it == picked }.ifEmpty { typed }
            if (mine.isNotEmpty()) {
                val hz = mine[next++ % mine.size]
                show("$deviceLabel · pagers · listening on %.4f MHz".format(java.util.Locale.US, hz / 1e6) + turnNote())
                val from = System.currentTimeMillis()
                hunt.listen(hz, { stop() || pagerChannel != picked }, onPage)
                if (System.currentTimeMillis() - from < 1_000L) Thread.sleep(1_000L) // the tuner didn't lock: don't spin
                continue
            }
            val known = pagerHz
            if (known == null) show("$deviceLabel · pagers · looking for a paging channel…" + turnNote())
            val hz = known ?: hunt.hunt(stop, onPage)
            if (hz == null) {
                if (stop()) break
                show("$deviceLabel · pagers · nothing decoding here · looking again shortly" + turnNote())
                val until = System.currentTimeMillis() + PAGER_RETRY_MS
                while (!stop() && System.currentTimeMillis() < until) Thread.sleep(200)
                continue
            }
            pagerHz = hz
            show("$deviceLabel · pagers · listening on %.4f MHz".format(java.util.Locale.US, hz / 1e6) + turnNote())
            val before = pagerLog.total
            val quiet = hunt.listen(hz, stop, onPage)
            if (quiet) pagerHz = null
            // A count only: frequency, capcodes and text stay off the log (the frequency too with "log everything").
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: pagers ${if (quiet) "channel went quiet" else "turn over or stopped"} after ${pagerLog.total - before} pages" +
                (if (logAll) " on %.4f MHz".format(java.util.Locale.US, hz / 1e6) else ""))
        }
    }

    /**
     * Tire-pressure sensors: [Tpms.CHANNELS] in turn, [TPMS_DWELL_MS] each, every message decoded to [onTpms]. IDs stay
     * off the log (counts only), even with "log everything": they would trace the cars around you.
     */
    private fun runTpms(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        var lastCheck = System.currentTimeMillis(); var away = false
        val stop = {
            val now = System.currentTimeMillis()
            if (now - lastCheck >= 2_000L) { lastCheck = now; away = gone(name) }
            halted || sdr.lost || away || !keeps(Mode.TPMS)
        }
        if (sdr.sampleRate % Tpms.RATE != 0) {
            // The decoder needs a whole multiple of its rate (an rtl_tcp server may refuse the one asked for).
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: tire sensors need ${SAMPLE_RATE / 1000} kS/s, the receiver is at ${sdr.sampleRate / 1000} kS/s: skipped")
            status = "$deviceLabel · tire sensors · couldn't set the sample rate" + turnNote()
            while (!stop()) Thread.sleep(200)
            return
        }
        val buf = ByteArray(STREAM_XFER)
        val sensors = HashSet<String>(); var heard = 0; var bursts = 0; var unlocked = 0
        var next = 0
        while (!stop()) {
            val hz = Tpms.CHANNELS[next++ % Tpms.CHANNELS.size]
            if (!sdr.tune(hz - TPMS_OFFSET_HZ)) { unlocked++; Thread.sleep(200); continue }
            sdr.resetBuffer()
            val demod = Tpms.Demod(sdr.sampleRate, TPMS_OFFSET_HZ.toDouble()) { r ->
                heard++; tpmsMessages++; sensors += r.key
                onTpms?.invoke(r)
            }
            val until = System.currentTimeMillis() + TPMS_DWELL_MS
            var fails = 0
            ReadAhead(sdr, buf.size).use { ahead ->
                while (!stop() && System.currentTimeMillis() < until) {
                    val n = ahead.read(buf)
                    if (n <= 0) {
                        if (++fails % STALL_CHUNKS == 0) status = "$deviceLabel · tire sensors · " + stalledLine(fails / STALL_CHUNKS, sdr is RtlTcp) + turnNote()
                        continue
                    }
                    fails = 0
                    demod.feed(buf, n / 2)
                }
            }
            bursts += demod.bursts
            if (!halted) status = "$deviceLabel · tire sensors · ${sensors.size} heard this turn" + turnNote()
        }
        // Counts only: sensor IDs would trace the cars around you.
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: tire sensors turn over: $bursts bursts, $heard messages from ${sensors.size} sensors" +
            (if (unlocked > 0) ", PLL unlocked $unlocked" else ""))
    }

    /** One look at FM radio, chunk by chunk, then at the weather channels, all inside one tuned chunk away from the centre spike. */
    private fun checkAntenna(sdr: SdrDevice): AntennaReading {
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        // The strongest station against the quietest chunk: a city's FM band can be full from end to end in places.
        var fmHz = 0L; var fmPeak = Float.NEGATIVE_INFINITY; var floor = Float.POSITIVE_INFINITY; var chunks = 0
        for (center in FM_CENTERS) {
            if (halted || sdr.lost) break
            val (offset, peak, quiet) = RadioWatch.widePeak(spectrum(sdr, center) ?: continue, sdr.sampleRate, FM_WIDTH_HZ)
            chunks++
            if (peak > fmPeak) { fmPeak = peak; fmHz = center + offset }
            if (quiet < floor) floor = quiet
        }
        val weatherCenter = WEATHER_HZ.first() - INPUT_OFFSET_HZ
        val weather = spectrum(sdr, weatherCenter)?.let { psd -> WEATHER_HZ.map { RadioWatch.channelSnrDb(psd, sdr.sampleRate, it - weatherCenter) } }
        if (chunks == 0 && weather == null) return AntennaReading(0, 0, 0, 0, ok = false)
        val fmDb = if (chunks == 0) 0 else (fmPeak - floor).toInt()
        val best = weather?.indices?.maxBy { weather[it] }
        // FM stations sit on a 100 kHz grid.
        val reading = AntennaReading((fmHz + 50_000) / 100_000 * 100_000, fmDb, best?.let { WEATHER_HZ[it] } ?: 0, best?.let { weather[it] } ?: 0)
        // Levels only: which station is strongest would tell where the phone is (unless "log everything" is on).
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: antenna check: FM radio $fmDb dB in $chunks of ${FM_CENTERS.size} chunks, " +
            "weather channels ${weather?.max() ?: "not read"} dB, gain ${GAIN_TENTHS_DB / 10.0} dB" +
            (if (logAll) ", strongest FM %.1f MHz, weather %.3f MHz".format(java.util.Locale.US, reading.fmHz / 1e6, reading.weatherHz / 1e6) else ""))
        return reading
    }

    /** Hops [FINDER_CENTERS] while [finder] is set: a key fob sends for a fraction of a second, so each look is short. */
    private fun runFinder(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: finding a remote's frequency")
        status = "$deviceLabel · finding a remote's frequency"
        var cycles = 0; var hits = 0; var best = 0; var lastCheck = System.currentTimeMillis()
        val pick = RemoteFinder()
        while (!halted && !sdr.lost && !reapplyNow && finder != null) {
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            for (center in FINDER_CENTERS) {
                val tell = finder ?: break
                val reads = spectra(sdr, center, FINDER_READS) ?: continue
                pick.look(center, reads.map { P25Hunt.candidates(center, it, sdr.sampleRate, FINDER_MIN_DB) })?.let {
                    hits++; best = maxOf(best, it.snrDb.toInt()); tell(it.hz, it.snrDb.toInt())
                    if (logAll) logHeard("remote finder: %.4f MHz %.0f dB".format(java.util.Locale.US, it.hz / 1e6, it.snrDb))
                }
            }
            val wasReady = pick.ready
            pick.endRound(); cycles++
            if (!wasReady && pick.ready) finder?.invoke(0L, 0) // what was already on the air is learnt: press now
        }
        // Levels only: a remote's frequency could single out the car or the house (unless "log everything" is on).
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: remote finder off after $cycles rounds, $hits signals heard" +
            (if (hits > 0) ", strongest $best dB" else ""))
    }

    /** A power spectrum of each of [reads] USB reads (~4 ms each) with the SDR tuned to [center]; null when the tuner failed. */
    private fun spectra(sdr: SdrDevice, center: Long, reads: Int): List<FloatArray>? {
        if (!sdr.tune(center)) return null
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        return (0 until reads).mapNotNull { sdr.read(buf).takeIf { it >= 2 * 1024 }?.let { n -> RadioWatch.powerSpectrum(buf, n / 2) } }
    }

    /** The power spectrum with the SDR tuned to [center]; null when the tuner or the samples failed. */
    private fun spectrum(sdr: SdrDevice, center: Long): FloatArray? {
        if (!sdr.tune(center)) return null
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        val iq = ByteArray(XFER * INPUT_READS)
        var got = 0
        for (i in 0 until INPUT_READS) {
            val n = sdr.read(buf)
            if (n <= 0) break
            System.arraycopy(buf, 0, iq, got, n); got += n
        }
        if (got < 2 * 1024) return null
        return RadioWatch.powerSpectrum(iq, got / 2)
    }

    /**
     * Holds [tuneHz] for the waterfall screen: a row of the ~2 MHz around it every few tens of ms. With [listen] on,
     * it's also played: tuned [INPUT_OFFSET_HZ] below so the channel sits clear of the centre spike, read without gaps.
     */
    private fun runTuned(sdr: SdrDevice, name: String) {
        val hz = tuneHz ?: return
        val listening = listen
        val center = if (listening) hz - INPUT_OFFSET_HZ else hz
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        val mhz = "%.3f MHz".format(java.util.Locale.US, hz / 1e6)
        val still = { !halted && !sdr.lost && tuneHz == hz && listen == listening && antennaCheck == null && finder == null && !reapplyNow }
        if (!sdr.tune(center)) {
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the tuner didn't lock on $mhz")
            status = "$deviceLabel · waterfall: the tuner can't tune to $mhz"
            while (still()) Thread.sleep(100)
            return
        }
        sdr.resetBuffer()
        val buf = ByteArray(XFER)
        repeat(sdr.settleReads) { sdr.read(buf) }
        // Listening: the channel demodulated to the speaker; without an audio output the waterfall carries on alone.
        var speakerOut: android.media.AudioTrack? = null
        val demod = if (!listening) null else AudioDemod(AudioDemod.modeFor(hz), INPUT_OFFSET_HZ, sdr.sampleRate) { pcm, n ->
            speakerOut?.write(pcm, 0, n)
        }
        // Narrow FM: an MDC-1200 burst names the radio keying up (unit ID, or an emergency), shown on the status line.
        var mdcCount = 0
        demod?.takeIf { it.mode == AudioDemod.Mode.NFM }?.let { d ->
            val mdc = Mdc1200(d.audioRate) { p ->
                mdcCount++
                status = "$deviceLabel · waterfall on $mhz · listening, ${d.mode.label} · ${p.label}"
                if (logAll) logHeard("MDC-1200 on $mhz: ${p.label}, opcode %02X argument %02X".format(p.op, p.arg))
            }
            d.tap = mdc::sample
        }
        if (demod != null) speakerOut = runCatching { newSpeaker(demod.audioRate).also { it.play() } }
            .onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: no audio output for listening - ${com.rfsentinel.app.util.HwLog.describe(it)}") }
            .getOrNull()
        val ahead = if (listening) ReadAhead(sdr, XFER) else null
        val what = demod?.let { "listening, ${it.mode.label}" } ?: "radio sweep paused"
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: waterfall on $mhz ($what)")
        status = "$deviceLabel · waterfall on $mhz · " + (if (demod != null && speakerOut == null) "no audio output" else what)
        val iq = ByteArray(XFER * TUNED_READS)
        var lastRow = 0L; var lastCheck = System.currentTimeMillis(); var fails = 0; var lastHeard = 0L
        try { while (still()) {
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            var got = 0
            for (i in 0 until TUNED_READS) {
                val n = ahead?.read(buf) ?: sdr.read(buf)
                if (n <= 0) break
                demod?.feed(buf, n / 2)
                if (demod?.open == true && speakerOut != null) com.rfsentinel.app.util.Voice.radioHeard()
                System.arraycopy(buf, 0, iq, got, n); got += n
            }
            if (got < 2 * 1024) {
                if (++fails % STALL_CHUNKS == 0) sdr.resetBuffer()
                continue
            }
            fails = 0
            val now = System.currentTimeMillis()
            val logNow = now - lastHeard >= TUNED_LOG_MS && logAll
            val listener = SdrWaterfall.listener
            if (!logNow && (listener == null || now - lastRow < TUNED_ROW_MS)) continue
            val psd = RadioWatch.powerSpectrum(iq, got / 2)
            if (logNow) { lastHeard = now; logHeard("waterfall on $mhz: ${P25Hunt.heard(P25Hunt.candidates(center, psd, sdr.sampleRate))}") }
            if (listener == null || now - lastRow < TUNED_ROW_MS) continue
            lastRow = now
            // The outer tenth on each side rolls off in the receiver's filter: left out.
            listener(SdrWaterfall.Row(listOf(SdrWaterfall.segment(center, psd, sdr.sampleRate, sdr.sampleRate * 0.45, null)), tuned = true, at = now))
        } } finally {
            ahead?.close()
            speakerOut?.let { runCatching { it.stop(); it.release() } }
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: waterfall left $mhz" + (if (listening) ", stopped listening" else "") +
            (if (mdcCount > 0) ", $mdcCount MDC-1200 bursts" else ""))
    }
}
