package com.rfsentinel.app.oui

import com.rfsentinel.app.data.AlertKind
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

data class OuiEntry(
    /**
     * What to match:
     *  - "AA:BB:CC"            vendor prefix (IEEE MA-L block)
     *  - "AA:BB:CC:DD:EE:FF"   one exact device
     *  - "name:<text>"         advertised name / SSID contains <text> (case-insensitive)
     *  - "starts:<text>"       advertised name / SSID starts with <text> (case-insensitive), e.g. every
     *                          network named "Flock-..."
     *  - "vendor:<text>"       manufacturer (IEEE registrant or Bluetooth company) contains <text>
     *  - "probe:<ssid>"        a device asks for this exact WiFi network name (probe request,
     *                          heard by a USB WiFi adapter or an ESP32 Marauder board)
     *  - "fp:<8 hex>"          a device whose probe requests have this fingerprint (same model / OS)
     *  - "adv:<8 hex>"         a Bluetooth device whose adverts have this pattern ([com.rfsentinel.app.detect.AdvertFingerprint.key]),
     *                          whatever its address
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
    val category: String? = null,
    /** Your entries: the ways it doesn't alert you ([AlertKind] names, comma-separated); null = every way. */
    val alertsOff: String? = null
) {
    val alertsOffSet: Set<AlertKind> get() = AlertKind.parse(alertsOff)

    val isCustom get() = confidence == "custom"
    val isNameRule get() = prefix.startsWith(NAME, ignoreCase = true)
    val isStartsRule get() = prefix.startsWith(STARTS, ignoreCase = true)
    val isVendorRule get() = prefix.startsWith(VENDOR, ignoreCase = true)
    val isProbeRule get() = prefix.startsWith(PROBE, ignoreCase = true)
    val isFingerprintRule get() = prefix.startsWith(FINGERPRINT, ignoreCase = true)
    val isAdvertRule get() = prefix.startsWith(ADVERT, ignoreCase = true)
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
        source = if (isCustom) YOURS else source.ifEmpty { "Watchlist preset" }
    )

    companion object {
        /** The source of hits from your own entries. */
        const val YOURS = "Your watchlist"
        const val NAME = "name:"
        const val STARTS = "starts:"
        const val VENDOR = "vendor:"
        const val PROBE = "probe:"
        const val FINGERPRINT = "fp:"
        const val ADVERT = "adv:"
        /** The source of entries made with "I saw it" ([com.rfsentinel.app.ui.DeviceActions.learn]). */
        const val LEARNED = "learned from I saw it"
    }
}
