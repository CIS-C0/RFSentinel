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
    enum class Driver { RTL88XXAU, RTL88X2BU, RTL8187, RT3070, RTL8814AU, MT7612U, AR9271 }

    /** A supported adapter: the chip name shown to the user and the driver that runs it. */
    data class Chip(val name: String, val driver: Driver)

    private val AU = Chip("RTL8811AU / 8821AU", Driver.RTL88XXAU)
    private val BU = Chip("RTL8812BU / 8822BU", Driver.RTL88X2BU)
    private val L8187 = Chip("RTL8187L", Driver.RTL8187)
    private val B8187 = Chip("RTL8187B", Driver.RTL8187)
    private val RT3070 = Chip("RT3070", Driver.RT3070)
    private val AU14 = Chip("RTL8814AU", Driver.RTL8814AU)
    private val MT = Chip("MT7612U", Driver.MT7612U)
    private val ATH = Chip("AR9271", Driver.AR9271)

    /**
     * USB IDs with a working driver: vendor:product -> chip. 88x2bu and 8814au IDs from the
     * Linux rtw88 driver, RTL8187 IDs from rtl8187, RT3070 IDs from rt2800usb (the RT3070
     * driver checks the chip and declines other Ralink chips sharing an ID), MT76x2U IDs from
     * mt76x2u, AR9271 IDs from ath9k_htc (not its AR7010 ones, which need other firmware).
     */
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
        (0x0411 to 0x03d1) to BU, (0x0411 to 0x03d0) to BU, // Buffalo
        (0x0bda to 0x8187) to L8187, // ALFA AWUS036H and other RTL8187L dongles
        (0x0b05 to 0x171d) to L8187, (0x0769 to 0x11f2) to L8187, (0x0789 to 0x010c) to L8187,
        (0x0846 to 0x6100) to L8187, (0x0846 to 0x6a00) to L8187, (0x03f0 to 0xca02) to L8187, // Netgear WG111v2, HP
        (0x0df6 to 0x000d) to L8187, (0x114b to 0x0150) to L8187, (0x1371 to 0x9401) to L8187,
        (0x13d1 to 0xabe6) to L8187, (0x18e8 to 0x6232) to L8187, (0x1b75 to 0x8187) to L8187,
        (0x0bda to 0x8189) to B8187, (0x0bda to 0x8197) to B8187, (0x0bda to 0x8198) to B8187,
        (0x050d to 0x705e) to B8187, (0x0846 to 0x4260) to B8187, (0x0df6 to 0x0028) to B8187, // Belkin, Netgear WG111v3
        (0x0df6 to 0x0029) to B8187, (0x1737 to 0x0073) to B8187,
        (0x148f to 0x3070) to RT3070, // ALFA AWUS036NH / AWUS036NEH and other RT3070 dongles
        (0x148f to 0x2070) to RT3070, (0x7392 to 0x7711) to RT3070, (0x1737 to 0x0077) to RT3070, (0x2019 to 0xab25) to RT3070,
        (0x0bda to 0x8813) to AU14, // ALFA AWUS1900
        (0x056e to 0x400b) to AU14, (0x056e to 0x400d) to AU14, (0x0846 to 0x9054) to AU14, // ELECOM, Netgear A7000
        (0x0b05 to 0x1817) to AU14, (0x0b05 to 0x1852) to AU14, (0x0b05 to 0x1853) to AU14, // ASUS USB-AC68
        (0x0e66 to 0x0026) to AU14, (0x2001 to 0x331a) to AU14, (0x20f4 to 0x809a) to AU14, (0x20f4 to 0x809b) to AU14,
        (0x2357 to 0x0106) to AU14, (0x7392 to 0xa834) to AU14, (0x7392 to 0xa833) to AU14, // TP-Link Archer T9UH, Edimax
        (0x0e8d to 0x7612) to MT, // ALFA AWUS036ACM, Aukey USB-AC1200
        (0x0b05 to 0x1833) to MT, (0x0b05 to 0x17eb) to MT, (0x0b05 to 0x180b) to MT, // ASUS USB-AC54 / AC55 / N53 B1
        (0x057c to 0x8503) to MT, (0x7392 to 0xb711) to MT, (0x056e to 0x400a) to MT, (0x0e8d to 0x7632) to MT,
        (0x0471 to 0x2126) to MT, (0x0471 to 0x7600) to MT, (0x2c4e to 0x0103) to MT,
        (0x0846 to 0x9014) to MT, (0x0846 to 0x9053) to MT, // Netgear WNDA3100v3, A6210
        (0x045e to 0x02e6) to MT, (0x045e to 0x02fe) to MT, (0x2357 to 0x0137) to MT, // Xbox One wireless adapter, TP-Link
        (0x0cf3 to 0x9271) to ATH, // ALFA AWUS036NHA, TP-Link TL-WN722N v1
        (0x0cf3 to 0x1006) to ATH, (0x0846 to 0x9030) to ATH, (0x07b8 to 0x9271) to ATH, (0x07d1 to 0x3a10) to ATH,
        (0x13d3 to 0x3327) to ATH, (0x13d3 to 0x3328) to ATH, (0x13d3 to 0x3346) to ATH, (0x13d3 to 0x3348) to ATH,
        (0x13d3 to 0x3349) to ATH, (0x13d3 to 0x3350) to ATH, (0x04ca to 0x4605) to ATH, (0x040d to 0x3801) to ATH,
        (0x0cf3 to 0xb003) to ATH, (0x0cf3 to 0xb002) to ATH, (0x057c to 0x8403) to ATH, (0x0471 to 0x209e) to ATH,
        (0x1eda to 0x2315) to ATH
    )
    /** Known monitor-mode chips without a driver here yet (named in the status so users know why). */
    private val KNOWN = mapOf(
        (0x0bda to 0x8812) to "RTL8812AU", (0x0bda to 0x881a) to "RTL8812AU",
        (0x0bda to 0xc811) to "RTL8811CU / 8821CU", (0x0bda to 0xc820) to "RTL8821CU", (0x0bda to 0xc812) to "RTL8812CU",
        (0x0cf3 to 0x7010) to "AR7010", (0x0cf3 to 0x7015) to "AR7010", (0x0e8d to 0x7610) to "MT7610U",
        (0x148f to 0x3071) to "RT3071", (0x148f to 0x3072) to "RT3072", (0x148f to 0x5370) to "RT5370",
        (0x148f to 0x5372) to "RT5372"
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

    /**
     * The log also goes to files/usbwifi.log (the previous one kept as usbwifi.log.1), so it
     * survives a restart and a tester can export it from Settings ([report]).
     */
    @Volatile private var logFile: java.io.File? = null
    private const val LOG_FILE = "usbwifi.log"
    private const val LOG_MAX_BYTES = 1_000_000L

    fun log(line: String) {
        Log.d(TAG, line)
        synchronized(logLines) {
            val now = java.util.Date()
            logLines.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(now) + "  " + line)
            while (logLines.size > 300) logLines.removeFirst()
            logFile?.let { f ->
                runCatching {
                    if (f.length() > LOG_MAX_BYTES) { val old = java.io.File(f.path + ".1"); old.delete(); f.renameTo(old) }
                    f.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(now) + "  " + line + "\n")
                }
            }
        }
    }

    /** An error for the log: its type, message and where it happened. */
    fun describe(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message}" + (e.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: "")

    /**
     * Everything a tester can send back about their adapter: app and phone, the USB devices
     * plugged in now, and the driver log. No nearby devices' addresses or network names.
     */
    fun report(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        sb.append("RF Sentinel - USB WiFi adapter log\n")
        sb.append("App ${com.rfsentinel.app.BuildConfig.VERSION_NAME} (${com.rfsentinel.app.BuildConfig.VERSION_CODE})")
            .append(if (com.rfsentinel.app.BuildConfig.DEBUG) " debug\n" else "\n")
        sb.append("Phone ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Exported ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
        sb.append("Status: ${status.ifBlank { "no adapter running" }}\n\n")
        sb.append("USB devices plugged in now:\n")
        val mgr = app.getSystemService(UsbManager::class.java)
        val devices = runCatching { mgr?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        if (devices.isEmpty()) sb.append("  none\n")
        for (d in devices) sb.append(describe(mgr, d))
        sb.append("\nDriver log, oldest first:\n")
        val files = listOf(java.io.File(app.filesDir, "$LOG_FILE.1"), java.io.File(app.filesDir, LOG_FILE)).filter { it.exists() }
        if (files.isEmpty()) sb.append(logText().ifBlank { "(empty - plug the adapter in and start a scan first)" }).append('\n')
        else synchronized(logLines) { files.forEach { f -> sb.append(runCatching { f.readText() }.getOrDefault("")) } }
        return sb.toString()
    }

    private fun describe(mgr: UsbManager?, d: UsbDevice): String {
        val sb = StringBuilder()
        val what = chipOf(d)?.let { "supported (${it.name})" }
            ?: knownOnly(d)?.let { "$it, not supported yet" }
            ?: if (com.rfsentinel.app.sdr.RtlSdr.isRtlSdr(d.vendorId, d.productId)) "RTL-SDR (radio activity detector)" else "not a supported WiFi adapter"
        val allowed = runCatching { mgr?.hasPermission(d) }.getOrNull() == true
        // Names are only readable once USB access is allowed.
        val name = listOfNotNull(runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull())
            .joinToString(" ").ifBlank { "(name shown once USB access is allowed)" }
        sb.append("  %04x:%04x %s - %s - USB access %s\n".format(d.vendorId, d.productId, name, what,
            if (allowed) "allowed" else "not allowed yet"))
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            val eps = (0 until intf.endpointCount).joinToString(", ") { e ->
                val ep = intf.getEndpoint(e)
                val type = when (ep.type) { 0 -> "control"; 1 -> "iso"; 2 -> "bulk"; else -> "interrupt" }
                val dir = if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "in" else "out"
                "0x%02x %s %s %d".format(ep.address, type, dir, ep.maxPacketSize)
            }
            sb.append("    interface %d class %02x/%02x: %s\n".format(i, intf.interfaceClass, intf.interfaceSubclass, eps.ifBlank { "no endpoints" }))
        }
        return sb.toString()
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
        logFile = java.io.File(app.filesDir, LOG_FILE)
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
            val known = all.firstOrNull { knownOnly(it) != null }
            val newStatus = known?.let { "USB WiFi adapter (${knownOnly(it)}) found - this chip isn't supported yet" }.orEmpty()
            if (known != null && newStatus != status) log("%04x:%04x (%s) plugged in: not supported yet".format(known.vendorId, known.productId, knownOnly(known)))
            status = newStatus
            return
        }
        if (!mgr.hasPermission(d)) {
            log("%04x:%04x (%s) plugged in - asking for USB access".format(d.vendorId, d.productId, chipOf(d)?.name))
            val pi = PendingIntent.getBroadcast(context, 1, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE)
            status = "USB WiFi adapter found - allow USB access on the prompt"
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        val chip = chipOf(d)!!
        runningDevice = d.deviceName
        status = "USB WiFi (${chip.name}) · starting…"
        log("Starting ${chip.name} (${"%04x:%04x".format(d.vendorId, d.productId)}) - RF Sentinel ${com.rfsentinel.app.BuildConfig.VERSION_NAME}, " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        thread = Thread({
            runCatching {
                val onStatus: (String) -> Unit = { s -> status = s }
                val onFrames: (List<MonitorSighting>) -> Unit = { list -> onSightings?.invoke(list) }
                when (chip.driver) {
                    Driver.RTL88XXAU -> {
                        Rtl8821auMonitor.scan5g = com.rfsentinel.app.util.Prefs.rtl8821au5g(context)
                        Rtl8821auMonitor.run(context, d, onStatus, onFrames)
                    }
                    Driver.RTL88X2BU -> Rtl8822buMonitor.run(context, d, chip.name, onStatus, onFrames)
                    Driver.RTL8187 -> Rtl8187Monitor.run(context, d, chip.name, onStatus, onFrames)
                    Driver.RT3070 -> Rt3070Monitor.run(context, d, chip.name, onStatus, onFrames)
                    Driver.RTL8814AU -> Rtl8814auMonitor.run(context, d, chip.name, onStatus, onFrames)
                    Driver.MT7612U -> Mt7612uMonitor.run(context, d, chip.name, onStatus, onFrames)
                    Driver.AR9271 -> Ar9271Monitor.run(context, d, "${chip.name} (experimental)", onStatus, onFrames)
                }
            }.onFailure { log("Driver stopped: ${describe(it)}"); status = "USB WiFi adapter error: ${it.message}" }
            synchronized(this) { if (runningDevice == d.deviceName) runningDevice = null }
        }, "usb-wifi").also { it.start() }
    }

    @Synchronized
    private fun stopAdapter(newStatus: String) {
        Rtl8821auMonitor.abort()
        Rtl8822buMonitor.abort()
        Rtl8187Monitor.abort()
        Rt3070Monitor.abort()
        Rtl8814auMonitor.abort()
        Mt7612uMonitor.abort()
        Ar9271Monitor.abort()
        runningDevice = null
        thread = null
        status = newStatus
        com.rfsentinel.app.esp.HeardBy.usb.clear() // unplugged or stopped: nothing is "heard by USB" any more
    }
}
