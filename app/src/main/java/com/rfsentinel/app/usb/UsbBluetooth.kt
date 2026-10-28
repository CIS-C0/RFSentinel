package com.rfsentinel.app.usb

/*
 * Bluetooth LE scanning with a USB Bluetooth controller: a plain dongle, or the Bluetooth half of
 * a combo WiFi + Bluetooth adapter (MT7921AU, RTL8821AU, RTL8822BU...), driven from userspace over
 * Android's USB host API, alongside the phone's own Bluetooth. The controller is brought up the way
 * the Linux btusb driver does it (vendor setup first: [BtMediatek], [BtRealtek]; then the HCI core's
 * reset), then scans passively - it never sends a scan request, never connects, never advertises.
 * Extended scanning on LE 1M and LE Coded (long range) when the controller has them, legacy
 * scanning otherwise. Settings › External hardware turns it off.
 */

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

object UsbBluetooth {

    /** One advertising report, as the controller gave it. [addr] is in HCI order (least significant byte first). */
    class Report(val addr: ByteArray, val addrType: Int, val rssi: Int, val data: ByteArray, val connectable: Boolean,
                 val phy: String?) {
        /** "AA:BB:CC:DD:EE:FF". */
        val mac get() = (5 downTo 0).joinToString(":") { "%02X".format(addr[it].toInt() and 0xff) }
    }

    /** Which bring-up a controller needs. */
    internal enum class Vendor { MEDIATEK, REALTEK, INTEL, GENERIC }

    private const val ACTION_PERMISSION = "com.rfsentinel.app.USB_BT_PERMISSION"
    private const val MANUFACTURER_REALTEK = 0x005D
    private const val MANUFACTURER_MEDIATEK = 0x0046

    /** Vendors whose controllers sit on a vendor-class (ff/01/01) interface (btusb_table). */
    private val VENDOR_CLASS_IDS = setOf(0x05ac, 0x105b, 0x0bb4, 0x0489, 0x04ca, 0x0a5c, 0x0b05, 0x050d, 0x13d3, 0x413c, 0x0930)

    /**
     * Standard controllers that need no firmware from the app (btusb runs them as they are), named by their
     * Bluetooth SIG company ID: CSR8510 dongles and their clones, Broadcom / Cypress BCM20702 and the like.
     */
    private val GENERIC_MAKERS = mapOf(0x000A to "CSR", 0x000F to "Broadcom", 0x0131 to "Cypress", 0x001D to "Qualcomm",
        0x0030 to "ST", 0x000D to "Texas Instruments")

    @Volatile var status: String = ""
        private set
    /** The live controller's name (e.g. "MT7921AU", "CSR"), null while none is scanning. */
    @Volatile var runningChip: String? = null
        private set
    /** The controller waiting for its firmware (FOSS build): its name and file names; null when none. */
    @Volatile var firmwareNeeded: Pair<String, List<String>>? = null
        private set
    /** Advertising reports heard, for the adverts-per-second counter. */
    val reportCount = AtomicLong()

    private var receiver: BroadcastReceiver? = null
    private var sink: ((List<Report>) -> Unit)? = null
    private var appContext: Context? = null
    private var thread: Thread? = null
    @Volatile private var runningDevice: String? = null
    /** The running session's stop flag: each session has its own, so a quick stop and start can't revive the old one. */
    @Volatile private var halt = java.util.concurrent.atomic.AtomicBoolean(true)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** The Bluetooth interface of [d] (btusb_table): class e0/01/01, or ff/01/01 for the vendors listed. */
    internal fun btInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { i ->
        (i.interfaceClass == 0xe0 && i.interfaceSubclass == 0x01 && i.interfaceProtocol == 0x01) ||
            (i.interfaceClass == 0xff && i.interfaceSubclass == 0x01 && i.interfaceProtocol == 0x01 && d.vendorId in VENDOR_CLASS_IDS)
    }?.takeIf { i -> (0 until i.endpointCount).any { i.getEndpoint(it).let { e -> e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN } } }

    /** Known from the USB ID alone: MediaTek (its chip ID registers are read before any HCI command), Realtek, Intel. */
    internal fun vendorOf(vendorId: Int, productId: Int): Vendor? = when {
        vendorId == 0x0e8d -> Vendor.MEDIATEK
        UsbWifi.chipOf(vendorId, productId)?.driver == UsbWifi.Driver.MT7921U -> Vendor.MEDIATEK
        vendorId == 0x0bda -> Vendor.REALTEK
        vendorId == 0x8087 -> Vendor.INTEL
        else -> null
    }

