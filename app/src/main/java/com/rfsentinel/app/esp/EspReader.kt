package com.rfsentinel.app.esp

/**
 * Reads one ESP32 board until [stop] is called. RF Sentinel only listens and
 * asks read-only questions: OUI-Spy may be sent `CMD:VERSION` and, in
 * Detector mode, `CMD:DUMP_LIVE` (what it has found so far); GhostESP is only
 * asked for `help`, to scan networks and to list them; ESP32 Marauder only for
 * `help` and its passive `sniffbeacon` / `sniffprobe` sniffers.
 */
class EspReader(
    private val port: SerialPort,
    private val onStatus: (String) -> Unit,
    private val onSightings: (List<EspSighting>) -> Unit
) {
    @Volatile private var stopped = false

    fun stop() { stopped = true }

    fun run() {
        val buf = ByteArray(4096)
        onStatus("ESP32 · listening…")
        // 1. OUI-Spy prints on its own (banners, status, detections).
        var heard = readFor(buf, 4_000) { OuiSpyReports.recognises(it) }
        if (stopped) return
        // 2. Detector, PCAP and BLE-sniff modes answer a version request.
        if (!OuiSpyReports.recognises(heard)) {
            port.write("CMD:VERSION\n")
            heard += readFor(buf, 1_500) { OuiSpyReports.recognises(it) }
        }
        if (stopped) return
        if (!OuiSpyReports.recognises(heard)) {
            // 3. GhostESP answers `help` with its banner.
            port.write("help\r\n")
            val banner = readFor(buf, 2_500) { GhostEspReports.recognises(it) }
            if (GhostEspReports.recognises(banner)) { pollGhostEsp(buf); return }
            // 4. ESP32 Marauder answers `help` with its command list (a Flipper Zero in
            //    USB-UART bridge mode passes it through from its ESP32 board).
            if (MarauderReports.recognises(heard + banner)) { pollMarauder(buf); return }
            if (MarauderReports.isFlipperCli(heard + banner)) {
                onStatus("Flipper Zero connected - on the Flipper open GPIO > USB-UART Bridge so RF Sentinel can reach its ESP32 board (Marauder)")
                // Wait for the bridge, asking `help` now and then (the Flipper's own command
                // line just lists its commands) until Marauder answers through it.
                while (!stopped) {
                    port.write("help\r\n")
                    if (MarauderReports.recognises(readFor(buf, 3_000) { MarauderReports.recognises(it) })) {
                        pollMarauder(buf); return
                    }
                }
                return
            }
            heard += banner
        }
        // 5. OUI-Spy - or a quiet board: Flock-You and Sky Spy say nothing until they find
        //    something, so keep listening rather than giving up on it.
        readOuiSpy(heard, buf)
    }

    private fun readOuiSpy(already: String, buf: ByteArray) {
        var mode = OuiSpyReports.modeOf(already)
        val pending = StringBuilder()
        var count = 0
        fun status() = onStatus(when {
            mode == OuiSpyReports.Mode.SELECTOR ->
                "OUI-Spy is in its mode selector - join its Wi-Fi \"oui-spy\", open 192.168.4.1 and pick Flock-You, Detector or Sky Spy"
            mode == OuiSpyReports.Mode.OTHER ->
                "This OUI-Spy mode isn't used by RF Sentinel - pick Flock-You, Detector or Sky Spy"
            mode == null && count == 0 -> "ESP32 connected · waiting for detections (OUI-Spy reports only when it finds something)"
            else -> "OUI-Spy ${mode?.label ?: ""} · live · $count detection${if (count == 1) "" else "s"}"
        })
        fun consume(text: String) {
            pending.append(text)
            while (true) {
                val nl = pending.indexOf('\n')
                if (nl < 0) break
                val line = pending.substring(0, nl)
                pending.delete(0, nl + 1)
                OuiSpyReports.modeOf(line)?.let { if (it != mode) { mode = it; status() } }
                OuiSpyReports.parse(line)?.let {
                    count++
                    onSightings(listOf(it))
                    status()
                }
            }
            if (pending.length > 16_384) pending.setLength(0) // binary output (PCAP modes) isn't for us
        }
        consume(already) // what was heard while identifying (a partial last line stays pending)
        status()
        // Detector keeps what it found in RAM: fetch it once (read-only).
        if (mode == OuiSpyReports.Mode.DETECTOR) port.write("CMD:DUMP_LIVE\n")
        var askedDump = mode == OuiSpyReports.Mode.DETECTOR
        while (!stopped) {
            val n = port.read(buf, 500)
            if (n > 0) consume(String(buf, 0, n))
            if (!askedDump && mode == OuiSpyReports.Mode.DETECTOR) {
                askedDump = true
                port.write("CMD:DUMP_LIVE\n")
            }
        }
    }

    private fun pollGhostEsp(buf: ByteArray) {
        onStatus("GhostESP · live")
        while (!stopped) {
            port.write("scanap\r\n")
            readFor(buf, 6_000)
            if (stopped) break
            port.write("stopscan\r\n")
            readFor(buf, 400)
            port.write("list -a\r\n")
            val networks = GhostEspReports.parseList(readFor(buf, 3_000))
            if (networks.isNotEmpty()) onSightings(networks)
            onStatus("GhostESP · live · ${networks.size} Wi-Fi networks")
        }
        runCatching { port.write("stopscan\r\n") }
    }

    /**
     * ESP32 Marauder: alternates its two passive sniffers - `sniffbeacon` (access points,
     * both bands on an ESP32-C5) and `sniffprobe` (client devices looking for networks) -
     * and passes on what it hears in batches. Nothing else is sent.
     */
    private fun pollMarauder(buf: ByteArray) {
        val seen = LinkedHashMap<String, EspSighting>()
        val channels = HashSet<Int>()
        // Devices heard this round (one beacon + one probe sniff), for the status counts.
        val apMacs = HashSet<String>(); val clientMacs = HashSet<String>()
        var fiveGhz = false
        var hint = ""
        val pending = StringBuilder()
        fun status() = onStatus(
            "Marauder · live · ${apMacs.size} networks, ${clientMacs.size} client devices" +
                (if (fiveGhz) " · 2.4 + 5 GHz" else "") + hint
        )
        fun flush() {
            if (seen.isEmpty()) return
            onSightings(seen.values.toList())
            seen.clear()
            status()
        }
        fun sniff(command: String, ms: Long) {
            port.write("$command\r\n")
            val until = System.currentTimeMillis() + ms
            var lastFlush = System.currentTimeMillis()
            while (!stopped && System.currentTimeMillis() < until) {
                val n = port.read(buf, 300)
                if (n > 0) {
                    pending.append(String(buf, 0, n))
                    while (true) {
                        val nl = pending.indexOf('\n')
                        if (nl < 0) break
                        val s = MarauderReports.parse(pending.substring(0, nl))
                        pending.delete(0, nl + 1)
                        if (s == null) continue
                        if (s.client) clientMacs += s.mac else apMacs += s.mac
                        // Keep the strongest reading per device in each batch.
                        val prev = seen[s.mac]
                        if (prev == null || s.rssi > prev.rssi) seen[s.mac] = s
                        if (s.frequencyMhz >= 5000) fiveGhz = true
                        channels += s.frequencyMhz
                    }
                    if (pending.length > 16_384) pending.setLength(0)
                }
                if (System.currentTimeMillis() - lastFlush >= 2_000) { flush(); lastFlush = System.currentTimeMillis() }
            }
            flush()
            port.write("stopscan\r\n")
            readFor(buf, 500)
        }
        status()
        var rounds = 0
        while (!stopped) {
            channels.clear()
            sniff("sniffbeacon", 20_000)
            if (stopped) break
            sniff("sniffprobe", 10_000)
            rounds++
            // Marauder only hops channels with its ChanHop setting on; say so rather than change it.
            hint = if (rounds >= 2 && channels.size == 1) " · stuck on one channel: turn on Channel Hop in Marauder's settings" else ""
            status()
            apMacs.clear(); clientMacs.clear()
        }
        runCatching { port.write("stopscan\r\n") }
    }

    /** Reads for up to [ms], stopping early once [done] is true for what was read. */
    private fun readFor(buf: ByteArray, ms: Long, done: (String) -> Boolean = { false }): String {
        val sb = StringBuilder()
        val until = System.currentTimeMillis() + ms
        while (!stopped && System.currentTimeMillis() < until) {
            val n = port.read(buf, 300)
            if (n > 0) {
                sb.append(String(buf, 0, n))
                if (done(sb.toString())) break
            }
        }
        return sb.toString()
    }
}
