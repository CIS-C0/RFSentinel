package com.rfsentinel.app.usb

/**
 * Karma / PineAP fake access points, from the beacons and probe responses a USB WiFi adapter hears
 * in monitor mode. A real access point answers under its own network name (a router with a guest
 * network uses a second address for it). A Karma attack (WiFi Pineapple, ESP32 tools) answers every
 * name a phone asks for, so that phones join it thinking it's their home or work network: one
 * transmitter, many names in a short time.
 *
 * Receive-only: it counts frames, nothing is sent. Fed from every driver's RX thread, so it's
 * synchronized. Pure logic, unit-tested. Threshold as in SignalSweep (GPL-3.0; the idea, not code).
 */
object KarmaWatch {

    /** [transmitter] answered under [names] (the most recent first) within [windowS] s. */
    data class Attack(val transmitter: String, val names: List<String>, val channel: Int, val windowS: Int)

    const val WINDOW_MS = 60_000L
    /** Different network names one transmitter answers under before it's called a fake access point. */
    const val MIN_NAMES = 4
    /** One alert per transmitter this often while it goes on. */
    const val REPEAT_MS = 5 * 60_000L
    private const val MAX_TRANSMITTERS = 2_000

    /** Set by the scanning service; called (on the RX thread) when a fake access point is found. */
    @Volatile var onAttack: ((Attack) -> Unit)? = null

    private class Tx {
        /** Name -> when last heard under it. */
        val names = LinkedHashMap<String, Long>()
        var lastSeen = 0L
        var lastReport = Long.MIN_VALUE / 2
    }

    private val txs = HashMap<String, Tx>()

    /** A beacon or probe response from [transmitter] under network name [ssid]. */
    fun frame(transmitter: String, ssid: String, channel: Int, now: Long = System.currentTimeMillis()): Attack? {
        val attack = synchronized(this) {
            if (txs.size > MAX_TRANSMITTERS) txs.entries.removeAll { now - it.value.lastSeen > WINDOW_MS }
            val t = txs.getOrPut(transmitter) { Tx() }
            t.lastSeen = now
            t.names.remove(ssid); t.names[ssid] = now
            t.names.entries.removeAll { now - it.value > WINDOW_MS }
            if (t.names.size < MIN_NAMES || now - t.lastReport < REPEAT_MS) return@synchronized null
            t.lastReport = now
            Attack(transmitter, t.names.keys.reversed(), channel, (WINDOW_MS / 1000).toInt())
        }
        attack?.let { a -> onAttack?.invoke(a) }
        return attack
    }

    @Synchronized
    fun clear() = txs.clear()
}
