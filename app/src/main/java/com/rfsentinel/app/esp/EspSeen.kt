package com.rfsentinel.app.esp

import java.util.concurrent.ConcurrentHashMap

/**
 * Which devices an ESP32 board (USB or OUI-SPY over Bluetooth) has reported,
 * and when, so the list can mark them and filter on them.
 */
object EspSeen {

    /** How long a device keeps its ESP32 mark after the board last reported it. */
    const val WINDOW_MS = 5 * 60_000L

    private val last = ConcurrentHashMap<String, Long>()

    fun mark(mac: String, now: Long = System.currentTimeMillis()) {
        last[mac.uppercase()] = now
    }

    fun recent(mac: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = last[mac.uppercase()] ?: return false
        if (now - t < WINDOW_MS) return true
        last.remove(mac.uppercase(), t)
        return false
    }

    fun clear() = last.clear()
}
