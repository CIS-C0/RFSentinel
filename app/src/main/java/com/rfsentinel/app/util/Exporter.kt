package com.rfsentinel.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.oui.OuiWatchlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exports the match log (CSV with every field, GPX / KML of GPS-tagged
 * matches for any maps app) and the user's watchlist (JSON). Files go to the
 * app cache and leave the phone only through the share sheet the user picks.
 */
object Exporter {

    /** What can be exported, and in which formats. */
    private enum class Scope(val title: String, val formats: List<String>) {
        EVERYTHING("Everything (one JSON file)", listOf("json")),
        SESSION("All nearby devices - this scan (matched or not)", listOf("csv", "json", "kml")),
        HISTORY("All devices ever seen - history", listOf("csv", "json")),
        MATCHES("Match log only", listOf("csv", "json", "gpx", "kml")),
        WATCHLIST("My watchlist entries", listOf("json"))
    }

    private val FORMAT_LABELS = mapOf(
        "csv" to "CSV - spreadsheet (Excel, Sheets)",
        "json" to "JSON - full detail, for tools and scripts",
        "kml" to "KML - map (Google Earth / My Maps)",
        "gpx" to "GPX - map / GPS apps"
    )

    /** Two steps: what to export, then which format. */
    fun showExportMenu(activity: AppCompatActivity) {
        val scopes = Scope.entries
        AlertDialog.Builder(activity)
            .setTitle("Export")
            .setItems(scopes.map { it.title }.toTypedArray()) { _, which -> chooseFormat(activity, scopes[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shortcut straight to the "all nearby devices" formats. */
    fun showExportAll(activity: AppCompatActivity) = chooseFormat(activity, Scope.SESSION)

    private fun chooseFormat(activity: AppCompatActivity, scope: Scope) {
        if (scope.formats.size == 1) {
            run(activity, scope, scope.formats[0])
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(scope.title)
            .setItems(scope.formats.map { FORMAT_LABELS.getValue(it) }.toTypedArray()) { _, which ->
                run(activity, scope, scope.formats[which])
            }
            .setNegativeButton("Back") { _, _ -> showExportMenu(activity) }
            .show()
    }

    private fun run(activity: AppCompatActivity, scope: Scope, format: String) {
        when (scope) {
            Scope.MATCHES -> exportLog(activity, format)
            Scope.WATCHLIST -> exportWatchlist(activity)
            else -> exportDevices(activity, scope, format)
        }
    }

    private val flags = DeviceExport.Flags(
        whitelisted = { com.rfsentinel.app.data.WhitelistCache.contains(it) },
        favorite = { com.rfsentinel.app.data.Favorites.contains(it) }
    )

    private fun exportDevices(activity: AppCompatActivity, scope: Scope, format: String) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getInstance(activity)
            // Full snapshots include the raw advertisement, RSSI history and positions.
            val session = com.rfsentinel.app.service.DeviceRegistry.snapshot()
                .mapNotNull { com.rfsentinel.app.service.DeviceRegistry.get(it.mac) }
                .sortedWith(compareByDescending<com.rfsentinel.app.service.DeviceRegistry.Snapshot> { it.best != null }.thenByDescending { it.rssi })
            val (name, text, mime) = withContext(Dispatchers.Default) {
                when (scope) {
                    Scope.SESSION -> {
                        if (session.isEmpty()) return@withContext null
                        when (format) {
                            "csv" -> Triple("nearby_devices", DeviceExport.sessionCsv(session, flags), "text/csv")
                            "kml" -> {
                                if (session.none { it.path.isNotEmpty() }) return@withContext Triple("", "", "nogps")
                                Triple("nearby_devices", DeviceExport.sessionKml(session), "application/vnd.google-earth.kml+xml")
                            }
                            else -> Triple("nearby_devices", DeviceExport.pretty(DeviceExport.sessionJson(session, flags)), "application/json")
                        }
                    }
                    Scope.HISTORY -> {
                        val rows = db.knownDeviceDao().all()
                        if (rows.isEmpty()) return@withContext null
                        if (format == "csv") Triple("device_history", DeviceExport.historyCsv(rows), "text/csv")
                        else Triple("device_history", DeviceExport.pretty(DeviceExport.historyJson(rows)), "application/json")
                    }
                    else -> Triple(
                        "full_export",
                        DeviceExport.everythingJson(
                            com.rfsentinel.app.BuildConfig.VERSION_NAME, session, flags,
                            db.knownDeviceDao().all(), db.detectionDao().allForExport(),
                            OuiWatchlist.allEntries().filter { it.isCustom }
                        ),
                        "application/json"
                    )
                }
            } ?: run {
                toast(activity, if (scope == Scope.SESSION) "No devices heard yet - start scanning first" else "No device history yet")
                return@launch
            }
            if (mime == "nogps") {
                toast(activity, "No GPS positions yet - turn on location while scanning (follower alerts or GPS tagging)")
                return@launch
            }
            share(activity, "rf_sentinel_${name}_${stamp()}.$format", text, mime, "Export")
        }
    }

    /** Export one recorded trace: pick a format, then whether to include every device. */
    fun showTripExport(activity: AppCompatActivity, tripId: Long) {
        val formats = listOf("gpx", "kml", "geojson", "csv")
        val labels = arrayOf(
            "GPX - track + device waypoints (GPS / map apps)",
            "KML - Google Earth / My Maps",
            "GeoJSON - web maps, QGIS",
            "CSV - devices heard on this trace (spreadsheet)"
        )
        AlertDialog.Builder(activity)
            .setTitle("Export trace")
            .setItems(labels) { _, which ->
                val format = formats[which]
                if (format == "csv") {
                    exportTrip(activity, tripId, format, onlyFlagged = false)
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("Which devices?")
                        .setItems(arrayOf("All devices heard on the trace", "Flagged devices only")) { _, w ->
                            exportTrip(activity, tripId, format, onlyFlagged = w == 1)
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportTrip(activity: AppCompatActivity, tripId: Long, format: String, onlyFlagged: Boolean) {
        activity.lifecycleScope.launch {
            // Make sure the latest buffered points are on disk before reading.
            if (com.rfsentinel.app.service.TripRecorder.activeTripId == tripId) {
                runCatching { com.rfsentinel.app.service.TripRecorder.flush(activity) }
            }
            val dao = AppDatabase.getInstance(activity).tripDao()
            val trip = dao.get(tripId) ?: run { toast(activity, "Trace not found"); return@launch }
            val points = dao.points(tripId)
            val devices = dao.devices(tripId)
            if (points.isEmpty() && devices.none { it.lat != null } && format != "csv") {
                toast(activity, "This trace has no GPS points yet")
                return@launch
            }
            val (text, mime, ext) = withContext(Dispatchers.Default) {
                when (format) {
                    "gpx" -> Triple(TripExport.gpx(trip, points, devices, onlyFlagged), "application/gpx+xml", "gpx")
                    "kml" -> Triple(TripExport.kml(trip, points, devices, onlyFlagged), "application/vnd.google-earth.kml+xml", "kml")
                    "geojson" -> Triple(TripExport.geoJson(trip, points, devices, onlyFlagged), "application/geo+json", "geojson")
                    else -> Triple(TripExport.devicesCsv(devices), "text/csv", "csv")
                }
            }
            val safeName = trip.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40)
            share(activity, "rf_sentinel_trace_${safeName}.$ext", text, mime, "Export trace")
        }
    }

    fun exportLog(activity: AppCompatActivity, format: String) {
        activity.lifecycleScope.launch {
            val rows = AppDatabase.getInstance(activity).detectionDao().allForExport()
            val usable = if (format == "csv" || format == "json") rows else rows.filter { it.latitude != null && it.longitude != null }
            if (usable.isEmpty()) {
                toast(
                    activity,
                    if (format == "csv" || format == "json") "No matches logged yet"
                    else "No GPS-tagged matches yet - turn on GPS tagging in Settings"
                )
                return@launch
            }
            val text = when (format) {
                "gpx" -> gpx(usable)
                "kml" -> kml(usable)
                "json" -> DeviceExport.pretty(DeviceExport.matchesJson(usable))
                else -> csv(usable)
            }
            val mime = when (format) {
                "gpx" -> "application/gpx+xml"
                "kml" -> "application/vnd.google-earth.kml+xml"
                "json" -> "application/json"
                else -> "text/csv"
            }
            share(activity, "rf_sentinel_matches_${stamp()}.$format", text, mime, "Export match log")
        }
    }

    private fun exportWatchlist(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val json = OuiWatchlist.exportCustomJson(activity)
            share(activity, "rf_sentinel_watchlist_${stamp()}.json", json, "application/json", "Export watchlist")
        }
    }

    fun csv(rows: List<DetectionEntity>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("timestamp,mac,label,category,confidence,source,rssi,vendor,device_name,evidence,latitude,longitude\n")
        rows.forEach { r ->
            sb.append(fmt.format(Date(r.timestamp))).append(',')
                .append(r.mac).append(',')
                .append(q(r.label)).append(',')
                .append(r.category).append(',')
                .append(r.confidence).append(',')
                .append(r.source).append(',')
                .append(r.rssi).append(',')
                .append(q(r.vendor)).append(',')
                .append(q(r.deviceName)).append(',')
                .append(q(r.evidence)).append(',')
                .append(r.latitude ?: "").append(',')
                .append(r.longitude ?: "").append('\n')
        }
        return sb.toString()
    }

    private fun q(s: String?): String = ExportText.csv(s)

    fun gpx(rows: List<DetectionEntity>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RF Sentinel\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        rows.forEach { r ->
            sb.append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">\n", r.latitude, r.longitude))
            sb.append("    <time>").append(iso.format(Date(r.timestamp))).append("</time>\n")
            sb.append("    <name>").append(x(r.label)).append("</name>\n")
            sb.append("    <desc>").append(x("${r.category} ${r.confidence}% - ${r.mac} - ${r.rssi} dBm - ${r.evidence}")).append("</desc>\n")
            sb.append("    <type>").append(x(r.category)).append("</type>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun kml(rows: List<DetectionEntity>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("  <name>RF Sentinel matches</name>\n")
        rows.forEach { r ->
            sb.append("  <Placemark>\n")
            sb.append("    <name>").append(x(r.label)).append("</name>\n")
            sb.append("    <description>").append(x("${fmt.format(Date(r.timestamp))} - ${r.category} ${r.confidence}% - ${r.mac} - ${r.rssi} dBm")).append("</description>\n")
            sb.append(String.format(Locale.US, "    <Point><coordinates>%.7f,%.7f,0</coordinates></Point>\n", r.longitude, r.latitude))
            sb.append("  </Placemark>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    private fun x(s: String): String = ExportText.xml(s)

    internal fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    internal suspend fun share(context: Context, fileName: String, content: String, mime: String, title: String) {
        val uri: Uri? = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeText(content)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                null
            }
        }
        if (uri == null) {
            toast(context, "Export failed")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
