package com.rfsentinel.app.oui

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

data class OuiEntry(
    /**
     * What to match:
     *  - "AA:BB:CC"            vendor prefix (IEEE MA-L block)
     *  - "AA:BB:CC:DD:EE:FF"   one exact device
     *  - "name:<text>"         advertised name / SSID contains <text> (case-insensitive)
     *  - "vendor:<text>"       manufacturer (IEEE registrant or Bluetooth company) contains <text>
     */
    val prefix: String,
    val label: String,
    val source: String = "",
    // "confirmed" = documented vendor registration + known equipment use,
    // "candidate" = plausible but not independently verified,
    // "custom"    = user-added entry
    val confidence: String = "confirmed",
    /** 0-100; defaults from [confidence] when absent. */
    val score: Int? = null,
    /** A [Category] name; defaults to CUSTOM for user entries. */
    val category: String? = null
) {
    val isCustom get() = confidence == "custom"
    val isNameRule get() = prefix.startsWith(NAME, ignoreCase = true)
    val isVendorRule get() = prefix.startsWith(VENDOR, ignoreCase = true)
    val ruleText get() = prefix.substringAfter(':').trim()

    val effectiveScore: Int
        get() = score ?: when (confidence) {
            "custom" -> 100
            "candidate" -> 50
            else -> 75
        }

    val effectiveCategory: Category
        get() = Category.parse(category) ?: if (isCustom) Category.CUSTOM else Category.PUBLIC_SAFETY

    fun toHit(evidence: String) = Hit(
        category = effectiveCategory,
        label = label,
        confidence = effectiveScore,
        evidence = evidence,
        source = if (isCustom) "Your watchlist" else source.ifEmpty { "Watchlist preset" }
    )

    companion object {
        const val NAME = "name:"
        const val VENDOR = "vendor:"
    }
}
