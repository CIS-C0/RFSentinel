package com.rfsentinel.app.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.TripPointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exports a recorded trace: your GPS track plus the devices heard along it
 * (placed where their signal was strongest). Pure Kotlin, unit-tested.
 */
object TripExport {

    private fun iso(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun local(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
    private fun x(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
    private fun q(s: String?): String = if (s == null) "" else "\"" + s.replace("\"", "\"\"") + "\""

    private fun describe(d: TripDeviceEntity) = buildString {
        append(d.mac)
        d.vendor?.let { append(" - ").append(it) }
        d.category?.let { append(" - ").append(it).append(' ').append(d.confidence).append('%') }
        append(" - best ").append(d.bestRssi).append(" dBm")
        d.evidence?.let { append(" - ").append(it) }
    }

    /** GPX 1.1: one track segment + a waypoint per positioned device. */
    fun gpx(trip: TripEntity, points: List<TripPointEntity>, devices: List<TripDeviceEntity>, onlyFlagged: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RF Sentinel\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(x(trip.name)).append("</name><time>").append(iso(trip.startTime)).append("</time></metadata>\n")
        devices.filter { it.lat != null && (!onlyFlagged || it.flagged) }.forEach { d ->
            sb.append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">\n", d.lat, d.lon))
            sb.append("    <time>").append(iso(d.lastSeen)).append("</time>\n")
            sb.append("    <name>").append(x(d.label)).append("</name>\n")
            sb.append("    <desc>").append(x(describe(d))).append("</desc>\n")
            sb.append("    <type>").append(x(d.category ?: "DEVICE")).append("</type>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("  <trk><name>").append(x(trip.name)).append("</name><trkseg>\n")
        points.forEach { p ->
            sb.append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n", p.lat, p.lon, iso(p.time)))
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** KML: the trace as a line, devices as placemarks (flagged in red, others grey). */
    fun kml(trip: TripEntity, points: List<TripPointEntity>, devices: List<TripDeviceEntity>, onlyFlagged: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("  <name>").append(x(trip.name)).append("</name>\n")
        sb.append("  <Style id=\"trace\"><LineStyle><color>ff2d62e0</color><width>4</width></LineStyle></Style>\n")
        sb.append("  <Style id=\"flagged\"><IconStyle><color>ff1a26b3</color></IconStyle></Style>\n")
        sb.append("  <Style id=\"device\"><IconStyle><color>ff9e9e9e</color><scale>0.6</scale></IconStyle></Style>\n")
        if (points.size >= 2) {
            sb.append("  <Placemark><name>Trace</name><styleUrl>#trace</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
            points.forEach { p -> sb.append(String.format(Locale.US, "    %.7f,%.7f,0\n", p.lon, p.lat)) }
            sb.append("  </coordinates></LineString></Placemark>\n")
        }
        devices.filter { it.lat != null && (!onlyFlagged || it.flagged) }.forEach { d ->
            sb.append("  <Placemark><name>").append(x(d.label)).append("</name>")
                .append("<styleUrl>#").append(if (d.flagged) "flagged" else "device").append("</styleUrl>")
                .append("<description>").append(x(describe(d) + " - " + local(d.firstSeen))).append("</description>")
                .append(String.format(Locale.US, "<Point><coordinates>%.7f,%.7f,0</coordinates></Point></Placemark>\n", d.lon, d.lat))
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    /** GeoJSON FeatureCollection (RFC 7946): LineString trace + Point devices. */
    fun geoJson(trip: TripEntity, points: List<TripPointEntity>, devices: List<TripDeviceEntity>, onlyFlagged: Boolean): String {
        val features = JsonArray()
        if (points.isNotEmpty()) {
            features.add(JsonObject().apply {
                addProperty("type", "Feature")
                add("geometry", JsonObject().apply {
                    addProperty("type", "LineString")
                    add("coordinates", JsonArray().apply {
                        points.forEach { p -> add(JsonArray().apply { add(p.lon); add(p.lat) }) }
                    })
                })
                add("properties", JsonObject().apply {
                    addProperty("kind", "trace"); addProperty("name", trip.name)
                    addProperty("start", iso(trip.startTime)); trip.endTime?.let { addProperty("end", iso(it)) }
                    addProperty("distanceM", Math.round(trip.distanceM))
                    add("times", JsonArray().apply { points.forEach { add(iso(it.time)) } })
                })
            })
        }
        devices.filter { it.lat != null && (!onlyFlagged || it.flagged) }.forEach { d ->
            features.add(JsonObject().apply {
                addProperty("type", "Feature")
                add("geometry", JsonObject().apply {
                    addProperty("type", "Point")
                    add("coordinates", JsonArray().apply { add(d.lon); add(d.lat) })
                })
                add("properties", JsonObject().apply {
                    addProperty("kind", "device"); addProperty("mac", d.mac); addProperty("label", d.label)
                    addProperty("name", d.name); addProperty("vendor", d.vendor); addProperty("deviceType", d.deviceType)
                    addProperty("source", d.source); addProperty("category", d.category); addProperty("confidence", d.confidence)
                    addProperty("evidence", d.evidence); addProperty("bestRssi", d.bestRssi)
                    addProperty("firstSeen", iso(d.firstSeen)); addProperty("lastSeen", iso(d.lastSeen))
                })
            })
        }
        val root = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", features)
        }
        return GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root)
    }

    /** Spreadsheet of every device heard on the trip. */
    fun devicesCsv(devices: List<TripDeviceEntity>): String {
        val sb = StringBuilder("mac,label,name,vendor,device_type,source,flagged,category,confidence,evidence,first_seen,last_seen,best_rssi,latitude,longitude\n")
        devices.forEach { d ->
            sb.append(d.mac).append(',').append(q(d.label)).append(',').append(q(d.name)).append(',')
                .append(q(d.vendor)).append(',').append(q(d.deviceType)).append(',').append(d.source).append(',')
                .append(d.flagged).append(',').append(d.category ?: "").append(',').append(d.confidence).append(',')
                .append(q(d.evidence)).append(',').append(local(d.firstSeen)).append(',').append(local(d.lastSeen)).append(',')
                .append(d.bestRssi).append(',').append(d.lat ?: "").append(',').append(d.lon ?: "").append('\n')
        }
        return sb.toString()
    }
}
