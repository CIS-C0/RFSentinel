package com.rfsentinel.app.ui

import android.content.Context
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.detect.AdStructure
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Bytes
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One section of a device's page: its title and its rows. */
typealias DeviceSection = Pair<String, List<Pair<String, String>>>

/**
 * The sections of a device's page (why it's flagged, identity, drone Remote ID, history, raw advertisement).
 * Shared by the page and by the scanner, which saves them while the device is heard ([com.rfsentinel.app.data.LastHeard]),
 * so a device no longer in range shows the same details as when it was.
 */
object DeviceSections {

    const val FLAGGED = "Why it's flagged"
    const val WAS_FLAGGED = "Why it was flagged"
    const val HISTORY = "History"
    const val RAW = "Raw advertisement"

    private fun dateFmt() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    /**
     * Every section for [mac]: [s] is the device as heard now (null when it isn't), [known] its saved record,
     * [pastMatches] / [pastHits] its match history. [saving]: the History section keeps only what this sighting adds
     * (the saved record and match history are read again when it's shown).
     */
    fun build(
        context: Context, mac: String, s: DeviceRegistry.Snapshot?, known: KnownDeviceEntity?,
        pastMatches: List<DetectionEntity>, pastHits: List<Hit>, saving: Boolean = false
    ): List<DeviceSection> {
        val out = mutableListOf<DeviceSection>()
        val a = s?.advert
        val dateFmt = dateFmt()
        val knownType = known?.let { DeviceIntel.savedType(it.deviceType, it.vendor, it.mac) }

        // Why flagged
        if (s == null && pastHits.isNotEmpty()) {
            out += WAS_FLAGGED to pastMatchRows(pastMatches, pastHits)
        } else if (s != null) {
            out += FLAGGED to if (s.hits.isEmpty()) {
                listOf("Result" to "Not flagged - no watchlist entry or equipment signature matched.")
            } else {
                s.hits.flatMap { h ->
                    listOf(
                        "● ${h.label}" to "${h.category.title} · ${h.tier.label} (${h.confidence}%)",
                        "   Evidence" to h.evidence,
                        "   Source" to h.source
                    )
                }
            }
        }

        // Identity
        val id = mutableListOf<Pair<String, String>>()
        (s?.deviceType ?: knownType)?.let { id += "Device type" to it }
        // The category the list files it under (a device no longer heard: from its saved type and name).
        (s?.let { DeviceFilter.seen(it) } ?: knownType?.let {
            DeviceFilter.Seen(mac, it, known?.name, pastHits, emptySet(), pastHits.isNotEmpty(), vendor = VendorDb.macVendor(mac))
        })?.let { id += "Category" to DeviceOrder.categoryLabel(it) }
        (s?.name ?: known?.name)?.let { id += (if (a?.isWifi == true) "Network name (SSID)" else "Advertised name") to it }
        VendorDb.macVendor(mac)?.let { id += "Address registrant (IEEE)" to it }
        a?.manufacturerData?.keys?.forEach { cid ->
            id += "Bluetooth company" to (VendorDb.company(cid) ?: "Unknown") + String.format(" (0x%04X)", cid)
        }
        if (s == null) pastMatches.firstOrNull { it.source == "BLE" || it.source == "WIFI" }?.let { m ->
            // The radio side as saved with its last match.
            id += "Radio" to (if (m.source == "BLE") "Bluetooth LE" else "WiFi") + (m.radio?.let { " · $it" } ?: "")
        }
        if (s != null) {
            id += "Address type" to s.addressType.label
            id += "Trackability" to s.addressType.trackability(known?.firstSeen, known?.lastSeen, known?.sessions ?: 0,
                DeviceRegistry.linkedFrom(mac) != null)
            id += "Radio" to s.sources.joinToString(" + ") { if (it == Advert.Source.BLE) "Bluetooth LE" else "WiFi" }
        }
        a?.connectable?.let { id += "Connectable" to if (it) "Yes (accepts connections)" else "No (broadcast only)" }
        a?.phy?.let { id += "PHY" to it }
        a?.txPower?.let { id += "Advertised TX power" to "$it dBm" }
        s?.facts?.forEach { id += it }
        DeviceNames.lookalike(mac, DeviceRegistry.fingerprintOf(DeviceRegistry.currentAddress(mac)))?.let {
            id += "Looks like" to "Your \"$it\": its adverts look the same, so it's that device under a new address, or one of the same model"
        }
        DeviceRegistry.companions(DeviceRegistry.currentAddress(mac)).takeIf { it.isNotEmpty() }?.let { c ->
            id += "Moved together with" to c.joinToString("\n") { (m, label) -> (DeviceNames.of(m) ?: label) + " · " + m }
        }
        s?.hits?.firstOrNull { it.category == com.rfsentinel.app.detect.Category.TRACKER }
            ?.takeIf { !com.rfsentinel.app.detect.SignatureEngine.reportsOwnerState(it) }?.let {
                DeviceRegistry.ownerPhone(DeviceRegistry.currentAddress(mac))?.let { phone ->
                    id += "With its owner" to "Moved with $phone at every place: most likely that phone owner's tag, so it doesn't alert as following you"
                }
            }
        // Heard by a USB WiFi adapter / Marauder board: its WPS block, fingerprint, network requests.
        com.rfsentinel.app.usb.ProbeIntel.of(mac)?.let { r ->
            if (r.hidden) id += "Hidden network" to (s?.name?.let { "Its name, \"$it\", was revealed by a device joining or asking for it" }
                ?: "It hides its name - shown once a device joins or asks for it (USB WiFi adapter)")
            r.wps?.deviceName?.let { id += "Device name (WPS)" to it }
            r.wps?.product?.let { id += "Maker / model (WPS)" to it }
            r.fingerprint?.let { fp ->
                id += "Probe fingerprint" to fp + if (OuiWatchlist.isFingerprintWatched(fp)) " · on your watchlist" else
                    " (same for every device of this model / OS - Watchlist can watch it)"
            }
            r.skeleton?.let { sk ->
                id += "Probe shape" to "%08x".format(sk) + (com.rfsentinel.app.detect.SignatureEngine.probeShapeNote(sk)?.let { " · $it" } ?: "") +
                    if (OuiWatchlist.isFingerprintWatched(com.rfsentinel.app.usb.ProbeIntel.shapeKey(sk))) " · on your watchlist" else ""
            }
            if (r.probed.isNotEmpty()) id += "Networks it asked for" to r.probed.reversed().joinToString(", ")
        }
        a?.wifi?.probeRun?.let { run ->
            id += "Wildcard probes" to run.describe() +
                if (run.nonStop) " · non-stop, like a mains-powered fixed device" else ""
        }
        if (id.isNotEmpty()) out += "Identity" to id

        // Drone Remote ID
        s?.remoteId?.let { r ->
            val rows = mutableListOf<Pair<String, String>>()
            r.uasId?.let { rows += "UAS ID" to it }
            r.idType?.let { rows += "ID type" to it }
            r.uaType?.let { rows += "Aircraft type" to it }
            r.status?.let { rows += "Status" to it }
            if (r.hasPosition) rows += "Drone position" to String.format(Locale.US, "%.6f, %.6f", r.latitude, r.longitude)
            r.altitudeGeoM?.let { rows += "Altitude (WGS84)" to String.format(Locale.US, "%.0f m", it) }
            r.heightM?.let { rows += "Height above takeoff/ground" to String.format(Locale.US, "%.0f m", it) }
            r.speedMs?.let { rows += "Ground speed" to String.format(Locale.US, "%.1f m/s (%s)", it, com.rfsentinel.app.util.Units.speed(it)) }
            r.verticalSpeedMs?.let { rows += "Vertical speed" to String.format(Locale.US, "%.1f m/s", it) }
            r.directionDeg?.let { rows += "Heading" to "$it°" }
            if (r.hasOperatorPosition) rows += "Operator position" to String.format(Locale.US, "%.6f, %.6f", r.operatorLatitude, r.operatorLongitude)
            r.operatorId?.let { rows += "Operator ID" to it }
            r.selfIdDescription?.let { rows += "Description" to it }
            out += "Drone Remote ID (ASTM F3411)" to rows
        }

        // Session & history
        out += HISTORY to if (saving) heardRows(context, s) else {
            val hist = mutableListOf<Pair<String, String>>()
            if (s != null) hist += "First heard this session" to DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(s.firstSeen))
            hist += savedRows(known, pastMatches)
            hist += heardRows(context, s)
            hist
        }

