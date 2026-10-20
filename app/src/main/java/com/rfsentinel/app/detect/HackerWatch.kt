package com.rfsentinel.app.detect

/**
 * Card skimmers and attack tools: exact signatures (Flipper Zero, Pwnagotchi, WiFi Pineapple,
 * ESP deauthers, Bluetooth card-skimmer modules) plus behaviours that need memory across
 * adverts: an evil-twin access point, a Bluetooth pairing-pop-up spam flood and a fake-tracker flood.
 * Signatures and their grading follow SquachWatch's DETECTIONS.md (GPL-3.0), re-checked
 * against the IEEE and Bluetooth SIG registries. Receive-only, like the rest of the app.
 */
object HackerWatch {

    private const val SRC = "SquachWatch DETECTIONS.md; Bluetooth SIG / IEEE registries"

    private val SKIMMER_NAME = Regex("^(HC-0[356]|RN42|BT04-A)\\b", RegexOption.IGNORE_CASE)
    private const val UUID_SPP = 0x1101
    private val FLIPPER_UUIDS = setOf(0x3081, 0x3082, 0x3083) // one per case colour
    private const val CID_FLIPPER = 0x0E29                     // Flipper Devices Inc. (not 0x0FBA: that's Cosonic)
    private const val OUI_FLIPPER = "0CFA22"
    private val HAK5_LA = setOf("02C0CA", "021337")
    /**
     * STMicroelectronics' STM32WB Bluetooth chips build their address from these prefixes (not in the
     * IEEE register). A Flipper Zero is one, but so are other STM32WB gadgets: a hint only.
     */
    private val STM32WB = setOf("80E126", "80E127")

    // ---- Action cameras (Category.OTHER_CAMERA) ------------------------------------------------
    private const val CID_ARASHI = 0x10D7   // Arashi Vision Inc. (Insta360)
    private const val UUID_ARASHI = 0xFC30  // Arashi Vision Inc. (Insta360)
    private const val CID_GOPRO = 0x02F2    // GoPro, Inc.
    private val UUID_GOPRO = setOf(0xFEA5, 0xFEA6) // GoPro, Inc.
    private val OTHER_CAMERA_PREFIXES = listOf("044169", "045747", "2474F7", "AC04AA", "D43260", "D4D919", "D89685", "F4DD9E")
        .associateWith { "GoPro camera" } + mapOf(
        "F05582" to "Insta360 camera",
        "201F55" to "DJI Osmo camera",          // DJI Osmo Technology (Osmo Action / Pocket / 360)
        "5870C6" to "YI camera"                 // Shanghai Xiaoyi (YI action and home cameras)
    )

    /**
     * Setup hotspots of cheap WiFi spy cameras (clock, charger, smoke-detector and mini cams).
     * They broadcast these while unconfigured or in hotspot mode; once joined to a home
     * network they look like any other device. V380: "MV" + 8 digits; LookCam Pro:
     * DGK-XXXXXX-XXXXX; TinyCam Pro: AI_XXXXXXX; HDSmartIPC: AI + 12 characters
     * (V380 setup guide; SpyGearGadgets setup notes); generic IP cameras: "IPC-" / "IPC_" + an ID
     * (Wardrive Go's hidden-camera list).
     */
    private class SetupSsid(val ssid: Regex, val app: String, val confidence: Int, val source: String)
    private const val SPY_SETUP = "V380 setup guide; SpyGearGadgets setup notes"
    private val HIDDEN_CAMERA_SSIDS = listOf(
        SetupSsid(Regex("^MV\\d{8}$"), "V380", 75, SPY_SETUP),
        SetupSsid(Regex("^DGK-[A-Z0-9]{6}-[A-Z0-9]{5}$", RegexOption.IGNORE_CASE), "LookCam Pro", 80, SPY_SETUP),
        SetupSsid(Regex("^AI_[A-Z0-9]{6,12}$", RegexOption.IGNORE_CASE), "TinyCam Pro", 60, SPY_SETUP),
        SetupSsid(Regex("^AI[A-Z0-9]{12}$"), "HDSmartIPC", 55, SPY_SETUP),
        SetupSsid(Regex("^IPC[-_][A-Z0-9]{4,12}$", RegexOption.IGNORE_CASE), "generic IP camera", 50,
            "Wardrive Go hidden-camera list (facts only)")
    )

