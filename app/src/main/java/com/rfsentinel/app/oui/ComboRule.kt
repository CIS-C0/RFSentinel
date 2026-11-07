package com.rfsentinel.app.oui

/**
 * A watchlist rule that matches only when every one of its conditions holds, e.g. a maker with no network name
 * on 5 GHz. Kept as an [OuiEntry] key, "all:" + the conditions joined by " & ":
 *  - "vendor~text"   the manufacturer contains text
 *  - "name^text"     the name / SSID starts with text
 *  - "name~text"     the name / SSID contains text
 *  - "noname"        no name / hidden SSID
 *  - "band=2.4|5|6|bt"
 *  - "probe=key"     its probe fingerprint or probe shape ("shape-...") is this
 *  - "type~text"     the device type contains text ("Laptop", "WiFi access point")
 *  - "rssi>=-70"     heard at least this strong (dBm)
 */
data class ComboRule(val conditions: List<Cond>) {

    sealed class Cond {
        data class Vendor(val text: String) : Cond()
        data class NameStarts(val text: String) : Cond()
        data class NameHas(val text: String) : Cond()
        object NoName : Cond()
        data class Band(val band: String) : Cond()
        data class Probe(val key: String) : Cond()
        data class Type(val text: String) : Cond()
        data class Rssi(val min: Int) : Cond()
    }

    /** What is known about one device when it's classified. */
    data class Facts(
        val name: String?,
        val vendors: List<String>,
        /** "2.4", "5", "6" or "bt". */
        val band: String?,
        /** Its probe fingerprint and probe shape key, when a USB adapter / ESP32 heard its probes. */
        val probeKeys: Set<String>,
        val type: String?,
        val rssi: Int
    )

    fun matches(f: Facts): Boolean = conditions.isNotEmpty() && conditions.all { c ->
        when (c) {
            is Cond.Vendor -> f.vendors.any { it.contains(c.text, ignoreCase = true) }
            is Cond.NameStarts -> f.name?.trimStart()?.startsWith(c.text, ignoreCase = true) == true
            is Cond.NameHas -> f.name?.contains(c.text, ignoreCase = true) == true
            Cond.NoName -> f.name.isNullOrBlank()
            is Cond.Band -> f.band == c.band
            is Cond.Probe -> c.key in f.probeKeys
            is Cond.Type -> f.type?.contains(c.text, ignoreCase = true) == true
            is Cond.Rssi -> f.rssi >= c.min
        }
    }

    val key: String get() = PREFIX + conditions.joinToString(SEP) { c ->
        when (c) {
            is Cond.Vendor -> "vendor~${c.text}"
            is Cond.NameStarts -> "name^${c.text}"
            is Cond.NameHas -> "name~${c.text}"
            Cond.NoName -> "noname"
            is Cond.Band -> "band=${c.band}"
            is Cond.Probe -> "probe=${c.key}"
            is Cond.Type -> "type~${c.text}"
            is Cond.Rssi -> "rssi>=${c.min}"
        }
    }

    /** In words, for the watchlist and the match's evidence: "Maker contains "Panasonic" · no name · 5 GHz". */
    fun describe(): String = conditions.joinToString(" · ") { c ->
        when (c) {
            is Cond.Vendor -> "maker contains \"${c.text}\""
            is Cond.NameStarts -> "name starts with \"${c.text}\""
            is Cond.NameHas -> "name contains \"${c.text}\""
            Cond.NoName -> "no name"
            is Cond.Band -> BANDS[c.band] ?: c.band
            is Cond.Probe -> if (c.key.startsWith("shape-")) "probe shape ${c.key.removePrefix("shape-")}" else "probe fingerprint ${c.key}"
            is Cond.Type -> "type contains \"${c.text}\""
            is Cond.Rssi -> "signal ${c.min} dBm or stronger"
        }
    }.replaceFirstChar { it.uppercase() }

    companion object {
        const val PREFIX = "all:"
        const val SEP = " & "
        /** Only an ampersand before a condition splits: "vendor~AT&T" is one condition. */
        private val NEXT_COND = Regex("""&(?=\s*(?:noname|vendor~|name\^|name~|band=|probe=|type~|rssi>=))""", RegexOption.IGNORE_CASE)
        val BANDS = linkedMapOf("2.4" to "2.4 GHz", "5" to "5 GHz", "6" to "6 GHz", "bt" to "Bluetooth")

        /** The band of a WiFi channel ([mhz]) or Bluetooth. */
        fun band(bluetooth: Boolean, mhz: Int?): String? = when {
            bluetooth -> "bt"
            mhz == null || mhz <= 0 -> null
            mhz < 3000 -> "2.4"
            mhz < 5925 -> "5"
            else -> "6"
        }

        /** The rule in [key], or null when it isn't one or a condition can't be read. */
        fun parse(key: String): ComboRule? {
            if (!key.startsWith(PREFIX, ignoreCase = true)) return null
            val parts = key.substring(PREFIX.length).split(NEXT_COND).map { it.trim() }.filter { it.isNotEmpty() }
            val conds = parts.map { p ->
                fun after(op: String) = p.substringAfter(op).trim().takeIf { it.isNotEmpty() }
                when {
                    p.equals("noname", true) -> Cond.NoName
                    p.startsWith("vendor~", true) -> after("~")?.let { Cond.Vendor(it) }
                    p.startsWith("name^", true) -> after("^")?.let { Cond.NameStarts(it) }
                    p.startsWith("name~", true) -> after("~")?.let { Cond.NameHas(it) }
                    p.startsWith("band=", true) -> after("=")?.lowercase()?.takeIf { it in BANDS }?.let { Cond.Band(it) }
                    p.startsWith("probe=", true) -> after("=")?.lowercase()?.let { Cond.Probe(it) }
                    p.startsWith("type~", true) -> after("~")?.let { Cond.Type(it) }
                    p.startsWith("rssi>=", true) -> after(">=")?.toIntOrNull()?.takeIf { it in -120..0 }?.let { Cond.Rssi(it) }
                    else -> null
                } ?: return null
            }
            return if (conds.isEmpty()) null else ComboRule(conds)
        }
    }
}
