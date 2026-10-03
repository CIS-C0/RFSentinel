package com.rfsentinel.app.usb

/**
 * Access points and client devices heard by a USB WiFi adapter in monitor mode,
 * built from raw 802.11 frames. Shared by the USB WiFi drivers; not thread-safe
 * (each driver feeds it from its own RX thread).
 */
class MonitorFrames {
    /** Also report client devices (probe requests, data frames), not just access points. */
    @Volatile var captureClients = true

    private class Sighting(val mac: String, var isAp: Boolean) {
        var ssid: String? = null; var bssid: String? = null
        var rssi = NOMINAL_RSSI; var rssiAt = 0L; var measured = false; var channel = 0
        var lastSeen = 0L
        // Per-window counters for driver diagnostics (debug builds).
        var winHits = 0; var winMeasured = 0; var winMax = -128; var winSum = 0L
        var winOfdm = 0; var winA = -128; var winB = -128
    }

    private val sightings = LinkedHashMap<String, Sighting>()

    val tracked: Int get() = sightings.size

    fun clear() = sightings.clear()

    /**
     * One received 802.11 frame in [b] from [d] to [end] (exclusive), heard while tuned
     * to [channel] at [rssi] dBm. Beacons and probe responses carry their own channel,
     * which wins over the tuned one (a strong access point leaks into neighbouring
     * channels, and a frame can arrive just after a hop). [rssi] is null when the
     * frame carried no usable signal report: the device keeps its last reading.
     */
    fun frame(b: ByteArray, d: Int, end: Int, channel: Int, rssi: Int?, now: Long = System.currentTimeMillis(),
              pathA: Int? = null, pathB: Int? = null) {
        if (end - d < 24 || end > b.size) return
        fun u8(i: Int) = b[i].toInt() and 0xff
        val fc = u8(d); val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xf
        val fc1 = u8(d + 1); val toDs = fc1 and 0x01; val fromDs = (fc1 shr 1) and 0x01
        val a1 = d + 4; val a2 = d + 10; val a3 = d + 16
        fun touch(at: Int, ap: Boolean, ch: Int = channel): Sighting? {
            if (!isReal(b, at)) return null
            val m = mac(b, at)
            val s = sightings.getOrPut(m) { Sighting(m, ap) }
            // Strongest reading of the last couple of seconds, so a device that drove off fades.
            if (rssi != null && (!s.measured || rssi > s.rssi || now - s.rssiAt > RSSI_WINDOW_MS)) {
                s.rssi = rssi; s.rssiAt = now; s.measured = true
            }
            s.channel = ch; s.lastSeen = now
            s.winHits++
            if (rssi != null) { s.winMeasured++; s.winSum += rssi; if (rssi > s.winMax) s.winMax = rssi }
            if (pathA != null || pathB != null) {
                s.winOfdm++
                pathA?.let { if (it > s.winA) s.winA = it }; pathB?.let { if (it > s.winB) s.winB = it }
            }
            return s
        }
        when (ftype) {
            0 -> when (sub) {
                // Beacon or probe response: an access point.
                8, 5 -> if (d + 36 <= end) {
                    val ies = Ies.read(b, d + 36, end)
                    touch(a3, ap = true, ch = ies.channel.takeIf { validChannel(it) } ?: channel)?.let {
                        it.isAp = true
                        if (ies.ssid != null && it.ssid == null) it.ssid = ies.ssid
                    }
                }
                // Probe request: a device looking for networks (e.g. a laptop or a Flock camera).
                4 -> if (captureClients) touch(a2, ap = false)
                0, 2, 10, 11, 12 -> if (captureClients) touch(a2, ap = false)?.let { it.bssid = mac(b, a3) }
            }
            2 -> if (captureClients) when {
                toDs == 1 && fromDs == 0 -> touch(a2, ap = false)?.let { it.bssid = mac(b, a1) }
                toDs == 0 && fromDs == 1 -> touch(a1, ap = false)?.let { it.bssid = mac(b, a2) }
                else -> touch(a2, ap = false)
            }
        }
    }

