package com.rfsentinel.app.detect

/**
 * Identifies the make / model of a WiFi access point - hidden or not - from
 * what it broadcasts in its beacons and probe responses (Android exposes these
 * information elements on API 30+):
 *
 *  - The WPS element (vendor IE 00:50:F2 type 4) often carries Manufacturer,
 *    Model Name, Model Number and Device Name (Wi-Fi Simple Configuration
 *    spec, attribute IDs 0x1021 / 0x1023 / 0x1024 / 0x1011 / 0x1054).
 *  - Cisco access points put their configured AP name in IE 133 (CCX).
 *  - Every vendor-specific IE starts with the IEEE OUI of the company that
 *    defined it: chipset makers (Broadcom, Qualcomm, MediaTek...) and
 *    equipment makers (Apple, Cisco, Aruba, Ubiquiti...).
 *
 * Also links a hidden network to a visible one broadcast by the same radio:
 * multi-SSID access points derive their extra BSSIDs from the base address.
 */
object WifiFingerprint {

    data class ApInfo(
        val manufacturer: String? = null,
        val modelName: String? = null,
        val modelNumber: String? = null,
        val deviceName: String? = null,
        val deviceKind: String? = null,
        val ciscoApName: String? = null,
        /** IEEE registrants of vendor-specific IE OUIs (chipset / equipment makers). */
        val ieVendors: List<String> = emptyList()
    ) {
        /** e.g. "NETGEAR R7000" or "TP-Link Archer AX55", null when WPS said nothing useful. */
        val model: String?
            get() {
                val parts = listOfNotNull(manufacturer?.let { DeviceIntel.shortVendor(it) }, modelName, modelNumber?.takeIf { n -> modelName?.contains(n, true) != true })
                    .distinctBy { it.lowercase() }
                    // Many vendors repeat the manufacturer in the model name.
                    .let { p -> if (p.size >= 2 && p[1].startsWith(p[0], ignoreCase = true)) p.drop(1) else p }
                return parts.joinToString(" ").takeIf { it.isNotBlank() }
            }

        /** Equipment (not chipset) makers seen in vendor IEs. */
        val equipmentVendors: List<String> get() = ieVendors.filterNot { CHIPSET.containsMatchIn(it) }
        val chipsetVendors: List<String> get() = ieVendors.filter { CHIPSET.containsMatchIn(it) }
    }

    private val WPS_OUI = byteArrayOf(0x00, 0x50, 0xF2.toByte())
    /** Generic-standard OUIs that say nothing about the maker. */
    private val GENERIC_OUIS = setOf("0050F2", "506F9A", "000FAC") // Microsoft (WPA/WMM/WPS), Wi-Fi Alliance, IEEE 802.11
    private val CHIPSET = Regex("broadcom|epigram|qualcomm|atheros|mediatek|ralink|realtek|quantenna|marvell|intel corp|celeno|airoha|espressif|cypress|infineon", RegexOption.IGNORE_CASE)

    private val WPS_CATEGORY = mapOf(
        1 to "Computer", 2 to "Input device", 3 to "Printer / scanner", 4 to "Camera", 5 to "Storage",
        6 to "Network infrastructure", 7 to "Display", 8 to "Multimedia device", 9 to "Gaming device",
        10 to "Phone", 11 to "Audio device", 12 to "Docking device"
    )
    private val WPS_NETWORK_SUB = mapOf(1 to "Access point", 2 to "Router", 3 to "Switch", 4 to "Gateway", 5 to "Bridge")

    fun parse(ies: List<Pair<Int, ByteArray>>): ApInfo {
        var info = ApInfo()
        // WPS data may be split over several consecutive vendor IEs: concatenate their bodies.
        val wps = ies.filter { (id, d) -> id == 221 && isWps(d) }
            .fold(ByteArray(0)) { acc, (_, d) -> acc + d.copyOfRange(4, d.size) }
        if (wps.isNotEmpty()) info = parseWps(wps, info)

        ies.firstOrNull { it.first == 133 && it.second.size >= 26 }?.let { (_, d) ->
            ascii(d.copyOfRange(10, 26))?.let { info = info.copy(ciscoApName = it) }
        }

        val owners = ies.filter { it.first == 221 && it.second.size >= 3 }
            .map { Bytes.hex(it.second.copyOfRange(0, 3), "").uppercase() }
            .filter { it !in GENERIC_OUIS }
            .distinct()
            .mapNotNull { oui -> VendorDb.macVendor(oui.chunked(2).joinToString(":") + ":00:00:00") }
            .distinct()
        return info.copy(ieVendors = owners)
    }

    private fun isWps(d: ByteArray) = d.size >= 4 && d[0] == WPS_OUI[0] && d[1] == WPS_OUI[1] && d[2] == WPS_OUI[2] && d[3] == 0x04.toByte()

    private fun parseWps(d: ByteArray, start: ApInfo): ApInfo {
        var info = start
        var i = 0
        while (i + 4 <= d.size) {
            val type = (Bytes.u8(d, i) shl 8) or Bytes.u8(d, i + 1)
            val len = (Bytes.u8(d, i + 2) shl 8) or Bytes.u8(d, i + 3)
            if (i + 4 + len > d.size) break
            val v = d.copyOfRange(i + 4, i + 4 + len)
            when (type) {
                0x1021 -> info = info.copy(manufacturer = ascii(v))
                0x1023 -> info = info.copy(modelName = ascii(v))
                0x1024 -> info = info.copy(modelNumber = ascii(v))
                0x1011 -> info = info.copy(deviceName = ascii(v))
                0x1054 -> if (len >= 8) {
                    val cat = (Bytes.u8(v, 0) shl 8) or Bytes.u8(v, 1)
                    val sub = (Bytes.u8(v, 6) shl 8) or Bytes.u8(v, 7)
                    val kind = if (cat == 6) WPS_NETWORK_SUB[sub] ?: "Network infrastructure" else WPS_CATEGORY[cat]
                    info = info.copy(deviceKind = kind)
                }
            }
            i += 4 + len
        }
        return info
    }

    /** Printable text with NUL padding and junk removed; null if nothing meaningful remains. */
    private fun ascii(b: ByteArray): String? {
        val s = String(b, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        if (s.isEmpty() || s.any { it.code < 0x20 }) return null
        // Placeholder values some firmwares ship with.
        if (s.matches(Regex("(?i)(0+|1234.*|none|n/?a|unknown|default|wireless router|router|ap|x+)"))) return null
        return s
    }

    /**
     * True when two BSSIDs are very likely radios / virtual interfaces of the
     * same access point: octets 2-5 equal and the last octet within 16, with the
     * first octet equal or one of them locally administered (virtual BSSID).
     */
    fun sameAccessPoint(a: String, b: String): Boolean {
        val x = a.split(':').mapNotNull { it.toIntOrNull(16) }
        val y = b.split(':').mapNotNull { it.toIntOrNull(16) }
        if (x.size != 6 || y.size != 6 || a.equals(b, ignoreCase = true)) return false
        if ((1..4).any { x[it] != y[it] }) return false
        if (kotlin.math.abs(x[5] - y[5]) > 16) return false
        return x[0] == y[0] || (x[0] and 0x02) != 0 || (y[0] and 0x02) != 0
    }
}