    /** Default WiFi / Bluetooth names of action cameras whose makers use generic chips. */
    private val ACTION_CAMERA_NAMES = listOf(
        Regex("osmo ?(action|pocket|360|nano)", RegexOption.IGNORE_CASE) to "DJI Osmo camera",
        Regex("^(hero\\d{1,2}\\b|gp\\d{8}$)", RegexOption.IGNORE_CASE) to "GoPro camera",
        Regex("^(sjcam|sj\\d{1,2}(pro|air|c)?\\b|sj\\d{4})", RegexOption.IGNORE_CASE) to "SJCAM camera",
        Regex("akaso", RegexOption.IGNORE_CASE) to "AKASO camera",
        Regex("^(ydxj_|yi[ _-]?(4k|lite|action|discovery))", RegexOption.IGNORE_CASE) to "YI action camera",
        Regex("^virb", RegexOption.IGNORE_CASE) to "Garmin VIRB camera",
        Regex("^theta", RegexOption.IGNORE_CASE) to "Ricoh THETA 360 camera",
        Regex("pixpro", RegexOption.IGNORE_CASE) to "Kodak PIXPRO camera",
        Regex("(hdr-as|fdr-x)\\d", RegexOption.IGNORE_CASE) to "Sony action camera",
        Regex("apeman|campark|crosstour|victure|dragon ?touch", RegexOption.IGNORE_CASE) to "Action camera"
    )

    private fun otherCameras(a: Advert, name: String, prefix: String): Hit? {
        val reg = "IEEE / Bluetooth SIG registries"
        if (a.source == Advert.Source.BLE) {
            val shorts = a.serviceUuids.mapNotNull { Advert.shortOf(it) } + a.serviceData.keys.mapNotNull { Advert.shortOf(it) }
            if (CID_ARASHI in a.manufacturerData) return Hit(Category.OTHER_CAMERA, "Insta360 camera", 85,
                "Company ID 0x10D7 (Arashi Vision, maker of Insta360)", reg)
            if (UUID_ARASHI in shorts) return Hit(Category.OTHER_CAMERA, "Insta360 camera", 85,
                "Service UUID 0xFC30 (Arashi Vision, maker of Insta360)", reg)
            if (name.contains("Insta360", ignoreCase = true)) return Hit(Category.OTHER_CAMERA, "Insta360 camera", 70,
                "Advertised name \"$name\"", reg)
            if (CID_GOPRO in a.manufacturerData) return Hit(Category.OTHER_CAMERA, "GoPro camera", 85,
                "Company ID 0x02F2 (GoPro, Inc.)", reg)
            if (shorts.any { it in UUID_GOPRO }) return Hit(Category.OTHER_CAMERA, "GoPro camera", 85,
                "GoPro service UUID 0xFEA5 / 0xFEA6", reg)
            if (name.startsWith("GoPro", ignoreCase = true)) return Hit(Category.OTHER_CAMERA, "GoPro camera", 70,
                "Advertised name \"$name\"", reg)
        }
        if (a.source == Advert.Source.WIFI) HIDDEN_CAMERA_SSIDS.firstOrNull { it.ssid.matches(name) }?.let { c ->
            return Hit(Category.OTHER_CAMERA, "Hidden / spy camera (${c.app} type)", c.confidence,
                "WiFi network \"$name\": the setup hotspot of cheap ${c.app} WiFi cameras, often sold hidden in clocks, " +
                    "chargers and smoke detectors. Some are ordinary indoor cameras - check the room.", c.source)
        }
        if (name.isNotEmpty()) ACTION_CAMERA_NAMES.firstOrNull { it.first.containsMatchIn(name) }?.let { (_, label) ->
            return Hit(Category.OTHER_CAMERA, label, 65, "${if (a.source == Advert.Source.WIFI) "WiFi network" else "Advertised name"} " +
                "\"$name\" (the camera's default name - names can be changed)", "Manufacturer defaults")
        }
        OTHER_CAMERA_PREFIXES[prefix]?.let { label ->
            return Hit(Category.OTHER_CAMERA, label, 75, "Address in the registered block ${prefix.chunked(2).joinToString(":")} " +
                "(${VendorDb.macVendor(a.mac) ?: label})", reg)
        }
        return null
    }

