package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.service.DeviceRegistry

/**
 * How long a device stays in the live list and radar after it was last heard (Settings >
 * Scanning, defaults 30 s Bluetooth / 60 s WiFi). Flagged, favourite and following devices
 * stay for the registry's full 3 minutes so a passing threat doesn't vanish between sightings.
 */
object LiveWindow {
    fun keep(s: DeviceRegistry.Snapshot, important: Boolean, now: Long, bleMs: Long, wifiMs: Long): Boolean {
        if (important || s.following || s.known?.favorite == true) return true
        val window = if (Advert.Source.WIFI in s.sources || s.source == Advert.Source.WIFI) wifiMs else bleMs
        return now - s.lastSeen <= window
    }
}
