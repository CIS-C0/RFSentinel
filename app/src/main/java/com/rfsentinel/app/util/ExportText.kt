package com.rfsentinel.app.util

/**
 * Safe text for exported files. Device names and WiFi SSIDs are chosen by
 * whoever owns the device, so exports must treat them as hostile input.
 */
object ExportText {

    /** Characters that make a spreadsheet treat a cell as a formula (CSV/formula injection). */
    private val FORMULA_START = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * A quoted CSV field (RFC 4180). A value that a spreadsheet would run as a
     * formula gets a leading apostrophe, so it is shown as text instead
     * (OWASP CSV-injection guidance).
     */
    fun csv(s: String?): String {
        if (s == null) return ""
        val safe = if (s.isNotEmpty() && s[0] in FORMULA_START) "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    /**
     * XML 1.0 text or attribute value: escapes markup characters and drops the
     * characters XML 1.0 forbids even when escaped (most control characters,
     * U+FFFE/U+FFFF and unpaired surrogates), which would make the file unparseable.
     */
    fun xml(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> Unit // unpaired: not allowed
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