    /** Per-advert signatures (no memory). */
    fun signatures(a: Advert): List<Hit> {
        val hits = ArrayList<Hit>()
        val name = a.name?.trim().orEmpty()
        val prefix = a.mac.replace(":", "").uppercase().take(6)
        otherCameras(a, name, prefix)?.let { hits += it }
        if (a.source == Advert.Source.BLE) {
            val shorts = a.serviceUuids.mapNotNull { Advert.shortOf(it) }.toSet()
            if (SKIMMER_NAME.containsMatchIn(name)) {
                hits += Hit(Category.SKIMMER, "Possible card skimmer (Bluetooth module)", 70,
                    "Advertised name \"$name\": the default name of the cheap serial modules built into card skimmers " +
                        "(gas pumps, ATMs). Hobby projects use them too - check the payment terminal.", SRC)
            } else if (UUID_SPP in shorts) {
                hits += Hit(Category.SKIMMER, "Possible card skimmer (Bluetooth module)", 50,
                    "Advertises the serial-port service 0x1101 used by skimmer modules - verify", SRC)
            }
            when {
                shorts.any { it in FLIPPER_UUIDS } -> hits += Hit(Category.HACKER, "Flipper Zero", 90,
                    "Flipper's own Bluetooth service UUID (one per case colour)", SRC)
                CID_FLIPPER in a.manufacturerData -> hits += Hit(Category.HACKER, "Flipper Zero", 90,
                    "Company ID 0x0E29 (Flipper Devices Inc.)", SRC)
                prefix == OUI_FLIPPER -> hits += Hit(Category.HACKER, "Flipper Zero", 85,
                    "Address in Flipper Devices' registered block 0C:FA:22", SRC)
                name.startsWith("Flipper ") -> hits += Hit(Category.HACKER, "Flipper Zero", 60,
                    "Advertised name \"$name\" (owners can rename it - verify)", SRC)
                prefix in STM32WB -> hits += Hit(Category.HACKER, "Possible Flipper Zero (STM32WB chip)", 30,
                    "Address ${prefix.chunked(2).joinToString(":")}: an STMicroelectronics STM32WB Bluetooth chip, which a " +
                        "Flipper Zero uses - so do other gadgets, weak", "SignalSweep research (GPL-3.0; facts only)")
            }
        } else if (a.source == Advert.Source.WIFI) {
            pwnagotchiName(a)?.let { n ->
                hits += Hit(Category.HACKER, "Pwnagotchi (WiFi handshake grabber)" + if (n.isNotEmpty()) ": $n" else "", 90,
                    "Its beacons carry the pwnagotchi peer-discovery JSON (pwnd_tot)", SRC)
            }
            when {
                name.startsWith("Pineapple_") -> hits += Hit(Category.HACKER, "WiFi Pineapple", 60,
                    "Management network \"$name\" (Hak5 WiFi Pineapple)", SRC)
                name.startsWith("pwned") -> hits += Hit(Category.HACKER, "WiFi deauther", 55,
                    "Control network \"$name\" (ESP8266 / ESP32 deauther default)", SRC)
                prefix in HAK5_LA -> hits += Hit(Category.HACKER, "Possible Hak5 device", 30,
                    "Locally administered address ${a.mac.take(8)} that Hak5 gear uses - anyone can set it, weak", SRC)
            }
        }
        return hits
    }

    /** The name in a pwnagotchi's beacon JSON ("" if absent), or null if this isn't one. */
    internal fun pwnagotchiName(a: Advert): String? {
        val ies = a.wifi?.infoElements ?: return null
        for ((id, data) in ies) {
            if (id != 221) continue
            val text = String(data, Charsets.ISO_8859_1)
            if (!text.contains("pwnd_tot")) continue
            val n = Regex("\"name\"\\s*:\\s*\"([^\"\\\\]{1,32})\"").find(text)?.groupValues?.get(1).orEmpty()
            return n.filter { it.code in 32..126 && it != '"' }
        }
        return null
    }

    // ---- evil twin ------------------------------------------------------------------------

    private class Ap(val bssid: String, var secured: Boolean)
    private val bySsid = LinkedHashMap<String, MutableList<Ap>>()
    private val twinsFlagged = HashSet<String>()

