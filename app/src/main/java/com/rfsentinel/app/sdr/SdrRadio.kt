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
 * An RTL-SDR dongle on an OTG cable, used while scanning as a receive-only radio-activity
 * detector ([RadioWatch]): it sweeps the public-safety bands and reports strong
 * transmissions nearby. Only signal strength per channel is measured. Its log goes into
 * the USB adapter log (Settings → Export USB WiFi adapter log).
 */
object SdrRadio {

    private const val ACTION_PERMISSION = "com.rfsentinel.app.SDR_PERMISSION"
    private const val SAMPLE_RATE = 2_048_000
    private const val GAIN_TENTHS_DB = 297
    private const val XFER = 16_384           // bytes per USB read (8192 samples)
    private const val READS_PER_CHUNK = 8     // ~32 ms of samples per tuned chunk
    private const val SETTLE_READS = 2        // samples after a retune are discarded

    @Volatile var status: String = ""; private set
    private var receiver: BroadcastReceiver? = null
    private var onEvent: ((RadioWatch.Event) -> Unit)? = null
    @Volatile private var runningDevice: String? = null
    @Volatile private var stop = false

    fun isSdr(d: UsbDevice) = RtlSdr.isRtlSdr(d.vendorId, d.productId)

    @Synchronized
    fun start(context: Context, events: (RadioWatch.Event) -> Unit) {
        val app = context.applicationContext
        onEvent = events
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && d.deviceName == runningDevice) halt("RTL-SDR unplugged")
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
        runningDevice = null
        status = newStatus
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    @Synchronized
    private fun connect(context: Context) {
        if (runningDevice != null) return
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        val d = runCatching { mgr.deviceList.values.firstOrNull { isSdr(it) } }.getOrNull() ?: return
        if (!mgr.hasPermission(d)) {
            UsbWifi.log("%04x:%04x (RTL-SDR) plugged in - asking for USB access".format(d.vendorId, d.productId))
            status = "RTL-SDR found - allow USB access on the prompt"
            val pi = PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        val conn = mgr.openDevice(d) ?: run { status = "RTL-SDR: couldn't open it"; return }
        runningDevice = d.deviceName
        stop = false
        status = "RTL-SDR · starting…"
        Thread({
            runCatching { run(RtlSdr(conn, d, UsbWifi::log), d) }
                .onFailure { UsbWifi.log("RTL-SDR stopped: ${UsbWifi.describe(it)}"); status = "RTL-SDR error: ${it.message}" }
            runCatching { conn.close() }
            synchronized(this) { if (runningDevice == d.deviceName) runningDevice = null }
        }, "rtl-sdr").start()
    }

    private fun run(sdr: RtlSdr, d: UsbDevice) {
        UsbWifi.log("=== RTL-SDR radio activity watch (%04x:%04x) ===".format(d.vendorId, d.productId))
        if (!sdr.open()) { status = "RTL-SDR: this tuner isn't supported yet (R820T / R820T2 / R860 / R828D only)"; return }
        sdr.setSampleRate(SAMPLE_RATE)
        sdr.setGain(GAIN_TENTHS_DB)
        sdr.resetBuffer()
        val plan = RadioWatch.plan()
        val watch = RadioWatch()
        UsbWifi.log("RTL-SDR: ${sdr.sampleRate} S/s, gain ${GAIN_TENTHS_DB / 10.0} dB, ${plan.size} chunks per sweep over " +
            RadioWatch.BANDS.joinToString { "%.1f-%.1f MHz".format(it.startHz / 1e6, it.endHz / 1e6) } + ", USB errors ${sdr.ctlErrors}")
        val buf = ByteArray(XFER)
        val iq = ByteArray(XFER * READS_PER_CHUNK)
        var lastLog = 0L; var sweepStart = System.currentTimeMillis(); var sweepMs = 0L
        var events = 0; var unlocked = 0; var readErrors = 0
        while (!stop) {
            for (center in plan) {
                if (stop) break
                if (!sdr.tune(center)) { unlocked++; continue }
                repeat(SETTLE_READS) { sdr.read(buf) }
                var got = 0
                for (i in 0 until READS_PER_CHUNK) {
                    val n = sdr.read(buf)
                    if (n <= 0) { readErrors++; break }
                    System.arraycopy(buf, 0, iq, got, n); got += n
                }
                if (got < 2 * 1024) continue
                val psd = RadioWatch.powerSpectrum(iq, got / 2)
                for (e in watch.analyze(center, psd, sdr.sampleRate, System.currentTimeMillis())) {
                    events++
                    UsbWifi.log("RTL-SDR: transmission ${e.mhz} MHz, ${e.snrDb} dB above the noise (${e.band.label})")
                    onEvent?.invoke(e)
                }
            }
            watch.sweepDone()
            val now = System.currentTimeMillis()
            sweepMs = now - sweepStart; sweepStart = now
            status = if (watch.sweeps < 6) "RTL-SDR · learning the radio bands here…"
                     else "RTL-SDR · watching police radio bands · $events transmissions nearby so far"
            if (now - lastLog > (if (watch.sweeps < 20) 10_000 else 60_000)) {
                lastLog = now
                UsbWifi.log("RTL-SDR: sweep ${watch.sweeps} in $sweepMs ms, ${watch.learnedChannels} channels, ${watch.busyChannels} always busy, " +
                    "$events reported, PLL unlocked $unlocked, read errors $readErrors, USB errors ${sdr.ctlErrors}")
            }
        }
        sdr.close()
        UsbWifi.log("RTL-SDR: stopped after ${watch.sweeps} sweeps")
    }
}
