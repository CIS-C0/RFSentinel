package com.rfsentinel.app.detect

import java.util.UUID

/**
 * Built-in payload / name / UUID / SSID signatures. MAC-prefix signatures for
 * law-enforcement vendors live in the editable OUI watchlist presets instead
 * (assets/oui_presets), so users can see and toggle them.
 *
 * Every row cites where it comes from; docs/SIGNATURES.md has the long-form
 * provenance. Confidence follows the evidence-quality ladder of the
 * all-cameras-are-beacons signature reference (Apache-2.0): payload tags and
 * vendor self-attesting SSIDs rank highest, SIG vendor IDs next, bare shared
 * identifiers stay in the "weak - verify" band (< 50).
 *
 * All company IDs and 16-bit UUIDs below were checked against the Bluetooth SIG
 * assigned-numbers lists, and all MAC prefixes against the IEEE registry
 * (see tools/gen_assets.py, which fails loudly on a mismatch).
 */
object SignatureEngine {

    const val APPLE_FIND_MY_LABEL = "Apple Find My tracker away from its owner (AirTag or compatible)"

    /** An Apple Find My tag match (AirTag or compatible), for Settings > Exclude AirTags. */
    fun isAppleFindMy(hit: Hit) = hit.category == Category.TRACKER && hit.label.startsWith("Apple Find My")


    private const val ACAB = "all-cameras-are-beacons signature reference (Apache-2.0)"
    private const val SIG = "Bluetooth SIG assigned numbers"
    private const val IEEE = "IEEE registry"
    private const val ASTM = "ASTM F3411 Remote ID (opendroneid-core-c, Apache-2.0)"
    private const val SIGNALSWEEP = "SignalSweep research (GPL-3.0; facts only, no code)"
    private const val FLIPDEFLOCK = "FlipDeFlock (GPL-3.0) probe IE fingerprints"

    /** FlipDeFlock's candidate Flock probe shapes ([com.rfsentinel.app.usb.ProbeIntel.skeletonHash]), single-report. */
    private val FLOCK_PROBE_SHAPES = mapOf(
        0x42D75CD1L to "seen on a Flock camera's 70:C9:4E module",
        0xD0BBEC4CL to "seen on Flock 24:B2:B9 and 70:08:94 modules"
    )

    /** Probe shapes FlipDeFlock found on ordinary phones and ESP32 boards: never a camera sign. */
    private val COMMON_PROBE_SHAPES = setOf(0x96FCD1B2L, 0x173D7A70L, 0x7C923B53L, 0x89C3DEBFL, 0xC59C341FL, 0xBA9FAFA0L)

    /** What a probe shape is known as, for the detail screen. */
    fun probeShapeNote(shape: Long): String? = FLOCK_PROBE_SHAPES[shape]?.let { "same as a Flock camera's ($it), unconfirmed" }
        ?: if (shape in COMMON_PROBE_SHAPES) "common to phones and ESP32 boards" else null

    // --- Bluetooth SIG company IDs (manufacturer data, AD 0xFF) ---------------
    private const val CID_APPLE = 0x004C
    private const val CID_TASER = 0x034D           // TASER International, Inc.
    private const val CID_MOTOROLA = 0x04EC        // Motorola Solutions
    private const val CID_XUNTONG = 0x09C8         // XUNTONG (BT module in Flock hardware)
    private const val CID_LUXOTTICA = 0x0D53       // Luxottica Group S.p.A (Ray-Ban Meta)
    private const val CID_SNAPCHAT = 0x03C2        // Snapchat Inc (Spectacles)
    private const val CID_VUZIX = 0x060C           // Vuzix Corporation
    private const val CID_META = 0x01AB            // Meta Platforms, Inc.
    private const val CID_META_TECH = 0x058E       // Meta Platforms Technologies (shared with Quest)
    private const val CID_ZEBRA = 0x01F1           // Zebra Technologies Corporation
    private const val CID_BROTHER = 0x0755         // Brother Industries, Ltd
    private const val CID_DRAEGER = 0x04BC         // Draegerwerk AG & Co. KGaA
    private const val CID_STRYKER = 0x0A5F         // Stryker (LIFEPAK maker since buying Physio-Control)

    // --- 16-bit service UUIDs ------------------------------------------------
    private val AXON_UUIDS = mapOf(0xFC81 to "Axon Enterprise", 0xFE6B to "TASER International", 0xFE6C to "TASER International")
    private val MOTOROLA_UUIDS = setOf(0xFD8E, 0xFE04)
    private val RAVEN_SHORTS = mapOf(0x3100 to "GPS", 0x3101 to "GPS", 0x3102 to "GPS", 0x3200 to "power", 0x3300 to "network",
        0x3400 to "upload", 0x3500 to "error")
    /** Flock's accessory GATT service, from a Flock camera's firmware (Flock-You issue #15, SquachWatch). */
    private val FLOCK_GATT = UUID.fromString("e8ccbb38-9532-46a8-9fe5-1814df172e6f")
    /** The same battery's second Flock service (the "safety" one), same source. */
    private val FLOCK_GATT_2 = UUID.fromString("20c944c1-add2-42d7-a638-967ee9a26ff6")
    private const val UUID_TILE_2 = 0xFEEC         // Tile, Inc. (second SIG-assigned UUID)

    /**
     * Plate-reader / traffic-enforcement makers by IEEE registration (ACAB alpr_candidates):
     * Jenoptik, Kapsch, Elsag (Leonardo) and its predecessor Selex, Neology, Redflex, Ubicquia
     * (streetlight nodes that host ALPR cameras). Their gear rarely shows over the air, but
     * when it does it's enforcement hardware.
     */
    private val ALPR_MAKER_PREFIXES = PrefixTable(mapOf(
        "00044C" to "Jenoptik", "48E3C3" to "Jenoptik", "00E06A" to "Kapsch", "0040DE" to "Elsag (Leonardo)",
        "70B3D51C0" to "Elsag (Leonardo)", "70B3D5520" to "Selex ES (Elsag)", "70B3D5F50" to "Selex ES (Elsag)",
        "00173D" to "Neology", "00307E" to "Redflex", "947BBE" to "Ubicquia"
    ))

