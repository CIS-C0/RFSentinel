package com.rfsentinel.app.esp

import java.util.concurrent.ConcurrentHashMap

/**
 * Which radio heard a device recently: an ESP32 board (USB or OUI-SPY over
 * Bluetooth) and/or the phone's own chips, so the list can badge and filter them.
 */
class RecentMacs {
    private val last = ConcurrentHashMap<String, Long>()

    fun mark(mac: String, now: Long = System.currentTimeMillis()) {
        last[mac.uppercase()] = now
    }

    fun recent(mac: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = mac.uppercase()
        val t = last[key] ?: return false
        if (now - t < HeardBy.WINDOW_MS) return true
        last.remove(key, t)
        return false
    }

    fun clear() = last.clear()
}

object HeardBy {
    /** How long a device keeps its badge after that radio last heard it. */
    const val WINDOW_MS = 5 * 60_000L

    val esp = RecentMacs()
    val phone = RecentMacs()
}
