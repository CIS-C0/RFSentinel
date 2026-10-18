package com.rfsentinel.app.usb

/**
 * Antenna check for the USB WiFi adapter, after Wardrive Go's (RocketGod): pick a nearby access
 * point as the reference, the adapter stops hopping and holds its channel, and every frame the
 * reference sends is kept with its signal per antenna (chain) where the adapter reports one.
 * That compares antennas by how strongly they hear the same transmitter; it isn't an SWR or
 * impedance measurement. Only the open screen uses it, and nothing here is logged or stored.
 *
 * Channels use [MonitorFrames]' numbering: the channel number on 2.4 / 5 GHz, MHz on 6 GHz.
 */
object AntennaCheck {

    /** An access point the adapter heard while the check is open. */
    class Ap(val bssid: String, val ssid: String?, val channel: Int, val rssi: Int, val lastSeen: Long)

    /** One frame from the reference: its signal, and per antenna when the adapter reports both. */
    internal class Sample(val at: Long, val rssi: Int?, val a: Int?, val b: Int?)

    /** Averages over a time window. [delta]: chain 0 minus chain 1, from frames that carried both. */
    class Stats(val frames: Int, val rssi: Double?, val a: Double?, val b: Double?, val both: Int, val delta: Double?)

    /** The check screen is open: collect access points and the reference's frames. */
    @Volatile var listening = false
        private set

    /** The channel the adapter should hold; 0 = hop as usual. */
    @Volatile var lockChannel = 0
        private set

    /** The channel the driver is holding for the check (set by the driver once it's tuned). */
    @Volatile var holding = 0

    @Volatile private var target: ByteArray? = null
    private val aps = LinkedHashMap<String, Ap>()
    private val samples = ArrayDeque<Sample>()

    private const val KEEP_MS = 30_000L
    private const val MAX_SAMPLES = 20_000

    fun open() { listening = true }

    /** Screen gone: the adapter goes back to hopping and everything heard is forgotten. */
    fun close() {
        listening = false
        unlock()
        synchronized(this) { aps.clear() }
    }

    fun lock(bssid: String, channel: Int) {
        synchronized(this) { samples.clear() }
        target = macBytes(bssid)
        holding = 0
        lockChannel = channel
    }

    fun unlock() {
        target = null
        lockChannel = 0
        holding = 0
        synchronized(this) { samples.clear() }
    }

    /** For a driver with a fixed channel list: the index of the channel to hold, -1 to hop as usual. */
    fun lockIndex(hop: IntArray): Int = lockChannel.let { if (it == 0) -1 else hop.indexOf(it) }

    // ---- from the drivers (RX thread, via MonitorFrames) ----

    internal fun heardAp(bssid: String, ssid: String?, channel: Int, rssi: Int?, now: Long) {
        if (channel == 0) return
        synchronized(this) {
            val old = aps[bssid]
            aps[bssid] = Ap(bssid, ssid ?: old?.ssid, channel,
                rssi ?: old?.rssi ?: MonitorFrames.NOMINAL_RSSI, now)
            if (aps.size > 500) aps.remove(aps.keys.first())
        }
    }

    /** A frame whose transmitter address sits at [ta] in [b]: kept when it's the reference's. */
    internal fun frame(b: ByteArray, ta: Int, rssi: Int?, pathA: Int?, pathB: Int?, now: Long) {
        val t = target ?: return
        if (ta + 6 > b.size) return
        for (k in 0 until 6) if (b[ta + k] != t[k]) return
        if (rssi == null && pathA == null && pathB == null) return
        synchronized(this) {
            samples.addLast(Sample(now, rssi, pathA, pathB))
            while (samples.size > MAX_SAMPLES || (samples.isNotEmpty() && now - samples.first().at > KEEP_MS)) samples.removeFirst()
        }
    }

    /** Debug builds' demo: a made-up frame from the reference, for checking the screen without an adapter. */
    internal fun demoSample(rssi: Int, a: Int?, b: Int?, now: Long) {
        if (target == null) return
        synchronized(this) { samples.addLast(Sample(now, rssi, a, b)); while (samples.size > MAX_SAMPLES) samples.removeFirst() }
    }

    // ---- for the screen ----

    /** Access points heard in the last 30 s, strongest first. */
    fun aps(now: Long): List<Ap> = synchronized(this) {
        aps.values.filter { now - it.lastSeen < KEEP_MS }.sortedByDescending { it.rssi }
    }

    /** The reference's frames between [from] and [to]. */
    fun stats(from: Long, to: Long): Stats = synchronized(this) { stats(samples.filter { it.at in from..to }) }

    internal fun stats(list: List<Sample>): Stats {
        val rs = list.mapNotNull { it.rssi }
        val pa = list.mapNotNull { it.a }
        val pb = list.mapNotNull { it.b }
        val both = list.filter { it.a != null && it.b != null }
        return Stats(list.size, rs.avgOrNull(), pa.avgOrNull(), pb.avgOrNull(), both.size,
            both.map { it.a!! - it.b!! }.avgOrNull())
    }

