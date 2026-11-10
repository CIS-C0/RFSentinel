package com.rfsentinel.app.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

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
    private const val XFER = 16_384           // bytes per USB read (8192 samples)
    /** For continuous decoding: one read is ~33 ms of samples; Android before 9 caps a transfer at 16 KB. */
    private val STREAM_XFER = if (android.os.Build.VERSION.SDK_INT >= 28) 131_072 else XFER
    private const val READS_PER_CHUNK = 8     // ~32 ms of samples per tuned chunk
    private const val SLOT_READS = 16         // ~64 ms: more than one TETRA or DMR frame, so a radio's slot is always in it
    private const val STALL_CHUNKS = 8        // chunks in a row without samples before the stream is restarted
    private const val ADSB_GAIN_TENTHS_DB = 496 // the tuner's most: aircraft are far and faint
    private const val ADSB_PUSH_MS = 2_000L
    private const val TUNED_READS = 4          // ~16 ms of samples per waterfall row
    private const val TUNED_ROW_MS = 40L       // at most ~25 rows a second
    private const val P25_RETRY_MS = 30_000L   // no control channel found: look again after this
    private const val PAGER_RETRY_MS = 5_000L  // no paging channel decoded: look again after this (they can be silent between pages)
    private const val INPUT_DELAY_MS = 400L    // after a grant: the radio is on the air by then
    private const val INPUT_GAP_MS = 5_000L    // at most one input check this often: each costs ~0.2 s of control channel
    private const val INPUT_READS = 16         // ~70 ms of the input
    /** An FM station fills at least this much. */
    private const val FM_WIDTH_HZ = 100_000L
    private const val INPUT_OFFSET_HZ = 250_000L // the input this far above the tuned centre, clear of the DC spike
    private const val RECORD_GAP_MS = 20_000L  // at most one call recording this often: each costs P25Record.SECONDS of control channel
    private const val FOLLOW_START_S = 1.5     // no voice this long after tuning to a call: it isn't there (or already over)
    private const val FOLLOW_QUIET_S = 1.0     // voice gone this long: the call is over
    private const val FOLLOW_MAX_MS = 120_000L // then back to the control channel regardless

    @Volatile var status: String = ""; private set
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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** "RTL-SDR" or "HackRF": the plugged-in device, for the status line. */
    @Volatile private var deviceLabel = "RTL-SDR"
    private val UNPLUGGED get() = "$deviceLabel unplugged"
    /** For the reading thread: stop now (scan stopped, unplugged, or a newer thread took over). */
    private val halted get() = stop || Thread.currentThread() !== worker
    enum class Mode(val label: String) { SWEEP("radio sweep"), ADSB("ADS-B"), P25("P25"), PAGER("pagers") }
    /** What the SDR does (Settings > RTL-SDR radio); with more than one it takes turns ([turnMs]). */
    @Volatile var modes: Set<Mode> = setOf(Mode.SWEEP)
    /** How long each scan's turn lasts (Settings › RTL-SDR radio); 60 s when not set. */
    @Volatile var turnMs: Map<Mode, Long> = emptyMap()
    /** The scan whose turn it is now; null when none is ticked. */
    @Volatile var mode: Mode? = null; private set
    @Volatile private var turnEnd = 0L
    /** The scans that get turns: P25 alone while a talkgroup is held ([p25Hold]). */
    private val active get() = if (p25Hold != null) setOf(Mode.P25) else modes
    /** [m] keeps the SDR: its turn, still ticked, and the waterfall not zoomed in. */
    private fun keeps(m: Mode) = mode == m && m in active && tuneHz == null && antennaCheck == null && (active.size == 1 || System.currentTimeMillis() < turnEnd)
    /** The scan after the current one, round the ticked ones. */
    private fun next(): Mode? = Mode.entries.filter { it in active }.let { l -> l.firstOrNull { it.ordinal > (mode?.ordinal ?: -1) } ?: l.firstOrNull() }
    /** " · ADS-B in 40 s" while taking turns, or " · holding talkgroup 1234", for the status line. */
    private fun turnNote(): String {
        p25Hold?.let { return " · holding talkgroup $it" }
        if (modes.size < 2) return ""
        val left = ((turnEnd - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return " · ${next()?.label} in $left s"
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
    /** A call started on this talkgroup (the service alerts on watched ones). */
    @Volatile var onP25Call: ((Int) -> Unit)? = null
    /** Settings › RTL-SDR radio: after a call starts, briefly listen where its radio transmits (the site's input). */
    @Volatile var p25Inputs = false
    /** A call's radio heard on the input: [snrDb] above the noise, so close to you (the call, its input frequency). */
    @Volatile var onP25Input: ((P25History.Call, Long, Int) -> Unit)? = null
    /** Settings › RTL-SDR radio: save the radio signal of clear calls ([P25Record]). */
    @Volatile var p25Record = false
    /** Settings › Listen to P25 voice: which clear calls to play live ([P25Follow]). */
    enum class Listen { OFF, WATCHED, ALL }
    @Volatile var p25Listen = Listen.OFF
    /** The watched talkgroups (Settings › Watch these talkgroups). */
    @Volatile var p25Watched = P25Log.Watched()
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
    /**
     * The Radio waterfall's "Antenna check": set to get one reading of the FM radio band (on the air everywhere) and of the
     * 7 weather-radio channels (162.400-162.550 MHz, United States only: Canada's went off the air in March 2026), then the
     * scans carry on. Called on the SDR's thread.
     */
    @Volatile var antennaCheck: ((AntennaReading) -> Unit)? = null
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
                            connect(app, ask = false)
                            main.postDelayed({ if (receiver != null) connect(app) }, com.rfsentinel.app.usb.UsbAttachActivity.ATTACH_GRACE_MS)
                        }
                        ACTION_PERMISSION, com.rfsentinel.app.usb.UsbAttachActivity.ACTION_READY -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && d.deviceName == runningDevice) { halt(UNPLUGGED); clearUnpluggedSoon() }
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
        p25Hold = null
    }

    @Synchronized
    private fun halt(newStatus: String) {
        stop = true
        worker = null
        runningDevice = null
        status = newStatus
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
                if (!gone(name)) return
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
            status = "$deviceLabel found - allow USB access on the prompt"
            if (!ask) return
            val pi = PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        val conn = mgr.openDevice(d) ?: run { status = "$deviceLabel: couldn't open it"; return }
        launch(d.deviceName, "%04x:%04x".format(d.vendorId, d.productId), { conn.close() }) {
            if (HackRf.isHackRf(d.vendorId, d.productId)) HackRf(conn, d, com.rfsentinel.app.util.HwLogs.sdr::log) else RtlSdr(conn, d, com.rfsentinel.app.util.HwLogs.sdr::log)
        }
    }

    /** Settings › Network SDR: an rtl_tcp server, used while no USB SDR is plugged in. */
    private fun connectNet(host: String, port: Int) {
        deviceLabel = "Network SDR · $host"
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
        status = "$deviceLabel · starting…"
        val net = name.startsWith(NET)
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
                    status = if (net) "$deviceLabel: can't connect, retrying" else UNPLUGGED
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
            status = if (sdr is HackRf) "HackRF: couldn't start it (see the USB log)"
                else "RTL-SDR: this tuner isn't supported yet (R820T / R820T2 / R860 / R828D only)"
            return false
        }
        sweepWatch = null; p25Hunt = null; p25Lock = null; pagerHz = null
        var tuned = false
        while (!halted && !sdr.lost && !gone(name)) {
            antennaCheck?.let { ask -> antennaCheck = null; ask(runCatching { checkAntenna(sdr) }.getOrDefault(AntennaReading(0, 0, 0, 0, ok = false))); tuned = true }
            if (tuneHz != null) { runTuned(sdr, name); tuned = true; continue }
            // Back from the waterfall: the scan it interrupted gets its turn again, not the next one.
            val m = mode?.takeIf { tuned && it in active } ?: next()
            tuned = false
            if (m == null) {
                mode = null
                status = "$deviceLabel · idle: no scan ticked in Settings › RTL-SDR radio"
                Thread.sleep(500); continue
            }
            val turn = turnMs[m] ?: 60_000L
            if (active.size > 1) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: turn: ${m.label} for ${turn / 1000} s")
            mode = m; turnEnd = System.currentTimeMillis() + turn
            when (m) {
                Mode.SWEEP -> runSweep(sdr, name)
                Mode.ADSB -> runAdsb(sdr, name)
                Mode.P25 -> runP25(sdr, name)
                Mode.PAGER -> runPager(sdr, name)
            }
        }
        if (sdr.lost) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the dongle stopped answering (unplugged?)")
        else if (halted) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: stopped (scanning stopped or the SDR scans switched off)")
        val away = sdr.lost || gone(name)
        if (!away) sdr.close()
        return away && !halted
    }

    private fun runAdsb(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(ModeS.SAMPLE_RATE)
        sdr.setGain(ADSB_GAIN_TENTHS_DB)
        if (!sdr.tune(ModeS.FREQ_HZ)) {
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the tuner didn't lock on 1090 MHz")
            status = "$deviceLabel · ADS-B: the tuner didn't lock on 1090 MHz"
            var waited = 0
            while (!halted && keeps(Mode.ADSB) && waited < 50) { Thread.sleep(100); waited++ }
            return
        }
        sdr.resetBuffer()
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B receiver on 1090 MHz, ${sdr.sampleRate} S/s, gain ${ADSB_GAIN_TENTHS_DB / 10.0} dB")
        status = "$deviceLabel · ADS-B · listening on 1090 MHz…" + turnNote()
        val xfer = STREAM_XFER
        val buf = ByteArray(xfer)
        val mag = IntArray(xfer / 2 + 2 * ModeS.OVERLAP)
        var have = 0
        val demod = ModeS.Demod()
        val tracker = AdsbTracker()
        val frames = ArrayList<ModeS.Frame>()
        var lastPush = 0L; var lastLog = System.currentTimeMillis(); var fails = 0; var messages = 0L; var logged = 0L
        var lastCheck = System.currentTimeMillis()
        // A reader thread keeps a read waiting on the dongle while the last one is decoded: no samples lost in between.
        val ahead = ReadAhead(sdr, xfer)
        try { while (!halted && !sdr.lost && keeps(Mode.ADSB)) {
            val n = ahead.read(buf)
            // Every couple of seconds: still plugged in? (Reads alone can fail quietly for a long time.)
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            if (n <= 0) {
                if (++fails % STALL_CHUNKS == 0) {
                    if (fails <= STALL_CHUNKS * 3) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: no samples for $fails reads in a row - restarting the stream")
                    if (!halted) status = "$deviceLabel · ADS-B · no samples from the SDR · restarting it" + turnNote()
                    sdr.resetBuffer()
                }
                continue
            }
            fails = 0
            ModeS.magnitudes(buf, n / 2, mag, have)
            val total = have + n / 2
            if (total < 2 * ModeS.OVERLAP) { have = total; continue }
            frames.clear()
            demod.scan(mag, total, frames)
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
                onAircraft?.invoke(located, unlocated)
                if (!halted) status = "$deviceLabel · ADS-B · ${located.size + unlocated.size} aircraft heard, ${located.size} with a position" + turnNote()
            }
            if (now - lastLog >= 60_000) {
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: ADS-B ${messages - logged} messages in ${(now - lastLog) / 1000} s, " +
                    "${demod.preambles} preambles, ${demod.fixed} fixed, ${tracker.count} aircraft, USB errors ${sdr.ctlErrors}")
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
        var cfg = config
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
        var row = ArrayList<SdrWaterfall.Segment>()
        while (!halted && !sdr.lost && keeps(Mode.SWEEP)) {
            // Settings changed: the new bands / targets / sensitivity from this sweep on.
            if (config != cfg) { cfg = config; plan = RadioWatch.plan(cfg); watch.config = cfg; logPlan() }
            for (center in plan) {
                if (halted || sdr.lost || !keeps(Mode.SWEEP)) break
                if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
                fun skipped() {
                    if (SdrWaterfall.listening) row += SdrWaterfall.blank(center, sdr.sampleRate, RadioWatch.USABLE_HZ,
                        cfg.bands.firstOrNull { RadioWatch.covers(it, center) }?.label)
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
                        if (!halted) status = "$deviceLabel · no samples from the SDR · restarting it" + turnNote()
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
                if (SdrWaterfall.listening) row += SdrWaterfall.segment(center, psd, sdr.sampleRate, RadioWatch.USABLE_HZ,
                    cfg.bands.firstOrNull { RadioWatch.covers(it, center) }?.label)
                for (e in watch.analyze(center, psd, sdr.sampleRate, System.currentTimeMillis(), bursts)) {
                    if (!e.repeat) {
                        events++
                        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: transmission ${e.mhz} MHz, ${e.snrDb} dB above the noise (${e.band.label})" +
                            (e.slots?.let { ", $it time slots" } ?: ""))
                    }
                    onEvent?.invoke(e)
                }
            }
            if (!keeps(Mode.SWEEP)) break // cut short: not a whole sweep
            watch.sweepDone()
            val now = System.currentTimeMillis()
            // Whole sweeps only: one begun before the screen opened lacks its first bands, and its different
            // layout made the screen start over on the next row (skipped chunks are in it, as blanks).
            if (row.size == plan.size) SdrWaterfall.listener?.invoke(SdrWaterfall.Row(row.sortedBy { it.startHz }, tuned = false, at = now))
            row = ArrayList()
            sweepMs = now - sweepStart; sweepStart = now
            if (gone(name)) break
            if (!halted) status = (if (watch.sweeps < 6) "$deviceLabel · learning the radio bands here…"
                     else "$deviceLabel · live · sweep ${sweepMs / 100 / 10.0} s · $events transmissions nearby so far") + turnNote()
            if (now - lastLog > (if (watch.sweeps < 20) 10_000 else 60_000)) {
                lastLog = now
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: sweep ${watch.sweeps} in $sweepMs ms, ${watch.learnedChannels} channels, ${watch.busyChannels} always busy, " +
                    "$events reported, PLL unlocked $unlocked, read errors $readErrors, chunks without samples $emptyChunks, " +
                    "tuner write failures ${sdr.tunerWriteFails}, USB errors ${sdr.ctlErrors}, noise " +
                    noise.entries.joinToString { (hz, v) -> "%.0f MHz %.0f dB".format(hz / 1e6, v.first / v.second) })
                noise.clear()
            }
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: radio sweep stopped after ${watch.sweeps} sweeps")
    }

    /** Finds a P25 control channel and follows it; looks again when it goes quiet or none was found. */
    private fun runP25(sdr: SdrDevice, name: String) {
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        val hunt = p25Hunt ?: P25Hunt(sdr, readAhead = true) { com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: $it") }.also { p25Hunt = it }
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
            val lock = known ?: hunt.hunt(stop)
            if (lock == null) {
                if (stop()) break
                com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 - no control channel decoded (${hunt.tried} channels tried), looking again in ${P25_RETRY_MS / 1000} s")
                status = "$deviceLabel · P25 · no control channel heard here · looking again shortly" + turnNote()
                val until = System.currentTimeMillis() + P25_RETRY_MS
                while (!stop() && System.currentTimeMillis() < until) Thread.sleep(200)
                continue
            }
            // Frequency and network code stay off the log: together they name the local system.
            com.rfsentinel.app.util.HwLogs.sdr.log(if (known != null) "$deviceLabel: P25 back on the control channel" else "$deviceLabel: P25 control channel found after ${hunt.tried} tries")
            p25Lock = lock
            if (lock.nac != p25Nac) { p25Log.clear(System.currentTimeMillis()); p25Nac = lock.nac } // another system: a new list
            val where = "%.3f MHz · NAC %03X".format(java.util.Locale.US, lock.hz / 1e6, lock.nac)
            status = "$deviceLabel · P25 · control channel $where" + turnNote()
            var messages = 0; var lastStatus = 0L
            var pending: Pair<P25History.Call, Long>? = null; var lastInput = 0L; var inputs = 0; var heard = 0
            var toRecord: P25History.Call? = null; var lastRecord = 0L; var recorded = 0
            var toFollow: P25History.Call? = null; var followed = 0
            // A call's input: once it's on the air, tune there for a moment and measure it (the control channel comes back after).
            // A call to play goes first, for as long as it lasts; then a call to record, its voice channel for a few seconds.
            val detour = detour@{
                toFollow?.let { call -> toFollow = null; if (followCall(sdr, call, stop)) followed++; return@detour true }
                toRecord?.let { call -> toRecord = null; if (recordCall(sdr, call)) recorded++; return@detour true }
                val (call, hz) = pending ?: return@detour false
                if (System.currentTimeMillis() - call.time < INPUT_DELAY_MS) return@detour false
                pending = null
                val snr = measureInput(sdr, hz) ?: return@detour true
                inputs++
                if (snr >= RadioWatch.activeDb(config.minSnrDb)) {
                    heard++; p25Log.heardInput(call.tg, call.target, snr, System.currentTimeMillis()); onP25Input?.invoke(call, hz, snr)
                }
                true
            }
            val detourDue = { toFollow != null || toRecord != null || pending?.let { System.currentTimeMillis() - it.first.time >= INPUT_DELAY_MS } == true }
            val lost = hunt.listen(lock, stop, detour, detourDue) {
                messages++; p25Messages++
                val now = System.currentTimeMillis()
                for (tg in p25Log.add(it, now)) onP25Call?.invoke(tg)
                val calls = p25Log.takeCalls()
                appContext?.let { c -> P25History.append(c, calls) }
                val hold = p25Hold
                if (p25Inputs && pending == null && now - lastInput >= INPUT_GAP_MS) {
                    calls.lastOrNull { it.source != null && it.channel != null && (hold == null || it.tg == hold) }?.let { c ->
                        p25Log.inputHz(c.channel!!)?.let { hz -> pending = c to hz; lastInput = now }
                    }
                }
                val listen = p25Listen
                if ((listen != Listen.OFF || hold != null) && !p25Mute && toFollow == null) {
                    calls.lastOrNull { !it.encrypted && it.hz != null && (if (hold != null) it.tg == hold else listen == Listen.ALL || it.tg in p25Watched) }
                        ?.let { toFollow = it }
                }
                if (p25Record && hold == null && toRecord == null && now - lastRecord >= RECORD_GAP_MS) {
                    calls.lastOrNull { !it.encrypted && it.hz != null && it !== toFollow }?.let { toRecord = it; lastRecord = now }
                }
                if (now - lastStatus >= 2_000L && !halted) { lastStatus = now; status = "$deviceLabel · P25 · control channel $where · $messages messages" + turnNote() }
            }
            if (lost) p25Lock = null
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 ${if (lost) "control channel went quiet" else "turn over or stopped"} after $messages messages (${hunt.blocks} blocks in all), " +
                p25Log.rows().let { r -> "${r.size} talkgroups / private calls, ${r.sumOf { it.calls }} calls" } +
                (if (inputs > 0) ", $inputs input checks, $heard radios heard nearby" else "") +
                (if (recorded > 0) ", $recorded calls recorded" else "") +
                (if (followed > 0) ", $followed calls played" else ""))
        }
        speaker?.release(); speaker = null
    }

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
        val follow = P25Follow(INPUT_OFFSET_HZ.toDouble()) { pcm ->
            // A little silence first: voice comes in 180 ms bursts, and the buffer shouldn't run dry between them.
            if (!started) { started = true; out.write(ShortArray(ImbeDecoder.RATE / 5), 0, ImbeDecoder.RATE / 5, android.media.AudioTrack.WRITE_NON_BLOCKING) }
            out.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
        }
        p25Playing = call
        status = "$deviceLabel · P25 · playing ${call.tg?.let { "talkgroup $it" } ?: "a private call"}" + turnNote()
        out.play()
        val start = System.currentTimeMillis()
        var fails = 0
        val ahead = ReadAhead(sdr, buf.size)
        try {
            while (!stop() && !p25Mute && !follow.ended && fails < STALL_CHUNKS &&
                follow.quietS < (if (follow.frames == 0) FOLLOW_START_S else FOLLOW_QUIET_S)) {
                val hold = p25Hold
                if (if (hold != null) hold != call.tg else p25Listen == Listen.OFF || System.currentTimeMillis() - start >= FOLLOW_MAX_MS) break
                val n = ahead.read(buf)
                if (n <= 0) { fails++; continue }
                fails = 0 // stalls in a row end it, not stalls over a long call
                follow.feed(buf, n / 2)
            }
        } finally { ahead.close(); p25Playing = null; out.stop() } // stop: what's buffered still plays
        if (follow.frames > 0) com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: P25 call played (%.1f s)".format(java.util.Locale.US, follow.frames * 0.18))
        return follow.frames > 0
    }

    /** 8 kHz mono speech out, as media (so it goes to the car or headphones like other audio). */
    private fun newSpeaker(): android.media.AudioTrack = android.media.AudioTrack.Builder()
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(ImbeDecoder.RATE)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(ImbeDecoder.RATE) // half a second
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
        val hunt = PagerHunt(sdr, { appContext?.let { com.rfsentinel.app.util.Prefs.pagerSpeeds(it) } ?: PagerDemod.SPEEDS }, STREAM_XFER, { stalled ->
            status = if (stalled) "$deviceLabel · pagers · no samples from the SDR · restarting it" + turnNote() else shown
        }) {
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
            if (!ignored) {
                pagerLog.add(page, hz, System.currentTimeMillis())
                onPagerPage?.invoke(page)
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
            // A count only: frequency, capcodes and text stay off the log.
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: pagers ${if (quiet) "channel went quiet" else "turn over or stopped"} after ${pagerLog.total - before} pages")
        }
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
        // Levels only: which station is strongest would tell where the phone is.
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: antenna check: FM radio $fmDb dB in $chunks of ${FM_CENTERS.size} chunks, " +
            "weather channels ${weather?.max() ?: "not read"} dB, gain ${GAIN_TENTHS_DB / 10.0} dB")
        // FM stations sit on a 100 kHz grid.
        return AntennaReading((fmHz + 50_000) / 100_000 * 100_000, fmDb, best?.let { WEATHER_HZ[it] } ?: 0, best?.let { weather[it] } ?: 0)
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

    /** Holds [tuneHz] for the waterfall screen: a row of the ~2 MHz around it every few tens of ms. */
    private fun runTuned(sdr: SdrDevice, name: String) {
        val hz = tuneHz ?: return
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        val mhz = "%.3f MHz".format(java.util.Locale.US, hz / 1e6)
        if (!sdr.tune(hz)) {
            com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: the tuner didn't lock on $mhz")
            status = "$deviceLabel · waterfall: the tuner can't tune to $mhz"
            while (!halted && !sdr.lost && tuneHz == hz && antennaCheck == null) Thread.sleep(100)
            return
        }
        sdr.resetBuffer()
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: waterfall on $mhz (radio sweep paused)")
        status = "$deviceLabel · waterfall on $mhz · radio sweep paused"
        val buf = ByteArray(XFER)
        val iq = ByteArray(XFER * TUNED_READS)
        var lastRow = 0L; var lastCheck = System.currentTimeMillis(); var fails = 0
        repeat(sdr.settleReads) { sdr.read(buf) }
        while (!halted && !sdr.lost && tuneHz == hz && antennaCheck == null) {
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            var got = 0
            for (i in 0 until TUNED_READS) {
                val n = sdr.read(buf)
                if (n <= 0) break
                System.arraycopy(buf, 0, iq, got, n); got += n
            }
            if (got < 2 * 1024) {
                if (++fails % STALL_CHUNKS == 0) sdr.resetBuffer()
                continue
            }
            fails = 0
            val now = System.currentTimeMillis()
            val listener = SdrWaterfall.listener
            if (listener == null || now - lastRow < TUNED_ROW_MS) continue
            lastRow = now
            val psd = RadioWatch.powerSpectrum(iq, got / 2)
            // The outer tenth on each side rolls off in the receiver's filter: left out.
            listener(SdrWaterfall.Row(listOf(SdrWaterfall.segment(hz, psd, sdr.sampleRate, sdr.sampleRate * 0.45, null)), tuned = true, at = now))
        }
        com.rfsentinel.app.util.HwLogs.sdr.log("$deviceLabel: waterfall left $mhz")
    }
}
