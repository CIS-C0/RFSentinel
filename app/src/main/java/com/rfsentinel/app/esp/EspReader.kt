package com.rfsentinel.app.esp

/**
 * Reads one ESP32 board until [stop] is called. RF Sentinel only listens:
 * OUI-Spy streams its detections by itself and is never sent anything;
 * GhostESP is only ever asked to scan for networks and list them.
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
        val heard = StringBuilder()
        val listenUntil = System.currentTimeMillis() + 4_000
        while (!stopped && System.currentTimeMillis() < listenUntil && !OuiSpyReports.recognises(heard.toString())) {
            val n = port.read(buf, 300)
            if (n > 0) heard.append(String(buf, 0, n))
        }
        if (stopped) return
        if (OuiSpyReports.recognises(heard.toString())) {
            readOuiSpy(heard.toString(), buf)
            return
        }
        // Not streaming on its own: see whether it's GhostESP.
        port.write("help\r\n")
        val banner = readFor(buf, 2_500)
        if (GhostEspReports.recognises(banner)) pollGhostEsp(buf)
        else onStatus("ESP32 connected, but its firmware isn't OUI-Spy or GhostESP")
    }

    private fun readOuiSpy(already: String, buf: ByteArray) {
        val pending = StringBuilder(already)
        var count = 0
        onStatus("OUI-Spy · live")
        while (!stopped) {
            val n = port.read(buf, 500)
            if (n <= 0) continue
            pending.append(String(buf, 0, n))
            while (true) {
                val nl = pending.indexOf('\n')
                if (nl < 0) break
                val line = pending.substring(0, nl)
                pending.delete(0, nl + 1)
                OuiSpyReports.parse(line)?.let {
                    count++
                    onSightings(listOf(it))
                    onStatus("OUI-Spy · live · $count detections")
                }
            }
            if (pending.length > 16_384) pending.setLength(0) // not line-based output (another mode)
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

    private fun readFor(buf: ByteArray, ms: Long): String {
        val sb = StringBuilder()
        val until = System.currentTimeMillis() + ms
        while (!stopped && System.currentTimeMillis() < until) {
            val n = port.read(buf, 300)
            if (n > 0) sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }
}
