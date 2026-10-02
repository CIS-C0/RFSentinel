package com.rfsentinel.app.esp

/**
 * Reads one ESP32 board until [stop] is called. RF Sentinel only listens and
 * asks read-only questions: OUI-Spy may be sent `CMD:VERSION` and, in
 * Detector mode, `CMD:DUMP_LIVE` (what it has found so far); GhostESP is only
 * asked for `help`, to scan networks and to list them.
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
            heard += banner
        }
        // 4. OUI-Spy - or a quiet board: Flock-You and Sky Spy say nothing until they find
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
