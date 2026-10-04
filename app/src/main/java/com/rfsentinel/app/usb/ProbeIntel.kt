package com.rfsentinel.app.usb

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * What a WiFi frame says about the device beyond its address, heard in monitor mode:
 *  - the WPS block many laptops, printers, routers and IoT devices include in their
 *    probe requests and beacons: maker, model and device name (e.g. "Panasonic
 *    Toughbook CF-33", a computer name like "PD-UNIT12", a router model);
 *  - a fingerprint of how a device builds its probe requests (which elements, in what
 *    order, its capability bits and vendor extensions). It stays the same when the
 *    address is randomized, so it marks a device model / OS, and can be watchlisted.
 */
object ProbeIntel {

    data class Wps(val manufacturer: String?, val model: String?, val modelNumber: String?, val deviceName: String?) {
        /** "Maker Model Number", or null when the block named nothing. */
        val product: String? get() = listOfNotNull(manufacturer, model, modelNumber).distinct().joinToString(" ").ifBlank { null }
        /** The best single name for the device list. */
        val label: String? get() = deviceName ?: product
        /** Everything it named, for watchlist name rules. */
        val text: String get() = listOfNotNull(deviceName, manufacturer, model, modelNumber).joinToString(" ")
    }

    /** Parses a WPS vendor element's body (after the 00:50:F2:04 header): big-endian type / length attributes. */
    fun parseWps(b: ByteArray, from: Int, end: Int): Wps? {
        var manufacturer: String? = null; var model: String? = null; var number: String? = null; var name: String? = null
        var i = from
        while (i + 4 <= end) {
            val type = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            if (i + 4 + len > end) break
            val v = { text(b, i + 4, len) }
            when (type) {
                0x1021 -> manufacturer = v()
                0x1023 -> model = v()
                0x1024 -> number = v()
                0x1011 -> name = v()
            }
            i += 4 + len
        }
        if (manufacturer == null && model == null && number == null && name == null) return null
        return Wps(manufacturer, model, number, name)
    }

    private fun text(b: ByteArray, at: Int, len: Int): String? {
        val s = String(b, at, len, Charsets.UTF_8).trim { it <= ' ' }
        // Placeholders some firmware fills in carry no information.
        return s.takeIf { it.isNotEmpty() && it.none { c -> c < ' ' || c == '�' } && it !in PLACEHOLDERS }
    }

    private val PLACEHOLDERS = setOf("0", "1", "123456", "Unknown", "unknown", "N/A", "NA", " ")

    /**
     * Fingerprint of a probe request's information elements from [from] to [end]: element
     * order plus the fields that describe the hardware and driver, not the moment (no
     * network name, channel, rates or WPS strings). 8 hex digits.
     */
    fun fingerprint(b: ByteArray, from: Int, end: Int): String? {
        val sb = StringBuilder()
        var i = from; var n = 0
        while (i + 2 <= end) {
            val tag = b[i].toInt() and 0xff; val len = b[i + 1].toInt() and 0xff
            if (i + 2 + len > end) break
            val d = i + 2
            when (tag) {
                0, 1, 3, 50 -> {} // name, rates, channel: change with what / where it asks
                45 -> sb.append("ht:").append(hex(b, d, minOf(len, 3)))            // HT capability info + A-MPDU
                127 -> sb.append("ext:").append(hex(b, d, len))                    // extended capabilities
                191 -> sb.append("vht:").append(hex(b, d, minOf(len, 4)))          // VHT capability info
                221 -> sb.append("v:").append(hex(b, d, minOf(len, 4)))            // vendor element: OUI + type
                255 -> sb.append("x:").append(if (len > 0) hex(b, d, 1) else "")  // extension element id
                else -> sb.append(tag)
            }
            sb.append(','); n++
            i = d + len
        }
        if (n < 2) return null // a bare wildcard request says nothing about the hardware
        val crc = CRC32().apply { update(sb.toString().toByteArray()) }
        return "%08x".format(crc.value)
    }

    private fun hex(b: ByteArray, at: Int, len: Int) = (at until at + len).joinToString("") { "%02x".format(b[it].toInt() and 0xff) }

    /** What external hardware learned about a device this scan, for its detail screen. */
    data class Record(val wps: Wps?, val fingerprint: String?, val probed: List<String>, val hidden: Boolean = false)

    private val byMac = ConcurrentHashMap<String, Record>()

    fun note(mac: String, wps: Wps?, fingerprint: String?, probed: List<String>, hidden: Boolean = false) {
        if (wps == null && fingerprint == null && probed.isEmpty() && !hidden) return
        val key = mac.uppercase()
        val old = byMac[key]
        byMac[key] = Record(wps ?: old?.wps, fingerprint ?: old?.fingerprint, probed.ifEmpty { old?.probed.orEmpty() },
            hidden || old?.hidden == true)
        if (byMac.size > 5000) byMac.keys.take(500).forEach { byMac.remove(it) }
    }

    fun of(mac: String): Record? = byMac[mac.uppercase()]

    fun clear() = byMac.clear()
}
