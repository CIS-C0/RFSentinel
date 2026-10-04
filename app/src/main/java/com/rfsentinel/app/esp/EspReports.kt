package com.rfsentinel.app.esp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId

/**
 * One device an ESP32 board reported. RF Sentinel turns it into a normal
 * observation, so all its own detection rules apply on top of [hits].
 */
data class EspSighting(
    val mac: String,
    val rssi: Int,
    val ble: Boolean,
    /** Wi-Fi network name (access points) or Bluetooth device name. */
    val name: String? = null,
    val frequencyMhz: Int = 0,
    /** Wi-Fi security in Android's ScanResult style ("[WPA2-PSK]"), when known. */
    val capabilities: String = "",
    val companyId: Int? = null,
    val serviceUuid16: Int? = null,
    /** Drone position (OUI-Spy Sky Spy mode). */
    val remoteId: RemoteId.Info? = null,
    /** Matches the board itself reported. */
    val hits: List<Hit> = emptyList(),
    /** A Wi-Fi client device (heard sending probe requests), not an access point. */
    val client: Boolean = false,
    /** Network names a client asked for by name (probe requests). */
    val probedSsids: List<String> = emptyList()
)

/**
 * Lines printed by OUI-Spy (colonelpanichacks/oui-spy-unified-blue) in its
 * streaming modes: Flock-You and Detector (`{"event":"detection",...}`) and
 * Sky Spy (`{"mac":..,"drone_lat":..}`).
 */
object OuiSpyReports {

    const val SOURCE = "OUI-Spy board over USB"

    fun recognises(text: String): Boolean =
        "\"event\":\"detection\"" in text || "\"drone_lat\"" in text || "\"status\":\"scanning\"" in text ||
            "OUI-SPY" in text || "[flockyou]" in text || "[SELECTOR]" in text

    /** OUI-Spy modes, as far as RF Sentinel is concerned. */
    enum class Mode(val label: String, val usable: Boolean) {
        FLOCK_YOU("Flock-You", true), DETECTOR("Detector", true), SKY_SPY("Sky Spy", true),
        SELECTOR("mode selector", false), OTHER("PCAP / BLE sniff / fox hunter", false)
    }

    /** Which mode [text] (banners, command replies or detection lines) shows, if any. */
    fun modeOf(text: String): Mode? = when {
        "\"drone_lat\"" in text || "\"status\":\"scanning\"" in text || "SKY SPY" in text -> Mode.SKY_SPY
        "\"detection_tier\"" in text || "[flockyou]" in text || "FLOCK-YOU" in text -> Mode.FLOCK_YOU
        "\"match_method\"" in text || "BLE DETECTOR" in text || "STARTING DETECTOR" in text ||
            "\"mode\":\"ble_detector\"" in text -> Mode.DETECTOR
        "[SELECTOR]" in text || "Firmware Selector" in text || "STARTING SELECTOR" in text -> Mode.SELECTOR
        "PCAP" in text || "BLE SNIFF" in text || "FOXHUNT" in text || "blesniff" in text -> Mode.OTHER
        else -> null
    }

    fun parse(line: String): EspSighting? {
        val t = line.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val o = runCatching { JsonParser.parseString(t).asJsonObject }.getOrNull() ?: return null
        // Detections from a previous session (replayed from the board's flash) are old news.
        if (o.text("replay_source") == "flash") return null
        return when {
            o.has("drone_lat") -> drone(o)
            o.text("event") == "detection" -> detection(o)
            else -> null
        }
    }

    private fun JsonObject.text(k: String): String? =
        get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(k: String): Int? =
        get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.coord(k: String): Double? =
        get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it != 0.0 }

    private fun detection(o: JsonObject): EspSighting? {
        val mac = o.text("mac_address")?.uppercase() ?: return null
        val method = o.text("detection_method").orEmpty()
        val ble = o.text("protocol") == "ble" || method.startsWith("ble_")
        val tier = o.number("detection_tier")
        val signature = o.text("matched_signature")
        val hit = when {
            "wildcard_probe" in method -> Hit(Category.ALPR, "Flock camera (wildcard probe)", 80,
                "Reported by OUI-Spy: a Flock-style network probe ($method)", SOURCE)
            method == "wifi_ssid" -> Hit(Category.ALPR, "Flock camera (Wi-Fi name)", 80,
                "Reported by OUI-Spy: Flock network name \"${o.text("ssid") ?: "?"}\"", SOURCE)
            tier != null -> Hit(Category.ALPR, "Possible Flock equipment", when (tier) { 3 -> 70; 2 -> 55; else -> 35 },
                "Reported by OUI-Spy Flock-You ($method, tier $tier)", SOURCE)
            signature != null -> Hit(Category.CUSTOM, "OUI-Spy target: $signature", 70,
                "Matched the watchlist set up on the OUI-Spy board", SOURCE)
            else -> null
        }
        return EspSighting(
            mac = mac, rssi = o.number("rssi") ?: -90, ble = ble,
            name = if (ble) o.text("local_name") ?: o.text("device_name") else o.text("ssid"),
            frequencyMhz = if (ble) 0 else o.number("frequency") ?: 0,
            companyId = o.number("company_id"), serviceUuid16 = o.number("service_uuid"),
            hits = listOfNotNull(hit)
        )
    }

    private fun drone(o: JsonObject): EspSighting? {
        val mac = o.text("mac")?.uppercase() ?: return null
        val info = RemoteId.Info(
            uasId = o.text("basic_id"),
            latitude = o.coord("drone_lat"), longitude = o.coord("drone_long"),
            altitudeGeoM = o.number("drone_altitude")?.toDouble(),
            operatorLatitude = o.coord("pilot_lat"), operatorLongitude = o.coord("pilot_long")
        )
        return EspSighting(
            mac = mac, rssi = o.number("rssi") ?: -90, ble = true, remoteId = info,
            hits = listOf(Hit(Category.DRONE, "Drone broadcasting Remote ID", 95,
                "Reported by OUI-Spy Sky Spy" + (info.uasId?.let { " (ID $it)" } ?: ""), SOURCE))
        )
    }
}

