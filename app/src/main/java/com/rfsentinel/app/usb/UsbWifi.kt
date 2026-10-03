package com.rfsentinel.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

/** One device heard by a USB WiFi adapter in monitor mode. */
data class MonitorSighting(
    val mac: String,
    /** Network name (access points), null for client devices and hidden networks. */
    val ssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    /** Access point (beacon / probe response) or a client device (probe request, data). */
    val isAccessPoint: Boolean,
    /** The access point a client was talking to, when known. */
    val bssid: String? = null
)

/**
 * USB WiFi adapters on an OTG cable, put in receive-only monitor mode while
 * scanning: no Android scan throttling, longer range, and client devices (laptops,
 * phones, cameras talking to a router) that the phone's own WiFi scan never sees.
 * Android asks once per adapter for USB permission.
 */
object UsbWifi {

    private const val TAG = "UsbWifi"
    private const val ACTION_PERMISSION = "com.rfsentinel.app.USB_WIFI_PERMISSION"

    /** USB IDs with a working driver: vendor:product -> chip. */
    private val SUPPORTED = mapOf(
        (0x0bda to 0x0811) to "RTL8811AU / RTL8821AU" // ALFA AWUS036ACS and other 88xxau dongles
    )
    /** Known monitor-mode chips without a driver here yet (named in the status so users know why). */
    private val KNOWN = mapOf(
        (0x0bda to 0x8812) to "RTL8812AU", (0x0bda to 0x881a) to "RTL8812AU", (0x0bda to 0x8813) to "RTL8814AU",
        (0x0cf3 to 0x9271) to "AR9271", (0x0e8d to 0x7612) to "MT7612U", (0x148f to 0x3070) to "RT3070",
        (0x0bda to 0x8187) to "RTL8187"
    )

    private var receiver: BroadcastReceiver? = null
    private var onSightings: ((List<MonitorSighting>) -> Unit)? = null
    private var thread: Thread? = null
    @Volatile private var runningDevice: String? = null

    /** Latest adapter status for the main screen and Settings ("" when none is plugged in). */
    @Volatile var status: String = ""
        private set

    /** Recent driver log lines (bring-up, RX stats, errors), for troubleshooting from Settings. */
    private val logLines = ArrayDeque<String>()

    fun log(line: String) {
        Log.d(TAG, line)
        synchronized(logLines) {
            logLines.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line)
            while (logLines.size > 300) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun chipOf(d: UsbDevice): String? = SUPPORTED[d.vendorId to d.productId]
    private fun knownOnly(d: UsbDevice): String? = KNOWN[d.vendorId to d.productId]

    @Synchronized
    fun start(context: Context, sightings: (List<MonitorSighting>) -> Unit) {
        val app = context.applicationContext
        onSightings = sightings
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && d.deviceName == runningDevice) stopAdapter("USB WiFi adapter unplugged")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        connect(app)
    }

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        stopAdapter("")
        onSightings = null
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    @Synchronized
    private fun connect(context: Context) {
        if (runningDevice != null) return
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        val all = runCatching { mgr.deviceList.values.toList() }.getOrDefault(emptyList())
        val d = all.firstOrNull { chipOf(it) != null }
        if (d == null) {
            status = all.firstNotNullOfOrNull { knownOnly(it) }
                ?.let { "USB WiFi adapter ($it) found - this chip isn't supported yet" }.orEmpty()
            return
        }
        if (!mgr.hasPermission(d)) {
            val pi = PendingIntent.getBroadcast(context, 1, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE)
            status = "USB WiFi adapter found - allow USB access on the prompt"
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        val chip = chipOf(d)!!
        runningDevice = d.deviceName
        status = "USB WiFi ($chip) · starting…"
        log("Starting $chip (${"%04x:%04x".format(d.vendorId, d.productId)})")
        thread = Thread({
            runCatching {
                Rtl8821auMonitor.run(context, d, { s -> status = s }) { list -> onSightings?.invoke(list) }
            }.onFailure { log("Driver stopped: ${it.message}"); status = "USB WiFi adapter error: ${it.message}" }
            synchronized(this) { if (runningDevice == d.deviceName) runningDevice = null }
        }, "usb-wifi").also { it.start() }
    }

    @Synchronized
    private fun stopAdapter(newStatus: String) {
        Rtl8821auMonitor.abort()
        runningDevice = null
        thread = null
        status = newStatus
    }
}
