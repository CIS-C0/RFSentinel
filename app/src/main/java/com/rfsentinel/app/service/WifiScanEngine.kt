package com.rfsentinel.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
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
    private val onResult: (ScanResult) -> Unit
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // Even when OUR request was throttled (EXTRA_RESULTS_UPDATED = false) the
            // cached results may come from another app's fresh scan, so use them either way.
            try {
                wifiManager.scanResults.forEach(onResult)
            } catch (e: SecurityException) {
                // Missing location permission - caller should have requested it already.
            }
        }
    }

    fun start() {
        if (registered) return
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
            wifiManager.startScan()
        } catch (e: Exception) {
            // Throttled or disabled - safe to ignore, next cycle will retry.
        }
    }
}