/**
 * GhostESP's `list -a` network list: `[0] SSID: Name, BSSID: AA:BB:..., RSSI: -55, Company: X`
 * (channel / band fields when the firmware prints them).
 */
object GhostEspReports {

    private val ANSI = Regex("""\u001B\[[0-9;]*m""")
    private val ROW = Regex("""\[\d+]\s*SSID:\s*(.*?),\s*BSSID:\s*([0-9A-Fa-f:]{17})(.*)""")
    private val RSSI = Regex("""RSSI:\s*(-?\d+)""")
    private val CHANNEL = Regex("""Channel:\s*(\d+)""")

    fun recognises(text: String): Boolean = "ghost esp" in text.lowercase() || "ghostesp" in text.lowercase()

    fun parseList(text: String): List<EspSighting> = text.lines().mapNotNull { raw ->
        val m = ROW.find(ANSI.replace(raw, "")) ?: return@mapNotNull null
        val ssid = m.groupValues[1].trim().takeUnless { it.isEmpty() || it.equals("(Hidden)", true) }
        val rest = m.groupValues[3]
        val ch = CHANNEL.find(rest)?.groupValues?.get(1)?.toIntOrNull()
        EspSighting(
            mac = m.groupValues[2].uppercase(),
            rssi = RSSI.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: -90,
            ble = false, name = ssid,
            frequencyMhz = when {
                ch == null -> 0
                ch == 14 -> 2484
                ch in 1..13 -> 2407 + ch * 5
                else -> 5000 + ch * 5
            }
        )
    }
}

/**
 * ESP32 Marauder (justcallmekoko/ESP32Marauder) serial output, from its passive sniffers.
 * `sniffbeacon`: `-55 Ch: 36 aa:bb:cc:dd:ee:ff ESSID: Name`;
 * `sniffprobe`: `-60 Ch: 6 Client: aa:bb:cc:dd:ee:ff Requesting: Name`.
 * Dual-band boards (ESP32-C5: Marauder v8, BFFB v2, T-Dongle C5, C5 DevKit) report 5 GHz channels too.
 */
object MarauderReports {

    private val ANSI = Regex("""\u001B\[[0-9;]*m""")
    private val BEACON = Regex("""^\s*(-?\d+)\s+Ch:\s*(\d+)\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\s+ESSID:\s?(.*)$""")
    private val PROBE = Regex("""^\s*(-?\d+)\s+Ch:\s*(\d+)\s+Client:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\s+Requesting:\s?(.*)$""")

    /** Marauder's `help` header, or its name in a boot banner. */
    fun recognises(text: String): Boolean =
        "============ Commands ============" in text || "sniffbeacon" in text || "Marauder" in text

    /** The Flipper Zero's own command line (not in USB-UART bridge mode). */
    fun isFlipperCli(text: String): Boolean =
        "Flipper Zero Command Line" in text || "\n>: " in text || text.startsWith(">: ")

    fun frequencyOf(ch: Int): Int = when {
        ch == 14 -> 2484
        ch in 1..13 -> 2407 + ch * 5
        ch in 32..177 -> 5000 + ch * 5
        else -> 0
    }

    fun parse(line: String): EspSighting? {
        val t = ANSI.replace(line, "").trimEnd('\r', ' ')
        BEACON.find(t)?.let { m ->
            val ch = m.groupValues[2].toInt()
            return EspSighting(
                mac = m.groupValues[3].uppercase(), rssi = m.groupValues[1].toInt(), ble = false,
                name = m.groupValues[4].trim().takeIf { it.isNotEmpty() && it.any { c -> c.code >= 0x20 } },
                frequencyMhz = frequencyOf(ch)
            )
        }
        PROBE.find(t)?.let { m ->
            return EspSighting(
                mac = m.groupValues[3].uppercase(), rssi = m.groupValues[1].toInt(), ble = false,
                frequencyMhz = frequencyOf(m.groupValues[2].toInt()), client = true,
                probedSsids = listOfNotNull(m.groupValues[4].trim().takeIf { com.rfsentinel.app.usb.MonitorFrames.validSsid(it) })
            )
        }
        return null
    }
}
