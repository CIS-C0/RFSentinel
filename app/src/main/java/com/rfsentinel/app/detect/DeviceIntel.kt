package com.rfsentinel.app.detect

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Best-effort identification of WHAT an ordinary device is, from passively
 * received metadata only: Apple Continuity message types, Microsoft Swift Pair,
 * Google Fast Pair, beacons, the GAP appearance value and name patterns.
 * Nothing here connects to a device.
 */
object DeviceIntel {

    data class Identity(
        /** Short type, e.g. "AirPods / Beats", "Phone", "WiFi access point". */
        val type: String,
        /** Extra decoded facts shown on the detail screen. */
        val facts: List<Pair<String, String>>
    )

    private const val CID_APPLE = 0x004C
    private const val CID_MICROSOFT = 0x0006
    private const val CID_SAMSUNG = 0x0075
    private const val CID_GOOGLE = 0x00E0

    private val APPLE_TYPES = mapOf(
        0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity pairing",
        0x08 to "\"Hey Siri\"", 0x09 to "AirPlay target", 0x0A to "AirPlay source", 0x0B to "Magic Switch",
        0x0C to "Handoff", 0x0D to "Instant Hotspot (target)", 0x0E to "Instant Hotspot (source)",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My", 0x16 to "Nearby Action (setup)"
    )

    private val NAME_TYPES = listOf(
        Regex("airpods|beats|buds|earbud|headphone|headset|jbl|bose|sony w[hf]-|soundcore|jabra|sennheiser|skullcandy", RegexOption.IGNORE_CASE) to "Headphones / earbuds",
        Regex("watch|fitbit|garmin|amazfit|mi band|galaxy fit|whoop|polar|suunto|oura", RegexOption.IGNORE_CASE) to "Wearable",
        Regex("\\b(tv|bravia|roku|chromecast|fire ?tv|shield)\\b|\\[tv\\]", RegexOption.IGNORE_CASE) to "TV / streaming device",
        Regex("tesla|model [3sxy]\\b|bmw|mercedes|audi|volkswagen|\\bvw\\b|toyota|honda|hyundai|\\bkia\\b|ford|chevrolet|nissan|subaru|volvo|polestar|rivian|mazda|jeep|carplay", RegexOption.IGNORE_CASE) to "Vehicle",
        Regex("printer|deskjet|officejet|laserjet|epson|canon|brother", RegexOption.IGNORE_CASE) to "Printer",
        Regex("airtag|\\btile\\b|smarttag|chipolo|pebblebee", RegexOption.IGNORE_CASE) to "Item tracker",
        Regex("keyboard|mouse|\\bmx \\w+|trackpad|controller|xbox|dualsense|dualshock|joy-con", RegexOption.IGNORE_CASE) to "Peripheral / controller",
        Regex("\\bnest\\b|\\bring\\b|echo|alexa|google home|\\bhue\\b|govee|wiz|kasa|tapo|switchbot|\\block\\b|august|yale", RegexOption.IGNORE_CASE) to "Smart-home device",
        Regex("iphone|ipad|macbook|imac|galaxy|pixel|oneplus|xiaomi|redmi|motorola|moto g", RegexOption.IGNORE_CASE) to "Phone / computer",
        Regex("speaker|soundbar|sonos|boom|flip \\d|charge \\d", RegexOption.IGNORE_CASE) to "Speaker"
    )

    /**
     * Apple proximity-pairing model IDs (as read big-endian from the Continuity
     * 0x07 message), from public Continuity protocol research (furiousMAC,
     * apple_bleee).
     */
    private val APPLE_MODELS = mapOf(
        0x0220 to "AirPods (1st gen)", 0x0F20 to "AirPods (2nd gen)", 0x1320 to "AirPods (3rd gen)",
        0x1920 to "AirPods 4", 0x1B20 to "AirPods 4 (ANC)",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x2420 to "AirPods Pro 2 (USB-C)",
        0x0A20 to "AirPods Max", 0x1F20 to "AirPods Max (USB-C)",
        0x0320 to "Powerbeats3", 0x0B20 to "Powerbeats Pro", 0x1D20 to "Powerbeats Pro 2",
        0x0520 to "BeatsX", 0x1020 to "Beats Flex", 0x0620 to "Beats Solo3", 0x0C20 to "Beats Solo Pro",
        0x0920 to "Beats Studio3", 0x1720 to "Beats Studio Pro", 0x1120 to "Beats Studio Buds",
        0x1620 to "Beats Studio Buds+", 0x1220 to "Beats Fit Pro"
    )

