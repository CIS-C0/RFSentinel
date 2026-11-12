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

    /**
     * [uuid]: its UUID-E (32 hex digits). Many WPS stacks derive it once for the device, so it stays the same on
     * every random address the device uses: one exact device, not just a model.
     */
    data class Wps(val manufacturer: String?, val model: String?, val modelNumber: String?, val deviceName: String?,
                   val uuid: String? = null) {
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
        var uuid: String? = null
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
                0x1047 -> if (len == 16) uuid = hex(b, i + 4, 16).takeUnless { u -> u.all { it == '0' } || u.all { it == 'f' } }
            }
            i += 4 + len
        }
        if (manufacturer == null && model == null && number == null && name == null && uuid == null) return null
        return Wps(manufacturer, model, number, name, uuid)
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

    /**
     * FlipDeFlock's probe "skeleton" (its ie_skeleton_hash, ported as is so its published values
     * match): FNV-1a over each element's id and length, plus the first 5 bytes (OUI + type) of
     * vendor elements, from [from] to [end] (no checksum). Unsigned 32-bit; null with no elements.
     * Coarse: many phones share one, so it marks a candidate, never a device.
     */
    fun skeletonHash(b: ByteArray, from: Int, end: Int): Long? {
        var h = 0x811c9dc5L
        fun fold(v: Int) { h = ((h xor (v.toLong() and 0xff)) * 0x01000193L) and 0xffffffffL }
        var i = from; var any = false
        while (i + 2 <= end) {
            val tag = b[i].toInt() and 0xff; val len = b[i + 1].toInt() and 0xff
            if (i + 2 + len > end) break
            fold(tag); fold(len)
            if (tag == 0xDD) for (k in 0 until minOf(len, 5)) fold(b[i + 2 + k].toInt())
            any = true
            i += 2 + len
        }
        return if (any) h else null
    }

    /** How a watched probe shape is written in the watchlist (after "fp:"), next to 8-digit fingerprints. */
    fun shapeKey(shape: Long) = "shape-%08x".format(shape)

    /** How a watched WPS UUID (one exact device, whatever its address) is written in the watchlist, after "fp:". */
    fun uuidKey(uuid: String) = "uuid-$uuid"

    /**
     * What a probe request's elements say about the device in words, e.g. "WiFi 6 · 2x2 · Apple tags ·
     * Broadcom chipset · 802.11v · MBO": what the 8-digit fingerprint stands for. Null with too little to say.
     */
    fun traits(ies: List<Pair<Int, ByteArray>>): String? {
        if (ies.none { it.first != 0 && it.first != 1 && it.first != 3 && it.first != 50 }) return null
        val info = com.rfsentinel.app.detect.WifiFingerprint.parse(ies)
        val ext = ies.firstOrNull { it.first == 127 }?.second
        // Extended capabilities: bit 19 BSS transition (802.11v), bit 31 interworking (Passpoint).
        fun bit(n: Int) = ext != null && n / 8 < ext.size && (ext[n / 8].toInt() shr (n % 8)) and 1 == 1
        // Wi-Fi Alliance 50:6F:9A type 0x16: MBO / OCE (Agile Multiband).
        val mbo = ies.any { (id, d) -> id == 221 && d.size >= 4 && (d[0].toInt() and 0xff) == 0x50 && (d[1].toInt() and 0xff) == 0x6F &&
            (d[2].toInt() and 0xff) == 0x9A && d[3].toInt() == 0x16 }
        fun names(v: List<String>) = v.map { com.rfsentinel.app.detect.DeviceIntel.shortVendor(it) }.distinct().joinToString("/").ifEmpty { null }
        return listOfNotNull(
            MonitorFrames.standard(ies).substringBefore(" ("),
            info.streams?.let { "${it}x$it" },
            names(info.equipmentVendors)?.let { "$it tags" },
            names(info.chipsetVendors)?.let { "$it chipset" },
            "802.11v".takeIf { bit(19) },
            "Passpoint".takeIf { bit(31) },
            "MBO".takeIf { mbo }
        ).joinToString(" · ")
    }

    /** [end] without the 4-byte checksum some drivers keep on a frame starting at [d]. */
    fun withoutFcs(b: ByteArray, d: Int, end: Int): Int {
        if (end - d < 28) return end
        val crc = CRC32().apply { update(b, d, end - d - 4) }.value
        for (k in 0 until 4) if ((b[end - 4 + k].toLong() and 0xff) != ((crc shr (8 * k)) and 0xff)) return end
        return end - 4
    }

    private fun hex(b: ByteArray, at: Int, len: Int) = (at until at + len).joinToString("") { "%02x".format(b[it].toInt() and 0xff) }

    /** What external hardware learned about a device this scan, for its detail screen. */
    data class Record(val wps: Wps?, val fingerprint: String?, val probed: List<String>, val hidden: Boolean = false,
                      val skeleton: Long? = null, val traits: String? = null, val previous: String? = null)

    private val byMac = ConcurrentHashMap<String, Record>()

    fun note(mac: String, wps: Wps?, fingerprint: String?, probed: List<String>, hidden: Boolean = false, skeleton: Long? = null,
             traits: String? = null, previous: String? = null) {
        if (wps == null && fingerprint == null && probed.isEmpty() && !hidden && skeleton == null && traits == null && previous == null) return
        val key = mac.uppercase()
        val old = byMac[key]
        // A later WPS block without its UUID keeps the one already heard.
        val w = wps?.let { if (it.uuid == null && old?.wps?.uuid != null) it.copy(uuid = old.wps.uuid) else it }
        byMac[key] = Record(w ?: old?.wps, fingerprint ?: old?.fingerprint, probed.ifEmpty { old?.probed.orEmpty() },
            hidden || old?.hidden == true, skeleton ?: old?.skeleton, traits ?: old?.traits, previous ?: old?.previous)
        if (byMac.size > 5000) byMac.keys.take(500).forEach { byMac.remove(it) }
    }

    fun of(mac: String): Record? = byMac[mac.uppercase()]

    /** Addresses heard this session whose probe requests have the fingerprint [fp]. */
    fun sharing(fp: String): Int = byMac.values.count { it.fingerprint == fp }

    fun clear() = byMac.clear()
}
