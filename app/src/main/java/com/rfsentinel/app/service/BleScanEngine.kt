package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.util.CrashLog
import com.rfsentinel.app.util.HwLog
import com.rfsentinel.app.util.HwLogs
import com.rfsentinel.app.util.Prefs

/**
 * BLE advertisement scanner. Uses only the standard public Android BLE scan API
 * (no root, no monitor mode, no packet injection). Every advertising BLE device in
 * range broadcasts these packets to anyone listening by design - this never connects
 * to any device. Android's ordinary scan is active (it asks scannable devices for their
 * scan response); with [Prefs.blePassive] on Android 16 QPR2 and later it only listens.
 *
 * Runs two scans side by side:
 *  - an unfiltered scan that sees everything (Android pauses it while the
 *    screen is off on most phones), and
 *  - a filtered scan on the signature identifiers that matter most, which
 *    Android keeps running with the screen off.
 *
 * Started and stopped from the main thread and from the service's periodic restart: both are locked.
 */
class BleScanEngine(
    private val context: Context,
    private val onResult: (ScanResult) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    /** Tracked separately: either scan can fail on its own, and both must always be stopped. */
    @Volatile private var scanning = false
    @Volatile private var filteredScanning = false
    /** Scanning with the extended (long-range) settings now. */
    @Volatile var extended = false
        private set
    /** Listening only, no scan requests ([Prefs.blePassive]). */
    @Volatile var passive = false
        private set

    /** Last result from the unfiltered scan (the filtered one alone keeps alerts going but not the list). */
    @Volatile var lastUnfilteredAt = 0L
        private set

    /** What the running scan was started with, to start it again in a simpler form when Android refuses it later. */
    private class Request(val mode: Int, val watched: List<String>, val eased: Boolean)
    private var last: Request? = null
    /** Android refused these after the scan had started: left out until the service stops the scan. */
    private var noExtended = false
    private var noPassive = false
    private var fewFilters = false
    private var failLogs = 0

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastUnfilteredAt = System.currentTimeMillis()
            onResult(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isNotEmpty()) lastUnfilteredAt = System.currentTimeMillis()
            results.forEach(onResult)
        }
        override fun onScanFailed(errorCode: Int) = failed(errorCode, filtered = false)
    }

    private val filteredCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
        override fun onScanFailed(errorCode: Int) = failed(errorCode, filtered = true)
    }

    /**
     * Android reports most refusals here, after startScan returned: settings it can't do, no free hardware
     * filters, too many starts. Each is logged ([HwLogs.phone]) and the scan is started again once in a simpler form.
     */
    @Synchronized
    private fun failed(code: Int, filtered: Boolean) {
        // Already started: the scan is running (two starts crossed); nothing failed.
        if (code == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return
        Log.w(TAG, "BLE scan failed: $code (filtered: $filtered)")
        if (failLogs++ < MAX_FAIL_LOGS) HwLogs.phone.log("Bluetooth: Android refused the ${if (filtered) "screen-off" else "main"} scan " +
            "(code $code: ${reason(code)}), extended $extended, passive $passive")
        val l = last
        if (filtered) {
            filteredScanning = false
            // Maybe more filters than the chip takes: once more with the short list.
            if (!fewFilters && l != null && code != SCAN_FAILED_TOO_OFTEN) { fewFilters = true; startFiltered(l) }
            return
        }
        scanning = false
        // Its settings may be what this phone can't do: once without the passive type, then without the extended ones.
        val simpler = when {
            code == SCAN_FAILED_TOO_OFTEN -> false // the service's watchdog starts it again later
            passive && !noPassive -> { noPassive = true; true }
            extended && !noExtended -> { noExtended = true; true }
            else -> false
        }
        if (simpler && l != null) { stopScans(); begin(l) }
    }

    /**
     * @param scanMode a ScanSettings.SCAN_MODE_* constant
     * @param watchedMacs exact-device watchlist addresses to keep watching with the screen off
     * @param eased no long-range (LE Coded) scan: it takes the most radio time from Bluetooth audio
     */
    @Synchronized
    fun start(scanMode: Int, watchedMacs: List<String>, eased: Boolean = false) {
        if (scanning) return
        // A previous unfiltered scan may have failed on its own while the filtered one kept running.
        stopScans()
        begin(Request(scanMode, watchedMacs, eased))
    }

    @SuppressLint("MissingPermission")
    private fun begin(r: Request) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return
        last = r

        // Extended advertising (BT5) carries Remote ID message packs on newer drones. Not where it crashed
        // the phone's Bluetooth before ([Prefs.bleLegacyOnly]).
        extended = !noExtended && runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false) && !Prefs.bleLegacyOnly(context)
        passive = !noPassive && passiveSupported() && Prefs.blePassive(context)
        fun tryStart(): Boolean = try {
            scanner?.startScan(null, settings(r), callback); true
        } catch (e: SecurityException) {
            // BLUETOOTH_SCAN (API 31+) or location (API <= 30) not granted.
            false
        } catch (e: Exception) {
            // Adapter turning off (IllegalStateException), or a Bluetooth stack that rejects the settings.
            Log.w(TAG, "BLE scan failed to start (extended: $extended, passive: $passive)", e)
            HwLogs.phone.log("Bluetooth: scan didn't start (extended $extended, passive $passive) - ${HwLog.describe(e)}")
            false
        }

        scanning = tryStart()
        if (!scanning && passive) { passive = false; scanning = tryStart() }
        if (!scanning && extended) { extended = false; scanning = tryStart() }
        if (!scanning) return
        Prefs.setBleExtendedRunning(context, extended)
        HwLogs.phone.logStatus("Bluetooth", "Bluetooth: scanning, mode ${r.mode}, " +
            (if (extended) "extended on ${if (r.eased) "LE 1M" else "every PHY"}" else "ordinary") + (if (passive) ", passive" else ", active"))
        startFiltered(r)
    }

    /**
     * Deliver every advert immediately (no batching). For the filtered scan, aggressive
     * matching reports a device from a single, even faint, advert instead of waiting
     * for several strong ones - that's what extends screen-off detection range.
     */
    @SuppressLint("NewApi") // the passive type is behind passiveSupported()
    private fun settings(r: Request) = ScanSettings.Builder().setScanMode(r.mode)
        .setReportDelay(0)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        .apply {
            // Extended advertising (BT5) and LE Coded (long-range) PHY where the radio supports it.
            if (extended) setLegacy(false).setPhy(if (r.eased) android.bluetooth.BluetoothDevice.PHY_LE_1M else ScanSettings.PHY_LE_ALL_SUPPORTED)
            // Listening only: a device is reported from its advert alone, not after its scan response came back too.
            if (passive && passiveSupported()) setScanType(ScanSettings.SCAN_TYPE_PASSIVE)
        }.build()

    /**
     * Same intensity as the main scan: this is the only scan Android keeps running with
     * the screen off, so a lower duty cycle here is where passing devices get missed.
     */
    @SuppressLint("MissingPermission")
    private fun startFiltered(r: Request) {
        val filters = when {
            fewFilters -> priorityFilters(r.watched)
            Build.VERSION.SDK_INT >= 33 && Prefs.bleScreenOffAll(context) -> everythingFilters() + priorityFilters(r.watched)
            else -> buildFilters(r.watched)
        }
        try {
            scanner?.startScan(filters, settings(r), filteredCallback)
            filteredScanning = true
        } catch (e: Exception) {
            Log.w(TAG, "Filtered scan unavailable", e)
            HwLogs.phone.log("Bluetooth: the screen-off scan didn't start - ${HwLog.describe(e)}")
        }
    }

    /** Stops both scans; what Android refused earlier is tried again at the next start. */
    @Synchronized
    fun stop() {
        stopScans()
        noExtended = false; noPassive = false; fewFilters = false
    }

    @SuppressLint("MissingPermission")
    private fun stopScans() {
        // Both, whatever the flags say: a scan Android runs but this class lost track of could never be stopped.
        try { scanner?.stopScan(callback) } catch (e: Exception) {
            // Adapter already turned off (IllegalStateException) or permission revoked.
        }
        try { scanner?.stopScan(filteredCallback) } catch (e: Exception) {}
        if (extended) { extended = false; Prefs.setBleExtendedRunning(context, false) }
        scanning = false
        filteredScanning = false
    }

    fun isScanning() = scanning

    /**
     * Almost every advert, by the kinds of data it carries (flags, service lists, names, service data, maker data):
     * still a filtered scan to Android, so it keeps running with the screen off ([Prefs.bleScreenOffAll]).
     */
    @androidx.annotation.RequiresApi(33)
    private fun everythingFilters(): List<ScanFilter> =
        intArrayOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x08, 0x09, 0x16, 0xFF).map { ScanFilter.Builder().setAdvertisingDataType(it).build() }

    /** The short list, for a phone with few hardware filters: surveillance makers, Apple, Remote ID, linked Sentinels, watched addresses. */
    private fun priorityFilters(watchedMacs: List<String>): List<ScanFilter> {
        val out = mutableListOf<ScanFilter>()
        for (cid in intArrayOf(0x034D, 0x04EC, 0x09C8, 0x004C)) out += ScanFilter.Builder().setManufacturerData(cid, ByteArray(0)).build()
        out += ScanFilter.Builder().setServiceData(ParcelUuid(Advert.uuid16(0xFFFA)), ByteArray(0)).build()
        out += ScanFilter.Builder().setManufacturerData(com.rfsentinel.app.link.PhoneLink.COMPANY,
            byteArrayOf(0xA1.toByte()), byteArrayOf(0xFF.toByte())).build()
        watchedMacs.take(8).forEach { mac -> runCatching { out += ScanFilter.Builder().setDeviceAddress(mac).build() } }
        return out
    }

    private fun buildFilters(watchedMacs: List<String>): List<ScanFilter> {
        val out = mutableListOf<ScanFilter>()
        // Vendor company IDs: Axon/TASER, Motorola Solutions, XUNTONG (Flock), glasses makers.
        for (cid in intArrayOf(0x034D, 0x04EC, 0x09C8, 0x0D53, 0x03C2, 0x060C, 0x01AB)) {
            out += ScanFilter.Builder().setManufacturerData(cid, ByteArray(0)).build()
        }
        // The makers behind most Bluetooth traffic, so ordinary devices (and trackers following
        // you) stay visible with the screen off, when Android suspends the unfiltered scan:
        // Apple (any frame, incl. Find My), Samsung, Google, Microsoft, Garmin, Bose, Sony, Xiaomi.
        for (cid in intArrayOf(0x004C, 0x0075, 0x00E0, 0x0006, 0x0087, 0x009E, 0x012D, 0x038F)) {
            out += ScanFilter.Builder().setManufacturerData(cid, ByteArray(0)).build()
        }
        // Service data: Remote ID, Find Hub / Eddystone, SmartTag, Tile, Google Fast Pair.
        for (short in intArrayOf(0xFFFA, 0xFEAA, 0xFD5A, 0xFEED, 0xFE2C)) {
            out += ScanFilter.Builder().setServiceData(ParcelUuid(Advert.uuid16(short)), ByteArray(0)).build()
        }
        // Advertised services: Axon/TASER, Flock Raven GPS, Motorola Solutions, Zebra printers,
        // Snap Spectacles and Meta glasses.
        for (short in intArrayOf(0xFC81, 0xFE6B, 0xFE6C, 0x3100, 0xFD8E, 0xFE04, 0xFE79, 0xFD66, 0xFE45, 0xFEB7, 0xFEB8)) {
            out += ScanFilter.Builder().setServiceUuid(ParcelUuid(Advert.uuid16(short))).build()
        }
        // HeyCyan-SDK camera glasses (e.g. Nilox Smart AI Glasses), as a service or service data.
        val heyCyan = ParcelUuid.fromString("7905fff0-b5ce-4e99-a40f-4b1e122d00d0")
        out += ScanFilter.Builder().setServiceUuid(heyCyan).build()
        out += ScanFilter.Builder().setServiceData(heyCyan, ByteArray(0)).build()
        // Linked Sentinels (Sentinel Link), so phones keep finding each other with the screen off.
        out += ScanFilter.Builder().setManufacturerData(com.rfsentinel.app.link.PhoneLink.COMPANY,
            byteArrayOf(0xA1.toByte()), byteArrayOf(0xFF.toByte())).build()
        // Controllers offload a limited number of filters; keep the total around 40.
        watchedMacs.take(8).forEach { mac ->
            runCatching { out += ScanFilter.Builder().setDeviceAddress(mac).build() }
        }
        return out
    }

    companion object {
        private const val TAG = "BleScanEngine"
        private const val MAX_FAIL_LOGS = 10
        /** ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY (API 30). */
        private const val SCAN_FAILED_TOO_OFTEN = 6

        /** Android 16 QPR2 (36.1) added the passive scan type. */
        fun passiveSupported() = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

        internal fun reason(code: Int) = when (code) {
            2 -> "the app couldn't be registered"; 3 -> "internal error"; 4 -> "not supported by this phone"
            5 -> "no hardware resources left"; 6 -> "scanning too often"; else -> "unknown"
        }

        /** The phone's Bluetooth stopped during an extended scan ([why]): the second time on this Android build, ordinary scans only. */
        fun strike(context: Context, why: String) {
            val n = Prefs.addBleExtendedStrike(context)
            Log.w(TAG, "Extended scan strike $n: $why")
            if (n == 2) CrashLog.note(context, "Bluetooth: $why during the extended (long-range) scan, twice. The phone's " +
                "Bluetooth may not handle it: using the ordinary scan until Android is updated.")
        }
    }
}