    /** What a device is, from standard (SIG-defined) services it advertises. */
    private val SERVICE_CLASSES = mapOf(
        0x180D to "Heart-rate sensor / fitness wearable",
        0x1812 to "Keyboard, mouse or game controller",
        0x1816 to "Bike speed / cadence sensor", 0x1818 to "Bike power meter",
        0x1826 to "Fitness machine (treadmill, bike...)", 0x1814 to "Running sensor",
        0x1810 to "Blood-pressure monitor", 0x1808 to "Glucose meter", 0x1809 to "Thermometer",
        0x181D to "Weight scale", 0x181B to "Body-composition scale", 0x1822 to "Pulse oximeter",
        0x183A to "Insulin pump / medical device",
        0x181A to "Environmental sensor", 0x1819 to "Location / navigation device",
        0x1802 to "Key finder / alert tag",
        0x184E to "LE Audio earbuds or speaker", 0x1850 to "LE Audio earbuds or speaker",
        0x1853 to "LE Audio earbuds or speaker", 0x1854 to "Hearing aid",
        0xFCD2 to "BTHome smart-home sensor", 0xFE95 to "Xiaomi smart-home device",
        0xFE2C to "Google Fast Pair accessory", 0xFD6F to "Exposure Notification beacon",
        0xFE79 to "Zebra printer / scanner", 0xFD66 to "Zebra device"
    )

    /** Device kind implied by the IEEE registrant of a fixed address. */
    private val VENDOR_CLASSES = listOf(
        Regex("cradlepoint|sierra wireless|peplink|inseego|digi international", RegexOption.IGNORE_CASE) to "Vehicle / cellular router",
        Regex("espressif|tuya|beken|bouffalo|realtek semi|telink", RegexOption.IGNORE_CASE) to "IoT / smart-home module",
        Regex("zebra tech|honeywell|datalogic", RegexOption.IGNORE_CASE) to "Rugged scanner / printer",
        Regex("garmin", RegexOption.IGNORE_CASE) to "Garmin wearable / GPS",
        Regex("motorola solutions|harris corp|kenwood", RegexOption.IGNORE_CASE) to "Two-way radio / public-safety gear",
        Regex("axon", RegexOption.IGNORE_CASE) to "Axon body cam / TASER",
        Regex("apple", RegexOption.IGNORE_CASE) to "Apple device",
        Regex("samsung", RegexOption.IGNORE_CASE) to "Samsung device",
        Regex("sonos|bose|harman|jbl|sennheiser|jabra|gn audio", RegexOption.IGNORE_CASE) to "Audio device",
        Regex("tesla|continental automotive|harman becker|alps alpine|denso|panasonic automotive|lg innotek", RegexOption.IGNORE_CASE) to "Vehicle system",
        Regex("dji|parrot|skydio|autel", RegexOption.IGNORE_CASE) to "Drone / controller",
        Regex("ring llc|amazon technologies|google|nest labs|arlo|wyze", RegexOption.IGNORE_CASE) to "Smart-home / camera device"
    )

