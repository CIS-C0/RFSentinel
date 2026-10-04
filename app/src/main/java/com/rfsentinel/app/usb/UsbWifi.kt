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
    val bssid: String? = null,
    /** Network names a client device asked for by name (probe requests). */
    val probedSsids: List<String> = emptyList(),
    /** Maker / model / device name from its WPS block. */
    val wps: ProbeIntel.Wps? = null,
    /** How it builds its probe requests: marks a device model / OS ([ProbeIntel.fingerprint]). */
    val fingerprint: String? = null,
    /** An access point hiding its name; [ssid] is then the name revealed by a device joining or asking for it. */
    val hiddenNetwork: Boolean = false
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

    /** Driver families. */
    enum class Driver { RTL88XXAU, RTL88X2BU }

    /** A supported adapter: the chip name shown to the user and the driver that runs it. */
    data class Chip(val name: String, val driver: Driver)

    private val AU = Chip("RTL8811AU / 8821AU", Driver.RTL88XXAU)
    private val BU = Chip("RTL8812BU / 8822BU", Driver.RTL88X2BU)

    /** USB IDs with a working driver: vendor:product -> chip. 88x2bu IDs from the Linux rtw88 driver. */
    private val SUPPORTED: Map<Pair<Int, Int>, Chip> = mapOf(
        (0x0bda to 0x0811) to AU, // ALFA AWUS036ACS and other 88xxau dongles
        (0x0bda to 0xb812) to Chip("RTL8812BU", Driver.RTL88X2BU), // generic AC1200 dongles (e.g. Wise Tiger)
        (0x0bda to 0xb82c) to Chip("RTL8822BU", Driver.RTL88X2BU),
        (0x0bda to 0x2102) to BU, (0x0bda to 0xb81a) to BU,
        (0x7392 to 0xb822) to BU, (0x7392 to 0xc822) to BU, (0x7392 to 0xd822) to BU, // Edimax EW-7822U*
        (0x7392 to 0xe822) to BU, (0x7392 to 0xf822) to BU,
        (0x0b05 to 0x1841) to BU, (0x0b05 to 0x184c) to BU, (0x0b05 to 0x19aa) to BU, // ASUS USB-AC53 / AC55 / AC58
        (0x0b05 to 0x1870) to BU, (0x0b05 to 0x1874) to BU,
        (0x2001 to 0x331e) to BU, (0x2001 to 0x331c) to BU, (0x2001 to 0x331f) to BU, (0x2001 to 0x3322) to BU, // D-Link
        (0x13b1 to 0x0043) to BU, (0x13b1 to 0x0045) to BU, // Linksys WUSB6400M / WUSB3600 v2
        (0x2357 to 0x012d) to BU, (0x2357 to 0x0138) to BU, (0x2357 to 0x0115) to BU, // TP-Link Archer T3U / T4U v3
        (0x2357 to 0x012e) to BU, (0x2357 to 0x0116) to BU, (0x2357 to 0x0117) to BU,
        (0x0846 to 0x9055) to BU, // Netgear A6150
        (0x0e66 to 0x0025) to BU, (0x04ca to 0x8602) to BU,
        (0x20f4 to 0x808a) to BU, (0x20f4 to 0x805a) to BU, // TRENDnet
        (0x056e to 0x4011) to BU, (0x2c4e to 0x0107) to BU, (0x2c4e to 0x010a) to BU,
        (0x0411 to 0x03d1) to BU, (0x0411 to 0x03d0) to BU // Buffalo
    )
    /** Known monitor-mode chips without a driver here yet (named in the status so users know why). */
    private val KNOWN = mapOf(
        (0x0bda to 0x8812) to "RTL8812AU", (0x0bda to 0x881a) to "RTL8812AU", (0x0bda to 0x8813) to "RTL8814AU",
        (0x0bda to 0xc811) to "RTL8811CU / 8821CU", (0x0bda to 0xc820) to "RTL8821CU", (0x0bda to 0xc812) to "RTL8812CU",
        (0x0cf3 to 0x9271) to "AR9271", (0x0e8d to 0x7612) to "MT7612U", (0x148f to 0x3070) to "RT3070",
        (0x0bda to 0x8187) to "RTL8187"
    )

    private var receiver: BroadcastReceiver? = null
    /** Debug builds: driver commands from adb, e.g.
     *  `adb shell am broadcast -a com.rfsentinel.app.USB_WIFI_CMD -p com.rfsentinel.app.debug --es cmd "park 11"` */
    private var cmdReceiver: BroadcastReceiver? = null
    private const val ACTION_CMD = "com.rfsentinel.app.USB_WIFI_CMD"
    private var onSightings: ((List<MonitorSighting>) -> Unit)? = null
    private var thread: Thread? = null
    @Volatile private var runningDevice: String? = null

    /** Latest adapter status for the main screen and Settings ("" when none is plugged in). */
    @Volatile var status: String = ""
        private set

    /** Recent driver log lines (bring-up, RX stats, errors), for troubleshooting from Settings. */
    private val logLines = ArrayDeque<String>()

    /** Debug builds: the log also goes to files/usbwifi.log (read with adb run-as), which logcat can't rotate away. */
    @Volatile private var logFile: java.io.File? = null

    fun log(line: String) {
        Log.d(TAG, line)
        synchronized(logLines) {
            val stamped = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line
            logLines.addLast(stamped)
            while (logLines.size > 300) logLines.removeFirst()
            logFile?.let { f -> runCatching { if (f.length() > 2_000_000) f.delete(); f.appendText(stamped + "\n") } }
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun chipOf(d: UsbDevice): Chip? = SUPPORTED[d.vendorId to d.productId]

    /** The chip behind a USB ID, for tests and the status line. */
    fun chipOf(vendorId: Int, productId: Int): Chip? = SUPPORTED[vendorId to productId]
    private fun knownOnly(d: UsbDevice): String? = KNOWN[d.vendorId to d.productId]

    @Synchronized
    fun start(context: Context, sightings: (List<MonitorSighting>) -> Unit) {
        val app = context.applicationContext
        onSightings = sightings
        if (com.rfsentinel.app.BuildConfig.DEBUG) logFile = java.io.File(app.filesDir, "usbwifi.log")
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
        if (com.rfsentinel.app.BuildConfig.DEBUG && cmdReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val cmd = intent.getStringExtra("cmd") ?: return
                    log("adb command: $cmd")
                    Rtl8822buMonitor.commands.add(cmd)
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(ACTION_CMD), ContextCompat.RECEIVER_EXPORTED)
            cmdReceiver = r
        }
        connect(app)
    }

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        cmdReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        cmdReceiver = null
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
        status = "USB WiFi (${chip.name}) · starting…"
        log("Starting ${chip.name} (${"%04x:%04x".format(d.vendorId, d.productId)})")
        thread = Thread({
            runCatching {
                val onStatus: (String) -> Unit = { s -> status = s }
                val onFrames: (List<MonitorSighting>) -> Unit = { list -> onSightings?.invoke(list) }
                when (chip.driver) {
                    Driver.RTL88XXAU -> Rtl8821auMonitor.run(context, d, onStatus, onFrames)
                    Driver.RTL88X2BU -> Rtl8822buMonitor.run(context, d, chip.name, onStatus, onFrames)
                }
            }.onFailure { log("Driver stopped: ${it.message}"); status = "USB WiFi adapter error: ${it.message}" }
            synchronized(this) { if (runningDevice == d.deviceName) runningDevice = null }
        }, "usb-wifi").also { it.start() }
    }

    @Synchronized
    private fun stopAdapter(newStatus: String) {
        Rtl8821auMonitor.abort()
        Rtl8822buMonitor.abort()
        runningDevice = null
        thread = null
        status = newStatus
        com.rfsentinel.app.esp.HeardBy.usb.clear() // unplugged or stopped: nothing is "heard by USB" any more
    }
}
