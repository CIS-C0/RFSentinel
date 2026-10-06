package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.service.DeviceRegistry

/**
 * How long a device stays in the live list and radar after it was last heard. Ordinary
 * devices leave quickly once out of range; flagged, favourite and following ones stay for
 * the registry's full 3 minutes so a passing threat doesn't vanish between sightings.
 */
object LiveWindow {
    /** Bluetooth devices advertise several times a second: 30 s of silence means gone. */
    const val BLE_MS = 30_000L
    /** WiFi is only seen on each scan: allow two scan rounds plus a margin, at least 60 s. */
    fun wifiMs(scanIntervalMs: Long): Long = maxOf(60_000L, 2 * scanIntervalMs + 10_000L)

    fun keep(s: DeviceRegistry.Snapshot, important: Boolean, now: Long, scanIntervalMs: Long): Boolean {
        if (important || s.following || s.known?.favorite == true) return true
        val window = if (Advert.Source.WIFI in s.sources || s.source == Advert.Source.WIFI) wifiMs(scanIntervalMs) else BLE_MS
        return now - s.lastSeen <= window
    }
}