    /**
     * Identifies a device from the strongest evidence available, in order:
     * decoded vendor protocols (Apple Continuity with exact model, Swift Pair,
     * Fast Pair, beacons), the GAP appearance, standard services, the name,
     * member-service owners, the address registrant and finally the company ID.
     * The "Identified by" fact says which one decided.
     */
    fun identify(a: Advert, macVendor: String? = null): Identity {
        val facts = mutableListOf<Pair<String, String>>()
        var type: String? = null
        var basis: String? = null

        if (a.isWifi) return identifyWifi(a, macVendor)

        // Every 16-bit service the device advertises, decoded by name.
        val shorts = (a.serviceUuids + a.serviceData.keys).mapNotNull { Advert.shortOf(it) }.distinct()
        if (shorts.isNotEmpty()) {
            facts += "Services" to shorts.joinToString { s ->
                String.format("0x%04X", s) + (VendorDb.uuid16(s)?.let { " $it" } ?: "")
            }
        }

        // Apple Continuity: manufacturer data is a list of [type][length][payload] TLVs.
        a.manufacturerData[CID_APPLE]?.let { d ->
            val found = mutableListOf<String>()
            var i = 0
            while (i + 1 < d.size) {
                val t = Bytes.u8(d, i)
                val len = Bytes.u8(d, i + 1)
                val name = APPLE_TYPES[t] ?: String.format("type 0x%02X", t)
                found += name
                if (t == 0x02 && len == 0x15 && i + 2 + 21 <= d.size) {
                    val uuid = Bytes.hex(d.copyOfRange(i + 2, i + 18), "")
                    val major = (Bytes.u8(d, i + 18) shl 8) or Bytes.u8(d, i + 19)
                    val minor = (Bytes.u8(d, i + 20) shl 8) or Bytes.u8(d, i + 21)
                    facts += "iBeacon" to "UUID $uuid, major $major, minor $minor"
                    type = "iBeacon"
                }
                if (t == 0x07 && i + 5 <= d.size) {
                    val model = (Bytes.u8(d, i + 3) shl 8) or Bytes.u8(d, i + 4)
                    val modelName = APPLE_MODELS[model]
                    facts += "Apple model ID" to String.format("0x%04X", model) + (modelName?.let { " ($it)" } ?: "")
                    if (modelName != null && type == null) { type = modelName; basis = "Apple Continuity model ID" }
                }
                if (t == 0x12) {
                    facts += "Find My state" to if (len == 0x19) "Separated from owner" else "Near its owner"
                }
                if (len == 0) break
                i += 2 + len
            }
            if (found.isNotEmpty()) facts += "Apple Continuity" to found.distinct().joinToString()
            if (basis == null) basis = "Apple Continuity message"
            type = type ?: when {
                0x07 in typesIn(d) -> "AirPods / Beats"
                0x12 in typesIn(d) -> "Apple Find My device (AirTag, iPhone, Mac...)"
                0x09 in typesIn(d) -> "Apple TV / AirPlay speaker"
                0x10 in typesIn(d) || 0x0C in typesIn(d) -> "Apple device (iPhone, iPad, Mac or Watch)"
                else -> "Apple device"
            }
        }
        fun decide(t: String?, why: String) {
            if (type == null && t != null) { type = t; basis = why }
        }

        a.manufacturerData[CID_MICROSOFT]?.let { d ->
            if (d.isNotEmpty()) {
                decide(when (Bytes.u8(d, 0)) {
                    0x03 -> "Windows Swift Pair accessory"
                    0x01 -> "Windows PC (Connected Devices beacon)"
                    else -> "Microsoft device"
                }, "Microsoft beacon")
            }
        }
        if (a.serviceData.keys.any { Advert.shortOf(it) == 0xFE2C }) {
            facts += "Google Fast Pair" to "Pairing / account-key broadcast"
        }
        a.serviceData[Advert.uuid16(0xFEAA)]?.takeIf { it.isNotEmpty() }?.let { d ->
            val frame = when (Bytes.u8(d, 0)) {
                0x00 -> "Eddystone-UID"; 0x10 -> "Eddystone-URL"; 0x20 -> "Eddystone-TLM"; 0x30 -> "Eddystone-EID"
                0x40 -> "Find Hub tracker (near owner)"; 0x41 -> "Find Hub tracker (separated)"; else -> null
            }
            if (frame != null) { facts += "Google 0xFEAA frame" to frame; decide(frame, "Google 0xFEAA frame") }
        }

        // GAP appearance (AD 0x19): the device's own statement of what it is.
        AdStructure.parse(a.rawBytes).firstOrNull { it.type == 0x19 && it.data.size >= 2 }?.let {
            val value = Bytes.u16le(it.data, 0)
            val desc = VendorDb.appearance(value)
            facts += "Appearance" to (desc ?: String.format("0x%04X", value))
            if (desc != null && value ushr 6 != 0) decide(desc, "advertised appearance")
        }

        // Standard services say what the device does (heart rate, HID, LE Audio...).
        shorts.firstNotNullOfOrNull { SERVICE_CLASSES[it] }?.let { decide(it, "advertised service") }

        val name = a.name
        if (name != null) decide(NAME_TYPES.firstOrNull { it.first.containsMatchIn(name) }?.second, "name pattern")

        // Brand-level fallbacks: member services (0xFCxx-0xFExx belong to one company),
        // the IEEE registrant of a fixed address, then the SIG company ID.
        if (CID_SAMSUNG in a.manufacturerData) decide("Samsung device", "company ID")
        if (CID_GOOGLE in a.manufacturerData) decide("Google device", "company ID")
        shorts.filter { it >= 0xFC00 }.firstNotNullOfOrNull { VendorDb.uuid16(it) }
            ?.let { decide("$it device", "member service UUID owner") }
        macVendor?.let { v -> decide(VENDOR_CLASSES.firstOrNull { it.first.containsMatchIn(v) }?.second ?: "$v device", "address registrant (IEEE)") }
        a.manufacturerData.keys.firstOrNull()?.let { cid -> decide(VendorDb.company(cid)?.let { "$it device" }, "company ID") }

        basis?.let { facts += "Identified by" to it }
        return Identity(type ?: "Bluetooth device", facts)
    }