    /**
     * Same network name from two different makers' hardware that disagree on security (one
     * open, one protected): one of them isn't what it claims to be. Mesh nodes and dual-band
     * boxes share a maker and are never flagged. Phone scans only (they report security).
     */
    @Synchronized
    fun evilTwin(a: Advert): Hit? {
        val w = a.wifi ?: return null
        if (w.client || w.capabilities.isBlank()) return null
        val ssid = a.name?.trim().orEmpty()
        if (ssid.isEmpty()) return null
        val secured = Regex("WPA|WEP|RSN|SAE|OWE").containsMatchIn(w.capabilities)
        val list = bySsid.getOrPut(ssid) { ArrayList() }
        if (bySsid.size > 400) bySsid.remove(bySsid.keys.first())
        list.firstOrNull { it.bssid == a.mac }?.let { it.secured = secured; return null }
        if (list.size >= 64) list.removeAt(0) // a hotspot chain ("xfinitywifi") has thousands of access points
        val rogue = list.any { !sameMaker(it.bssid, a.mac) && it.secured != secured }
        list += Ap(a.mac, secured)
        if (!rogue || !twinsFlagged.add(ssid + "|" + a.mac)) return null
        return Hit(Category.HACKER, "Possible evil twin WiFi network", 60,
            "\"$ssid\" is broadcast by two different makers' hardware, one ${if (secured) "protected" else "OPEN"} and one " +
                "${if (secured) "open" else "protected"}: one copy may be a fake set up to catch logins. Don't join it.", SRC)
    }

    private fun sameMaker(m1: String, m2: String): Boolean {
        fun key(m: String): String = m.replace(":", "").uppercase().take(6)
        if (key(m1) == key(m2)) return true
        val v1 = VendorDb.macVendor(m1)
        return v1 != null && v1 == VendorDb.macVendor(m2)
    }

    // ---- Bluetooth spam flood -------------------------------------------------------------

    /** Pairing-popup addresses heard in the last minute, oldest first, so one address counts once. */
    private val spamSeen = LinkedHashMap<String, Long>()
    private var lastFlood = Long.MIN_VALUE / 2
    const val SPAM_THRESHOLD = 50

    /**
     * Called once per newly heard Bluetooth address. A flood of new addresses sending
     * pairing pop-ups (Apple proximity pairing / nearby action, Google Fast Pair, Microsoft
     * Swift Pair) is what Flipper Zero and ESP32 "BLE spam" attacks look like. An address already
     * counted is skipped, even if the caller sees it as new again (its classification cache cleared).
     */
    @Synchronized
    fun bleSpam(a: Advert, now: Long): Hit? {
        if (a.source != Advert.Source.BLE || !isPairingPopup(a)) return null
        val it = spamSeen.values.iterator()
        while (it.hasNext() && now - it.next() > 60_000L) it.remove()
        if (spamSeen.containsKey(a.mac)) return null
        spamSeen[a.mac] = now
        if (spamSeen.size < SPAM_THRESHOLD || now - lastFlood < 10 * 60_000L) return null
        lastFlood = now
        return Hit(Category.HACKER, "Bluetooth spam attack nearby", 75,
            "${spamSeen.size} new devices sent pairing pop-ups in one minute - typical of a Flipper Zero / ESP32 " +
                "Bluetooth spam attack. Ignore pairing requests you didn't start.", SRC)
    }

    internal fun isPairingPopup(a: Advert): Boolean {
        a.manufacturerData[0x004C]?.let { d -> if (d.isNotEmpty() && (d[0].toInt() == 0x07 || d[0].toInt() == 0x0F)) return true }
        a.manufacturerData[0x0006]?.let { d -> if (d.isNotEmpty() && d[0].toInt() == 0x03) return true } // Swift Pair
        return a.serviceData.keys.any { Advert.shortOf(it) == 0xFE2C } // Google Fast Pair
    }

    // ---- Fake-tracker flood ---------------------------------------------------------------

    /** A flood of fake [network] trackers: [addresses] throwaway addresses in the last [windowS] s, [sample] one of them. */
    data class TagFlood(val network: String, val addresses: Int, val sample: String, val windowS: Int)

