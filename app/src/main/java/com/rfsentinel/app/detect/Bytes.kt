package com.rfsentinel.app.detect

/** Small byte helpers shared by the parsers. */
object Bytes {
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun hex(bytes: ByteArray?, sep: String = " "): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + sep.length))
        bytes.forEachIndexed { i, b ->
            if (i > 0) sb.append(sep)
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    fun u16le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    fun i32le(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    /** True when [haystack] contains the ASCII [needle] in either byte order. */
    fun containsAscii(haystack: ByteArray?, needle: String): Boolean {
        if (haystack == null || haystack.size < needle.length) return false
        val fwd = needle.toByteArray(Charsets.US_ASCII)
        val rev = fwd.reversedArray()
        return indexOf(haystack, fwd) >= 0 || indexOf(haystack, rev) >= 0
    }

    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Printable ASCII, trimmed of NUL padding; null if there's nothing printable. */
    fun ascii(b: ByteArray, from: Int, len: Int): String? {
        if (from >= b.size) return null
        val end = minOf(b.size, from + len)
        val s = String(b.copyOfRange(from, end), Charsets.US_ASCII)
            .trimEnd('\u0000').filter { it.code in 0x20..0x7E }.trim()
        return s.ifEmpty { null }
    }
}

/** One AD structure of a BLE advertisement (Core Spec Vol 3, Part C, 11). */
data class AdStructure(val type: Int, val data: ByteArray) {
    val typeName: String get() = AD_TYPE_NAMES[type] ?: String.format("Type 0x%02X", type)

    companion object {
        private val AD_TYPE_NAMES = mapOf(
            0x01 to "Flags", 0x02 to "16-bit UUIDs (partial)", 0x03 to "16-bit UUIDs",
            0x04 to "32-bit UUIDs (partial)", 0x05 to "32-bit UUIDs", 0x06 to "128-bit UUIDs (partial)",
            0x07 to "128-bit UUIDs", 0x08 to "Short name", 0x09 to "Complete name",
            0x0A to "TX power", 0x0D to "Class of device", 0x10 to "Device ID",
            0x12 to "Connection interval", 0x14 to "16-bit solicitation UUIDs",
            0x15 to "128-bit solicitation UUIDs", 0x16 to "Service data (16-bit)",
            0x17 to "Public target address", 0x18 to "Random target address", 0x19 to "Appearance",
            0x1A to "Advertising interval", 0x1B to "LE device address", 0x1C to "LE role",
            0x20 to "Service data (32-bit)", 0x21 to "Service data (128-bit)", 0x24 to "URI",
            0x2A to "Mesh message", 0x2B to "Mesh beacon", 0x2C to "BIG info",
            0x2E to "Resolvable set identifier", 0x30 to "Broadcast name",
            0xFF to "Manufacturer data"
        )

        /** Splits raw advertisement bytes into AD structures; stops at padding or corruption. */
        fun parse(raw: ByteArray?): List<AdStructure> {
            if (raw == null) return emptyList()
            val out = mutableListOf<AdStructure>()
            var i = 0
            while (i < raw.size) {
                val len = raw[i].toInt() and 0xFF
                // A structure is [len][type][len-1 data bytes]; its last byte is raw[i + len].
                if (len == 0 || i + len >= raw.size) break
                val type = raw[i + 1].toInt() and 0xFF
                out += AdStructure(type, raw.copyOfRange(i + 2, i + 1 + len))
                i += 1 + len
            }
            return out
        }
    }

    override fun equals(other: Any?) = other is AdStructure && type == other.type && data.contentEquals(other.data)
    override fun hashCode() = 31 * type + data.contentHashCode()
}
