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

    private const val ACAB = "all-cameras-are-beacons signature reference (Apache-2.0)"
    private const val SIG = "Bluetooth SIG assigned numbers"
    private const val IEEE = "IEEE registry"
    private const val ASTM = "ASTM F3411 Remote ID (opendroneid-core-c, Apache-2.0)"

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

    // --- 16-bit service UUIDs ------------------------------------------------
    private val AXON_UUIDS = mapOf(0xFC81 to "Axon Enterprise", 0xFE6B to "TASER International", 0xFE6C to "TASER International")
    private val MOTOROLA_UUIDS = setOf(0xFD8E, 0xFE04)
    private val RAVEN_SHORTS = mapOf(0x3100 to "GPS", 0x3200 to "power", 0x3300 to "network", 0x3400 to "upload", 0x3500 to "error")
    private const val UUID_REMOTE_ID = 0xFFFA      // ASTM Remote ID (SIG SDO UUID)
    private const val UUID_SMARTTAG = 0xFD5A       // Samsung Electronics
    private const val UUID_TILE = 0xFEED           // Tile, Inc.
    private const val UUID_GOOGLE_FMDN = 0xFEAA    // Google LLC (Eddystone / Find Hub)
    private const val UUID_SPECTACLES = 0xFE45     // Snapchat Inc
    private val META_UUIDS = setOf(0xFEB7, 0xFEB8) // Meta Platforms, Inc.
    private val ZEBRA_UUIDS = setOf(0xFE79, 0xFD66) // Zebra Technologies (FE79 = Link-OS printer BLE service)
    private val FORTIN_UUIDS = setOf(0xFDE1, 0xFDCA) // Fortin Electronic Systems (vehicle interface modules)
    private const val UUID_DRAEGER = 0xFCDA        // Draeger

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
    /** Zebra's default Bluetooth name is the serial: 2-char plant, 3-letter model code, YYWW + 5 digits. */
    private val ZEBRA_SERIAL = Regex("^[A-Z0-9]{2}[A-Z]{3}\\d{9}$")

    fun classify(a: Advert): List<Hit> {
        val hits = if (a.isBle) classifyBle(a) else classifyWifi(a)
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
        if (CID_TASER in a.manufacturerData) {
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
            name.startsWith("Flock", ignoreCase = true) ->
                hits += Hit(Category.ALPR, "Possible Flock Safety device", 55,
                    "Advertised name starts with \"Flock\" (brand string - verify)", ACAB)
            xuntong ->
                hits += Hit(Category.ALPR, "XUNTONG Bluetooth module (used in Flock hardware)", 45,
                    "Company ID 0x09C8 - shared silicon, also in other products", "$SIG; $ACAB")
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
                hits += Hit(Category.TRACKER, "Apple Find My tracker away from its owner (AirTag or compatible)", 70,
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
        a.serviceData[Advert.uuid16(UUID_SMARTTAG)]?.takeIf { it.isNotEmpty() }?.let {
            hits += Hit(Category.TRACKER, "Samsung SmartTag", 55,
                "Service data on UUID 0xFD5A (Samsung)", "$SIG; arXiv 2501.17452")
        }
        a.serviceData[Advert.uuid16(UUID_TILE)]?.takeIf { it.isNotEmpty() }?.let {
            hits += Hit(Category.TRACKER, "Tile tracker", 55, "Service data on UUID 0xFEED (Tile, Inc.)", SIG)
        }

        // ---- Smart / recording glasses --------------------------------------
        if (a.serviceUuids.any { it == HEYCYAN || it == HEYCYAN_REVERSED } ||
            a.serviceData.keys.any { it == HEYCYAN || it == HEYCYAN_REVERSED }) {
            hits += Hit(Category.GLASSES, "Camera smart glasses (HeyCyan SDK)", 68,
                "HeyCyan glasses SDK service UUID (full 128-bit match)", "$ACAB (yj_nearbyglasses)")
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

    private fun classifyWifi(a: Advert): List<Hit> {
        val hits = mutableListOf<Hit>()
        val ssid = a.name?.trim().orEmpty()
        if (ssid.startsWith("Flock-", ignoreCase = true)) {
            hits += Hit(Category.ALPR, "Flock Safety camera", 88,
                "WiFi network \"$ssid\" (Flock- prefix: the vendor names its own AP)",
                "$ACAB (ryanohoro, GainSec research)")
        }
        if (ssid.startsWith("ARLO_VMB_") || ssid.startsWith("NTGR_VMB_")) {
            hits += Hit(Category.NETWORK_CAMERA, "Arlo camera base station", 88,
                "WiFi network \"$ssid\" (Arlo base-station SSID)", "$ACAB (field capture)")
        }
        a.wifi?.infoElements?.firstOrNull { it.first == 221 && RemoteId.isWifiIe(it.second) }?.let {
            hits += Hit(Category.DRONE, "Drone broadcasting Remote ID (WiFi)", 95,
                "ASTM F3411 vendor information element (OUI FA:0B:BC) in the WiFi beacon", ASTM)
        }
        if (!isLocallyAdministered(a.mac)) {
            CAMERA_PREFIXES.match(a.mac)?.let { (prefix, vendor) ->
                hits += Hit(Category.NETWORK_CAMERA, "$vendor camera, hub or recorder", 65,
                    "WiFi address in $vendor's registered block ${fmtPrefix(prefix)}", "$IEEE; $ACAB")
            }
        }
        droneByPrefix(a)?.let { hits += it }
        return hits
    }

    private fun droneByPrefix(a: Advert): Hit? {
        if (isLocallyAdministered(a.mac)) return null
        val (prefix, vendor) = DRONE_PREFIXES.match(a.mac) ?: return null
        return Hit(Category.DRONE, "$vendor equipment (drone or controller)", 60,
            "Address in $vendor's registered block ${fmtPrefix(prefix)}; no Remote ID decoded",
            "$IEEE; $ACAB")
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