    /** A real tag keeps its address for a long time; a fake one is heard a couple of times, briefly. */
    const val FLOOD_MAX_HITS = 3
    const val FLOOD_MAX_SPAN_MS = 10_000L
    /** Quiet this long and an address counts as gone. */
    const val FLOOD_QUIET_MS = 30_000L
    /** This many gone short-lived addresses of one network within [FLOOD_WINDOW_MS] is a flood. */
    const val FLOOD_EVIDENCE = 6
    const val FLOOD_WINDOW_MS = 5 * 60_000L
    /** Weaker than this doesn't count: real tags in passing cars are short-lived too, but far. */
    const val FLOOD_MIN_RSSI = -80
    private const val FLOOD_MAX_ADDRESSES = 64

    private class TagAddr(val first: Long, var last: Long, var hits: Int, var best: Int)
    private class Network {
        val addrs = LinkedHashMap<String, TagAddr>()
        /** When each short-lived address went quiet, by address. */
        val evidence = LinkedHashMap<String, Long>()
        var lastReport = Long.MIN_VALUE / 2
    }
    private val networks = HashMap<String, Network>()

    /**
     * Which finding network a tracker advert belongs to, or null. Only adverts no phone sends:
     * Apple's separated Find My frame, Google Find Hub frames, Samsung SmartTag and Tile services.
     */
    internal fun trackerNetwork(a: Advert): String? {
        if (a.source != Advert.Source.BLE) return null
        a.manufacturerData[0x004C]?.let { d -> if (d.size >= 2 && Bytes.u8(d, 0) == 0x12 && Bytes.u8(d, 1) == 0x19) return "Apple Find My" }
        a.serviceData[Advert.uuid16(0xFEAA)]?.let { d -> if (d.isNotEmpty() && Bytes.u8(d, 0) in 0x40..0x41) return "Google Find Hub" }
        val shorts = a.serviceData.keys.mapNotNull { Advert.shortOf(it) }
        if (shorts.any { it == 0xFD5A || it == 0xFD59 }) return "Samsung SmartTag"
        if (shorts.any { it == 0xFEED || it == 0xFEEC }) return "Tile"
        return null
    }

    /**
     * Every tracker advert. Tools like a Flipper Zero flood an area with fake trackers, each on a
     * throwaway address: heard once or twice, then never again. Real tags keep theirs. One alarm says
     * what's going on (and again every few minutes while it lasts); real trackers are still reported
     * as ever. The approach and thresholds follow SignalSweep's field-tuned detector (GPL-3.0; the idea,
     * not code).
     */
    @Synchronized
    fun tagFlood(a: Advert, now: Long): TagFlood? {
        val kind = trackerNetwork(a) ?: return null
        val n = networks.getOrPut(kind) { Network() }
        fun gone(mac: String, t: TagAddr, at: Long) {
            val fake = t.hits <= FLOOD_MAX_HITS && t.last - t.first <= FLOOD_MAX_SPAN_MS && t.best >= FLOOD_MIN_RSSI
            if (fake && mac !in n.evidence) n.evidence[mac] = at
        }
        val iter = n.addrs.entries.iterator()
        while (iter.hasNext()) {
            val (mac, t) = iter.next()
            if (mac != a.mac && now - t.last >= FLOOD_QUIET_MS) { gone(mac, t, t.last + FLOOD_QUIET_MS); iter.remove() }
        }
        val t = n.addrs[a.mac]
        if (t != null) { t.last = now; t.hits++; if (a.rssi > t.best) t.best = a.rssi }
        else {
            if (n.addrs.size >= FLOOD_MAX_ADDRESSES) n.addrs.entries.first().let { (mac, old) -> gone(mac, old, now); n.addrs.remove(mac) }
            n.addrs[a.mac] = TagAddr(now, now, 1, a.rssi)
        }
        n.evidence.entries.removeAll { now - it.value > FLOOD_WINDOW_MS }
        if (n.evidence.size < FLOOD_EVIDENCE || now - n.lastReport < FLOOD_WINDOW_MS) return null
        n.lastReport = now
        return TagFlood(kind, n.evidence.size, n.evidence.keys.last(), (FLOOD_WINDOW_MS / 1000).toInt())
    }

    @Synchronized
    fun reset() {
        bySsid.clear(); twinsFlagged.clear(); spamSeen.clear(); lastFlood = Long.MIN_VALUE / 2; networks.clear()
    }
}
