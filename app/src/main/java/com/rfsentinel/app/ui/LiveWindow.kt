package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.esp.HeardBy
import com.rfsentinel.app.service.DeviceRegistry

/**
 * How long a device stays in the live list and radar after it was last heard (Settings >
 * Scanning, defaults 30 s Bluetooth / 60 s WiFi / 60 s external hardware), flagged devices included - never shorter
 * than while they still count as in range. Favourite and following devices stay for the
 * registry's full 3 minutes.
 */
object LiveWindow {
    /** A Bluetooth device not heard for this long is out of range (adverts come every second or so). */
    const val BLE_IN_RANGE_MS = 8_000L

    /** The WiFi scan interval in use (kept current by the scan service): WiFi devices only refresh once per scan. */
    @Volatile var wifiScanMs = 30_000L

    /** Still being heard: Bluetooth within 8 s, WiFi within one scan round + 10 s. */
    fun inRange(s: DeviceRegistry.Snapshot, now: Long = System.currentTimeMillis()): Boolean {
        val wifi = Advert.Source.WIFI in s.sources || s.source == Advert.Source.WIFI
        return now - s.lastSeen <= if (wifi) wifiScanMs + 10_000L else BLE_IN_RANGE_MS
    }

    /**
     * Flagged (more than a very weak match) AND in range: what drives the live alerts (bubble, banner, radar blips, car
     * headline, proximity beeps). A flagged device that's gone stops alerting right away but
     * stays in the list and history.
     */
    fun alerting(s: DeviceRegistry.Snapshot, now: Long = System.currentTimeMillis()): Boolean =
        s.best?.listOnly == false && !com.rfsentinel.app.data.WhitelistCache.contains(s.mac) && inRange(s, now)

    fun keep(s: DeviceRegistry.Snapshot, now: Long, bleMs: Long, wifiMs: Long, externalMs: Long = wifiMs): Boolean {
        if (s.following || s.known?.favorite == true) return true
        val wifi = Advert.Source.WIFI in s.sources || s.source == Advert.Source.WIFI
        // A flagged device never leaves the list while it still alerts (bubble, banner, beeps).
        if (s.best != null && inRange(s, now)) return true
        val phoneMs = if (wifi) wifiMs else bleMs
        // Heard by an ESP32 board, a USB adapter or a linked Sentinel: its own time, or the longer one when the
        // phone hears it too.
        val external = HeardBy.esp.recent(s.mac, now) || HeardBy.usb.recent(s.mac, now) || HeardBy.peer.recent(s.mac, now)
        val ms = when {
            !external -> phoneMs
            HeardBy.phone.recent(s.mac, now) -> maxOf(phoneMs, externalMs)
            else -> externalMs
        }
        return now - s.lastSeen <= ms
    }
}
