package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.rfsentinel.app.detect.Advert

/**
 * Passive, receive-only BLE advertisement scanner. Uses only the standard
 * public Android BLE scan API (no root, no monitor mode, no packet
 * injection). Every advertising BLE device in range broadcasts these packets
 * to anyone listening by design - this never connects to or interacts with
 * any device.
 *
 * Runs two scans side by side:
 *  - an unfiltered scan that sees everything (Android pauses it while the
 *    screen is off on most phones), and
 *  - a filtered scan on the signature identifiers that matter most, which
 *    Android keeps running with the screen off.
 */
class BleScanEngine(
    private val context: Context,
    private val onResult: (ScanResult) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Unfiltered BLE scan failed: $errorCode")
            scanning = false
        }
    }

    private val filteredCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Filtered BLE scan failed: $errorCode")
        }
    }

    /**
     * @param scanMode a ScanSettings.SCAN_MODE_* constant
     * @param watchedMacs exact-device watchlist addresses to keep watching with the screen off
     */
    @SuppressLint("MissingPermission")
    fun start(scanMode: Int, watchedMacs: List<String>) {
        if (scanning) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return

        // Extended advertising (BT5) carries Remote ID message packs on newer drones.
        val extended = runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        fun settings(mode: Int) = ScanSettings.Builder().setScanMode(mode).apply {
            if (extended) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }.build()

        try {
            scanner?.startScan(null, settings(scanMode), callback)
            scanning = true
        } catch (e: SecurityException) {
            // BLUETOOTH_SCAN (API 31+) or location (API <= 30) not granted.
            scanning = false
            return
        } catch (e: IllegalStateException) {
            scanning = false
            return
        }
        try {
            scanner?.startScan(buildFilters(watchedMacs), settings(ScanSettings.SCAN_MODE_BALANCED), filteredCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Filtered scan unavailable", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
            scanner?.stopScan(filteredCallback)
        } catch (e: Exception) {
            // Adapter already turned off (IllegalStateException) or permission revoked.
        }
        scanning = false
    }

    fun isScanning() = scanning

    private fun buildFilters(watchedMacs: List<String>): List<ScanFilter> {
        val out = mutableListOf<ScanFilter>()
        // Vendor company IDs: Axon/TASER, Motorola Solutions, XUNTONG (Flock), glasses makers.
        for (cid in intArrayOf(0x034D, 0x04EC, 0x09C8, 0x0D53, 0x03C2, 0x060C, 0x01AB)) {
            out += ScanFilter.Builder().setManufacturerData(cid, ByteArray(0)).build()
        }
        // Apple Find My "separated from owner" frame: type 0x12, length 0x19.
        out += ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x12, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        // Service data: Remote ID, Find Hub / Eddystone, SmartTag, Tile.
        for (short in intArrayOf(0xFFFA, 0xFEAA, 0xFD5A, 0xFEED)) {
            out += ScanFilter.Builder().setServiceData(ParcelUuid(Advert.uuid16(short)), ByteArray(0)).build()
        }
        // Advertised services: Axon/TASER UUIDs and the Flock Raven GPS service.
        for (short in intArrayOf(0xFC81, 0xFE6B, 0xFE6C, 0x3100)) {
            out += ScanFilter.Builder().setServiceUuid(ParcelUuid(Advert.uuid16(short))).build()
        }
        watchedMacs.take(10).forEach { mac ->
            runCatching { out += ScanFilter.Builder().setDeviceAddress(mac).build() }
        }
        return out
    }

    companion object {
        private const val TAG = "BleScanEngine"
    }
}
