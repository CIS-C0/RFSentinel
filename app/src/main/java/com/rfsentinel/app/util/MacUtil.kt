package com.rfsentinel.app.util

/** MAC / OUI string helpers shared by the scanner, watchlist and whitelist. */
object MacUtil {
    private val FULL_MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val OUI = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){2}$")

    /** Uppercases and converts "-" separators to ":", e.g. "aa-bb-cc" -> "AA:BB:CC". */
    fun normalize(mac: String): String = mac.trim().uppercase().replace("-", ":")

    fun isValidMac(mac: String): Boolean = FULL_MAC.matches(normalize(mac))

    fun isValidOui(prefix: String): Boolean = OUI.matches(normalize(prefix))

    /**
     * An IEEE block prefix: MA-L "AA:BB:CC" (24 bits), MA-M "AA:BB:CC:D" (28 bits)
     * or MA-S "AA:BB:CC:DD:E" (36 bits). The small blocks share their first three
     * bytes with other companies, so they must be matched on all their bits.
     */
    fun isValidBlock(prefix: String): Boolean = BLOCK.matches(normalize(prefix))

    /** Hex digits only, e.g. "8C:1F:64:DF:0" -> "8C1F64DF0". */
    fun hex(macOrPrefix: String): String = normalize(macOrPrefix).replace(":", "")

    private val BLOCK = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){2}(:[0-9A-F]|:[0-9A-F]{2}:[0-9A-F])?$")

    /** First three octets of a MAC, e.g. "AA:BB:CC:DD:EE:FF" -> "AA:BB:CC". */
    fun oui(mac: String): String = normalize(mac).take(8)

    /**
     * True when the locally-administered bit is set, i.e. the address is
     * randomized/private (common for phones' BLE and WiFi). Its first three
     * octets are then not a real vendor OUI, and the address itself may rotate.
     */
    fun isRandomized(mac: String): Boolean {
        val first = normalize(mac).take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }
}