    @Synchronized
    fun start(context: Context, sightings: (List<Report>) -> Unit) {
        val app = context.applicationContext
        appContext = app
        sink = sightings
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            connect(app, ask = false)
                            main.postDelayed({ if (receiver != null) connect(app) }, UsbAttachActivity.ATTACH_GRACE_MS)
                        }
                        // A combo adapter's access is asked for by the WiFi side: its answer starts this side too.
                        ACTION_PERMISSION, UsbWifi.ACTION_PERMISSION, UsbAttachActivity.ACTION_READY -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && d.deviceName == runningDevice) stopAdapter("")
                            else if (d != null && firmwareNeeded != null && btInterface(d) != null) { firmwareNeeded = null; status = "" }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
                addAction(UsbWifi.ACTION_PERMISSION)
                addAction(UsbAttachActivity.ACTION_READY)
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
        sink = null
    }

    /** Tries again (firmware just downloaded, or the setting turned back on). */
    fun retry(context: Context) { if (receiver != null) connect(context.applicationContext) }

    /** Settings › External hardware: the switch was flipped while scanning. */
    fun settingChanged(context: Context) {
        if (com.rfsentinel.app.util.Prefs.usbBluetooth(context)) retry(context)
        else { stopAdapter(""); firmwareNeeded = null }
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    @Synchronized
    private fun connect(context: Context, ask: Boolean = true) {
        if (runningDevice != null) return
        if (!com.rfsentinel.app.util.Prefs.usbBluetooth(context)) { status = ""; return }
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        val all = runCatching { mgr.deviceList.values.toList() }.getOrDefault(emptyList())
        // Not RTL-SDR / HackRF sticks, which have no Bluetooth interface anyway.
        val d = all.firstOrNull { btInterface(it) != null } ?: run { if (firmwareNeeded == null) status = ""; return }
        if (!mgr.hasPermission(d)) {
            // The WiFi side of a combo adapter asks for access itself: one prompt, not two.
            if (!ask || UsbWifi.chipOf(d) != null) { status = "USB Bluetooth found - allow USB access on the prompt"; return }
            UsbWifi.log("BT: %04x:%04x plugged in - asking for USB access".format(d.vendorId, d.productId))
            val pi = PendingIntent.getBroadcast(context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE)
            status = "USB Bluetooth found - allow USB access on the prompt"
            runCatching { mgr.requestPermission(d, pi) }
            return
        }
        runningDevice = d.deviceName
        val mine = java.util.concurrent.atomic.AtomicBoolean(false).also { halt = it }
        val previous = thread
        status = "USB Bluetooth · starting…"
        thread = Thread({
            try {
                // A quick stop and start: the last session lets go of the adapter first.
                previous?.join(3_000)
                if (previous?.isAlive == true) status = "USB Bluetooth: the last session is still stopping - unplug and replug the adapter"
                else run(context, mgr, d, mine)
            } catch (e: Exception) {
                UsbWifi.log("BT: stopped - ${UsbWifi.describe(e)}")
                if (!mine.get()) status = "USB Bluetooth: error · ${e.message}"
            }
            synchronized(this) { if (halt === mine) runningDevice = null }
        }, "usb-bt").also { it.start() }
    }

    @Synchronized
    private fun stopAdapter(newStatus: String) {
        halt.set(true)
        runningDevice = null
        status = newStatus
        com.rfsentinel.app.esp.HeardBy.usbBt.clear()
    }

    // ---- bring-up and scanning ----

    private fun run(ctx: Context, mgr: UsbManager, d: UsbDevice, halt: java.util.concurrent.atomic.AtomicBoolean) {
        val intf = btInterface(d) ?: return
        val evtIn = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .first { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN }
        val conn = mgr.openDevice(d) ?: run { status = "USB Bluetooth: couldn't open the adapter"; return }
        var h: HciUsb? = null
        var vendor: Vendor? = null
        var ext = false
        try {
            val id = "%04x:%04x".format(d.vendorId, d.productId)
            UsbWifi.log("=== USB Bluetooth bring-up ($id, interface ${intf.id}) - RF Sentinel ${com.rfsentinel.app.BuildConfig.VERSION_NAME} ===")
            BulkRx.claim("BT", conn, intf) { halt.get() }
            val hci = HciUsb(conn, evtIn, "BT").also { h = it }
            hci.startReader() // btusb_open: the interrupt endpoint is read from here on
            vendor = vendorOf(d.vendorId, d.productId)
            var name = "USB Bluetooth"
            if (vendor == null) {
                // Not known from its USB ID: the controller's own manufacturer ID decides.
                val v = hci.cmd(0x1001)
                val maker = if (v.size >= 9) HciUsb.le16(v, 5) else -1
                vendor = when (maker) { MANUFACTURER_REALTEK -> Vendor.REALTEK; MANUFACTURER_MEDIATEK -> Vendor.MEDIATEK; else -> Vendor.GENERIC }
                if (vendor == Vendor.GENERIC) name = GENERIC_MAKERS[maker] ?: "generic"
                UsbWifi.log("BT: manufacturer 0x%04x (%s)".format(maker, vendor.name.lowercase()))
            }
            when (vendor) {
                Vendor.INTEL -> { status = "USB Bluetooth (Intel) isn't supported: it needs Intel's own firmware loader"; return }
                Vendor.MEDIATEK -> {
                    val chip = BtMediatek.identify(hci)
                    name = chip.name
                    if (!firmwareReady(ctx, name, listOfNotNull(chip.firmware))) return
                    status = "USB Bluetooth ($name) · loading firmware…"
                    BtMediatek.setup(ctx, hci, chip, "BT $name")?.let { status = "USB Bluetooth: $it"; return }
                }
                Vendor.REALTEK -> {
                    val found = BtRealtek.identify(hci, "BT RTL")
                    name = found.name
                    if (!firmwareReady(ctx, name, found.files)) return
                    status = "USB Bluetooth ($name) · loading firmware…"
                    BtRealtek.setup(ctx, hci, found, "BT $name")?.let { status = "USB Bluetooth: $it"; return }
                }
                Vendor.GENERIC -> {}
            }
            if (halt.get()) return
            status = "USB Bluetooth ($name) · starting scan…"
            ext = startScan(hci, name)
            status = "USB Bluetooth ($name) · live · " + if (ext) "LE 1M + Coded" else "LE"
            runningChip = name
            hci.onAdvertising = { e -> parse(e).takeIf { it.isNotEmpty() }?.let { reportCount.addAndGet(it.size.toLong()); sink?.invoke(it) } }
            var lastLog = System.currentTimeMillis()
            var lastCount = reportCount.get()
            while (!halt.get()) {
                Thread.sleep(500)
                if (hci.unplugged) throw IOException("the adapter stopped answering")
                val now = System.currentTimeMillis()
                if (now - lastLog >= 60_000) {
                    val c = reportCount.get()
                    UsbWifi.log("BT: ${c - lastCount} advertising reports in the last minute, ${hci.eventsIn} events in all")
                    lastLog = now; lastCount = c
                }
            }
        } finally {
            runningChip = null
            h?.let { hci ->
                hci.onAdvertising = null
                if (!hci.unplugged && halt.get()) {
                    // hci_dev_close: scanning off, then the vendor shutdown (MediaTek: Bluetooth function off).
                    runCatching { if (ext) hci.cmd(0x2042, byteArrayOf(0, 0, 0, 0, 0, 0)) else hci.cmd(0x200C, byteArrayOf(0, 0)) }
                    if (vendor == Vendor.MEDIATEK) BtMediatek.shutdown(hci)
                }
                hci.close()
            }
            runCatching { conn.releaseInterface(intf) }
            runCatching { conn.close() }
        }
    }

    /** False (and the firmware question raised) when a file isn't in this build or downloaded yet. */
    private fun firmwareReady(ctx: Context, chip: String, files: List<String>): Boolean {
        val missing = Firmware.missingFiles(ctx, files)
        if (missing.isEmpty()) { firmwareNeeded = null; return true }
        if (firmwareNeeded?.first != chip) {
            UsbWifi.log("BT: $chip needs firmware that isn't downloaded yet: ${missing.joinToString { it.name }}")
            FirmwarePrompt.notify(ctx, "$chip Bluetooth")
        }
        firmwareNeeded = chip to files
        status = "USB Bluetooth ($chip) needs its chip firmware: tap the notification, or this line in Settings, to download it"
        return false
    }

    /**
     * The HCI core's init, the part an LE scanner needs (hci_init1..4 of the Linux core), then a
     * passive scan with duplicates kept. True when it's an extended scan (1M, plus Coded when the controller has it).
     */
    private fun startScan(h: HciUsb, name: String): Boolean {
        h.cmdOk(0x0C03) // Reset
        val features = h.cmdOk(0x1003) // Read Local Supported Features
        if (features.size < 9 || features[1 + 4].toInt() and 0x40 == 0) throw IOException("$name has no Bluetooth LE")
        h.cmdOk(0x1001).let { v -> UsbWifi.log("BT: $name HCI version %d, revision %04x, LMP subversion %04x".format(v[1].toInt() and 0xff, HciUsb.le16(v, 2), HciUsb.le16(v, 7))) }
        // Event mask: hardware error (bit 15) and LE meta (bit 61); command complete / status always come.
        h.cmdOk(0x0C01, byteArrayOf(0, 0x80.toByte(), 0, 0, 0, 0, 0, 0x20))
        val le = h.cmdOk(0x2003) // LE Read Local Supported Features
        val extAdv = le.size >= 3 && le[1 + 1].toInt() and 0x10 != 0   // bit 12
        val coded = le.size >= 3 && le[1 + 1].toInt() and 0x08 != 0    // bit 11
        // LE event mask: advertising report (bit 1), extended advertising report (bit 12).
        h.cmdOk(0x2001, byteArrayOf(0x02, if (extAdv) 0x10 else 0, 0, 0, 0, 0, 0, 0))
        val interval = HciUsb.u16(0x0060); val window = HciUsb.u16(0x0030)
        return if (extAdv) {
            // Set Extended Scan Parameters: public own address, accept all, then per PHY: passive, interval, window.
            val phys = if (coded) 0x05 else 0x01
            val per = byteArrayOf(0) + interval + window
            h.cmdOk(0x2041, byteArrayOf(0, 0, phys.toByte()) + per + (if (coded) per else ByteArray(0)))
            h.cmdOk(0x2042, byteArrayOf(1, 0, 0, 0, 0, 0)) // enable, duplicates kept, no duration, no period
            UsbWifi.log("BT: $name extended passive scan on LE 1M" + if (coded) " + LE Coded" else "")
            true
        } else {
            // Legacy: passive, the same interval, the full window.
            h.cmdOk(0x200B, byteArrayOf(0) + interval + interval + byteArrayOf(0, 0))
            h.cmdOk(0x200C, byteArrayOf(1, 0))
            UsbWifi.log("BT: $name legacy passive scan")
            false
        }
    }

    private val partial = HashMap<String, ByteArray>()

    /** LE Advertising Report (0x02) and LE Extended Advertising Report (0x0D) events into reports. */
    internal fun parse(e: ByteArray): List<Report> {
        if (e.size < 4) return emptyList()
        val out = ArrayList<Report>()
        val sub = e[2].toInt() and 0xff
        var n = e[3].toInt() and 0xff
        var p = 4
        if (sub == 0x02) {
            while (n-- > 0 && p + 9 <= e.size) {
                val type = e[p].toInt() and 0xff
                val addrType = e[p + 1].toInt() and 0xff
                val addr = e.copyOfRange(p + 2, p + 8)
                val len = e[p + 8].toInt() and 0xff
                if (p + 9 + len + 1 > e.size) break
                val data = e.copyOfRange(p + 9, p + 9 + len)
                val rssi = e[p + 9 + len].toInt()
                p += 10 + len
                // ADV_IND and ADV_DIRECT_IND are connectable; scan responses don't come in a passive scan.
                out += Report(addr, addrType, rssi, data, type == 0x00 || type == 0x01, "LE 1M (legacy)")
            }
        } else if (sub == 0x0D) {
            while (n-- > 0 && p + 24 <= e.size) {
                val type = HciUsb.le16(e, p)
                val addrType = e[p + 2].toInt() and 0xff
                val addr = e.copyOfRange(p + 3, p + 9)
                val primary = e[p + 9].toInt() and 0xff
                val secondary = e[p + 10].toInt() and 0xff
                val rssi = e[p + 13].toInt()
                val len = e[p + 23].toInt() and 0xff
                if (p + 24 + len > e.size) break
                var data = e.copyOfRange(p + 24, p + 24 + len)
                p += 24 + len
                if (addrType == 0xff) continue // anonymous: no address to show
                // Data status (bits 5-6): 1 = more to come in the next report, 2 = truncated.
                val key = addr.joinToString("") { "%02x".format(it) } + addrType
                val status = (type shr 5) and 0x3
                partial.remove(key)?.let { data = it + data }
                if (status == 1) { if (data.size < 1650) partial[key] = data; continue }
                if (partial.size > 64) partial.clear()
                val legacy = type and 0x10 != 0
                val phy = when {
                    primary == 3 -> "LE Coded (long range)"
                    legacy -> "LE 1M (legacy)"
                    secondary == 2 -> "LE 1M / 2M (extended)"
                    else -> "LE 1M (extended)"
                }
                out += Report(addr, addrType, rssi, data, type and 0x01 != 0, phy)
            }
        }
        return out
    }
}
