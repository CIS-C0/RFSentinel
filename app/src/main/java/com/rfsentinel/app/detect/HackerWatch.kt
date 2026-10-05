package com.rfsentinel.app.detect

/**
 * Card skimmers and attack tools: exact signatures (Flipper Zero, Pwnagotchi, WiFi Pineapple,
 * ESP deauthers, Bluetooth card-skimmer modules) plus two behaviours that need memory across
 * adverts: an evil-twin access point and a Bluetooth pairing-pop-up spam flood.
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

    /** Per-advert signatures (no memory). */
    fun signatures(a: Advert): List<Hit> {
        val hits = ArrayList<Hit>()
        val name = a.name?.trim().orEmpty()
        val prefix = a.mac.replace(":", "").uppercase().take(6)
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

    private val spamTimes = ArrayDeque<Long>()
    private var lastFlood = Long.MIN_VALUE / 2
    const val SPAM_THRESHOLD = 50

    /**
     * Called once per newly heard Bluetooth address. A flood of new addresses sending
     * pairing pop-ups (Apple proximity pairing / nearby action, Google Fast Pair, Microsoft
     * Swift Pair) is what Flipper Zero and ESP32 "BLE spam" attacks look like.
     */
    @Synchronized
    fun bleSpam(a: Advert, now: Long): Hit? {
        if (a.source != Advert.Source.BLE || !isPairingPopup(a)) return null
        spamTimes.addLast(now)
        while (spamTimes.isNotEmpty() && now - spamTimes.first() > 60_000L) spamTimes.removeFirst()
        if (spamTimes.size < SPAM_THRESHOLD || now - lastFlood < 10 * 60_000L) return null
        lastFlood = now
        return Hit(Category.HACKER, "Bluetooth spam attack nearby", 75,
            "${spamTimes.size} new devices sent pairing pop-ups in one minute - typical of a Flipper Zero / ESP32 " +
                "Bluetooth spam attack. Ignore pairing requests you didn't start.", SRC)
    }

    internal fun isPairingPopup(a: Advert): Boolean {
        a.manufacturerData[0x004C]?.let { d -> if (d.isNotEmpty() && (d[0].toInt() == 0x07 || d[0].toInt() == 0x0F)) return true }
        a.manufacturerData[0x0006]?.let { d -> if (d.isNotEmpty() && d[0].toInt() == 0x03) return true } // Swift Pair
        return a.serviceData.keys.any { Advert.shortOf(it) == 0xFE2C } // Google Fast Pair
    }

    @Synchronized
    fun reset() {
        bySsid.clear(); twinsFlagged.clear(); spamTimes.clear(); lastFlood = Long.MIN_VALUE / 2
    }
}