    /**
     * Camera / security makers matched by the IEEE registrant name, so every prefix they hold
     * counts (Hikvision alone has 100+), not just a hand-picked few.
     */
    private val CAMERA_MAKER_NAME = Regex(
        "hikvision|zhejiang dahua|ezviz|lorex|amcrest|swann communications|wyze labs|^ring llc|arlo technologies|reolink|" +
            "vivint|simplisafe|uniview|vivotek|avigilon|verkada|geovision|mobotix|sunell|march networks|" +
            "ava security|hanwha vision|hanwha techwin|samsung techwin|axis communications|" +
            "fantasia trading|shenzhen phaten|tp-link.*tapo",
        RegexOption.IGNORE_CASE
    )
    private const val UUID_REMOTE_ID = 0xFFFA      // ASTM Remote ID (SIG SDO UUID)
    private const val UUID_SMARTTAG = 0xFD5A       // Samsung Electronics
    private const val UUID_SMARTTAG_SETUP = 0xFD59 // Samsung Electronics (a SmartTag not yet registered)
    private const val UUID_TILE = 0xFEED           // Tile, Inc.
    private const val UUID_CHIPOLO = 0xFE33        // CHIPOLO d.o.o. (as AirGuard, SEEMOO / TU Darmstadt, Apache-2.0)
    private const val UUID_GOOGLE_FMDN = 0xFEAA    // Google LLC (Eddystone / Find Hub)
    private const val UUID_SPECTACLES = 0xFE45     // Snapchat Inc
    private val META_UUIDS = setOf(0xFEB7, 0xFEB8) // Meta Platforms, Inc.
    private val ZEBRA_UUIDS = setOf(0xFE79, 0xFD66) // Zebra Technologies (FE79 = Link-OS printer BLE service)
    private val FORTIN_UUIDS = setOf(0xFDE1, 0xFDCA) // Fortin Electronic Systems (vehicle interface modules)
    private const val UUID_DRAEGER = 0xFCDA        // Draeger
    private const val UUID_STRYKER = 0xFC6E        // Stryker
    private val ZOLL_UUIDS = setOf(0xFE22, 0xFE23) // Zoll Medical Corporation

    /** Ambulance defibrillator / monitor makers by IEEE registration (Wardrive Go's EMS list, registrants checked). */
    private val EMS_PREFIXES = PrefixTable(mapOf("40BD9E" to "Physio-Control (LIFEPAK)", "000DD1" to "Stryker"))

    /** HeyCyan smart-glasses SDK service; must match all 16 bytes (Apple ANCS differs by 2 bytes). */
    private val HEYCYAN = UUID.fromString("7905fff0-b5ce-4e99-a40f-4b1e122d00d0")
    private val HEYCYAN_REVERSED = reverse(HEYCYAN)

    // --- MAC prefixes (IEEE, verified by tools/gen_assets.py) -----------------
    private val DRONE_PREFIXES = PrefixTable(
        listOf("60601F", "34D262", "481CB9", "E47A2C", "58B858", "04A85A", "8C5823", "0C9AE6", "882985", "4C43F6")
            .associateWith { "DJI" } +
            listOf("9C5A8A", "EC72F7", "3491F0").associateWith { "DJI Baiwang (DJI subsidiary)" } +
            listOf("00121C", "00267E", "9003B7", "903AE6", "A0143D").associateWith { "Parrot" } +
            mapOf(
                "381D14" to "Skydio", "EC5BCDE" to "Autel Robotics", "E0B6F58" to "Yuneec",
                "EC715E" to "Freefly Systems", "B030C8" to "Teal Drones", "001AF9" to "AeroVironment",
                "8C1F64B07" to "AeroVironment", "34B5F32" to "Inspired Flight", "AC86D17" to "Quantum Systems",
                "8C1F640F1" to "ideaForge", "8C1F64A2D" to "ACSL", "74B80F" to "Zipline",
                "24A10D7" to "Cyon Drones", "B44D43A" to "UAV Navigation", "14DD48" to "Shield AI",
                "E8B470C" to "Anduril Industries"
            )
    )

    private val CAMERA_PREFIXES = PrefixTable(
        listOf("A41162", "FC9C98", "486264").associateWith { "Arlo" } +
            listOf("3CA070", "70AD43", "741348", "74AB93", "C819D8", "F074C1").associateWith { "Blink (Amazon)" } +
            // From Flock-You-Android / Fieldwatch / OUI-Spy lists, registrant checked against IEEE.
            listOf("CC47BD").associateWith { "Rhombus" } +
            listOf("B4A382", "4419B6", "54C415", "2857BE", "C056E3", "4CBD8F", "1868CB", "C42F90").associateWith { "Hikvision" } +
            listOf("E0508B", "3CEF8C", "4C11BF", "A0BD1D", "9002A9").associateWith { "Dahua" } +
            listOf("00408C", "ACCC8E", "B8A44F", "E82725").associateWith { "Axis" } +
            listOf("000918").associateWith { "Hanwha Vision (Wisenet)" } +
            listOf("44B423", "E43022").associateWith { "Hanwha Vision (Wisenet)" } +
            // Commercial / city surveillance camera makers, registrants checked against IEEE.
            listOf("E0A700").associateWith { "Verkada" } +
            listOf("701AD5").associateWith { "Avigilon Alta" } +
            listOf("001BD8", "00407F").associateWith { "FLIR" } +
            listOf("0010BE", "001281").associateWith { "March Networks" } +
            listOf("0013E2").associateWith { "GeoVision" } +
            listOf("0003C5").associateWith { "Mobotix" } +
            listOf("001C27").associateWith { "Sunell" } +
            listOf("000131", "000463", "50FACB9").associateWith { "Bosch Security" } +
            listOf("000322", "6C27C83").associateWith { "IDIS" } +
            listOf("48EA63", "6CF17E", "88263F", "C47905").associateWith { "Uniview" } +
            listOf("0002D1").associateWith { "Vivotek" } +
            listOf("9C8ECD").associateWith { "Amcrest" } +
            listOf("EC71DB").associateWith { "Reolink" } +
            listOf("2CAA8E", "D03F27").associateWith { "Wyze" } +
            listOf("0018AE").associateWith { "TVT" } +
            listOf("E8ABFA").associateWith { "Reecam" } +
            listOf("187F88", "242BD6", "343EA4", "54E019", "5C475E", "649A63", "90486C", "9C7613", "AC9FC3", "C4DBAD", "CC3BFB").associateWith { "Ring (Amazon)" } +
            mapOf(
                "38F25D" to "Ezviz", "14BA88" to "Uniview", "3446632" to "Amcrest", "A4DA222" to "Wyze",
                "0C0EC14" to "Swann", "542B57" to "Night Owl", "D0C193" to "SkyBell", "B0B3537" to "WUUK"
            )
    )

