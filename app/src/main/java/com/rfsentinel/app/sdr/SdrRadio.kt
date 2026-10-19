package com.rfsentinel.app.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.rfsentinel.app.usb.UsbWifi

/**
 * An RTL-SDR dongle or a HackRF ([SdrDevice]) on an OTG cable, used while scanning as a receive-only radio-activity
 * detector ([RadioWatch]): it sweeps the public-safety bands and reports strong
 * transmissions nearby. Only signal strength per channel is measured. Its log goes into
 * the USB adapter log (Settings → Export USB WiFi adapter log).
 *
 * Or, with [adsb] on, it stays on 1090 MHz as an ADS-B receiver ([ModeS], [AdsbTracker]):
 * aircraft heard directly, no internet needed, handed to [onAircraft] every few seconds.
 */
object SdrRadio {

    private const val ACTION_PERMISSION = "com.rfsentinel.app.SDR_PERMISSION"
    private const val SAMPLE_RATE = 2_048_000
    private const val GAIN_TENTHS_DB = 297
    private const val XFER = 16_384           // bytes per USB read (8192 samples)
    private const val READS_PER_CHUNK = 8     // ~32 ms of samples per tuned chunk
    private const val TETRA_READS = 16        // ~64 ms: more than one TETRA frame, so a radio's slot is always in it
    private const val STALL_CHUNKS = 8        // chunks in a row without samples before the stream is restarted
    private const val ADSB_GAIN_TENTHS_DB = 496 // the tuner's most: aircraft are far and faint
    private const val ADSB_PUSH_MS = 2_000L

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
    @Volatile private var usb: UsbManager? = null
    private var appContext: Context? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** "RTL-SDR" or "HackRF": the plugged-in device, for the status line. */
    @Volatile private var deviceLabel = "RTL-SDR"
    private val UNPLUGGED get() = "$deviceLabel unplugged"
    /** For the reading thread: stop now (scan stopped, unplugged, or a newer thread took over). */
    private val halted get() = stop || Thread.currentThread() !== worker
    /** Receive ADS-B on 1090 MHz instead of sweeping the radio bands; switches at the next sweep. */
    @Volatile var adsb = false
    /** Where you are (latitude, longitude), to drop impossible aircraft positions. */
    @Volatile var location: (() -> Pair<Double, Double>?)? = null
    /** Aircraft with a position, and addresses heard without one, every couple of seconds in ADS-B mode. */
    @Volatile var onAircraft: ((List<com.rfsentinel.app.online.PoliceAircraft.Plane>, List<String>) -> Unit)? = null

    fun isSdr(d: UsbDevice) = RtlSdr.isRtlSdr(d.vendorId, d.productId) || HackRf.isHackRf(d.vendorId, d.productId)
    private fun labelOf(d: UsbDevice) = if (HackRf.isHackRf(d.vendorId, d.productId)) "HackRF" else "RTL-SDR"

