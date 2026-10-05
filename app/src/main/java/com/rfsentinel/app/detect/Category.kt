package com.rfsentinel.app.detect

/**
 * What kind of equipment a signature points at. Each category can be switched
 * on/off in Settings; [defaultEnabled] follows the false-positive profile of the
 * underlying signatures (see docs/SIGNATURES.md).
 */
enum class Category(
    val title: String,
    val shortTag: String,
    val colorArgb: Int,
    val defaultEnabled: Boolean
) {
    BODY_CAM("Body camera / Axon equipment", "BODY CAM", 0xFFC8431A.toInt(), true),
    ALPR("License-plate / traffic enforcement camera", "ALPR", 0xFFB3261E.toInt(), true),
    AUDIO_SENSOR("Audio / gunshot sensor (Flock Raven, ShotSpotter)", "AUDIO", 0xFF9C2A6B.toInt(), true),
    PUBLIC_SAFETY("Public-safety vendor gear (Motorola, i-PRO...)", "PUBLIC SAFETY", 0xFFB26A00.toInt(), true),
    DRONE("Drone (Remote ID / drone maker)", "DRONE", 0xFF1F5FBF.toInt(), true),
    TRACKER("Item tracker away from its owner", "TRACKER", 0xFF6A3FB5.toInt(), true),
    GLASSES("Smart / recording glasses", "GLASSES", 0xFF00796B.toInt(), true),
    NETWORK_CAMERA("Network / home security camera", "CAMERA", 0xFF5D6D7E.toInt(), false),
    CELL_ANOMALY("Fake cell tower signs (IMSI catcher)", "CELL", 0xFF7B1FA2.toInt(), true),
    GNSS("GPS / satellite jamming or spoofing signs", "GNSS", 0xFF00838F.toInt(), true),
    RADIO("Two-way radio transmitting nearby (needs an RTL-SDR)", "RADIO", 0xFF0277BD.toInt(), true),
    /** Online (ADS-B feeds): off by default, it sends a rounded position to adsb.fi / adsb.lol. */
    AIRCRAFT("Police / government aircraft overhead (online: adsb.fi, adsb.lol)", "AIRCRAFT", 0xFF5E35B1.toInt(), false),
    /** Online (the user's OpenWeb Ninja key): off by default, third-party service used at the user's own risk. */
    POLICE_REPORT("Police reported on Waze (online: your OpenWeb Ninja key)", "WAZE", 0xFF1E88E5.toInt(), false),
    /** Bluetooth serial modules built into gas-pump / ATM card skimmers (BLE names, receive-only). */
    SKIMMER("Card skimmers (gas pumps, ATMs)", "SKIMMER", 0xFF6D4C41.toInt(), true),
    /** Off by default: plenty of hobbyists carry a Flipper. */
    HACKER("Hacking tools: Flipper Zero, Pwnagotchi, WiFi Pineapple, evil twin WiFi, Bluetooth spam", "HACKER", 0xFFD81B60.toInt(), false),
    CUSTOM("Your watchlist", "WATCHLIST", 0xFFC8431A.toInt(), true);

    companion object {
        fun parse(name: String?): Category? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Coarse confidence bands shown in the UI and used for the alert threshold. */
enum class Tier(val label: String, val min: Int) {
    WEAK("weak - verify", 0),
    MEDIUM("probable", 50),
    STRONG("strong", 80);

    companion object {
        fun of(confidence: Int): Tier = when {
            confidence >= STRONG.min -> STRONG
            confidence >= MEDIUM.min -> MEDIUM
            else -> WEAK
        }
    }
}

/**
 * One signature that matched an observation.
 * @param evidence what in the radio data matched, in plain words
 * @param source where the signature comes from (registry / research citation)
 */
data class Hit(
    val category: Category,
    val label: String,
    val confidence: Int,
    val evidence: String,
    val source: String
) {
    val tier: Tier get() = Tier.of(confidence)
}