    private val PENGUIN = Regex("^Penguin-\\d+$")
    private val FS_HEX = Regex("^FS-[0-9A-Fa-f]+$")
    private val PENGUIN_NUMERIC = Regex("^\\d{8,12}$")
    /** Zebra's default Bluetooth name is the serial: 2-char plant, 3-letter model code, YYWW + 5 digits. */
    private val ZEBRA_SERIAL = Regex("^[A-Z0-9]{2}[A-Z]{3}\\d{9}$")

    /** IDO / VeryFit smartwatch model names: IDW26, ID205, ID207... */
    private val IDO_WATCH = Regex("^ID[A-Z]?\\d{2,4}[A-Z]?$")
    private const val UUID_IDO = 0x0AF0

    /**
     * Cheap smartwatches (IDO / VeryFit: IDW26 and friends) put company ID 0x034D in front of their own address
     * and advertise service 0x0AF0. Axon gear doesn't: its payload never repeats the address.
     */
    private fun isTaserIdSquatter(a: Advert, name: String, shorts: Set<Int>): Boolean {
        val payload = a.manufacturerData[CID_TASER] ?: return false
        val own = a.mac.split(':').mapNotNull { it.toIntOrNull(16)?.toByte() }
        val repeatsAddress = own.size == 6 && payload.size >= 6 && (0 until 6).all { payload[it] == own[it] }
        return repeatsAddress && (UUID_IDO in shorts || IDO_WATCH.matches(name))
    }

    fun classify(a: Advert): List<Hit> {
        val hits = (if (a.isBle) classifyBle(a) else classifyWifi(a)) + HackerWatch.signatures(a)
        return hits.sortedByDescending { it.confidence }
    }

