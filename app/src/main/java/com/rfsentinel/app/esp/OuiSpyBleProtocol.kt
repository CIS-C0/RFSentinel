package com.rfsentinel.app.esp

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The Bluetooth protocol of OUI-SPY "App-Controlled" firmware
 * (lukeswitz/oui-spy-unified-blue, src/protocol.h + ble_gatt.cpp), as far as
 * RF Sentinel uses it: switching detection engines on/off and decoding the
 * detection notifications. Pure Kotlin; unit-tested with packets laid out like
 * the firmware's packDetection().
 */
object OuiSpyBleProtocol {

    private const val BASE = "-0a15-4b70-ba00-c010ae1ba01c"
    val SERVICE: UUID = UUID.fromString("00000001$BASE")
    val DEVICE_INFO: UUID = UUID.fromString("00000001$BASE")
    val ENGINE_CONTROL: UUID = UUID.fromString("00000002$BASE")
    val DETECTION_EVENTS: UUID = UUID.fromString("00000010$BASE")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Name prefix the boards advertise ("OUI-SPY-xxxx", "OUI-SPY-MGR-...") */
    const val NAME_PREFIX = "OUI-SPY"

    // Engine IDs (protocol.h EngineId).
    const val ENGINE_DETECTOR = 0
    const val ENGINE_FLOCK_BLE = 1
    const val ENGINE_FLOCK_WIFI = 2
    const val ENGINE_SKYSPY = 4
    /** Survey mode: reports every Wi-Fi network and Bluetooth device the board hears. */
    const val ENGINE_WARDRIVE = 6

    /** Detection engines; RF Sentinel never touches the others (UniPwn, PCAP, Foxhunter). */
    val ENGINES = listOf(ENGINE_FLOCK_BLE, ENGINE_FLOCK_WIFI, ENGINE_SKYSPY, ENGINE_DETECTOR)

    /** Engines to switch on; [relayAll] adds the survey engine so RF Sentinel's own rules see everything. */
    fun engines(relayAll: Boolean) = if (relayAll) ENGINES + ENGINE_WARDRIVE else ENGINES

    fun enable(engine: Int): ByteArray = byteArrayOf(0x01, engine.toByte())
    fun disable(engine: Int): ByteArray = byteArrayOf(0x00, engine.toByte())
    /** DISABLE_ALL (0x0F): the board goes quiet when RF Sentinel stops scanning. */
    fun disableAll(): ByteArray = byteArrayOf(0x0F, 0x00)

    const val SOURCE = "OUI-SPY board over Bluetooth"

    /** Offline-spool frames: a 9-byte header starting 0xFF and a 1-byte 0xFE end marker. */
    fun isSpoolHeader(d: ByteArray) = d.size == 9 && d[0] == 0xFF.toByte()
    fun isSpoolEnd(d: ByteArray) = d.size == 1 && d[0] == 0xFE.toByte()

    /** Decodes one Detection Events notification, or null for frames RF Sentinel doesn't use. */
    fun decode(d: ByteArray): EspSighting? {
        if (d.size < 19) return null
        val raw = d[0].toInt() and 0xFF
        if (raw and 0x80 != 0) return null // detected while no phone was connected (old)
        val engine = raw and 0x7F
        val mac = (1..6).joinToString(":") { "%02X".format(d[it].toInt() and 0xFF) }
        val rssi = d[7].toInt()
        val channel = d[8].toInt() and 0xFF
        val method = d[13].toInt() and 0xFF
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return when (engine) {
            ENGINE_FLOCK_BLE, ENGINE_FLOCK_WIFI -> flock(engine, d, mac, rssi, channel, method)
            ENGINE_SKYSPY -> if (d.size >= 155) drone(d, b, mac, rssi, method) else null
            ENGINE_DETECTOR -> detector(d, mac, rssi, channel, method)
            ENGINE_WARDRIVE -> survey(d, mac, rssi, channel, method)
            else -> null
        }
    }

    // auth_mode: 0 open, 1 WEP, 2 WPA, 3 WPA2, 4 WPA/WPA2, 5 WPA2-Enterprise, 6 WPA3.
    private val AUTH = listOf("[ESS]", "[WEP][ESS]", "[WPA-PSK][ESS]", "[WPA2-PSK][ESS]",
        "[WPA-PSK][WPA2-PSK][ESS]", "[WPA2-EAP][ESS]", "[RSN-SAE][ESS]")

    /** A plain sighting with no verdict: RF Sentinel's own watchlist and rules judge it. */
    private fun survey(d: ByteArray, mac: String, rssi: Int, channel: Int, method: Int): EspSighting? {
        if (d.size < 74) return null
        return when (method) {
            0 -> EspSighting(mac = mac, rssi = rssi, ble = false, name = text(d, 19, 33),
                frequencyMhz = freq(channel), capabilities = AUTH.getOrElse(d[52].toInt() and 0xFF) { "" })
            1 -> EspSighting(mac = mac, rssi = rssi, ble = true, name = text(d, 53, 21))
            else -> null
        }
    }