    /** Access points and client devices heard within [windowMs]. */
    fun live(now: Long, windowMs: Long): Pair<Int, Int> {
        var aps = 0; var clients = 0
        for (s in sightings.values) if (now - s.lastSeen < windowMs) { if (s.isAp) aps++ else clients++ }
        return aps to clients
    }

    /** Everything heard within [freshMs], for the app; forgets what hasn't been heard for [pruneMs]. */
    fun drain(now: Long, freshMs: Long, pruneMs: Long = PRUNE_MS): List<MonitorSighting> {
        val batch = sightings.values.filter { now - it.lastSeen < freshMs }.map { s ->
            MonitorSighting(
                mac = s.mac,
                ssid = s.ssid,
                rssi = s.rssi.coerceIn(-120, -20),
                frequencyMhz = frequencyOf(s.channel),
                isAccessPoint = s.isAp,
                bssid = s.bssid
            )
        }
        sightings.entries.removeAll { now - it.value.lastSeen > pruneMs }
        return batch
    }

    /**
     * Diagnostics: every device heard since the last call, strongest first, as
     * "mac ssid ch N frames max/avg dBm"; resets the window.
     */
    fun window(limit: Int = 12): List<String> {
        val heard = sightings.values.filter { it.winHits > 0 }.sortedByDescending { it.winMax }
        val out = heard.take(limit).map { s ->
            "%s %-14s ch %3d %3d fr max %4d avg %4d%s%s".format(s.mac, (s.ssid ?: "").take(14), s.channel, s.winHits,
                s.winMax, if (s.winMeasured > 0) (s.winSum / s.winMeasured).toInt() else 0, if (s.isAp) " AP" else "",
                if (s.winOfdm > 0) " | OFDM %d: path A %d, B %d".format(s.winOfdm, s.winA, s.winB) else "")
        }
        for (s in sightings.values) { s.winHits = 0; s.winMeasured = 0; s.winMax = -128; s.winSum = 0; s.winOfdm = 0; s.winA = -128; s.winB = -128 }
        return out
    }

    /** Network name and channel from a management frame's information elements. */
    data class Ies(val ssid: String?, val channel: Int) {
        companion object {
            fun read(b: ByteArray, from: Int, end: Int): Ies {
                var ssid: String? = null; var ds = 0; var ht = 0
                var i = from
                while (i + 2 <= end) {
                    val tag = b[i].toInt() and 0xff; val tl = b[i + 1].toInt() and 0xff
                    if (i + 2 + tl > end) break
                    when (tag) {
                        0 -> if (ssid == null) ssid = String(b, i + 2, tl, Charsets.UTF_8).trim { it <= ' ' }
                        3 -> if (tl >= 1) ds = b[i + 2].toInt() and 0xff // DS parameter set (2.4 GHz)
                        61 -> if (tl >= 1) ht = b[i + 2].toInt() and 0xff // HT operation: primary channel
                    }
                    i += 2 + tl
                }
                return Ies(ssid, if (ds != 0) ds else ht)
            }
        }
    }

    companion object {
        const val PRUNE_MS = 60_000L
        /** Reported for a device whose frames never carried a signal reading. */
        const val NOMINAL_RSSI = -60
        private const val RSSI_WINDOW_MS = 2_000L

        fun validChannel(ch: Int) = ch in 1..14 || ch in 32..177

        /** Centre frequency of a 20 MHz WiFi channel, 0 when unknown. */
        fun frequencyOf(ch: Int): Int = when (ch) {
            in 1..13 -> 2407 + ch * 5
            14 -> 2484
            in 32..177 -> 5000 + ch * 5
            else -> 0
        }

        private fun mac(b: ByteArray, at: Int) = (at until at + 6).joinToString(":") { "%02x".format(b[it].toInt() and 0xff) }

        /** Not broadcast, empty or a multicast group address. */
        private fun isReal(b: ByteArray, at: Int): Boolean {
            val m0 = b[at].toInt() and 0xff
            if (m0 and 0x01 != 0) return false // group address (broadcast, IPv4/IPv6 multicast, STP...)
            for (k in 0 until 6) if (b[at + k].toInt() != 0) return true
            return false
        }
    }
}
