package com.rfsentinel.app.esp

import java.util.concurrent.ConcurrentHashMap

/**
 * Which radio heard a device recently: an ESP32 board (USB or OUI-SPY over
 * Bluetooth), the phone's own chips and/or a linked Sentinel, so the list can badge and filter them.
 */
class RecentMacs(private val windowMs: Long = HeardBy.WINDOW_MS) {
    private val last = ConcurrentHashMap<String, Long>()

    fun mark(mac: String, now: Long = System.currentTimeMillis()) {
        last[mac.uppercase()] = now
        // A monitor-mode adapter hears thousands of addresses that are never looked up again.
        if (last.size > MAX_MACS) last.entries.removeAll { now - it.value >= windowMs }
    }

    fun recent(mac: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = mac.uppercase()
        val t = last[key] ?: return false
        if (now - t < windowMs) return true
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
    /** Until when (ms) the badge holds, and the Sentinel's name. */
    private val last = ConcurrentHashMap<String, Pair<Long, String>>()

    /** [holdMs]: as long as the device stays listed ([HeardBy.MESH_HOLD_MS] over the mesh), so its row keeps its badge. */
    fun mark(mac: String, via: String, now: Long = System.currentTimeMillis(), holdMs: Long = HeardBy.WINDOW_MS) {
        last.merge(mac.uppercase(), now + holdMs to via) { old, new -> maxOf(old.first, new.first) to new.second }
        if (last.size > 5_000) last.entries.removeAll { now >= it.value.first }
    }

    /** The Sentinel that heard [mac] recently, or null. */
    fun via(mac: String, now: Long = System.currentTimeMillis()): String? {
        val key = mac.uppercase()
        val v = last[key] ?: return null
        if (now < v.first) return v.second
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
    /** A USB Bluetooth adapter (dongle, or the Bluetooth half of a combo adapter). */
    val usbBt = RecentMacs()
    val phone = RecentMacs()
    /** Another Sentinel (Sentinel Link), whichever way it came. */
    val peer = PeerMacs()
    /** ...over Bluetooth, and over a Meshtastic node or the internet relay: their own filter chips. */
    val peerBluetooth = RecentMacs()
    /** Kept until the sender's next resend at the latest ([com.rfsentinel.app.link.MeshOutbox.repeatMs]): LoRa repeats a device only every 15 minutes. */
    val peerMesh = RecentMacs(MESH_HOLD_MS)
    const val MESH_HOLD_MS = 16 * 60_000L

    /** This phone or its own hardware heard [mac] recently. */
    fun ownRecent(mac: String) = phone.recent(mac) || esp.recent(mac) || usb.recent(mac) || usbBt.recent(mac)

    /**
     * Which of this phone's own radios heard [mac]: a USB adapter, an ESP32 board, the Meshtastic / MeshCore node
     * (a LoRa node it reported, [name] tells), or the phone itself.
     */
    fun own(mac: String, source: com.rfsentinel.app.detect.Advert.Source, name: String? = null): String = when {
        usb.recent(mac) -> "USB WiFi"
        usbBt.recent(mac) -> "USB Bluetooth"
        esp.recent(mac) -> if (isLoRaNode(mac, name)) "Mesh node" else "ESP32"
        source == com.rfsentinel.app.detect.Advert.Source.WIFI -> "WiFi"
        else -> "Bluetooth"
    }

    /** Which radio heard [mac]: one of this phone's own, else the linked Sentinel ("via Pixel 7"). The list, the car and History share it. */
    fun tag(mac: String, source: com.rfsentinel.app.detect.Advert.Source, name: String? = null): String =
        peer.via(mac)?.takeIf { !ownRecent(mac) }?.let { "via $it" } ?: own(mac, source, name)
}
