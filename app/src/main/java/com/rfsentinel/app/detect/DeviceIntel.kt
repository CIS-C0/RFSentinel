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

    /**
     * [identity] named by a match of a kind turned off in the detection list: the match's name as the
     * device's type, and what matched among the facts (no colour, no alert).
     */
    fun quietlyIdentified(identity: Identity, hit: Hit): Identity = Identity(hit.label,
        identity.facts + listOf("Identified as" to "${hit.label} · ${hit.category.title}", "Matched" to hit.evidence))

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

    /**
     * What an iPhone, iPad, Mac or Watch is doing, from the low 4 bits of its Nearby Info status byte
     * (furiousMAC Continuity research, as checked against 2026 captures by SignalSweep; facts only).
     */
    private val APPLE_ACTIVITY = mapOf(
        0x01 to "Activity reporting off", 0x03 to "Idle", 0x05 to "Playing audio, screen locked",
        0x07 to "In use, screen on", 0x09 to "Playing video", 0x0A to "Watch on wrist, unlocked",
        0x0B to "Just used", 0x0D to "Driving", 0x0E to "On a call"
    )

    /** The standard of a drone's Remote ID heard over Wi-Fi Aware (NAN) rather than in a beacon. */
    const val WIFI_AWARE = "Wi-Fi Aware (NAN)"

    /** Meshtastic's BLE service (Meshtastic firmware / app protocol docs). */
    private val MESHTASTIC_SERVICE = java.util.UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private const val MESH_NODE = "Mesh radio node (Meshtastic / MeshCore LoRa)"

    /** Passenger trains' onboard WiFi (Wardrive Go's rail list, plus Deutsche Bahn's ICE network). */
    internal val TRAIN_SSID = Regex("amtrak|brightline|eurostar|sncf|wifi_inoui|wifionice|via ?rail|^via[-_ ]?wi-?fi\$", RegexOption.IGNORE_CASE)

    /**
     * What a network is, from the default name its maker or internet provider gives it: home routers
     * by provider (many countries, so a name says what, never where), providers' public hotspots,
     * mobile hotspot boxes, and gadgets that make their own network (dashcams, drones, cameras, TVs,
     * devices waiting to be set up). First match wins.
     */
    private val SSID_TYPES = listOf(
        // Providers' public hotspots (before their home-router patterns).
        "^xfinitywifi$|^xfinity ?mobile$" to "Public WiFi hotspot - Xfinity", "^coxwifi$" to "Public WiFi hotspot - Cox",
        "^optimumwifi$" to "Public WiFi hotspot - Optimum", "^spectrumwifi( plus)?$" to "Public WiFi hotspot - Spectrum",
        "^attwifi$" to "Public WiFi hotspot - AT&T", "^btwi-?fi|^bt-?fon$|^btopenzone" to "Public WiFi hotspot - BT",
        "^shaw ?(open|go|passpoint)" to "Public WiFi hotspot - Shaw", "^telekom(_fon)?$|^telekom_fon" to "Public WiFi hotspot - Telekom",
        "^vodafone ?(homespot|hotspot|wifi)$" to "Public WiFi hotspot - Vodafone", "^orange$|^freewifi(_secure)?$|^sfr wifi" to "Public WiFi hotspot - French provider",
        "^_?the cloud$|^virgin ?media ?wifi|^skywifi$" to "Public WiFi hotspot - UK provider",
        // Mobile hotspot boxes.
        "^(verizon-)?mifi|^jetpack|^verizon-[a-z0-9]{4,6}-[a-z0-9]{4}$|^nighthawk ?m\\d|^netgear-?m[1-6]\\b|^moxee|^inseego|^t-mobile hotspot|^huawei-e5|^alcatel link|^zte[_-]?mf" to "Mobile hotspot box",
        // Home routers by provider.
        "^att(?=.*\\d)[a-z0-9]{6,7}$|^att-?wifi-" to "Home router - AT&T", "^fios-[a-z0-9]{5}$|^verizon_[a-z0-9]{6}$" to "Home router - Verizon",
        "^spectrum(setup)?-[a-z0-9]{2,4}$|^myspectrumwifi" to "Home router - Spectrum", "^xfinity|^home-[0-9a-f]{4}(-5g)?$" to "Home router - Xfinity",
        "^centurylink" to "Home router - CenturyLink", "^frontier\\d{3,4}$" to "Home router - Frontier",
        "^cox-?wifi-" to "Home router - Cox", "^optimum ?\\d|^optimum-" to "Home router - Optimum", "^tmobile ?home|^t-mobile ?home|^tmobile-[0-9a-f]{4}$" to "Home router - T-Mobile",
        "^bell\\d{3}$" to "Home router - Bell", "^telus\\d{4}" to "Home router - Telus", "^rogers|^ignite ?\\d|^ignite-" to "Home router - Rogers",
        "^videotron|^helix-?" to "Home router - Videotron", "^shaw-[0-9a-f]{6}" to "Home router - Shaw", "^sasktel" to "Home router - SaskTel",
        "^cogeco-" to "Home router - Cogeco", "^eastlink" to "Home router - Eastlink", "^virgin ?plus|^virgin-?\\d" to "Home router - Virgin Plus",
        "^bt-[a-z0-9]{6,7}$|^bthub\\d|^btbhub|^bt ?smart ?hub" to "Home router - BT", "^sky(?=.*\\d)[a-z0-9]{5}$|^sky_?wifi_" to "Home router - Sky",
        "^vm[0-9a-f]{7}$|^virgin ?media" to "Home router - Virgin Media", "^talktalk" to "Home router - TalkTalk",
        "^plusnet-" to "Home router - Plusnet", "^ee-[a-z0-9]{5,7}$|^ee-hub|^ee ?smart" to "Home router - EE",
        "^livebox-" to "Home router - Orange", "^freebox-" to "Home router - Free", "^sfr[-_]|^neufbox" to "Home router - SFR",
        "^bbox-" to "Home router - Bouygues", "^magentawlan|^wlan-[0-9a-z]{6}$|^speedport" to "Home router - Telekom",
        "^vodafone-?[0-9a-z]{4,}|^vodafone ?(station|homespot)" to "Home router - Vodafone", "^o2-wlan|^o2 ?homespot" to "Home router - O2",
        "^movistar_|^miwifi_" to "Home router - Movistar", "^tim-\\d|^telecomitalia" to "Home router - TIM", "^fastweb" to "Home router - Fastweb",
        "^telstra[0-9a-f]{4}" to "Home router - Telstra", "^optus_" to "Home router - Optus", "^ziggo" to "Home router - Ziggo",
        "^kpn ?[0-9a-z]{4}|^experiabox" to "Home router - KPN", "^upc\\d{6,7}$" to "Home router - UPC",
        // Public WiFi of chains found in many countries, and plainly free networks.
        "^(google )?starbucks|^mcdonalds|^mcdonald's|^mcd ?free|^tim ?hortons|^panera|^boingo|^wayport|^ikea ?wifi|^subway ?wifi|^costa ?wifi|^pret ?wifi|^kfc ?wifi|^burger ?king|^walmart ?wifi" to "Public WiFi hotspot",
        "^_?free[ _-]?(public[ _-]?)?wi-?fi|^public[ _-]?wi-?fi$|^_?airport[ _-]?(free[ _-]?)?wi-?fi" to "Public WiFi hotspot",
        // Routers' own default names (the maker, before anyone renamed it).
        "^netgear\\d{2}(-5g)?$|^orbi\\d{2,3}$" to "Home router - NETGEAR", "^tp-?link_[0-9a-f]{4}(_5g)?$|^deco[_-]?[0-9a-f]{4}$" to "Home router - TP-Link",
        "^asus(_[0-9a-f]{2})?(_5g)?$|^asus_rt" to "Home router - ASUS", "^linksys\\d{5}(_5ghz)?$" to "Home router - Linksys",
        "^d-?link[-_][0-9a-f]{4}" to "Home router - D-Link", "^belkin[._-][0-9a-f]{3}" to "Home router - Belkin",
        "^huawei-[0-9a-z]{4}(-5g)?$" to "Home router - Huawei", "^tenda_[0-9a-f]{6}" to "Home router - Tenda",
        "^xiaomi_[0-9a-f]{4}|^redmi_[0-9a-f]{4}" to "Home router - Xiaomi", "^mercusys_[0-9a-f]{4}" to "Home router - Mercusys",
        "^arris-[0-9a-f]{4}|^sbg\\d{4}" to "Home router - ARRIS", "^sagemcom[0-9a-f]{4}" to "Home router - Sagemcom",
        "^technicolor|^tc8717t|^tg1672g" to "Home router - Technicolor", "^hitron-[0-9a-f]{4}" to "Home router - Hitron",
        "^fritz!?box" to "Home router - FRITZ!Box", "^zyxel_[0-9a-f]{4}" to "Home router - Zyxel", "^ubee[0-9a-f]{4}" to "Home router - Ubee",
        // Gadgets that make their own network.
        "^70mai|^viofo|^blackvue|^thinkware|^nextbase|^vantrue|^rexing|^garmin ?dash|^dashcam|^dash ?cam|^roav|^redtiger|^azdome" to "Dashcam",
        "^gopro|^gp[0-9]{8}$|^hero\\d+|^insta360|^osmo ?action|^akaso" to "Action camera",
        "^tello-|^dji[-_ ]|^mavic|^spark-|^phantom|^anafi|^skycontroller|^autel|^evo ?ii|^skydio|^parrot" to "Drone / controller",
        "\\[tv\\]|^direct-[0-9a-z]{2}-(\\[tv\\]|samsung|lg|bravia|tcl|hisense|vizio)|^bravia|^lg[_ ]?(webos)?[_ ]?tv|^vizio|^roku-|^androidtv|^chromecast" to "Smart TV / streaming device",
        "^esp[-_][0-9a-f]{4,6}$|^esp32|^esp8266|^tasmota-|^shelly|^sonoff|^espressif|^wled-|^openmqttgateway" to "IoT board (ESP / smart-home firmware)",
        "^tp-?link_smart|^kasa_|^tapo_|^wemo\\.|^meross_|^smartlife-|^smart ?life|^tuya|^gosund|^govee|^wiz_|^hue-" to "Smart plug / bulb waiting to be set up",
        "^ring setup|^ring-[0-9a-f]{4}|^nest-|^arlo|^blink-|^wyze|^eufy|^reolink|^ipc[-_]|^ezviz|^hikvision|^imou" to "Security camera / doorbell",
        "^nintendo|^switch_|^ps[45]-|^xbox" to "Game console"
    ).map { (re, type) -> Regex(re, RegexOption.IGNORE_CASE) to type }

    private val PHONE_TYPE = Regex("^(iphone|apple watch|phone hotspot|phone / computer|apple device \\(iphone|samsung device|google device|phone$|phone -)", RegexOption.IGNORE_CASE)
    private val PHONE_NAME = Regex("iphone|galaxy|pixel|'s phone|oneplus|redmi|moto g|xperia|nothing phone", RegexOption.IGNORE_CASE)
    /** A phone maker's other gear, named after the phone ("Galaxy Buds", "Pixel Watch", "Galaxy Tab"). */
    private val NOT_PHONE_NAME = Regex("buds|watch|\\btab\\b|book|\\bfit\\b|band|\\bring\\b|smarttag|\\btag\\b|\\btv\\b|tablet|\\bear", RegexOption.IGNORE_CASE)

    /** True when a device's type (from [identify]) or broadcast name says it's a phone (or a phone's hotspot). */
    fun isPhone(type: String?, name: String?): Boolean =
        type?.let { PHONE_TYPE.containsMatchIn(it) } == true ||
            name?.let { PHONE_NAME.containsMatchIn(it) && !NOT_PHONE_NAME.containsMatchIn(it) } == true

    /** What a network's name says it is ([SSID_TYPES]), or null. */
    fun ssidType(ssid: String): String? = SSID_TYPES.firstOrNull { it.first.containsMatchIn(ssid.trim()) }?.second

    /**
     * A train's WiFi rides along with you, and its router often comes from the same vehicle-router
     * makers police cars use (Cradlepoint, Sierra Wireless): those matches are dropped for it.
     */
    fun dropTrainRouter(hits: List<Hit>, a: Advert): List<Hit> {
        val ssid = a.name ?: return hits
        if (a.isBle || a.wifi?.client == true || !TRAIN_SSID.containsMatchIn(ssid)) return hits
        return hits.filterNot { it.category == Category.PUBLIC_SAFETY }
    }

    private val NAME_TYPES = listOf(
        Regex("meshtastic|^meshcore", RegexOption.IGNORE_CASE) to MESH_NODE,
        Regex("airpods|beats|buds|earbud|headphone|headset|jbl|bose|sony w[hf]-|soundcore|jabra|sennheiser|skullcandy", RegexOption.IGNORE_CASE) to "Headphones / earbuds",
        Regex("watch|fitbit|garmin|amazfit|mi band|galaxy fit|whoop|polar|suunto|oura", RegexOption.IGNORE_CASE) to "Wearable",
        Regex("\\b(tv|bravia|roku|chromecast|fire ?tv|shield)\\b|\\[tv\\]", RegexOption.IGNORE_CASE) to "TV / streaming device",
        // Car makers and their infotainment systems' Bluetooth names (whole words: "audi" is in "audio").
        Regex("tesla|model [3sxy]\\b|\\bbmw\\b|mercedes|\\baudi\\b|volkswagen|\\bvw\\b|toyota|lexus|honda|acura|hyundai|genesis|\\bkia\\b|\\bford\\b|lincoln|" +
            "chevrolet|\\bchevy\\b|cadillac|\\bgmc\\b|buick|nissan|infiniti|subaru|volvo|polestar|rivian|mazda|jeep|dodge|chrysler|porsche|land rover|" +
            "jaguar|mini cooper|peugeot|renault|citro[eë]n|skoda|\\bopel\\b|vauxhall|\\bfiat\\b|carplay|uconnect|\\bmy ?car\\b|^sync$|mbux|\\bmmi\\b|" +
            "idrive|onstar|mylink|entune", RegexOption.IGNORE_CASE) to "Vehicle",
        Regex("printer|deskjet|officejet|laserjet|epson|canon|brother", RegexOption.IGNORE_CASE) to "Printer",
        Regex("airtag|\\btile\\b|smarttag|chipolo|pebblebee", RegexOption.IGNORE_CASE) to "Item tracker",
        Regex("keyboard|mouse|\\bmx \\w+|trackpad|controller|xbox|dualsense|dualshock|joy-con", RegexOption.IGNORE_CASE) to "Peripheral / controller",
        Regex("gopro|insta360|osmo|dash ?cam|doorbell|\\bcam\\b|camera|arlo|wyze ?cam|eufy ?cam|blink", RegexOption.IGNORE_CASE) to "Camera",
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

    /**
     * Car makers and car-electronics suppliers (infotainment, telematics), as IEEE registrants spell them,
     * with the name shown. Their addresses on a WiFi network mean a car's own WiFi.
     */
    private val AUTOMOTIVE = listOf(
        "^tesla,? ?inc" to "Tesla", "^ford motor" to "Ford", "^general motors" to "GM", "^volkswagen" to "Volkswagen",
        "^bmw ag" to "BMW", "^mercedes-benz" to "Mercedes-Benz", "^daimler truck" to "Daimler Truck",
        "^honda motor|^sony honda mobility" to "Honda", "toyota" to "Toyota", "^volvo car" to "Volvo",
        "^polestar" to "Polestar", "^lucid motors" to "Lucid", "^rivian" to "Rivian", "^nio co" to "NIO",
        "harman/becker|^harman connected" to "Harman", "continental automotive|continental vehicle" to "Continental",
        "bosch car multimedia" to "Bosch", "^lg innotek" to "LG Innotek", "visteon" to "Visteon",
        "panasonic automotive" to "Panasonic Automotive", "^denso" to "Denso",
        "alps ?alpine|^alpsalpine|alpine electronics" to "Alps Alpine", "^aptiv" to "Aptiv", "^valeo" to "Valeo",
        "marelli" to "Marelli", "clarion" to "Clarion", "^peiker" to "peiker", "^lear corporation" to "Lear",
        "desay sv" to "Desay SV", "joynext|preh car connect" to "Preh / Joynext", "pateo" to "PATEO"
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    /** The car maker or supplier a registrant is, or null. */
    fun automotiveMaker(vendor: String?): String? =
        vendor?.let { v -> AUTOMOTIVE.firstOrNull { it.first.containsMatchIn(v.trim()) }?.second }

    /** WiFi types older versions gave a car's own network, before it was named by its maker's address. */
    private val OLD_CAR_WIFI_TYPES = setOf("Hidden WiFi network", "Phone hotspot", "WiFi Direct / printer", "Vehicle WiFi", "WiFi access point")

    /** A saved record's type as this version names it: snapshots, traces and history saved before car WiFi
     *  was named by maker read "Vehicle WiFi - <maker>" too. The saved data itself is left as it was. */
    fun savedType(type: String?, vendor: String?, mac: String): String? {
        if (type == null || !(type in OLD_CAR_WIFI_TYPES || type.startsWith("WiFi access point - ") || type.startsWith("Vehicle system (WiFi)"))) return type
        val car = automotiveMaker(vendor ?: VendorDb.macVendor(mac)) ?: return type
        val model = type.substringAfter("Vehicle system (WiFi) - ", "").takeIf { it.isNotEmpty() }
        return "Vehicle WiFi - $car" + (model?.let { " - $it" } ?: "")
    }

    /** A saved label that was only the old type reads as the new one; a network or device name stays. */
    fun savedLabel(label: String, type: String?, vendor: String?, mac: String): String =
        if (label == type) savedType(type, vendor, mac) ?: label else label

    /** Device kind implied by the IEEE registrant of a fixed address. */
    private val VENDOR_CLASSES = listOf(
        Regex("cradlepoint|sierra wireless|peplink|inseego|digi international", RegexOption.IGNORE_CASE) to "Vehicle / cellular router",
        Regex("espressif|tuya|beken|bouffalo|realtek semi|telink", RegexOption.IGNORE_CASE) to "IoT / smart-home module",
        Regex("zebra tech|honeywell|datalogic", RegexOption.IGNORE_CASE) to "Rugged scanner / printer",
        Regex("garmin", RegexOption.IGNORE_CASE) to "Garmin wearable / GPS",
        Regex("motorola solutions|harris corp|kenwood", RegexOption.IGNORE_CASE) to "Two-way radio / public-safety gear",
        Regex("axon", RegexOption.IGNORE_CASE) to "Axon body cam / TASER",
        Regex("zepcam", RegexOption.IGNORE_CASE) to "Zepcam body camera",
        Regex("apple", RegexOption.IGNORE_CASE) to "Apple device",
        Regex("samsung", RegexOption.IGNORE_CASE) to "Samsung device",
        Regex("sonos|bose|harman|jbl|sennheiser|jabra|gn audio", RegexOption.IGNORE_CASE) to "Audio device",
        Regex(AUTOMOTIVE.joinToString("|") { "(?:${it.first.pattern})" }, RegexOption.IGNORE_CASE) to "Vehicle system",
        Regex("dji|parrot|skydio|autel", RegexOption.IGNORE_CASE) to "Drone / controller",
        Regex("ring llc|arlo|wyze|eufy|reolink|hikvision|dahua|ezviz|axis communications|hanwha", RegexOption.IGNORE_CASE) to "Security camera / doorbell",
        Regex("amazon technologies|google|nest labs", RegexOption.IGNORE_CASE) to "Smart-home / camera device"
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
                if (t == 0x10 && len >= 1 && i + 2 < d.size) {
                    APPLE_ACTIVITY[Bytes.u8(d, i + 2) and 0x0F]?.let { facts += "Apple activity" to it }
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
                // Only a Watch reports being on a wrist or offers Magic Switch; only a phone drives,
                // and only a phone or cellular iPad offers its hotspot.
                0x0B in typesIn(d) || appleActivity(d) == 0x0A -> "Apple Watch"
                appleActivity(d) == 0x0D -> "iPhone"
                0x0E in typesIn(d) -> "iPhone or cellular iPad"
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
        a.serviceData.entries.firstOrNull { Advert.shortOf(it.key) == 0xFE2C }?.value?.let { d ->
            // In pairing mode the service data is just the 3-byte model ID; otherwise an account-key filter.
            if (d.size == 3) {
                val model = (Bytes.u8(d, 0) shl 16) or (Bytes.u8(d, 1) shl 8) or Bytes.u8(d, 2)
                val product = VendorDb.fastPairModel(model)
                facts += "Google Fast Pair" to "Ready to pair, model ID " + String.format("0x%06X", model) + (product?.let { " ($it)" } ?: "")
                decide(product, "Fast Pair model ID")
            } else {
                facts += "Google Fast Pair" to "Pairing / account-key broadcast"
            }
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

        // LoRa mesh radios (after Wardrive Go): Meshtastic's own service, else the node's default name.
        if (MESHTASTIC_SERVICE in a.serviceUuids) decide(MESH_NODE, "Meshtastic service UUID")

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

    /** The activity code (low 4 bits of the status byte) of an Apple Nearby Info message, or null. */
    private fun appleActivity(d: ByteArray): Int? {
        var i = 0
        while (i + 1 < d.size) {
            val len = Bytes.u8(d, i + 1)
            if (Bytes.u8(d, i) == 0x10 && len >= 1 && i + 2 < d.size) return Bytes.u8(d, i + 2) and 0x0F
            if (len == 0) break
            i += 2 + len
        }
        return null
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
        "Axon body cam / TASER", "Vehicle system", "Drone / controller", "Smart-home / camera device", "Rugged scanner / printer",
        "Security camera / doorbell"
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
            if (!w.client) facts += "Security" to security(w.capabilities)
            else facts += "Heard by" to "USB WiFi adapter (monitor mode)"
        }
        val ssid = a.name
        val type = when {
            w?.client == true -> "WiFi device (laptop, phone or camera on a network)"
            w?.standard == WIFI_AWARE -> "Drone Remote ID over Wi-Fi Aware"
            ssid.isNullOrEmpty() -> "Hidden WiFi network"
            TRAIN_SSID.containsMatchIn(ssid) -> "Train WiFi (passenger rail)"
            ssidType(ssid) != null -> ssidType(ssid)!!
            Regex("iphone|androidap|galaxy|pixel|hotspot|'s phone", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "Phone hotspot"
            Regex("direct-|printer|hp-print|epson|canon", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "WiFi Direct / printer"
            Regex("tesla|carplay|android auto|\\bcar\\b|\\bbmw\\b|mercedes|\\baudi\\b|toyota|uconnect|onstar|\\bmy ?car\\b|\\bvw\\b|volkswagen|hyundai|\\bkia\\b|porsche|volvo|rivian|polestar", RegexOption.IGNORE_CASE).containsMatchIn(ssid) -> "Vehicle WiFi"
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

        // A network from a car maker's or car-electronics supplier's address is a car's own WiFi, whatever
        // its name says ("Hotspot", "DIRECT-...", a plain name): named for what it is, so cars stand apart.
        val car = if (w?.client == true || w?.standard == WIFI_AWARE || type == "Train WiFi (passenger rail)") null
            else automotiveMaker(macVendor)
        if (car != null) {
            facts += "Identified by" to "address registrant (IEEE): ${macVendor!!.trim()}, a car maker or car-electronics supplier"
            if (type != "WiFi access point" && type != "Vehicle WiFi" && type != "Hidden WiFi network") facts += "Network name suggests" to type
            return Identity("Vehicle WiFi - $car" + (ap.model?.let { " - $it" } ?: ""), facts)
        }

        val generic = type == "WiFi access point" || type == "Hidden WiFi network"
        // A generic SSID says little; the registrant of a fixed BSSID can say more.
        val byVendor = macVendor?.let { v -> VENDOR_CLASSES.firstOrNull { it.first.containsMatchIn(v) }?.second }
            ?.takeIf { it in WIFI_VENDOR_KINDS }
        if (byVendor != null && generic) {
            facts += "Identified by" to "address registrant (IEEE)"
            return Identity("$byVendor (WiFi)" + (ap.model?.let { " - $it" } ?: ""), facts)
        }
        if (!generic) return Identity(type, facts)

        // What kind of network a plain or hidden name hides, from the beacon itself.
        networkKind(w, ap, macVendor, ssid.isNullOrEmpty())?.let { (kind, why) ->
            facts += "Identified by" to why
            if (ssid.isNullOrEmpty()) facts += "Network name" to "Hidden (not broadcast)"
            return Identity(kind, facts)
        }

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

    /**
     * Internet providers' own router models, as their WPS data names them (model name / number / device
     * name). Platforms several providers share are named by model, not provider.
     */
    private val ISP_ROUTER_MODELS = listOf(
        "livebox" to "Orange Livebox", "freebox" to "Free Freebox", "\\bbbox\\b" to "Bouygues Bbox", "speedport" to "Telekom Speedport",
        "fritz!? ?box" to "FRITZ!Box", "vodafone ?station|gigacube" to "Vodafone Station", "sky ?(hub|q hub|broadband)" to "Sky Hub",
        "bt ?smart ?hub|bt ?hub" to "BT Smart Hub", "giga ?hub|home ?hub ?[34]000" to "Bell Home Hub", "\\bbgw\\d{3}|\\bnvg5\\d{2}|5268ac" to "AT&T gateway",
        "\\bg[13]100\\b|\\bcr1000|fios" to "Verizon Fios router", "\\bxb[678]\\b|\\bcgm4\\d{3}|\\btg1682|\\btg3482" to "XB gateway (Comcast platform)",
        "\\bsax1v1|\\brac2v1" to "Spectrum router", "\\beero\\b" to "eero mesh", "\\bdeco\\b" to "TP-Link Deco mesh", "\\borbi\\b" to "NETGEAR Orbi mesh"
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    /** Makers of the routers internet providers hand out, as the IEEE lists them (short name shown). */
    private val ISP_ROUTER_MAKERS = listOf(
        "^sagemcom" to "Sagemcom", "^commscope|^arris$" to "CommScope / ARRIS", "^vantiva|^technicolor" to "Technicolor / Vantiva",
        "^askey" to "Askey", "^sercomm" to "Sercomm", "^hitron" to "Hitron", "^ubee" to "Ubee", "^calix" to "Calix",
        "^actiontec" to "Actiontec", "^2wire|^pace americas" to "2Wire / Pace", "^compal broadband" to "Compal Broadband",
        "^castlenet" to "CastleNet", "^nokia (solutions|shanghai)|^alcatel-lucent shanghai" to "Nokia", "^avm (audiovisuelles|gmbh)" to "AVM (FRITZ!Box)",
        "^huawei technologies" to "Huawei", "^zte corporation" to "ZTE"
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    /** Makers of home routers and mesh systems sold in stores. */
    private val HOME_ROUTER_MAKERS = listOf(
        "^netgear" to "NETGEAR", "^tp-link" to "TP-Link", "^asustek" to "ASUS", "linksys" to "Linksys", "^belkin" to "Belkin",
        "^d-link" to "D-Link", "tenda technology" to "Tenda", "^mercusys" to "Mercusys", "^eero" to "eero", "^synology" to "Synology"
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    /** Makers of business / campus WiFi (their own address or their beacon tags). Aruba's tags carry HPE's registration. */
    private val BUSINESS_AP_MAKERS = listOf(
        "^cisco systems" to "Cisco", "^cisco meraki" to "Cisco Meraki", "^hewlett packard enterprise" to "Aruba (HPE)",
        "^ruckus" to "Ruckus", "^fortinet" to "Fortinet", "^juniper networks|^mist systems" to "Juniper Mist",
        "^extreme networks" to "Extreme Networks", "^cambium" to "Cambium"
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    private fun maker(table: List<Pair<Regex, String>>, vararg owners: String?): String? =
        owners.filterNotNull().firstNotNullOfOrNull { o -> table.firstOrNull { it.first.containsMatchIn(o.trim()) }?.second }

    /**
     * A network with a plain (or no) name, sorted from its beacon: Passpoint public WiFi, account sign-in
     * (enterprise) business WiFi, a provider's router by its WPS model, business access points by maker,
     * provider and store-bought home routers by maker or by the WPS "router / gateway" kind, then open WiFi.
     * The kind and why, or null.
     */
    internal fun networkKind(w: Advert.WifiInfo?, ap: WifiFingerprint.ApInfo, macVendor: String?, hidden: Boolean = false): Pair<String, String>? {
        if (w == null || w.client) return null
        val ies = w.infoElements
        val enterprise = w.capabilities.contains("EAP")
        val open = security(w.capabilities) == "Open (no password)"
        // Public only when the beacon says so and nobody needs the owner's password. Home routers often carry an
        // Interworking element (107) too, saying "private network": that alone means nothing.
        // Passpoint: the Wi-Fi Alliance HS2.0 indication (50:6F:9A type 0x10), always with account sign-in.
        val hs20 = ies.any { (id, d) ->
            id == 221 && d.size >= 4 && Bytes.u8(d, 0) == 0x50 && Bytes.u8(d, 1) == 0x6F && Bytes.u8(d, 2) == 0x9A && Bytes.u8(d, 3) == 0x10 }
        if (hs20 && enterprise) return "Public WiFi hotspot (Passpoint)" to "Passpoint / Hotspot 2.0 elements in the beacon"
        // Interworking access network type (low 4 bits): 2 chargeable public, 3 free public.
        val access = ies.firstOrNull { it.first == 107 && it.second.isNotEmpty() }?.let { Bytes.u8(it.second, 0) and 0x0F }
        if ((access == 2 || access == 3) && (enterprise || open) && !hidden) {
            return "Public WiFi hotspot" to "beacon's Interworking element: ${if (access == 2) "paid" else "free"} public network"
        }
        val business = maker(BUSINESS_AP_MAKERS, macVendor, *ap.ieVendors.toTypedArray())
        if (enterprise) return "Business WiFi (account sign-in)" + (business?.let { " - $it" } ?: "") to
            "WPA-Enterprise security: people sign in with an account (offices, schools, hotels)"
        val wpsText = listOfNotNull(ap.manufacturer, ap.modelName, ap.modelNumber, ap.deviceName).joinToString(" ")
        ISP_ROUTER_MODELS.firstOrNull { it.first.containsMatchIn(wpsText) }?.let { (_, name) ->
            return "Home router - $name" + (ap.model?.let { " ($it)" } ?: "") to "router model in the beacon's WPS data"
        }
        if (business != null) return "Business access point - $business" to "business WiFi maker (address or beacon tags)"
        maker(ISP_ROUTER_MAKERS, macVendor, ap.manufacturer)?.let {
            return "Home router (internet provider's) - ${ap.model ?: it}" to "maker of the routers internet providers hand out (address or WPS)"
        }
        maker(HOME_ROUTER_MAKERS, macVendor, ap.manufacturer)?.let {
            return "Home router - ${ap.model ?: it}" to "home router maker (address or WPS)"
        }
        if (ap.deviceKind == "Router" || ap.deviceKind == "Gateway") {
            return "Home router" + (ap.model?.let { " - $it" } ?: "") to "WPS device kind \"${ap.deviceKind}\""
        }
        if (!hidden && open) return "Open WiFi (public or guest)" to "no password"
        return null
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

    /** Device distances in feet (and miles past 1000 ft): the app's units ([com.rfsentinel.app.util.Units]). */
    var imperial: Boolean
        get() = com.rfsentinel.app.util.Units.imperial
        set(v) { com.rfsentinel.app.util.Units.imperial = v }

    fun applyUnits(context: android.content.Context, locale: java.util.Locale = java.util.Locale.getDefault()) =
        com.rfsentinel.app.util.Units.apply(context, locale)

    fun formatDistance(m: Double): String = formatDistance(m, imperial)

    fun length(m: Double, feet: Boolean = imperial): String = com.rfsentinel.app.util.Units.length(m, feet)
    fun speed(ms: Double, feet: Boolean = imperial): String = com.rfsentinel.app.util.Units.speed(ms, feet)

    fun formatDistance(m: Double, feet: Boolean): String = if (!feet) when {
        m < 1 -> "< 1 m"
        m < 10 -> "~${m.roundToInt()} m"
        else -> "~${(m / 5).roundToInt() * 5} m"
    } else {
        val ft = m * 3.28084
        when {
            ft < 3 -> "< 3 ft"
            ft < 30 -> "~${ft.roundToInt()} ft"
            ft < 1000 -> "~${(ft / 10).roundToInt() * 10} ft"
            else -> "~" + String.format(java.util.Locale.US, "%.1f mi", m / 1609.344)
        }
    }
}
