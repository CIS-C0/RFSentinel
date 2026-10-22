package com.rfsentinel.app.esp

import java.util.concurrent.ConcurrentHashMap

/**
 * Which radio heard a device recently: an ESP32 board (USB or OUI-SPY over
 * Bluetooth), the phone's own chips and/or a linked Sentinel, so the list can badge and filter them.
 */
class RecentMacs {
    private val last = ConcurrentHashMap<String, Long>()

    fun mark(mac: String, now: Long = System.currentTimeMillis()) {
        last[mac.uppercase()] = now
        // A monitor-mode adapter hears thousands of addresses that are never looked up again.
        if (last.size > MAX_MACS) last.entries.removeAll { now - it.value >= HeardBy.WINDOW_MS }
    }

    fun recent(mac: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = mac.uppercase()
        val t = last[key] ?: return false
        if (now - t < HeardBy.WINDOW_MS) return true
        last.remove(key, t)
        return false
    }

    /** Heard within [ms]. */
    fun within(mac: String, ms: Long, now: Long = System.currentTimeMillis()): Boolean =
        last[mac.uppercase()]?.let { now - it < ms } == true

    fun clear() = last.clear()

    private companion object { const val MAX_MACS = 5_000 }
}

/** Devices another Sentinel (Sentinel Link) heard, with that Sentinel's name. */
class PeerMacs {
    private val last = ConcurrentHashMap<String, Pair<Long, String>>()

    fun mark(mac: String, via: String, now: Long = System.currentTimeMillis()) {
        last[mac.uppercase()] = now to via
        if (last.size > 5_000) last.entries.removeAll { now - it.value.first >= HeardBy.WINDOW_MS }
    }

    /** The Sentinel that heard [mac] recently, or null. */
    fun via(mac: String, now: Long = System.currentTimeMillis()): String? {
        val key = mac.uppercase()
        val v = last[key] ?: return null
        if (now - v.first < HeardBy.WINDOW_MS) return v.second
        last.remove(key, v)
        return null
    }

    fun recent(mac: String, now: Long = System.currentTimeMillis()) = via(mac, now) != null

    fun clear() = last.clear()
}

object HeardBy {
    /** How long a device keeps its badge after that radio last heard it. */
    const val WINDOW_MS = 5 * 60_000L

    val esp = RecentMacs()
    /** A USB WiFi adapter in monitor mode. */
    val usb = RecentMacs()
    val phone = RecentMacs()
    /** Another Sentinel (Sentinel Link). */
    val peer = PeerMacs()
}