    @Synchronized
    fun start(context: Context, events: (RadioWatch.Event) -> Unit) {
        val app = context.applicationContext
        appContext = app
        onEvent = events
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> connect(app)
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

    /** Whether the dongle [name] is no longer plugged in (no unplug notice needed). */
    private fun gone(name: String): Boolean =
        runCatching { usb?.deviceList?.values?.none { it.deviceName == name } }.getOrNull() == true

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    @Synchronized
    private fun connect(context: Context) {
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        usb = mgr
        runningDevice?.let { name ->
            if (!gone(name)) return
            // Unplugged without the system saying so: let that one go so the dongle can start again.
            UsbWifi.log("$deviceLabel: $name is gone - starting over")
            halt("")
        }
        val d = runCatching { mgr.deviceList.values.firstOrNull { isSdr(it) } }.getOrNull() ?: return
        deviceLabel = labelOf(d)
        if (!mgr.hasPermission(d)) {
            UsbWifi.log("%04x:%04x ($deviceLabel) plugged in - asking for USB access".format(d.vendorId, d.productId))
            status = "$deviceLabel found - allow USB access on the prompt"
            val pi = PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        val conn = mgr.openDevice(d) ?: run { status = "$deviceLabel: couldn't open it"; return }
        runningDevice = d.deviceName
        stop = false
        status = "$deviceLabel · starting…"
        val t = Thread({
            val sdr: SdrDevice = if (HackRf.isHackRf(d.vendorId, d.productId)) HackRf(conn, d, UsbWifi::log) else RtlSdr(conn, d, UsbWifi::log)
            val result = runCatching { run(sdr, d) }
            val failure = result.exceptionOrNull()
            runCatching { conn.close() }
            synchronized(this) {
                if (worker !== Thread.currentThread()) return@synchronized
                worker = null
                runningDevice = null
                // Never leave the last "live" line behind: unplugged, failed, or just stopped answering.
                if (gone(d.deviceName) || result.getOrNull() == true) {
                    UsbWifi.log("$deviceLabel: unplugged or not answering")
                    status = UNPLUGGED
                    clearUnpluggedSoon()
                    // Plugged back in already (it gets a new name), or it was only stuck: try again shortly.
                    appContext?.let { main.postDelayed({ if (receiver != null) connect(it) }, 3_000L) }
                } else if (failure != null) {
                    UsbWifi.log("$deviceLabel stopped: ${UsbWifi.describe(failure)}")
                    status = "$deviceLabel error: ${failure.message}"
                }
            }
        }, "rtl-sdr")
        worker = t
        t.start()
    }

    /** Reads until stopped; true when it ended because the dongle went away (unplugged or not answering). */
    private fun run(sdr: SdrDevice, d: UsbDevice): Boolean {
        UsbWifi.log("=== $deviceLabel radio activity watch (%04x:%04x) ===".format(d.vendorId, d.productId))
        if (!sdr.open()) {
            if (sdr.lost || gone(d.deviceName)) return true
            status = if (sdr is HackRf) "HackRF: couldn't start it (see the USB log)"
                else "RTL-SDR: this tuner isn't supported yet (R820T / R820T2 / R860 / R828D only)"
            return false
        }
        while (!halted && !sdr.lost && !gone(d.deviceName)) if (adsb) runAdsb(sdr, d.deviceName) else runSweep(sdr, d.deviceName)
        if (sdr.lost) UsbWifi.log("$deviceLabel: the dongle stopped answering (unplugged?)")
        val away = sdr.lost || gone(d.deviceName)
        if (!away) sdr.close()
        return away && !halted
    }

    private fun runAdsb(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(ModeS.SAMPLE_RATE)
        sdr.setGain(ADSB_GAIN_TENTHS_DB)
        if (!sdr.tune(ModeS.FREQ_HZ)) {
            UsbWifi.log("$deviceLabel: the tuner didn't lock on 1090 MHz")
            status = "$deviceLabel · ADS-B: the tuner didn't lock on 1090 MHz"
            var waited = 0
            while (!halted && adsb && waited < 50) { Thread.sleep(100); waited++ }
            return
        }
        sdr.resetBuffer()
        UsbWifi.log("$deviceLabel: ADS-B receiver on 1090 MHz, ${sdr.sampleRate} S/s, gain ${ADSB_GAIN_TENTHS_DB / 10.0} dB")
        status = "$deviceLabel · ADS-B · listening on 1090 MHz…"
        // One read is ~33 ms of samples; Android before 9 caps a transfer at 16 KB.
        val xfer = if (android.os.Build.VERSION.SDK_INT >= 28) 131_072 else XFER
        val buf = ByteArray(xfer)
        val mag = IntArray(xfer / 2 + 2 * ModeS.OVERLAP)
        var have = 0
        val demod = ModeS.Demod()
        val tracker = AdsbTracker()
        val frames = ArrayList<ModeS.Frame>()
        var lastPush = 0L; var lastLog = System.currentTimeMillis(); var fails = 0; var messages = 0L; var logged = 0L
        var lastCheck = System.currentTimeMillis()
        while (!halted && !sdr.lost && adsb) {
            val n = sdr.read(buf)
            // Every couple of seconds: still plugged in? (Reads alone can fail quietly for a long time.)
            if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
            if (n <= 0) {
                if (++fails % STALL_CHUNKS == 0) {
                    if (fails <= STALL_CHUNKS * 3) UsbWifi.log("$deviceLabel: no samples for $fails reads in a row - restarting the stream")
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
                if (!halted) status = "$deviceLabel · ADS-B · ${located.size + unlocated.size} aircraft heard, ${located.size} with a position"
            }
            if (now - lastLog >= 60_000) {
                UsbWifi.log("$deviceLabel: ADS-B ${messages - logged} messages in ${(now - lastLog) / 1000} s, " +
                    "${demod.preambles} preambles, ${demod.fixed} fixed, ${tracker.count} aircraft, USB errors ${sdr.ctlErrors}")
                lastLog = now; logged = messages
            }
        }
        onAircraft?.invoke(emptyList(), emptyList())
        UsbWifi.log("$deviceLabel: ADS-B receiver stopped after $messages messages")
    }

    private fun runSweep(sdr: SdrDevice, name: String) {
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        var cfg = config
        var plan = RadioWatch.plan(cfg)
        val watch = RadioWatch()
        watch.config = cfg
        fun logPlan() = UsbWifi.log("$deviceLabel: ${sdr.sampleRate} S/s, gain ${GAIN_TENTHS_DB / 10.0} dB, ${plan.size} chunks per sweep over " +
            cfg.bands.joinToString { "%.1f-%.1f MHz".format(it.startHz / 1e6, it.endHz / 1e6) } +
            (if (cfg.targets.isNotEmpty()) ", ${cfg.targets.size} watched frequencies" else "") +
            ", reporting from ${cfg.minSnrDb} dB, USB errors ${sdr.ctlErrors}")
        logPlan()
        val buf = ByteArray(XFER)
        val iq = ByteArray(XFER * maxOf(READS_PER_CHUNK, TETRA_READS))
        var lastLog = 0L; var sweepStart = System.currentTimeMillis(); var sweepMs = 0L
        var events = 0; var unlocked = 0; var readErrors = 0; var failedChunks = 0
        var lastCheck = System.currentTimeMillis()
        while (!halted && !sdr.lost && !adsb) {
            // Settings changed: the new bands / targets / sensitivity from this sweep on.
            if (config != cfg) { cfg = config; plan = RadioWatch.plan(cfg); watch.config = cfg; logPlan() }
            for (center in plan) {
                if (halted || sdr.lost || adsb) break
                if (System.currentTimeMillis() - lastCheck >= 2_000L) { lastCheck = System.currentTimeMillis(); if (gone(name)) break }
                if (!sdr.tune(center)) { unlocked++; continue }
                repeat(sdr.settleReads) { sdr.read(buf) }
                // TETRA chunks also keep the power in 2 ms steps, to see a radio's 14 ms time-slot bursts.
                val tetra = cfg.bands.any { it.kind == RadioWatch.Kind.TETRA && RadioWatch.covers(it, center) }
                var got = 0
                for (i in 0 until if (tetra) TETRA_READS else READS_PER_CHUNK) {
                    val n = sdr.read(buf)
                    if (n <= 0) { readErrors++; break }
                    System.arraycopy(buf, 0, iq, got, n); got += n
                }
                if (got < 2 * 1024) {
                    // The sample stream stalled: restart it (as librtlsdr's reset_buffer) instead of sweeping on with nothing.
                    if (++failedChunks % STALL_CHUNKS == 0) {
                        if (failedChunks <= STALL_CHUNKS * 3) UsbWifi.log("$deviceLabel: no samples for $failedChunks chunks in a row - restarting the stream")
                        sdr.resetBuffer()
                    }
                    continue
                }
                failedChunks = 0
                val bursts = if (tetra) RadioWatch.burstSpectrum(iq, got / 2, sdr.sampleRate) else null
                val psd = bursts?.psd ?: RadioWatch.powerSpectrum(iq, got / 2)
                for (e in watch.analyze(center, psd, sdr.sampleRate, System.currentTimeMillis(), bursts)) {
                    if (!e.repeat) {
                        events++
                        UsbWifi.log("$deviceLabel: transmission ${e.mhz} MHz, ${e.snrDb} dB above the noise (${e.band.label})" +
                            if (e.tdma) ", TETRA time slots" else "")
                    }
                    onEvent?.invoke(e)
                }
            }
            watch.sweepDone()
            val now = System.currentTimeMillis()
            sweepMs = now - sweepStart; sweepStart = now
            if (gone(name)) break
            if (!halted) status = if (watch.sweeps < 6) "$deviceLabel · learning the radio bands here…"
                     else "$deviceLabel · live · sweep ${sweepMs / 100 / 10.0} s · $events transmissions nearby so far"
            if (now - lastLog > (if (watch.sweeps < 20) 10_000 else 60_000)) {
                lastLog = now
                UsbWifi.log("$deviceLabel: sweep ${watch.sweeps} in $sweepMs ms, ${watch.learnedChannels} channels, ${watch.busyChannels} always busy, " +
                    "$events reported, PLL unlocked $unlocked, read errors $readErrors, tuner write failures ${sdr.tunerWriteFails}, USB errors ${sdr.ctlErrors}")
            }
        }
        UsbWifi.log("$deviceLabel: radio sweep stopped after ${watch.sweeps} sweeps")
    }
}
