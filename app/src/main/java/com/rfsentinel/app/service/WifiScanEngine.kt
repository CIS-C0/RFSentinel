package com.rfsentinel.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Passive WiFi access-point scanner. NOTE: android.net.wifi.WifiManager
 * scan results only surface access points / hotspots being broadcast -
 * a laptop or MDT acting purely as a WiFi *client* (not broadcasting its
 * own AP/hotspot) will not appear here. This is a real limitation: most
 * in-vehicle laptops (e.g. Panasonic Toughbooks) connect out via cellular
 * and don't broadcast a discoverable AP, so this engine mainly helps for
 * equipment that runs its own WiFi hotspot (some body-cam docking
 * stations, mobile routers, etc).
 */
class WifiScanEngine(
    private val context: Context,
    private val onResults: (List<ScanResult>) -> Unit
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var registered = false
    /** The newest result handed on (its ScanResult.timestamp). */
    private var newestUs = 0L
    /** Scan requests Android refused in a row (its limit of about 4 per 2 minutes, or WiFi scanning off). */
    @Volatile var refused = 0
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // Even when OUR request was throttled (EXTRA_RESULTS_UPDATED = false) the
            // cached results may come from another app's fresh scan, so use them - but
            // only entries actually heard recently. The cache also holds access points
            // from minutes ago; replaying those as "seen now" would place them at your
            // current position and could fake a device following you.
            try {
                val nowUs = SystemClock.elapsedRealtime() * 1000
                // Every band, always: the WiFi bands setting is for external adapters only.
                // And only once each: a broadcast without a new scan carries the same results again.
                val fresh = wifiManager.scanResults.filter { nowUs - it.timestamp <= MAX_RESULT_AGE_US && it.timestamp > newestUs }
                if (fresh.isNotEmpty()) { newestUs = fresh.maxOf { it.timestamp }; onResults(fresh) }
            } catch (e: SecurityException) {
                // Missing location permission - caller should have requested it already.
            }
        }
    }

    companion object {
        /** Cached scan results older than this are ignored (ScanResult.timestamp is µs since boot). */
        const val MAX_RESULT_AGE_US = 30_000_000L
    }

    fun start() {
        if (registered) return
        newestUs = 0L; refused = 0
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // Protected system broadcasts still reach NOT_EXPORTED receivers.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // already unregistered
        }
        registered = false
    }

    /**
     * Android throttles startScan() to a handful of calls per app per ~2 minutes
     * outside a small allowance (varies by OEM/API level). A foreground service
     * with a visible notification improves - but doesn't eliminate - this.
     */
    fun requestScan() {
        try {
            @Suppress("DEPRECATION") // no replacement API; still the only way to request a scan
            if (wifiManager.startScan()) refused = 0 else refused++
            com.rfsentinel.app.util.HwLogs.phone.logStatus("WiFi", if (refused >= 3) "WiFi: Android is refusing scan requests (its limit, or WiFi scanning is off)" else "WiFi: scanning")
        } catch (e: Exception) {
            // Throttled or disabled - safe to ignore, next cycle will retry.
        }
    }
}