        // Raw advertisement
        if (a != null) {
            val raw = mutableListOf<Pair<String, String>>()
            a.manufacturerData.forEach { (cid, data) ->
                raw += String.format("Manufacturer 0x%04X", cid) to Bytes.hex(data)
            }
            a.serviceUuids.forEach { u ->
                val short = Advert.shortOf(u)
                raw += "Service UUID" to (if (short != null) String.format("0x%04X", short) + (VendorDb.uuid16(short)?.let { " - $it" } ?: "") else u.toString())
            }
            a.serviceData.forEach { (u, data) ->
                val short = Advert.shortOf(u)
                val label = if (short != null) String.format("0x%04X", short) + (VendorDb.uuid16(short)?.let { " $it" } ?: "") else u.toString()
                raw += "Service data $label" to Bytes.hex(data)
            }
            AdStructure.parse(a.rawBytes).forEach { ad ->
                raw += String.format("AD 0x%02X %s", ad.type, ad.typeName) to Bytes.hex(ad.data).ifEmpty { "(empty)" }
            }
            a.wifi?.let { w ->
                raw += "Capabilities" to w.capabilities.ifEmpty { "(none)" }
                raw += "Information elements" to "${w.infoElements.size}" +
                    if (w.infoElements.isNotEmpty()) " (IDs " + w.infoElements.map { it.first }.distinct().joinToString() + ")" else ""
            }
            if (raw.isNotEmpty()) out += RAW to raw
        }
        return out
    }

    /** What it matched when it was heard (the newest match of each kind), from the match history. */
    fun pastMatchRows(pastMatches: List<DetectionEntity>, pastHits: List<Hit>): List<Pair<String, String>> {
        val dateFmt = dateFmt()
        return pastMatches.distinctBy { it.label }.take(pastHits.size).zip(pastHits).flatMap { (m, h) ->
            listOfNotNull(
                "● ${h.label}" to "${h.category.title} · ${h.tier.label} (${h.confidence}%)",
                h.evidence.takeIf { it.isNotBlank() }?.let { "   Evidence" to it },
                "   Last matched" to dateFmt.format(Date(m.timestamp))
            )
        }
    }

    /** The saved record and match history. */
    fun savedRows(known: KnownDeviceEntity?, pastMatches: List<DetectionEntity>): List<Pair<String, String>> {
        val dateFmt = dateFmt()
        val hist = mutableListOf<Pair<String, String>>()
        known?.let {
            hist += "First ever seen" to dateFmt.format(Date(it.firstSeen))
            hist += "Last seen (saved)" to dateFmt.format(Date(it.lastSeen))
            hist += "Scanning sessions seen in" to "${it.sessions}"
            hist += "Detect count" to "${it.detectCount} (at most one per minute)"
        } ?: run { hist += "History" to "First time this address has been heard" }
        if (pastMatches.isNotEmpty()) {
            hist += "Logged matches" to "${pastMatches.size}" + (if (pastMatches.size >= 200) "+" else "")
            pastMatches.take(5).forEach { m ->
                hist += dateFmt.format(Date(m.timestamp)) to "${m.label} · ${m.rssi} dBm" +
                    (if (m.latitude != null) String.format(Locale.US, " · %.5f, %.5f", m.latitude, m.longitude) else "")
            }
        }
        return hist
    }

    /** What this sighting adds: how far it came along, where it was placed. */
    private fun heardRows(context: Context, s: DeviceRegistry.Snapshot?): List<Pair<String, String>> {
        val hist = mutableListOf<Pair<String, String>>()
        if (s != null && s.path.size >= 2) {
            val first = s.path.first()
            val moved = s.path.maxOf { DeviceRegistry.metersBetween(first.lat, first.lon, it.lat, it.lon) }
            hist += "Travelled with you" to String.format(Locale.US, "%.0f m while in range", moved)
        }
        s?.broadcast?.let { b ->
            hist += "Position (its own GPS, over V2X)" to String.format(Locale.US, "%.6f, %.6f", b.lat, b.lon)
        } ?: s?.located?.let { e ->
            hist += "Pinpointed position" to String.format(Locale.US, "%.6f, %.6f · ", e.lat, e.lon) +
                com.rfsentinel.app.detect.Locator.describe(e)
        } ?: s?.takeIf { it.best != null && !it.following && com.rfsentinel.app.util.Prefs.pinpointFlagged(context) }?.let {
            hist += "Pinpointing" to "Not yet: pass it from more than one side (around the block) and it is placed on the map " +
                "with the circle it is very likely in"
        }
        return hist
    }
}
