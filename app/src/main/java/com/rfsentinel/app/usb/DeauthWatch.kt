package com.rfsentinel.app.usb

/**
 * WiFi deauthentication / disassociation floods, from the management frames a USB WiFi adapter
 * hears in monitor mode. A jammer or hacking tool (ESP32 / ESP8266 deauther, Flipper with a WiFi
 * board, WiFi Pineapple) knocks devices off a network by sending these frames over and over in the
 * network's name. Real networks send a handful (a client leaving, an idle one dropped), never a
 * stream. Frames protected by 802.11w (PMF) are skipped: an attacker can't forge those.
 *
 * Receive-only: it counts frames, nothing is sent. Fed from every driver's RX thread, so it's
 * synchronized. Pure logic, unit-tested.
 */
object DeauthWatch {

    /** A flood on one network (BSSID): [frames] in the last [windowS] s, [broadcast] = sent to every device on it. */
    data class Attack(val bssid: String, val frames: Int, val broadcast: Boolean, val channel: Int, val windowS: Int)

    /**
     * The adapter hops channels (each one gets a fraction of a second at a time), so a flood is only
     * partly heard. Two ways to call it:
     *  - a burst: [BURST_WEIGHT] in [BURST_MS], a frame to every device counting twice. Normal networks
     *    reach 11-24 (SignalSweep's field measurements; the idea, not code);
     *  - a stream: [MIN_FRAMES] in [WINDOW_MS], heard in at least [MIN_SLICES] separate [SLICE_MS] slices
     *    with [SLICE_FRAMES] each. A normal network's frames come in one short burst (an access point
     *    restarting drops everyone at once) and then stop; an attack keeps going.
     */
    const val WINDOW_MS = 30_000L
    const val MIN_FRAMES = 25
    const val BURST_MS = 5_000L
    const val BURST_WEIGHT = 30
    const val SLICE_MS = 5_000L
    const val MIN_SLICES = 3
    const val SLICE_FRAMES = 4
    /** One alert per network this often while a flood goes on. */
    const val REPEAT_MS = 5 * 60_000L
    private const val MAX_KEPT = 4_000
    private const val MAX_NETWORKS = 500

    /** Set by the scanning service; called (on the RX thread) when a flood is found. */
    @Volatile var onAttack: ((Attack) -> Unit)? = null

    private class Net {
        val frames = ArrayDeque<Pair<Long, Boolean>>()
        var channel = 0
        var lastReport = Long.MIN_VALUE / 2
    }

    private val nets = HashMap<String, Net>()

    /** One unprotected deauth / disassoc frame for network [bssid], to [broadcast] or one device. */
    fun frame(bssid: String, broadcast: Boolean, channel: Int, now: Long = System.currentTimeMillis()): Attack? {
        val attack = synchronized(this) {
            if (nets.size > MAX_NETWORKS) nets.entries.removeAll { (_, n) -> n.frames.isEmpty() || now - n.frames.last().first > WINDOW_MS }
            val n = nets.getOrPut(bssid) { Net() }
            n.frames.addLast(now to broadcast)
            n.channel = channel
            while (n.frames.isNotEmpty() && (now - n.frames.first().first > WINDOW_MS || n.frames.size > MAX_KEPT)) n.frames.removeFirst()
            if (now - n.lastReport < REPEAT_MS || !(burst(n, now) || stream(n, now))) return@synchronized null
            n.lastReport = now
            Attack(bssid, n.frames.size, n.frames.count { it.second } * 2 >= n.frames.size, channel, (WINDOW_MS / 1000).toInt())
        }
        attack?.let { a -> onAttack?.invoke(a) }
        return attack
    }

    private fun burst(n: Net, now: Long) =
        n.frames.sumOf { (t, all) -> if (now - t <= BURST_MS) (if (all) 2 else 1) else 0 } >= BURST_WEIGHT

    private fun stream(n: Net, now: Long): Boolean {
        if (n.frames.size < MIN_FRAMES) return false
        val perSlice = n.frames.groupingBy { (now - it.first) / SLICE_MS }.eachCount()
        return perSlice.count { it.value >= SLICE_FRAMES } >= MIN_SLICES
    }

    @Synchronized
    fun clear() = nets.clear()
}
