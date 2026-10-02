package com.rfsentinel.app.esp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Drives [EspReader] with a fake board that answers like each firmware does. */
class EspReaderTest {

    /** Emits [boot] at start, then replies to commands via [reply]. */
    private class FakeBoard(boot: String, private val reply: (String) -> String?) : SerialPort {
        val written = CopyOnWriteArrayList<String>()
        private val out = ConcurrentLinkedQueue<ByteArray>().apply { if (boot.isNotEmpty()) add(boot.toByteArray()) }

        fun push(text: String) { out.add(text.toByteArray()) }

        override fun write(text: String) {
            written += text.trim()
            reply(text.trim())?.let { push(it) }
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val b = out.poll() ?: run { Thread.sleep(minOf(timeoutMs, 20).toLong()); return 0 }
            System.arraycopy(b, 0, buf, 0, b.size)
            return b.size
        }

        override fun close() {}
    }

    private fun runFor(board: FakeBoard, ms: Long, afterStart: (FakeBoard) -> Unit = {}): Pair<List<String>, List<EspSighting>> {
        val statuses = CopyOnWriteArrayList<String>()
        val seen = CopyOnWriteArrayList<EspSighting>()
        val r = EspReader(board, { statuses += it }) { seen += it }
        val t = Thread { r.run() }
        t.start()
        afterStart(board)
        Thread.sleep(ms)
        r.stop()
        t.join(3_000)
        return statuses to seen
    }

    private val flockLine = """{"event":"detection","detection_method":"wifi_wildcard_probe","detection_tier":3,"protocol":"wifi_2_4ghz","mac_address":"70:c9:4e:11:22:33","oui":"70:c9:4e","device_name":"","rssi":-71,"channel":6,"frequency":2437,"ssid":""}""" + "\n"

    @Test
    fun streamingFlockYouIsReadWithoutSendingAnything() {
        val board = FakeBoard(flockLine) { null }
        val (statuses, seen) = runFor(board, 600)
        assertEquals(1, seen.size)
        assertTrue(board.written.isEmpty())
        assertTrue(statuses.last().startsWith("OUI-Spy Flock-You · live · 1 detection"))
    }

    @Test
    fun aQuietBoardIsKeptAndItsLaterDetectionsAreRead() {
        // Flock-You with nothing around: silent, ignores CMD:VERSION and help.
        val board = FakeBoard("") { null }
        val (statuses, seen) = runFor(board, 9_500) { b ->
            Thread { Thread.sleep(8_800); b.push(flockLine) }.start()
        }
        assertEquals(listOf("CMD:VERSION", "help"), board.written.toList())
        assertTrue(statuses.any { it.startsWith("ESP32 connected · waiting for detections") })
        assertEquals(1, seen.size)
    }

    @Test
    fun detectorIsIdentifiedAndItsLiveTableFetched() {
        val det = """{"event":"detection","protocol":"ble","detection_method":"ble_oui","mac_address":"00:25:df:aa:bb:cc","addr_type":"public","rssi":-55,"rssi_min":-70,"rssi_max":-50,"company_id":845,"service_uuid":null,"local_name":"","device_name":"","match_method":"oui","matched_signature":"00:25:DF","first_seen_ms":1,"last_seen_ms":2,"hit_count":3,"replay_source":"ram"}"""
        val old = det.replace("\"ram\"", "\"flash\"").replace("aa:bb:cc", "00:00:01")
        val board = FakeBoard("") { cmd ->
            when (cmd) {
                "CMD:VERSION" -> "OUI-SPY BLE DETECTOR 1.2 built Jan 1 2026\n"
                "CMD:DUMP_LIVE" -> "BEGIN_DUMP live bytes=0 count=2\n$det\n$old\nEND_DUMP live count=2\n"
                else -> null
            }
        }
        val (statuses, seen) = runFor(board, 6_000)
        assertEquals(listOf("CMD:VERSION", "CMD:DUMP_LIVE"), board.written.toList())
        assertEquals(listOf("00:25:DF:AA:BB:CC"), seen.map { it.mac }) // the replayed old session is skipped
        assertTrue(statuses.last().startsWith("OUI-Spy Detector · live"))
    }

    @Test
    fun selectorModeTellsTheUserToPickAMode() {
        val board = FakeBoard("[SELECTOR] *** SELECTOR FULLY INITIALIZED ***\n[SELECTOR] WiFi AP: 'oui-spy'\n") { null }
        val (statuses, seen) = runFor(board, 600)
        assertTrue(seen.isEmpty())
        assertTrue(statuses.last().startsWith("OUI-Spy is in its mode selector"))
        assertFalse(board.written.contains("help"))
    }

    @Test
    fun ghostEspIsPolledForNetworks() {
        val board = FakeBoard("") { cmd ->
            when (cmd) {
                "help" -> "Ghost ESP Commands:\n"
                "list -a" -> "[0] SSID: Example Net, BSSID: 34:53:D2:C4:5D:E6, RSSI: -55, Company: Unknown\n"
                else -> null
            }
        }
        val (_, seen) = runFor(board, 16_500)
        assertTrue(board.written.containsAll(listOf("help", "scanap", "stopscan", "list -a")))
        assertTrue(seen.any { it.name == "Example Net" })
    }
}