    private fun text(d: ByteArray, from: Int, len: Int): String? {
        if (d.size < from + len) return null
        val end = (from until from + len).firstOrNull { d[it] == 0.toByte() } ?: (from + len)
        return String(d, from, end - from, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun freq(channel: Int) = when (channel) {
        0 -> 0
        14 -> 2484
        in 1..13 -> 2407 + channel * 5
        else -> 5000 + channel * 5
    }

    private fun flock(engine: Int, d: ByteArray, mac: String, rssi: Int, channel: Int, method: Int): EspSighting {
        val wifi = engine == ENGINE_FLOCK_WIFI
        val isRaven = d.size > 19 && d[19] != 0.toByte()
        val name = text(d, 37, 32)
        val hit = when {
            isRaven -> Hit(Category.AUDIO_SENSOR, "Raven gunshot sensor (Flock)", 85,
                "Reported by OUI-SPY: Raven service" + (text(d, 20, 16)?.let { ", firmware $it" } ?: ""), SOURCE)
            wifi && method >= 4 -> Hit(Category.ALPR, "Flock camera (wildcard probe)", 80,
                "Reported by OUI-SPY: Flock-style network probe", SOURCE)
            wifi && method == 3 -> Hit(Category.ALPR, "Flock camera (Wi-Fi name)", 80,
                "Reported by OUI-SPY: Flock network name" + (name?.let { " \"$it\"" } ?: ""), SOURCE)
            wifi -> Hit(Category.ALPR, "Possible Flock equipment", 45,
                "Reported by OUI-SPY: Flock radio-module address (common chip, weak)", SOURCE)
            method == 1 -> Hit(Category.ALPR, "Flock equipment (Bluetooth name)", 80,
                "Reported by OUI-SPY: Flock / Penguin device name" + (name?.let { " \"$it\"" } ?: ""), SOURCE)
            else -> Hit(Category.ALPR, "Flock equipment (Bluetooth)", 70,
                "Reported by OUI-SPY Flock-BLE (method $method)", SOURCE)
        }
        return EspSighting(
            mac = mac, rssi = rssi, ble = !wifi, name = if (wifi) null else name,
            frequencyMhz = if (wifi) freq(channel) else 0, hits = listOf(hit)
        )
    }

    private fun drone(d: ByteArray, b: ByteBuffer, mac: String, rssi: Int, method: Int): EspSighting {
        fun coord(at: Int) = b.getDouble(at).takeIf { it != 0.0 && it.isFinite() }
        val info = RemoteId.Info(
            uasId = text(d, 19, 21),
            latitude = coord(61), longitude = coord(69),
            altitudeGeoM = b.getShort(77).toDouble(),
            heightM = b.getShort(79).toDouble(),
            operatorLatitude = coord(85), operatorLongitude = coord(93),
            selfIdDescription = text(d, 101, 24)
        )
        val via = when (method) { 0 -> "Bluetooth"; 1 -> "Wi-Fi NAN"; else -> "Wi-Fi beacon" }
        return EspSighting(
            mac = mac, rssi = rssi, ble = method == 0, remoteId = info,
            hits = listOf(Hit(Category.DRONE, "Drone broadcasting Remote ID", 95,
                "Reported by OUI-SPY Sky Spy ($via)" + (info.uasId?.let { ", ID $it" } ?: ""), SOURCE))
        )
    }

    private fun detector(d: ByteArray, mac: String, rssi: Int, channel: Int, method: Int): EspSighting {
        val desc = text(d, 20, 32)
        val hit = when (method) {
            2 -> Hit(Category.TRACKER, "Tracker following you (OUI-SPY)", 75,
                "Reported by OUI-SPY Detector" + (desc?.let { ": $it" } ?: ""), SOURCE)
            7 -> Hit(Category.GLASSES, "Smart glasses", 70, "Reported by OUI-SPY Detector (glasses signature)", SOURCE)
            8 -> Hit(Category.BODY_CAM, "Axon equipment", 75, "Reported by OUI-SPY Detector (Axon signature)", SOURCE)
            0, 1 -> Hit(Category.CUSTOM, "OUI-SPY target" + (desc?.let { ": $it" } ?: ""), 70,
                "Matched the watchlist set on the OUI-SPY board", SOURCE)
            else -> null // Flipper, Pwnagotchi, deauth / probe floods: outside RF Sentinel's scope
        }
        val wifi = method == 1 || method == 4 || method == 5
        return EspSighting(mac = mac, rssi = rssi, ble = !wifi,
            frequencyMhz = if (wifi) freq(channel) else 0, hits = listOfNotNull(hit))
    }
}