    private fun typesIn(d: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        var i = 0
        while (i + 1 < d.size) {
            out += Bytes.u8(d, i)
            val len = Bytes.u8(d, i + 1)
            if (len == 0) break
            i += 2 + len
        }
        return out
    }

    private val WIFI_VENDOR_KINDS = setOf(
        "Vehicle / cellular router", "IoT / smart-home module", "Two-way radio / public-safety gear",
        "Axon body cam / TASER", "Vehicle system", "Drone / controller", "Smart-home / camera device", "Rugged scanner / printer"
    )

    private fun identifyWifi(a: Advert, macVendor: String?): Identity {
        val w = a.wifi
        val facts = mutableListOf<Pair<String, String>>()
        if (w != null) {
            val band = when (w.frequencyMhz) {
                in 2400..2500 -> "2.4 GHz"; in 4900..5925 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "${w.frequencyMhz} MHz"
            }
            facts += "Band" to "$band (${w.frequencyMhz} MHz, channel ${channelOf(w.frequencyMhz)})"
            w.standard?.let { facts += "Standard" to it }
            facts += "Security" to security(w.capabilities)
        }
        val ssid = a.name
        val type = when {
            ssid.isNullOrEmpty() -> "Hidden WiFi network"
            Regex("iphone|androidap|galaxy|pixel|hotspot|'s phone", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "Phone hotspot"
            Regex("direct-|printer|hp-print|epson|canon", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "WiFi Direct / printer"
            Regex("tesla|carplay|android auto|\\bcar\\b|bmw|mercedes|audi|toyota", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "Vehicle WiFi"
            else -> "WiFi access point"
        }
        // Make / model from the beacon itself (works for hidden networks too).
        val ap = WifiFingerprint.parse(w?.infoElements.orEmpty())
        ap.model?.let { facts += "AP model (WPS)" to it }
        ap.deviceName?.let { facts += "AP device name (WPS)" to it }
        ap.deviceKind?.let { facts += "Device kind (WPS)" to it }
        ap.ciscoApName?.let { facts += "Cisco AP name" to it }
        if (ap.equipmentVendors.isNotEmpty()) facts += "Equipment maker (vendor IEs)" to ap.equipmentVendors.joinToString { shortVendor(it) }
        if (ap.chipsetVendors.isNotEmpty()) facts += "WiFi chipset (vendor IEs)" to ap.chipsetVendors.joinToString { shortVendor(it) }

        val generic = type == "WiFi access point" || type == "Hidden WiFi network"
        // A generic SSID says little; the registrant of a fixed BSSID can say more.
        val byVendor = macVendor?.let { v -> VENDOR_CLASSES.firstOrNull { it.first.containsMatchIn(v) }?.second }
            ?.takeIf { it in WIFI_VENDOR_KINDS }
        if (byVendor != null && generic) {
            facts += "Identified by" to "address registrant (IEEE)"
            return Identity("$byVendor (WiFi)" + (ap.model?.let { " - $it" } ?: ""), facts)
        }
        if (!generic) return Identity(type, facts)

        // Most specific first: WPS model, Cisco AP name, BSSID registrant (if not a
        // chipset maker), then an equipment maker's vendor IE.
        val (make, why) = when {
            ap.model != null -> ap.model!! to "WPS model in the beacon"
            ap.ciscoApName != null -> "Cisco AP \"${ap.ciscoApName}\"" to "Cisco AP-name element in the beacon"
            macVendor != null && !Regex("broadcom|qualcomm|mediatek|realtek|intel corp", RegexOption.IGNORE_CASE).containsMatchIn(macVendor) ->
                shortVendor(macVendor) to "BSSID registrant (IEEE)"
            ap.equipmentVendors.isNotEmpty() -> shortVendor(ap.equipmentVendors.first()) to "vendor-specific element in the beacon"
            else -> null to null
        }
        if (make == null) return Identity(type, facts)
        facts += "Identified by" to why!!
        return Identity("$type - $make", facts)
    }

    /** "NETGEAR, Inc." -> "NETGEAR"; "TP-LINK TECHNOLOGIES CO.,LTD." -> "TP-LINK". */
    fun shortVendor(v: String): String =
        v.replace(Regex("(?i)(?:[,.]\\s*|\\s+)(inc\\.?|corp(oration)?\\.?|co\\.?,?\\s*ltd\\.?|ltd\\.?|llc|gmbh|s\\.?a\\.?|limited|technologies|technology|systems|networks|electronics|communications?|international)\\b.*$"), "")
            .trim().trimEnd(',', '.').ifEmpty { v }

    fun security(caps: String): String = when {
        caps.contains("SAE") -> "WPA3"
        caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP (insecure)"
        caps.contains("OWE") -> "Enhanced Open (OWE)"
        else -> "Open (no password)"
    }

    fun channelOf(mhz: Int): Int = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz in 5000..5895 -> (mhz - 5000) / 5
        mhz in 5955..7115 -> (mhz - 5950) / 5
        else -> 0
    }

    /**
     * Very rough distance. BLE: log-distance path loss with n = 2.5 and the
     * advertised TX power (minus ~41 dB to get the 1 m reference) when present,
     * else a typical -59 dBm at 1 m. WiFi: free-space loss assuming a 20 dBm AP.
     * Walls, bodies and antenna orientation easily cause 2-3x errors.
     */
    fun distanceMeters(a: Advert, rssi: Double = a.rssi.toDouble()): Double {
        if (a.isWifi) {
            val f = a.wifi?.frequencyMhz?.takeIf { it > 0 } ?: 2437
            val exp = (20.0 - rssi - 20 * log10(f.toDouble()) + 27.55) / 20.0
            return 10.0.pow(exp).coerceIn(0.5, 500.0)
        }
        val measuredAt1m = a.txPower?.let { it - 41 } ?: -59
        return 10.0.pow((measuredAt1m - rssi) / 25.0).coerceIn(0.1, 300.0)
    }

    fun formatDistance(m: Double): String = when {
        m < 1 -> "< 1 m"
        m < 10 -> "~${m.roundToInt()} m"
        else -> "~${(m / 5).roundToInt() * 5} m"
    }
}