    private fun classifyBle(a: Advert): List<Hit> {
        val hits = mutableListOf<Hit>()
        val name = a.name?.trim().orEmpty()
        val shorts = a.serviceUuids.mapNotNull { Advert.shortOf(it) }.toSet()
        val dataShorts = a.serviceData.keys.mapNotNull { Advert.shortOf(it) }.toSet()
        val allShorts = shorts + dataShorts

        // ---- Body cams -----------------------------------------------------
        val payloads = a.serviceData.values + a.manufacturerData.values
        if (payloads.any { Bytes.containsAscii(it, "BWCDEVICE") } || Bytes.containsAscii(a.rawBytes, "BWCDEVICE")) {
            hits += Hit(Category.BODY_CAM, "Axon body camera", 90,
                "Advert payload carries Axon's \"BWCDEVICE\" tag (works even with a randomized address)",
                "$ACAB - field-validated against visually confirmed cameras")
        }
        if (CID_TASER in a.manufacturerData && !isTaserIdSquatter(a, name, allShorts)) {
            hits += Hit(Category.BODY_CAM, "Axon / TASER equipment (type unknown)", 60,
                "Manufacturer data from company ID 0x034D (TASER International)",
                "$SIG; ID spans body cams, holster sensors, TASER handles and batteries")
        }
        AXON_UUIDS.keys.firstOrNull { it in allShorts }?.let { u ->
            hits += Hit(Category.BODY_CAM, "Axon / TASER equipment (type unknown)", 60,
                String.format("Service UUID 0x%04X (%s)", u, AXON_UUIDS[u]), SIG)
        }
        if (name.contains("BodyWorn Remote", ignoreCase = true)) {
            hits += Hit(Category.BODY_CAM, "Utility BodyWorn camera remote", 80,
                "Advertised name contains \"BodyWorn Remote\"",
                "$ACAB (nite-oui-collection capture)")
        }

        // ---- Motorola Solutions (radios, cameras, accessories) -------------
        if (CID_MOTOROLA in a.manufacturerData || MOTOROLA_UUIDS.any { it in allShorts }) {
            hits += Hit(Category.PUBLIC_SAFETY, "Motorola Solutions equipment (radio, camera or accessory)", 45,
                "Motorola Solutions company ID 0x04EC or service UUID 0xFD8E/0xFE04",
                "$SIG; the same IDs are used by retail, school and venue two-way radios")
        }

        // ---- Mobile ticket printers (e-citations) ---------------------------
        ZEBRA_UUIDS.firstOrNull { it in allShorts }?.let { u ->
            hits += if (ZEBRA_SERIAL.matches(name)) {
                Hit(Category.PUBLIC_SAFETY, "Zebra mobile printer (e-ticket printer in patrol cars)", 50,
                    String.format("Service UUID 0x%04X (Zebra)", u) +
                        " and a factory serial-number name \"$name\" - an unrenamed " +
                        "fleet printer. Also used by parking officers, couriers and field technicians",
                    "$SIG; Zebra Link-OS BLE app note (default friendly name = serial); field capture")
            } else {
                Hit(Category.PUBLIC_SAFETY, "Zebra printer", 30,
                    String.format("Service UUID 0x%04X (Zebra Technologies)", u) +
                        " - common in warehouses, stores and deliveries", SIG)
            }
        }

        if (CID_ZEBRA in a.manufacturerData && ZEBRA_UUIDS.none { it in allShorts }) {
            hits += Hit(Category.PUBLIC_SAFETY, "Zebra device (mobile printer or handheld)", 25,
                "Zebra Technologies company ID 0x01F1 - mobile printers (incl. e-ticket printers) but also " +
                    "store scanners and handhelds", SIG)
        }
        if (CID_BROTHER in a.manufacturerData) {
            hits += Hit(Category.PUBLIC_SAFETY, "Brother printer", 20,
                "Brother Industries company ID 0x0755 - RuggedJet / PocketJet in-car printers, but mostly " +
                    "home and office printers", SIG)
        }

        // ---- Vehicle and impairment-testing gear ----------------------------
        if (FORTIN_UUIDS.any { it in allShorts }) {
            hits += Hit(Category.PUBLIC_SAFETY, "Fortin vehicle interface module", 20,
                "Fortin Electronic Systems service UUID 0xFDE1/0xFDCA - interface modules used when upfitting " +
                    "police vehicles, but also remote starters in many ordinary cars", SIG)
        }
        if (CID_DRAEGER in a.manufacturerData || UUID_DRAEGER in allShorts) {
            hits += Hit(Category.PUBLIC_SAFETY, "Dräger device (breath / drug screening or medical)", 35,
                "Draegerwerk company ID 0x04BC or service UUID 0xFCDA - Dräger makes the roadside breath " +
                    "(Alcotest) and drug (DrugTest) screening devices police use, and hospital and gas-detection gear", SIG)
        }

        // ---- Ambulance (EMS) medical gear: often at the same scenes as police ----
        if (ZOLL_UUIDS.any { it in allShorts }) {
            hits += Hit(Category.PUBLIC_SAFETY, "ZOLL medical device (ambulance defibrillator / monitor)", 40,
                "Zoll Medical service UUID 0xFE22/0xFE23 - ZOLL X Series and AED monitors in ambulances, but also " +
                    "hospital gear and the LifeVest a heart patient can wear", "$SIG; Wardrive Go's EMS list (facts only)")
        }
        if (CID_STRYKER in a.manufacturerData || UUID_STRYKER in allShorts) {
            hits += Hit(Category.PUBLIC_SAFETY, "Stryker medical device (LIFEPAK defibrillator, ambulance stretcher)", 35,
                "Stryker company ID 0x0A5F or service UUID 0xFC6E - LIFEPAK monitors and powered stretchers in ambulances, " +
                    "and plenty of hospital equipment", "$SIG; Wardrive Go's EMS list (facts only)")
        }

        // ---- Flock Safety ALPR + Raven --------------------------------------
        val xuntong = CID_XUNTONG in a.manufacturerData
        when {
            name.contains("FS Ext Battery", ignoreCase = true) ->
                hits += Hit(Category.ALPR, "Flock Safety camera battery", 80,
                    "Advertised name \"FS Ext Battery\"", "$ACAB (ryanohoro research)")
            PENGUIN.matches(name) ->
                hits += Hit(Category.ALPR, "Flock Safety device", if (xuntong) 80 else 70,
                    "Name pattern \"Penguin-<digits>\"" + if (xuntong) " plus XUNTONG module ID 0x09C8" else "",
                    "$ACAB (ryanohoro research)")
            FS_HEX.matches(name) ->
                hits += Hit(Category.ALPR, "Flock Safety device", if (xuntong) 80 else 70,
                    "Name pattern \"FS-<hex>\"" + if (xuntong) " plus XUNTONG module ID 0x09C8" else " (generic white-label prefix - verify)",
                    "$ACAB (field capture 2026-06)")
            // Penguin batteries on firmware from March 2025 advertise a bare 8-12 digit ID.
            xuntong && PENGUIN_NUMERIC.matches(name) ->
                hits += Hit(Category.ALPR, "Flock Safety device", 80,
                    "Numeric name \"$name\" plus XUNTONG module ID 0x09C8 (newer Penguin firmware)",
                    "$ACAB (flock-detection research)")
            // Newer Penguin batteries also carry their serial ("TN" + digits) in the module's data, name or not.
            xuntong && hasTnSerial(a.manufacturerData[CID_XUNTONG]) ->
                hits += Hit(Category.ALPR, "Flock Safety device", 80,
                    "XUNTONG module ID 0x09C8 with a \"TN<digits>\" serial in its data (Flock Penguin battery)",
                    "$SIGNALSWEEP; oui-spy notes")
            name.startsWith("Flock", ignoreCase = true) ->
                hits += Hit(Category.ALPR, "Possible Flock Safety device", 55,
                    "Advertised name starts with \"Flock\" (brand string - verify)", ACAB)
            name.contains("Pigvision", ignoreCase = true) ->
                hits += Hit(Category.ALPR, "Possible Flock Safety device", 55,
                    "Advertised name \"$name\" (Pigvision: Flock camera name - verify)", "$ACAB (flock-detection research)")
            xuntong ->
                hits += Hit(Category.ALPR, "XUNTONG Bluetooth module (used in Flock hardware)", 45,
                    "Company ID 0x09C8 - shared silicon, also in other products", "$SIG; $ACAB")
        }
        if (a.serviceUuids.contains(FLOCK_GATT) || a.serviceData.keys.contains(FLOCK_GATT)) {
            hits += Hit(Category.ALPR, "Flock Safety device", 80,
                "Flock accessory service UUID e8ccbb38-... (from Flock camera firmware)", "Flock-You research; SquachWatch")
        } else if (a.serviceUuids.contains(FLOCK_GATT_2) || a.serviceData.keys.contains(FLOCK_GATT_2)) {
            hits += Hit(Category.ALPR, "Flock Safety device", 80,
                "Flock accessory service UUID 20c944c1-... (from Flock camera firmware)", "Flock-You research; $SIGNALSWEEP")
        }
        RAVEN_SHORTS.keys.filter { it in shorts }.takeIf { it.isNotEmpty() }?.let { found ->
            hits += Hit(Category.AUDIO_SENSOR, "Flock Raven audio / gunshot sensor", 80,
                "Raven service UUIDs: " + found.joinToString { String.format("0x%04X (%s)", it, RAVEN_SHORTS[it]) },
                "$ACAB (field capture)")
        }

        // ---- Drones ---------------------------------------------------------
        a.serviceData[Advert.uuid16(UUID_REMOTE_ID)]?.let { data ->
            if (RemoteId.isBleRemoteId(data)) {
                hits += Hit(Category.DRONE, "Drone broadcasting Remote ID", 95,
                    "ASTM F3411 Remote ID message on service UUID 0xFFFA", ASTM)
            }
        }
        droneByPrefix(a)?.let { hits += it }

        // ---- Trackers separated from their owner ----------------------------
        a.manufacturerData[CID_APPLE]?.let { d ->
            if (d.size >= 2 && Bytes.u8(d, 0) == 0x12 && Bytes.u8(d, 1) == 0x19) {
                hits += Hit(Category.TRACKER, APPLE_FIND_MY_LABEL, 70,
                    "Apple Find My offline-finding frame (type 0x12, length 0x19 = separated state)",
                    "$ACAB; arXiv 2501.17452")
            }
        }
        a.serviceData[Advert.uuid16(UUID_GOOGLE_FMDN)]?.let { d ->
            if (d.isNotEmpty() && Bytes.u8(d, 0) == 0x41) {
                hits += Hit(Category.TRACKER, "Google Find Hub tracker away from its owner", 70,
                    "Find Hub Network frame 0x41 (separated state) on UUID 0xFEAA",
                    "Google Find Hub Network Accessory Spec; $ACAB")
            }
        }
        val smartTag = a.serviceData[Advert.uuid16(UUID_SMARTTAG)]?.takeIf { it.isNotEmpty() }
        if (smartTag != null) {
            hits += Hit(Category.TRACKER, "Samsung SmartTag", 55,
                "Service data on UUID 0xFD5A (Samsung)", "$SIG; arXiv 2501.17452")
        } else if (a.serviceData[Advert.uuid16(UUID_SMARTTAG_SETUP)]?.isNotEmpty() == true) {
            hits += Hit(Category.TRACKER, "Samsung SmartTag (not set up yet)", 40,
                "Service data on UUID 0xFD59 (Samsung): a tag still being set up, usually next to its owner",
                "$SIG; arXiv 2210.14702; $SIGNALSWEEP")
        }
        (a.serviceData[Advert.uuid16(UUID_TILE)] ?: a.serviceData[Advert.uuid16(UUID_TILE_2)])?.takeIf { it.isNotEmpty() }?.let {
            hits += Hit(Category.TRACKER, "Tile tracker", 55, "Service data on UUID 0xFEED / 0xFEEC (Tile, Inc.)", SIG)
        }
        // Chipolo tags often broadcast no name: their own service UUID gives them away.
        if (a.serviceData.keys.contains(Advert.uuid16(UUID_CHIPOLO)) || a.serviceUuids.contains(Advert.uuid16(UUID_CHIPOLO))) {
            hits += Hit(Category.TRACKER, "Chipolo tracker", 55, "Service UUID 0xFE33 (CHIPOLO d.o.o.)", "$SIG; AirGuard (SEEMOO, Apache-2.0)")
        }

        // ---- Smart / recording glasses --------------------------------------
        if (a.serviceUuids.any { it == HEYCYAN || it == HEYCYAN_REVERSED } ||
            a.serviceData.keys.any { it == HEYCYAN || it == HEYCYAN_REVERSED }) {
            hits += Hit(Category.GLASSES, "Camera smart glasses (HeyCyan SDK, e.g. Nilox Smart AI Glasses)", 68,
                "HeyCyan glasses SDK service UUID (full 128-bit match)", "$ACAB (yj_nearbyglasses)")
        } else if (Regex("heycyan|nilox", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
            hits += Hit(Category.GLASSES, "Camera smart glasses (HeyCyan SDK, e.g. Nilox Smart AI Glasses)", 55,
                "Advertised name \"$name\" (HeyCyan / Nilox; names can be changed - verify)", "yj_nearbyglasses name tokens")
        }
        if (CID_LUXOTTICA !in a.manufacturerData && Regex("ray-?ban", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
            hits += Hit(Category.GLASSES, "Ray-Ban Meta smart glasses", 55,
                "Advertised name \"$name\" (names can be changed - verify)", "yj_nearbyglasses name tokens")
        }
        if (CID_LUXOTTICA in a.manufacturerData) {
            hits += Hit(Category.GLASSES, "Ray-Ban Meta smart glasses", 70, "Company ID 0x0D53 (Luxottica)", SIG)
        }
        if (CID_SNAPCHAT in a.manufacturerData || UUID_SPECTACLES in allShorts) {
            hits += Hit(Category.GLASSES, "Snap Spectacles camera glasses", 70,
                "Snapchat company ID 0x03C2 or service UUID 0xFE45", SIG)
        }
        if (CID_VUZIX in a.manufacturerData) {
            hits += Hit(Category.GLASSES, "Vuzix camera AR glasses", 70, "Company ID 0x060C (Vuzix)", SIG)
        }
        a.manufacturerData[CID_META_TECH]?.let { d ->
            if (Bytes.containsAscii(d, "META_RB_GLASS")) {
                hits += Hit(Category.GLASSES, "Ray-Ban / Oakley Meta smart glasses", 72,
                    "Meta manufacturer data carries the META_RB_GLASS token", ACAB)
            }
        }
        if (CID_META in a.manufacturerData || META_UUIDS.any { it in allShorts }) {
            hits += Hit(Category.GLASSES, "Meta hardware - possibly Ray-Ban Meta glasses", 45,
                "Meta Platforms company ID 0x01AB or UUID 0xFEB7/0xFEB8 (also used by Quest headsets)",
                "$SIG; $ACAB field capture 2026-07-31")
        }
        return hits
    }

    /**
     * In-car network names police vehicles get from their vendors' setup guides:
     *  - Axon Fleet (in-car video on a Cradlepoint): Axon's guide names the 5 GHz network
     *    after the vehicle - its examples are "8554-5g" and "Axon12-5g".
     *  - Cradlepoint vehicle routers left on their default name: model + last 3 address
     *    digits ("IBR900-3ab", "-5g" on 5 GHz). Also ambulances, transit and utility fleets.
     */
    internal fun policeVehicleSsid(ssid: String): Hit? = when {
        AXON_FLEET_SSID.matches(ssid) -> Hit(Category.PUBLIC_SAFETY, "Police car network - Axon Fleet in-car video", 80,
            "WiFi network \"$ssid\" follows Axon's Fleet naming (\"Axon12-5g\")", AXON_FLEET_GUIDE)
        VEHICLE_NUMBER_5G_SSID.matches(ssid) -> Hit(Category.PUBLIC_SAFETY, "Possible police car network (vehicle number + -5g)", 45,
            "WiFi network \"$ssid\" is a vehicle number + \"-5g\", Axon's Fleet naming (\"8554-5g\"). Weak alone: " +
                "stronger when the router is a Cradlepoint or Sierra Wireless", AXON_FLEET_GUIDE)
        CRADLEPOINT_VEHICLE_SSID.matches(ssid) -> Hit(Category.PUBLIC_SAFETY, "Cradlepoint vehicle router (default network name)", 40,
            "WiFi network \"$ssid\" is a Cradlepoint in-vehicle router's default name (model + last 3 address digits). " +
                "Police cars, but also ambulances, buses, utility fleets and traffic-signal cabinets", "Cradlepoint default SSID (product label); $IEEE")
        else -> null
    }

    /** The network an Axon body camera or device opens while pairing ("AB3-...", "AXON-..."). */
    internal fun axonPairingSsid(ssid: String): Hit? {
        if (AXON_FLEET_SSID.matches(ssid)) return null // an in-car network: policeVehicleSsid
        AXON_BODY_SSID.find(ssid)?.let { m ->
            val gen = m.groupValues[1]
            return Hit(Category.BODY_CAM, "Axon Body $gen camera (pairing network)", 80,
                "WiFi network \"$ssid\": Axon Body $gen cameras open an \"AB$gen-\" network while pairing",
                "Axon admin documentation; $SIGNALSWEEP")
        }
        if (ssid.startsWith("AXON-", ignoreCase = true)) {
            return Hit(Category.BODY_CAM, "Axon device hotspot (body camera or in-car)", 75,
                "WiFi network \"$ssid\": the \"AXON-\" hotspot Axon devices open", "Axon admin documentation; $SIGNALSWEEP")
        }
        return null
    }

    private val AXON_BODY_SSID = Regex("^AB([234])-", RegexOption.IGNORE_CASE)
    private val AXON_FLEET_SSID = Regex("^axon[-_ ]?\\d{1,6}[-_ ]?5g$", RegexOption.IGNORE_CASE)
    private val VEHICLE_NUMBER_5G_SSID = Regex("^\\d{2,6}[-_ ]5g$", RegexOption.IGNORE_CASE)
    private val CRADLEPOINT_VEHICLE_SSID = Regex("^(ibr\\d{3,4}[a-z]{0,4}|r1900|r2100)-[0-9a-f]{3}(-5g)?$", RegexOption.IGNORE_CASE)
    private const val AXON_FLEET_GUIDE = "Axon Fleet 2/3 network and Cradlepoint configuration guides (axon.com)"

    /**
     * WiFi module prefixes found in Flock Safety cameras (flock-you dataset, NitekryDPaul /
     * DeFlock research). They're generic chip makers (Liteon, Espressif, Silicon Labs...),
     * so they only count together with a Flock product network name or a hidden network.
     */
    private val FLOCK_MODULE_PREFIXES = setOf(
        "70C94E", "3C9180", "D8F3BC", "803049", "B83532", "145AFC", "744CA1", "083A88", "9C2F9D", "C03532",
        "940853", "E4AAEA", "F46ADD", "F8A2D6", "24B2B9", "00F48D", "D03957", "E8D0FC", "E04F43", "B81EA4",
        "700894", "588E81", "EC1BBD", "3C71BF", "5800E3", "9035EA", "5C93A2", "646E69", "4827EA", "A4CF12", "826BF2",
        // Added from flock-you's 2026 list via SignalSweep (Liteon and Silicon Labs blocks).
        "14B5CD", "040D84", "1C34F1", "385B44", "943469", "B4E3F9", "F082C0", "E00AF6"
    )

    /**
     * The module prefixes trusted for the wildcard-probe signature: the list above without the ones
     * that also turn up in plenty of ordinary devices (two Espressif blocks, one seen on a Sony media
     * player, and a randomized-address prefix). A bare wildcard probe from any other device means
     * nothing; from these it's the only thing a current Flock camera still sends (SignalSweep, DeFlock).
     */
    private val FLOCK_PROBE_PREFIXES = FLOCK_MODULE_PREFIXES - setOf("F8A2D6", "A4CF12", "3C71BF", "826BF2")

    /**
     * Community-listed Flock module prefixes that are doubtful (SignalSweep): A4:CF:12 is a generic
     * Espressif block, F8:A2:D6 was seen on a Sony media player, 82:6B:F2 is a randomized-address
     * prefix any phone can land on. Very weak: listed, never alerted.
     */
    private val FLOCK_DOUBTFUL_PREFIXES = setOf("A4CF12", "F8A2D6", "826BF2")
    private val FLOCK_PRODUCT_SSID = Regex("^(flock|falcon|sparrow|raven|penguin)[_-]", RegexOption.IGNORE_CASE)

    private fun classifyWifi(a: Advert): List<Hit> {
        val hits = mutableListOf<Hit>()
        val ssid = a.name?.trim().orEmpty()
        if (ssid.startsWith("Flock-", ignoreCase = true)) {
            hits += Hit(Category.ALPR, "Flock Safety camera", 88,
                "WiFi network \"$ssid\" (Flock- prefix: the vendor names its own AP)",
                "$ACAB (ryanohoro, GainSec research)")
        }
        else if (Regex("flockos|flocksafety|pigvision|fs ext battery", RegexOption.IGNORE_CASE).containsMatchIn(ssid) ||
            Regex("^Penguin-\\d+$").matches(ssid)) {
            hits += Hit(Category.ALPR, "Possible Flock Safety device", 60,
                "WiFi network \"$ssid\" (Flock product name - verify)", "$ACAB (flock-detection / flock-you research)")
        }
        // A Flock-used WiFi module: strong with a Flock product name, weak on a hidden network.
        val module = a.mac.replace(":", "").uppercase().take(6)
        if (a.wifi?.client != true && module in FLOCK_DOUBTFUL_PREFIXES) {
            if (ssid.isEmpty() || (FLOCK_PRODUCT_SSID.containsMatchIn(ssid) && !ssid.startsWith("Flock-", ignoreCase = true))) {
                hits += Hit(Category.ALPR, "Very weak Flock signature", Tier.VERY_WEAK_MAX,
                    (if (ssid.isEmpty()) "Hidden WiFi network" else "WiFi network \"$ssid\"") + " on ${fmtPrefix(module)}, a doubtful " +
                        "community-listed Flock module prefix also found in ordinary devices. Listed only, never alerted",
                    "$ACAB (flock-you list); $SIGNALSWEEP")
            }
        } else if (a.wifi?.client != true && module in FLOCK_MODULE_PREFIXES) {
            if (FLOCK_PRODUCT_SSID.containsMatchIn(ssid) && !ssid.startsWith("Flock-", ignoreCase = true)) {
                hits += Hit(Category.ALPR, "Flock Safety camera", 85,
                    "WiFi network \"$ssid\" (Flock product name) on a WiFi module Flock cameras use (${fmtPrefix(a.mac.replace(":", "").uppercase().take(6))})",
                    "$ACAB (flock-you / MILLIE research)")
            } else if (ssid.isEmpty()) {
                hits += Hit(Category.ALPR, "Possible Flock camera (hidden network)", 35,
                    "Hidden WiFi network on a module Flock cameras use (${fmtPrefix(a.mac.replace(":", "").uppercase().take(6))}); " +
                        "the same chips are in many ordinary devices - verify", "$ACAB (flock-you / MILLIE research)")
            }
        }
        // A Flock camera with its management network off (since late 2025) still looks for networks:
        // wildcard probe requests from a Flock radio module, heard by a USB WiFi adapter.
        val w = a.wifi
        if (w != null && w.client && w.wildcardProbe && !isLocallyAdministered(a.mac) &&
            a.mac.replace(":", "").uppercase().take(6) in FLOCK_PROBE_PREFIXES) {
            val prefix = fmtPrefix(a.mac.replace(":", "").uppercase().take(6))
            hits += if (w.flockProbeIe) Hit(Category.ALPR, "Flock Safety camera (probe request)", 75,
                "Wildcard probe requests from a WiFi module Flock cameras use ($prefix), carrying the Wi-Fi Alliance " +
                    "element 50:6F:9A:16 03 01 03 Flock cameras send. Ordinary devices with the same chip can match - verify",
                "$SIGNALSWEEP; DeFlock / flock-you research")
            else Hit(Category.ALPR, "Possible Flock camera (probe request)", 60,
                "Wildcard probe requests from a WiFi module Flock cameras use ($prefix). In field tests this caught " +
                    "11 of 12 cameras with 2 false alarms; ordinary devices with the same chip can match - verify",
                "$SIGNALSWEEP; DeFlock / flock-you research")
        }
        // FlipDeFlock's candidate probe shapes: how a Flock camera lays out its probe requests,
        // which stays the same when it randomizes its address. Single-report, so weak.
        val shape = w?.probeSkeleton
        if (w != null && w.client && shape != null && shape in FLOCK_PROBE_SHAPES) {
            val prefix = a.mac.replace(":", "").uppercase().take(6)
            val onFlockModule = !isLocallyAdministered(a.mac) && prefix in FLOCK_PROBE_PREFIXES
            hits += Hit(Category.ALPR, "Possible Flock camera (probe shape)", if (onFlockModule) 50 else 35,
                "Its probe requests are built exactly like a Flock camera's (shape ${"%08x".format(shape)}, " +
                    "${FLOCK_PROBE_SHAPES[shape]})" + (if (onFlockModule) " on a module Flock cameras use (${fmtPrefix(prefix)})" else "") +
                    ". One report so far, and other devices can share a shape - verify", FLIPDEFLOCK)
        }
        // Axon body cameras open their own network while pairing (Axon's admin documentation).
        if (a.wifi?.client != true) axonPairingSsid(ssid)?.let { hits += it }
        if (ssid.startsWith("ARLO_VMB_") || ssid.startsWith("NTGR_VMB_")) {
            hits += Hit(Category.NETWORK_CAMERA, "Arlo camera base station", 88,
                "WiFi network \"$ssid\" (Arlo base-station SSID)", "$ACAB (field capture)")
        }
        // Vendor-documented in-car network names (access points only: a client's name is
        // its WPS device name, not a network).
        if (a.wifi?.client != true) policeVehicleSsid(ssid)?.let { hits += it }
        a.wifi?.infoElements?.firstOrNull { it.first == 221 && RemoteId.isWifiIe(it.second) }?.let {
            hits += if (RemoteId.isFrenchIe(it.second))
                Hit(Category.DRONE, "Drone broadcasting its French electronic ID (WiFi)", 95,
                    "French \"signalement électronique\" vendor information element (OUI 6A:5C:35) in the WiFi beacon",
                    "Arrêté of 27 December 2019 (France); github.com/khancyr/droneID_FR")
            else if (a.wifi?.standard == DeviceIntel.WIFI_AWARE) Hit(Category.DRONE, "Drone broadcasting Remote ID (Wi-Fi Aware)", 95,
                "ASTM F3411 Remote ID message pack in a Wi-Fi Aware (NAN) service discovery", ASTM)
            else Hit(Category.DRONE, "Drone broadcasting Remote ID (WiFi)", 95,
                "ASTM F3411 vendor information element (OUI FA:0B:BC) in the WiFi beacon", ASTM)
        }
        if (!isLocallyAdministered(a.mac)) {
            val listed = CAMERA_PREFIXES.match(a.mac)
            listed?.let { (prefix, vendor) ->
                hits += Hit(Category.NETWORK_CAMERA, "$vendor camera, hub or recorder", 65,
                    "WiFi address in $vendor's registered block ${fmtPrefix(prefix)}", "$IEEE; $ACAB")
            }
            // Any other block registered to a camera / security maker.
            if (listed == null) VendorDb.macVendor(a.mac)?.takeIf { CAMERA_MAKER_NAME.containsMatchIn(it) }?.let { owner ->
                hits += Hit(Category.NETWORK_CAMERA, "${cameraMaker(owner)} camera, hub or recorder", 60,
                    "WiFi address registered to $owner", "$IEEE; $ACAB")
            }
            ALPR_MAKER_PREFIXES.match(a.mac)?.let { (prefix, maker) ->
                hits += Hit(Category.ALPR, "$maker traffic / plate-camera hardware", 60,
                    "WiFi address in $maker's registered block ${fmtPrefix(prefix)} - enforcement-camera maker, verify",
                    "$IEEE; $ACAB (alpr_candidates)")
            }
            EMS_PREFIXES.match(a.mac)?.let { (prefix, maker) ->
                hits += Hit(Category.PUBLIC_SAFETY, "$maker medical device (ambulance defibrillator / monitor)", 45,
                    "WiFi address in $maker's registered block ${fmtPrefix(prefix)} - LIFEPAK monitors send their data " +
                        "over WiFi from ambulances; hospitals use them too", "$IEEE; Wardrive Go's EMS list (facts only)")
            }
        }
        droneByPrefix(a)?.let { hits += it }
        return hits
    }

    /** DJI's handheld cameras and gimbals share its address blocks; they aren't drones. */
    private val DJI_HANDHELD = Regex("^(dji ?)?osmo|^om ?\\d", RegexOption.IGNORE_CASE)

    private fun droneByPrefix(a: Advert): Hit? {
        if (isLocallyAdministered(a.mac)) return null
        if (a.name?.trim()?.let { DJI_HANDHELD.containsMatchIn(it) } == true) return null
        val (prefix, vendor) = DRONE_PREFIXES.match(a.mac) ?: return null
        return Hit(Category.DRONE, "$vendor equipment (drone or controller)", 60,
            "Address in $vendor's registered block ${fmtPrefix(prefix)}; no Remote ID decoded",
            "$IEEE; $ACAB")
    }

    /** A short maker name from an IEEE registrant ("Hangzhou Hikvision Digital Technology Co.,Ltd" -> "Hikvision"). */
    internal fun cameraMaker(owner: String): String {
        val o = owner.lowercase()
        return listOf(
            "hikvision" to "Hikvision", "dahua" to "Dahua", "ezviz" to "Ezviz", "lorex" to "Lorex", "amcrest" to "Amcrest",
            "swann" to "Swann", "wyze" to "Wyze", "ring llc" to "Ring (Amazon)", "arlo" to "Arlo", "reolink" to "Reolink",
            "vivint" to "Vivint", "simplisafe" to "SimpliSafe", "uniview" to "Uniview", "vivotek" to "Vivotek",
            "avigilon" to "Avigilon", "verkada" to "Verkada", "geovision" to "GeoVision", "mobotix" to "Mobotix",
            "sunell" to "Sunell", "march networks" to "March Networks", "ava security" to "Ava Security",
            "hanwha" to "Hanwha Vision", "samsung techwin" to "Hanwha Vision", "axis" to "Axis", "blink" to "Blink (Amazon)",
            "fantasia trading" to "Eufy (Anker)", "phaten" to "Phaten (camera OEM)", "tapo" to "TP-Link Tapo"
        ).firstOrNull { it.first in o }?.second ?: owner
    }

    /** "TN" followed by a digit somewhere in a Flock Penguin battery's XUNTONG data: its serial. */
    private fun hasTnSerial(d: ByteArray?): Boolean {
        if (d == null) return false
        for (i in 0 until d.size - 2) {
            if (d[i] == 'T'.code.toByte() && d[i + 1] == 'N'.code.toByte() && d[i + 2] in '0'.code.toByte()..'9'.code.toByte()) return true
        }
        return false
    }

    private fun isLocallyAdministered(mac: String): Boolean =
        (mac.take(2).toIntOrNull(16) ?: 0) and 0x02 != 0

    private fun fmtPrefix(hex: String) = hex.chunked(2).joinToString(":")

    private fun reverse(u: UUID): UUID {
        val bytes = ByteArray(16)
        for (i in 0 until 8) bytes[i] = (u.mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) bytes[8 + i] = (u.leastSignificantBits ushr (56 - 8 * i)).toByte()
        bytes.reverse()
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}
