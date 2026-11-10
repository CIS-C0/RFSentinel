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
        /** Heard transmitting itself, not only named as the receiver of someone else's frame. */
        var heard = false
        var lastSeen = 0L
        /** Network names this client asked for (probe requests), oldest first. */
        val probed = LinkedHashSet<String>()
        /** Maker / model / device name from its WPS block, when it sends one. */
        var wps: ProbeIntel.Wps? = null
        /** How it builds its probe requests ([ProbeIntel.fingerprint]). */
        var fingerprint: String? = null
        /** FlipDeFlock's probe skeleton hash ([ProbeIntel.skeletonHash]). */
        var skeleton: Long? = null
        /** An access point that hides its name (blank name in its beacons). */
        var hidden = false
        /** A client that sent wildcard probe requests; with the Flock-camera Wi-Fi Alliance element. */
        var wildcard = false; var flockIe = false
        /** How steadily it sends wildcard probes ([com.rfsentinel.app.detect.ProbeBehaviour]). */
        var probeRate: com.rfsentinel.app.detect.ProbeBehaviour.Counter? = null
        /** An access point's latest beacon elements and its "protected" capability bit; [iesAt]: when they, or a client's [traits], were read. */
        var ies: List<Pair<Int, ByteArray>>? = null; var iesAt = 0L; var privacy = false
        /** What a client's probe requests say about it in words ([ProbeIntel.traits]), refreshed every [IES_REFRESH_MS]. */
        var traits: String? = null
        /** When its WiFi started, from the beacon clock (TSF, microseconds since the radio came up). */
        var upSince: Long? = null
        /** Data-frame bytes it sent while heard. */
        var dataBytes = 0L
        // Per-window counters for driver diagnostics (debug builds).
        var winHits = 0; var winMeasured = 0; var winMax = -128; var winSum = 0L
        var winOfdm = 0; var winA = -128; var winB = -128
    }

    private val sightings = LinkedHashMap<String, Sighting>()

    /** Names of hidden networks seen in connection requests before their access point was heard. */
    private val pendingNames = LinkedHashMap<String, String>()

    val tracked: Int get() = sightings.size

    fun clear() { sightings.clear(); pendingNames.clear() }

    /**
     * One received 802.11 frame in [b] from [d] to [end] (exclusive), heard while tuned
     * to [channel] at [rssi] dBm. Beacons and probe responses carry their own channel,
     * which wins over the tuned one (a strong access point leaks into neighbouring
     * channels, and a frame can arrive just after a hop). [rssi] is null when the
     * frame carried no usable signal report: the device keeps its last reading.
     */
    fun frame(b: ByteArray, d: Int, end: Int, channel: Int, rssi: Int?, now: Long = System.currentTimeMillis(),
              pathA: Int? = null, pathB: Int? = null) {
        frameCount.incrementAndGet()
        PcapRecorder.frame(b, d, end, channel, rssi, now)
        if (end - d < 24 || end > b.size) return
        fun u8(i: Int) = b[i].toInt() and 0xff
        val fc = u8(d); val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xf
        val fc1 = u8(d + 1); val toDs = fc1 and 0x01; val fromDs = (fc1 shr 1) and 0x01
        val a1 = d + 4; val a2 = d + 10; val a3 = d + 16
        val check = AntennaCheck.listening
        if (check) AntennaCheck.frame(b, a2, rssi, pathA, pathB, now)
        // [sent]: the device transmitted this frame, so its signal is this device's (not so for the receiver of a router's frame).
        fun touch(at: Int, ap: Boolean, ch: Int = channel, sent: Boolean = true): Sighting? {
            if (!isReal(b, at)) return null
            val m = mac(b, at)
            val s = sightings.getOrPut(m) { Sighting(m, ap) }
            val rssi = rssi.takeIf { sent }
            if (sent) s.heard = true
            // Strongest reading of the last couple of seconds, so a device that drove off fades.
            if (rssi != null && (!s.measured || rssi > s.rssi || now - s.rssiAt > RSSI_WINDOW_MS)) {
                s.rssi = rssi; s.rssiAt = now; s.measured = true
            }
            s.channel = ch; s.lastSeen = now
            s.winHits++
            if (rssi != null) { s.winMeasured++; s.winSum += rssi; if (rssi > s.winMax) s.winMax = rssi }
            if (sent && (pathA != null || pathB != null)) {
                s.winOfdm++
                pathA?.let { if (it > s.winA) s.winA = it }; pathB?.let { if (it > s.winB) s.winB = it }
            }
            return s
        }
        // Deauthentication (12) / disassociation (10) not protected by 802.11w: counted for flood alarms.
        if (ftype == 0 && (sub == 12 || sub == 10) && (fc1 and 0x40) == 0 && isReal(b, a3)) {
            DeauthWatch.frame(mac(b, a3), (0 until 6).all { b[a1 + it] == 0xFF.toByte() }, channel, now)
        }
        when (ftype) {
            0 -> when (sub) {
                // Beacon or probe response: an access point.
                8, 5 -> if (d + 36 <= end) {
                    val ies = Ies.read(b, d + 36, ProbeIntel.withoutFcs(b, d, end))
                    touch(a3, ap = true, ch = ies.channel.takeIf { validChannel(it) } ?: channel)?.let {
                        it.isAp = true
                        val name = ies.ssid?.takeIf { n -> validSsid(n) }
                        // A hidden network beacons a blank name, but answers a device that asks for
                        // it by name with the real one (probe response).
                        if (name == null) { if (sub == 8) it.hidden = true }
                        else if (it.ssid == null) it.ssid = name
                        if (it.ssid == null) pendingNames.remove(it.mac)?.let { n -> it.ssid = n }
                        ies.wps?.let { w -> it.wps = w }
                        // Fixed fields: the 8-byte beacon clock (microseconds since its WiFi came up), interval, capability info.
                        val tsf = (0 until 8).fold(0L) { acc, k -> acc or (u8(d + 24 + k).toLong() shl (8 * k)) }
                        if (tsf in 1 until MAX_TSF_US) it.upSince = now - tsf / 1000
                        if (it.ies == null || now - it.iesAt > IES_REFRESH_MS) {
                            it.ies = elements(b, d + 36, ProbeIntel.withoutFcs(b, d, end)); it.iesAt = now
                            it.privacy = (u8(d + 34) and 0x10) != 0
                        }
                        if (check) AntennaCheck.heardAp(it.mac, it.ssid, it.channel, rssi, now)
                    }
                    // One transmitter answering under many network names: a Karma / PineAP fake access point.
                    if (isReal(b, a2)) ies.ssid?.takeIf { validSsid(it) }?.let { KarmaWatch.frame(mac(b, a2), it, channel, now) }
                }
                // Probe request: a device looking for networks (e.g. a laptop or a Flock camera),
                // often by name - the networks it has joined before.
                // (Bare scanner probes - a fresh random address each burst - are skipped: see isScannerProbe.)
                4 -> if (captureClients && !isScannerProbe(b, d, end)) touch(a2, ap = false)?.let { s ->
                    // Without the checksum some drivers keep: read as elements, its bytes would now and
                    // then add a bogus one and flip the fingerprint.
                    val body = ProbeIntel.withoutFcs(b, d, end)
                    val ies = Ies.read(b, d + 24, body)
                    ies.wps?.let { s.wps = it }
                    if (ies.wildcard) {
                        s.wildcard = true
                        (s.probeRate ?: com.rfsentinel.app.detect.ProbeBehaviour.Counter().also { s.probeRate = it }).probe(now)
                    }
                    if (ies.flockIe) s.flockIe = true
                    ProbeIntel.fingerprint(b, d + 24, body)?.let { s.fingerprint = it }
                    ProbeIntel.skeletonHash(b, d + 24, body)?.let { s.skeleton = it }
                    if (s.traits == null || now - s.iesAt > IES_REFRESH_MS) {
                        ProbeIntel.traits(elements(b, d + 24, body))?.let { s.traits = it }; s.iesAt = now
                    }
                    ies.ssid?.takeIf { validSsid(it) }?.let { name ->
                        s.probed.remove(name); s.probed.add(name)
                        if (s.probed.size > MAX_PROBED) s.probed.remove(s.probed.first())
                    }
                }
                0, 2, 10, 11, 12 -> {
                    // A device joining a network names it in its (re)association request: that
                    // also reveals a hidden network's name.
                    if (sub == 0 || sub == 2) revealFromAssociation(b, d + 24 + (if (sub == 0) 4 else 10), end, mac(b, a3))
                    // Sender and network the same: the access point itself (deauth, auth reply), not a client.
                    if (captureClients && mac(b, a2) != mac(b, a3)) touch(a2, ap = false)?.let { it.bssid = mac(b, a3) }
                }
            }
            2 -> {
                val size = end - d
                if (captureClients) when {
                    toDs == 1 && fromDs == 0 -> touch(a2, ap = false)?.let { it.bssid = mac(b, a1); it.dataBytes += size }
                    toDs == 0 && fromDs == 1 -> touch(a1, ap = false, sent = false)?.let { it.bssid = mac(b, a2) }
                    else -> touch(a2, ap = false)
                }
                // Sent by the router to one of its devices.
                if (toDs == 0 && fromDs == 1) sightings[mac(b, a2)]?.let { it.dataBytes += size }
            }
        }
    }

    /**
     * A bare broadcast probe as sent by ESP32 scanners (OUI-SPY's survey engine, GhostESP):
     * empty SSID and only the four 802.11b rates, nothing else, from a new random address
     * after every channel hop. Phones and laptops always add more elements. Counting them
     * would add a "new device" every second while such a board scans next to the adapter.
     */
    internal fun isScannerProbe(b: ByteArray, d: Int, end: Int): Boolean {
        val body = d + 24
        val rest = end - (body + 8)
        if (rest != 0 && rest != 4) return false // + FCS when the driver keeps it
        fun u8(i: Int) = b[i].toInt() and 0xff
        if ((u8(d + 10) and 0x02) == 0) return false // locally administered (random) source
        return u8(body) == 0 && u8(body + 1) == 0 && u8(body + 2) == 1 && u8(body + 3) == 4 &&
            u8(body + 4) == 0x82 && u8(body + 5) == 0x84 && u8(body + 6) == 0x8B && u8(body + 7) == 0x96
    }

    private fun revealFromAssociation(b: ByteArray, from: Int, end: Int, bssid: String) {
        if (from >= end) return
        val name = Ies.read(b, from, end).ssid?.takeIf { validSsid(it) } ?: return
        val ap = sightings[bssid]
        if (ap != null) { if (ap.ssid == null) ap.ssid = name }
        else {
            pendingNames[bssid] = name
            if (pendingNames.size > 200) pendingNames.remove(pendingNames.keys.first())
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
        // A client only ever named as the receiver of a router's frames was never heard itself: it waits for its
        // first own frame instead of showing at a made-up level.
        val batch = sightings.values.filter { now - it.lastSeen < freshMs && (it.isAp || it.heard) }.map { s ->
            MonitorSighting(
                mac = s.mac,
                ssid = s.ssid,
                rssi = s.rssi.coerceIn(-120, -20),
                frequencyMhz = frequencyOf(s.channel),
                isAccessPoint = s.isAp,
                bssid = s.bssid,
                probedSsids = s.probed.toList(),
                wps = s.wps,
                fingerprint = s.fingerprint,
                probeTraits = s.traits,
                probeSkeleton = s.skeleton,
                hiddenNetwork = s.hidden,
                wildcardProbe = s.wildcard,
                flockProbeIe = s.flockIe,
                probeRun = s.probeRate?.run(),
                infoElements = s.ies.orEmpty(),
                capabilities = s.ies?.let { capabilities(it, s.privacy) } ?: "",
                standard = s.ies?.let { standard(it) },
                upSince = s.upSince,
                dataBytes = s.dataBytes
            )
        }
        sightings.entries.removeAll { now - it.value.lastSeen > pruneMs }
        return batch
    }

    fun emit(freshMs: Long, onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = drain(System.currentTimeMillis(), freshMs)
        if (batch.isNotEmpty()) onFrame(batch)
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

    /**
     * Network name and channel from a management frame's information elements. [wildcard]: an empty
     * name element (a probe request asking every network to answer). [flockIe]: the Wi-Fi Alliance
     * element 50:6F:9A type 0x16 with 03 01 03, which Flock cameras put in their probe requests
     * (also some ordinary clients: it only ever tightens a Flock-module match).
     */
    data class Ies(val ssid: String?, val channel: Int, val wps: ProbeIntel.Wps? = null,
                   val wildcard: Boolean = false, val flockIe: Boolean = false) {
        companion object {
            private val FLOCK_IE = byteArrayOf(0x50, 0x6F, 0x9A.toByte(), 0x16, 0x03, 0x01, 0x03)

            fun read(b: ByteArray, from: Int, end: Int): Ies {
                var ssid: String? = null; var ds = 0; var ht = 0; var wps: ProbeIntel.Wps? = null
                var wildcard = false; var flockIe = false
                var i = from
                while (i + 2 <= end) {
                    val tag = b[i].toInt() and 0xff; val tl = b[i + 1].toInt() and 0xff
                    if (i + 2 + tl > end) break
                    when (tag) {
                        0 -> {
                            if (tl == 0) wildcard = true
                            if (ssid == null) ssid = String(b, i + 2, tl, Charsets.UTF_8).trim { it <= ' ' }
                        }
                        3 -> if (tl >= 1) ds = b[i + 2].toInt() and 0xff // DS parameter set (2.4 GHz)
                        61 -> if (tl >= 1) ht = b[i + 2].toInt() and 0xff // HT operation: primary channel
                        221 -> if (tl >= 4 && b[i + 2].toInt() == 0x00 && b[i + 3].toInt() == 0x50 &&
                            (b[i + 4].toInt() and 0xff) == 0xF2 && b[i + 5].toInt() == 0x04) {
                            ProbeIntel.parseWps(b, i + 6, i + 2 + tl)?.let { wps = it } // WPS
                        } else if (tl == FLOCK_IE.size && (0 until tl).all { k -> b[i + 2 + k] == FLOCK_IE[k] }) flockIe = true
                    }
                    i += 2 + tl
                }
                return Ies(ssid, if (ds != 0) ds else ht, wps, wildcard, flockIe)
            }
        }
    }

    companion object {
        /** Every frame the adapters received, for the main screen's frames-per-second line. */
        val frameCount = java.util.concurrent.atomic.AtomicLong()

        const val PRUNE_MS = 60_000L
        /** Reported for a device whose frames never carried a signal reading. */
        const val NOMINAL_RSSI = -60
        private const val RSSI_WINDOW_MS = 2_000L

        fun validChannel(ch: Int) = ch in 1..14 || ch in 32..177

        /** A network name worth keeping: not empty (a wildcard probe), no control characters. */
        fun validSsid(s: String) = s.isNotEmpty() && s.length <= 32 && s.none { it < ' ' || it == '�' }

        private const val MAX_PROBED = 16
        private const val IES_REFRESH_MS = 10_000L
        /** A beacon clock past 5 years is junk (a corrupt frame), not uptime. */
        private const val MAX_TSF_US = 5L * 365 * 86_400 * 1_000_000

        /** The elements of a frame body as (id, payload), like Android's scan results; an extension element (255) keeps its extension ID as first byte. */
        internal fun elements(b: ByteArray, from: Int, end: Int): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>()
            var i = from
            while (i + 2 <= end) {
                val len = b[i + 1].toInt() and 0xff
                if (i + 2 + len > end) break
                out += (b[i].toInt() and 0xff) to b.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
            }
            return out
        }

        /** Android's scan-result security text ("[RSN-PSK][ESS]") from the RSN / WPA elements, so a USB sighting reads like the phone's own. */
        internal fun capabilities(ies: List<Pair<Int, ByteArray>>, privacy: Boolean): String {
            val rsn = ies.firstOrNull { it.first == 48 }?.second
            val wpa = ies.firstOrNull { (id, d) -> id == 221 && d.size >= 4 && d[0].toInt() == 0 && d[1].toInt() == 0x50 &&
                (d[2].toInt() and 0xff) == 0xF2 && d[3].toInt() == 1 }?.second
            val parts = listOfNotNull(wpa?.let { "[WPA-${akms(it, 4) ?: "?"}]" }, rsn?.let { "[RSN-${akms(it, 0) ?: "?"}]" })
            return when {
                parts.isNotEmpty() -> parts.joinToString("") + "[ESS]"
                privacy -> "[WEP][ESS]"
                else -> "[ESS]"
            }
        }

        // Key management suites (IEEE 802.11 table 9-151; the WPA element uses 1 and 2 the same way).
        private val AKM = mapOf(1 to "EAP", 3 to "EAP", 5 to "EAP", 11 to "EAP", 12 to "EAP", 13 to "EAP",
            2 to "PSK", 4 to "PSK", 6 to "PSK", 8 to "SAE", 9 to "SAE", 24 to "SAE", 25 to "SAE", 18 to "OWE")

        /** "PSK", "PSK+SAE", "EAP"... from an RSN body at [start]: version, group cipher, pairwise ciphers, then the key management list. */
        private fun akms(d: ByteArray, start: Int): String? {
            fun u16(i: Int) = (d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8)
            var i = start + 6
            if (i + 2 > d.size) return null
            i += 2 + 4 * u16(i)
            if (i + 2 > d.size) return null
            val n = u16(i); i += 2
            return (0 until n).mapNotNull { k -> d.getOrNull(i + 4 * k + 3)?.let { AKM[it.toInt() and 0xff] } }
                .distinct().joinToString("+").ifEmpty { null }
        }

        /** The WiFi generation its beacon announces, in the phone scan's words. */
        internal fun standard(ies: List<Pair<Int, ByteArray>>): String {
            fun ext(id: Int) = ies.any { it.first == 255 && it.second.isNotEmpty() && (it.second[0].toInt() and 0xff) == id }
            return when {
                ext(108) -> "WiFi 7 (802.11be)"
                ext(35) -> "WiFi 6 (802.11ax)"
                ies.any { it.first == 191 } -> "WiFi 5 (802.11ac)"
                ies.any { it.first == 45 } -> "WiFi 4 (802.11n)"
                else -> "Legacy (802.11a/b/g)"
            }
        }

        /** Centre frequency of a 20 MHz WiFi channel, 0 when unknown; a 6 GHz channel comes as its MHz already. */
        fun frequencyOf(ch: Int): Int = when (ch) {
            in 1..13 -> 2407 + ch * 5
            14 -> 2484
            in 32..177 -> 5000 + ch * 5
            in 5955..7115 -> ch
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