    private fun List<Int>.avgOrNull(): Double? = if (isEmpty()) null else average()

    /** Per-bucket averages for the screen's history lines: chain 0, chain 1 and the frame signal (NaN where none). */
    class History(val a: FloatArray, val b: FloatArray, val rssi: FloatArray)

    /** The reference's last [windowMs] in [buckets] equal slices, oldest first. */
    fun history(now: Long, windowMs: Long = KEEP_MS, buckets: Int = 60): History =
        synchronized(this) { history(samples.toList(), now, windowMs, buckets) }

    internal fun history(list: List<Sample>, now: Long, windowMs: Long, buckets: Int): History {
        val sa = DoubleArray(buckets); val na = IntArray(buckets)
        val sb = DoubleArray(buckets); val nb = IntArray(buckets)
        val sr = DoubleArray(buckets); val nr = IntArray(buckets)
        for (s in list) {
            val age = now - s.at
            if (age < 0 || age >= windowMs) continue
            val i = buckets - 1 - (age * buckets / windowMs).toInt()
            s.a?.let { sa[i] += it; na[i]++ }
            s.b?.let { sb[i] += it; nb[i]++ }
            s.rssi?.let { sr[i] += it; nr[i]++ }
        }
        fun avg(s: DoubleArray, n: IntArray) = FloatArray(buckets) { if (n[it] == 0) Float.NaN else (s[it] / n[it]).toFloat() }
        return History(avg(sa, na), avg(sb, nb), avg(sr, nr))
    }

    /**
     * Auto reference: the strongest access point heard in the last [freshMs], once the adapter
     * has had [settleMs] since the screen opened to visit every channel. Null while it's still listening.
     */
    fun strongest(aps: List<Ap>, now: Long, since: Long, settleMs: Long = 12_000, freshMs: Long = 8_000): Ap? {
        if (now - since < settleMs) return null
        return aps.filter { now - it.lastSeen < freshMs }.maxByOrNull { it.rssi }
    }

    // ---- verdicts (shared with the tests) ----

    /** At least this many frames with both antennas before saying anything about them. */
    const val MIN_BOTH = 8

    /** Chain 0 vs chain 1 from their same-frame difference [delta] (dB, chain 0 minus chain 1, after zeroing). */
    fun chainVerdict(delta: Double): String {
        val d = Math.abs(delta)
        val strong = if (delta > 0) 0 else 1
        return when {
            d <= 1.0 -> "Both antennas hear the reference about the same (within 1 dB)."
            d <= 3.0 -> "Chain $strong is a little stronger (%.1f dB): normal for where the antennas point.".format(d)
            else -> "Chain ${1 - strong} is %.1f dB weaker: possible weak or loose antenna on that port. ".format(d) +
                "Swap the two antennas: if the weak side moves with the antenna, it's the antenna; if it stays, it's the port or cable."
        }
    }

    /**
     * Which port was covered: [before] while both antennas were free, [after] while one was
     * covered. Null when it can't tell (nothing dropped, or both dropped alike).
     */
    fun identify(before: Stats, after: Stats): String {
        val a0 = before.a; val b0 = before.b; val a1 = after.a; val b1 = after.b
        if (a0 == null || b0 == null || a1 == null || b1 == null || before.both < MIN_BOTH || after.both < MIN_BOTH / 2)
            return "Couldn't read both antennas: wait for more frames from the reference, then retry."
        val dropA = a0 - a1; val dropB = b0 - b1
        if (Math.max(dropA, dropB) < 3.0 || Math.abs(dropA - dropB) < 3.0)
            return "Couldn't tell which one: cover ONE antenna fully, hold still, then retry " +
                "(chain 0 dropped %.1f dB, chain 1 %.1f dB).".format(dropA, dropB)
        val covered = if (dropA > dropB) 0 else 1
        return "Chain $covered is the port you covered: it dropped %.1f dB, the other %.1f dB.".format(
            Math.max(dropA, dropB), Math.min(dropA, dropB))
    }

    /** Antenna A vs antenna B, each the average signal of a capture (dBm). */
    fun compare(a: Double, b: Double, what: String = ""): String {
        val d = b - a
        return when {
            Math.abs(d) <= 1.0 -> "${what}A and B hear the reference about the same (within 1 dB)."
            d > 0 -> "${what}antenna B is %.1f dB stronger.".format(d)
            else -> "${what}antenna A is %.1f dB stronger.".format(-d)
        }.replaceFirstChar { it.uppercase() }
    }

    /** "5 GHz ch 36" for a channel in [MonitorFrames]' numbering. */
    fun channelName(ch: Int): String = when {
        ch >= 5955 -> "6 GHz ch ${(ch - 5950) / 5}"
        ch > 14 -> "5 GHz ch $ch"
        else -> "2.4 GHz ch $ch"
    }

    private fun macBytes(mac: String): ByteArray? {
        val parts = mac.split(':')
        if (parts.size != 6) return null
        return ByteArray(6) { parts[it].toIntOrNull(16)?.toByte() ?: return null }
    }
}
