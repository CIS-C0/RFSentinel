package com.rfsentinel.app.detect

import java.util.UUID

/**
 * One radio observation, normalized from either a BLE advertisement or a WiFi
 * scan result. Pure Kotlin (no Android types) so the detection code can be
 * unit-tested on the JVM.
 */
class Advert(
    /** Normalized "AA:BB:CC:DD:EE:FF". */
    val mac: String,
    val source: Source,
    val rssi: Int,
    /** BLE local name, or WiFi SSID. */
    val name: String? = null,
    /** BLE manufacturer-specific data (AD 0xFF), keyed by SIG company ID. */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** Advertised service UUIDs (AD 0x02-0x07). Solicitation UUIDs are deliberately excluded. */
    val serviceUuids: List<UUID> = emptyList(),
    /** Service data (AD 0x16 / 0x20 / 0x21), keyed by service UUID. */
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    /** Raw advertisement bytes (BLE) - parsed into AD structures for the detail screen. */
    val rawBytes: ByteArray? = null,
    /** Advertised TX power (AD 0x0A) in dBm, if present. */
    val txPower: Int? = null,
    val addressType: AddressType = AddressType.UNKNOWN,
    val connectable: Boolean? = null,
    /** BLE PHY description (e.g. "LE 1M", "LE Coded") or null. */
    val phy: String? = null,
    /** WiFi only. */
    val wifi: WifiInfo? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Source { BLE, WIFI }

    class WifiInfo(
        val frequencyMhz: Int,
        val capabilities: String,
        val standard: String?,
        /** Beacon information elements (id, payload), API 30+ only. */
        val infoElements: List<Pair<Int, ByteArray>>,
        /** A client device (laptop, phone, camera) heard by a monitor-mode adapter, not an access point. */
        val client: Boolean = false
    )

    val isBle get() = source == Source.BLE
    val isWifi get() = source == Source.WIFI

    companion object {
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        /** Expands a SIG 16-bit UUID onto the Bluetooth base UUID. */
        fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("%08x", short and 0xFFFF) + BASE_UUID_SUFFIX)

        /** The 16-bit short of a base-UUID-derived UUID, or null for a vendor 128-bit UUID. */
        fun shortOf(uuid: UUID): Int? {
            val s = uuid.toString()
            if (!s.endsWith(BASE_UUID_SUFFIX) || !s.startsWith("0000")) return null
            return s.substring(4, 8).toInt(16)
        }
    }
}

/**
 * How trackable a device's address is. For BLE the controller reports public vs
 * random (API 35+); the random sub-type comes from the top two bits of the
 * most significant byte (Bluetooth Core Spec Vol 6, Part B, 1.3).
 */
enum class AddressType(val label: String, val trackable: String) {
    PUBLIC("Public (IEEE-assigned)", "Trackable - this address never changes"),
    RANDOM_STATIC("Random static", "Usually stable until reboot or reset - partly trackable"),
    RESOLVABLE_PRIVATE("Resolvable private (RPA)", "Rotates every few minutes - hard to track"),
    NON_RESOLVABLE("Non-resolvable private", "Rotates - not trackable"),
    WIFI_GLOBAL("WiFi, globally unique", "Fixed hardware address - trackable"),
    WIFI_LOCAL("WiFi, locally administered", "Randomized / private address"),
    UNKNOWN("Unknown", "Android didn't report the address type (needs Android 15+)");

    companion object {
        /** Sub-type of a BLE random address from its most significant byte. */
        fun ofRandom(mac: String): AddressType {
            val msb = mac.take(2).toIntOrNull(16) ?: return UNKNOWN
            return when (msb shr 6) {
                0b11 -> RANDOM_STATIC
                0b01 -> RESOLVABLE_PRIVATE
                0b00 -> NON_RESOLVABLE
                else -> UNKNOWN // 0b10 is reserved
            }
        }

        fun ofWifi(mac: String): AddressType {
            val msb = mac.take(2).toIntOrNull(16) ?: return UNKNOWN
            return if (msb and 0x02 != 0) WIFI_LOCAL else WIFI_GLOBAL
        }
    }
}
