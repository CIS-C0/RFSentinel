package com.rfsentinel.app.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.AdStructure
import com.rfsentinel.app.detect.Bytes
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.service.DeviceRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializes EVERY device the app knows about - matched or not - for the
 * "Export all" option: the live scan session (full detail, raw advertisement
 * data) and the long-term device history.
 */
object DeviceExport {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private fun iso(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun local(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    /** Per-device flags the registry doesn't hold (whitelist / favorite). */
    class Flags(val whitelisted: (String) -> Boolean, val favorite: (String) -> Boolean)

    // ---- Live session -------------------------------------------------------------

    fun sessionCsv(devices: List<DeviceRegistry.Snapshot>, flags: Flags): String {
        val sb = StringBuilder(
            "mac,name,vendor,device_type,address_type,radios,rssi,best_rssi,distance_m,first_seen,last_seen," +
                "packets,flagged,category,confidence,match_label,evidence,following,whitelisted,favorite," +
                "first_seen_ever,sessions,company_ids,service_uuids,latitude,longitude\n"
        )
        devices.forEach { s ->
            val best = s.best
            val a = s.advert
            val last = s.path.lastOrNull()
            sb.append(s.mac).append(',')
                .append(q(s.name)).append(',')
                .append(q(s.vendor)).append(',')
                .append(q(s.deviceType)).append(',')
                .append(q(s.addressType.label)).append(',')
                .append(s.sources.joinToString("+")).append(',')
                .append(s.rssi).append(',')
                .append(s.bestRssi).append(',')
                .append(String.format(Locale.US, "%.1f", s.distanceM)).append(',')
                .append(local(s.firstSeen)).append(',')
                .append(local(s.lastSeen)).append(',')
                .append(s.sightings).append(',')
                .append(best != null).append(',')
                .append(best?.category?.name ?: "").append(',')
                .append(best?.confidence ?: "").append(',')
                .append(q(best?.label)).append(',')
                .append(q(best?.evidence)).append(',')
                .append(s.following).append(',')
                .append(flags.whitelisted(s.mac)).append(',')
                .append(flags.favorite(s.mac)).append(',')
                .append(s.known?.let { local(it.firstSeen) } ?: "").append(',')
                .append(s.known?.sessions ?: "").append(',')
                .append(q(a?.manufacturerData?.keys?.joinToString(" ") { String.format("0x%04X", it) })).append(',')
                .append(q(a?.serviceUuids?.joinToString(" ") { u -> Advert.shortOf(u)?.let { String.format("0x%04X", it) } ?: u.toString() })).append(',')
                .append(last?.lat ?: "").append(',')
                .append(last?.lon ?: "").append('\n')
        }
        return sb.toString()
    }

    fun sessionJson(devices: List<DeviceRegistry.Snapshot>, flags: Flags): JsonArray {
        val arr = JsonArray()
        devices.forEach { s ->
            val o = JsonObject()
            o.addProperty("mac", s.mac)
            o.addProperty("name", s.name)
            o.addProperty("vendor", s.vendor)
            o.addProperty("ieeeRegistrant", VendorDb.macVendor(s.mac))
            o.addProperty("deviceType", s.deviceType)
            o.addProperty("addressType", s.addressType.label)
            o.addProperty("trackability", s.addressType.trackable)
            o.add("radios", JsonArray().apply { s.sources.forEach { add(it.name) } })
            o.addProperty("rssi", s.rssi)
            o.addProperty("bestRssi", s.bestRssi)
            o.addProperty("distanceMetersRough", Math.round(s.distanceM * 10) / 10.0)
            o.addProperty("firstSeen", iso(s.firstSeen))
            o.addProperty("lastSeen", iso(s.lastSeen))
            o.addProperty("packets", s.sightings)
            o.addProperty("following", s.following)
            o.addProperty("whitelisted", flags.whitelisted(s.mac))
            o.addProperty("favorite", flags.favorite(s.mac))
            o.add("matches", JsonArray().apply {
                s.hits.forEach { h ->
                    add(JsonObject().apply {
                        addProperty("category", h.category.name)
                        addProperty("label", h.label)
                        addProperty("confidence", h.confidence)
                        addProperty("tier", h.tier.name)
                        addProperty("evidence", h.evidence)
                        addProperty("source", h.source)
                    })
                }
            })
            o.add("facts", JsonObject().apply { s.facts.forEach { (k, v) -> addProperty(k, v) } })
            s.known?.let { k ->
                o.add("history", JsonObject().apply {
                    addProperty("firstSeenEver", iso(k.firstSeen))
                    addProperty("sessions", k.sessions)
                    addProperty("detectCount", k.detectCount)
                })
            }
            s.remoteId?.let { o.add("remoteId", gson.toJsonTree(it)) }
            s.advert?.let { o.add("advertisement", advertJson(it)) }
            if (s.path.isNotEmpty()) {
                o.add("positions", JsonArray().apply {
                    s.path.forEach { p ->
                        add(JsonObject().apply {
                            addProperty("time", iso(p.time)); addProperty("lat", p.lat); addProperty("lon", p.lon)
                        })
                    }
                })
            }
            if (s.history.isNotEmpty()) {
                o.add("rssiHistory", JsonArray().apply {
                    s.history.forEach { h -> add(JsonArray().apply { add(h.time); add(h.rssi) }) }
                })
            }
            arr.add(o)
        }
        return arr
    }

    private fun advertJson(a: Advert): JsonObject = JsonObject().apply {
        a.txPower?.let { addProperty("txPower", it) }
        a.connectable?.let { addProperty("connectable", it) }
        a.phy?.let { addProperty("phy", it) }
        add("manufacturerData", JsonObject().apply {
            a.manufacturerData.forEach { (cid, d) ->
                addProperty(String.format("0x%04X", cid) + (VendorDb.company(cid)?.let { " $it" } ?: ""), Bytes.hex(d))
            }
        })
        add("serviceUuids", JsonArray().apply { a.serviceUuids.forEach { add(it.toString()) } })
        add("serviceData", JsonObject().apply { a.serviceData.forEach { (u, d) -> addProperty(u.toString(), Bytes.hex(d)) } })
        a.rawBytes?.let { raw ->
            addProperty("raw", Bytes.hex(raw, ""))
            add("adStructures", JsonArray().apply {
                AdStructure.parse(raw).forEach { ad ->
                    add(JsonObject().apply {
                        addProperty("type", String.format("0x%02X", ad.type))
                        addProperty("name", ad.typeName)
                        addProperty("data", Bytes.hex(ad.data))
                    })
                }
            })
        }
        a.wifi?.let { w ->
            add("wifi", JsonObject().apply {
                addProperty("frequencyMhz", w.frequencyMhz)
                addProperty("capabilities", w.capabilities)
                addProperty("standard", w.standard)
                add("infoElementIds", JsonArray().apply { w.infoElements.forEach { add(it.first) } })
            })
        }
    }

    /** KML of devices that have a GPS position (needs location while scanning). */
    fun sessionKml(devices: List<DeviceRegistry.Snapshot>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n  <name>RF Sentinel - nearby devices</name>\n")
        devices.filter { it.path.isNotEmpty() }.forEach { s ->
            val p = s.path.last()
            sb.append("  <Placemark>\n    <name>").append(x(s.best?.label ?: s.name ?: s.deviceType)).append("</name>\n")
                .append("    <description>").append(x("${s.mac} - ${s.vendor ?: ""} - ${s.rssi} dBm - ${local(s.lastSeen)}")).append("</description>\n")
                .append(String.format(Locale.US, "    <Point><coordinates>%.7f,%.7f,0</coordinates></Point>\n", p.lon, p.lat))
                .append("  </Placemark>\n")
        }
        return sb.append("</Document>\n</kml>\n").toString()
    }

    // ---- Long-term history ----------------------------------------------------------

    fun historyCsv(rows: List<KnownDeviceEntity>): String {
        val sb = StringBuilder("mac,name,vendor,device_type,first_seen,last_seen,sessions,detect_count,favorite,note\n")
        rows.forEach { r ->
            sb.append(r.mac).append(',').append(q(r.name)).append(',').append(q(r.vendor)).append(',')
                .append(q(r.deviceType)).append(',').append(local(r.firstSeen)).append(',').append(local(r.lastSeen)).append(',')
                .append(r.sessions).append(',').append(r.detectCount).append(',').append(r.favorite).append(',')
                .append(q(r.note)).append('\n')
        }
        return sb.toString()
    }

    fun historyJson(rows: List<KnownDeviceEntity>): JsonArray = JsonArray().apply {
        rows.forEach { r ->
            add(JsonObject().apply {
                addProperty("mac", r.mac); addProperty("name", r.name); addProperty("vendor", r.vendor)
                addProperty("deviceType", r.deviceType); addProperty("firstSeen", iso(r.firstSeen))
                addProperty("lastSeen", iso(r.lastSeen)); addProperty("sessions", r.sessions)
                addProperty("detectCount", r.detectCount); addProperty("favorite", r.favorite); addProperty("note", r.note)
            })
        }
    }

    fun matchesJson(rows: List<DetectionEntity>): JsonArray = gson.toJsonTree(rows).asJsonArray

    /** One file with everything: session, history, match log, watchlist entries. */
    fun everythingJson(
        appVersion: String,
        session: List<DeviceRegistry.Snapshot>,
        flags: Flags,
        history: List<KnownDeviceEntity>,
        matches: List<DetectionEntity>,
        watchlist: List<OuiEntry>
    ): String {
        val root = JsonObject()
        root.addProperty("exportedBy", "RF Sentinel $appVersion")
        root.addProperty("exportedAt", iso(System.currentTimeMillis()))
        root.add("sessionDevices", sessionJson(session, flags))
        root.add("deviceHistory", historyJson(history))
        root.add("matchLog", matchesJson(matches))
        root.add("watchlist", gson.toJsonTree(watchlist))
        return gson.toJson(root)
    }

    fun pretty(el: com.google.gson.JsonElement): String = gson.toJson(el)

    private fun q(s: String?): String = if (s == null) "" else "\"" + s.replace("\"", "\"\"") + "\""
    private fun x(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
