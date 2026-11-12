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

    /**
     * A flood on one network (BSSID): [frames] in the last [windowS] s, [broadcast] = sent to every device on it; [tool]: the
     * attack tool its frames match, when they match one.
     */
    data class Attack(val bssid: String, val frames: Int, val broadcast: Boolean, val channel: Int, val windowS: Int, val tool: Tool? = null)

    /**
     * Attack tools whose floods look like no other, read from each one's source code (the facts only): which frames it sends
     * and with which reason code. Each lets its user change these, so a match is likely, not proof.
     */
    enum class Tool(val label: String, val how: String) {
        MDK4("MDK4", "deauthentication and disassociation frames both ways, with reason codes 1, 3, 5 and 8: the mix MDK4 sends"),
        AIREPLAY("aireplay-ng", "deauthentication frames only, all with reason code 7: aireplay-ng's default"),
        GHOST_ESP("GhostESP", "deauthentication and disassociation frames, all with reason code 7: how GhostESP (ESP32) sends them"),
        MARAUDER("ESP32 Marauder", "deauthentication frames only, all with reason code 2: how ESP32 Marauder (also on Flipper Zero WiFi boards) sends them"),
        ESP8266_DEAUTHER("ESP8266 Deauther", "deauthentication and disassociation frames, all with reason code 1: the ESP8266 Deauther's default")
    }

    /** One frame heard: when, to every device or not, a disassociation (else a deauthentication), its reason code (-1: unread). */
    internal class Frame(val at: Long, val broadcast: Boolean, val disassoc: Boolean = false, val reason: Int = -1)

    /** The [Tool] a flood's [frames] match, or null: too few frames with a reason code read, or no tool's pattern. */
    internal fun toolOf(frames: Collection<Frame>): Tool? {
        val read = frames.filter { it.reason >= 0 }
        if (read.size < MIN_TOOL_FRAMES) return null
        val reasons = read.groupingBy { it.reason }.eachCount()
        val disassoc = read.any { it.disassoc }
        fun mostly(r: Int) = (reasons[r] ?: 0) >= read.size * 0.9
        return when {
            // MDK4: "leaving" (3, 8) as the device, "unspecified" / "AP full" (1, 5) as the access point. Hops miss some: 3 of the 4 do.
            disassoc && reasons.size >= 3 && reasons.keys.all { it in MDK4_REASONS } -> Tool.MDK4
            mostly(7) -> if (disassoc) Tool.GHOST_ESP else Tool.AIREPLAY
            mostly(2) && !disassoc -> Tool.MARAUDER
            mostly(1) && disassoc -> Tool.ESP8266_DEAUTHER
            else -> null
        }
    }

    private val MDK4_REASONS = setOf(1, 3, 5, 8)
    /** Frames with a reason code a tool is named from, at least. */
    private const val MIN_TOOL_FRAMES = 10

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
        val frames = ArrayDeque<Frame>()
        var lastReport = Long.MIN_VALUE / 2
    }

    private val nets = HashMap<String, Net>()

    /**
     * One unprotected deauth / disassoc frame for network [bssid], to [broadcast] or one device: a [disassoc]iation or not,
     * with [reason] code (-1 when the frame was too short to hold it).
     */
    fun frame(bssid: String, broadcast: Boolean, channel: Int, now: Long = System.currentTimeMillis(),
              disassoc: Boolean = false, reason: Int = -1): Attack? {
        val attack = synchronized(this) {
            if (nets.size > MAX_NETWORKS) nets.entries.removeAll { (_, n) -> n.frames.isEmpty() || now - n.frames.last().at > WINDOW_MS }
            val n = nets.getOrPut(bssid) { Net() }
            n.frames.addLast(Frame(now, broadcast, disassoc, reason))
            while (n.frames.isNotEmpty() && (now - n.frames.first().at > WINDOW_MS || n.frames.size > MAX_KEPT)) n.frames.removeFirst()
            if (now - n.lastReport < REPEAT_MS || !(burst(n, now) || stream(n, now))) return@synchronized null
            n.lastReport = now
            Attack(bssid, n.frames.size, n.frames.count { it.broadcast } * 2 >= n.frames.size, channel, (WINDOW_MS / 1000).toInt(), toolOf(n.frames))
        }
        attack?.let { a -> onAttack?.invoke(a) }
        return attack
    }

    private fun burst(n: Net, now: Long) =
        n.frames.sumOf { f -> if (now - f.at <= BURST_MS) (if (f.broadcast) 2 else 1) else 0 } >= BURST_WEIGHT

    private fun stream(n: Net, now: Long): Boolean {
        if (n.frames.size < MIN_FRAMES) return false
        val perSlice = n.frames.groupingBy { (now - it.at) / SLICE_MS }.eachCount()
        return perSlice.count { it.value >= SLICE_FRAMES } >= MIN_SLICES
    }

    @Synchronized
    fun clear() = nets.clear()
}
