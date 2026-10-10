package com.rfsentinel.app.util

import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.DocumentsContract
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.service.DeviceRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Settings > Data > Live export (off by default): while scanning, keeps three files up to date
 * (every 30 s) in a folder the user picks, outside the app's private storage, so another
 * mapping app can load them close to live:
 *  - rfsentinel_live_matches.csv: every match of this scan (devices and radio hits);
 *  - rfsentinel_live_matches.kml: the same matches that carry a position;
 *  - rfsentinel_live.geojson: every device placed on the map now, plus your position.
 * The folder is reached through Android's storage-access framework (no storage permission).
 */
object LiveExport {

    const val CSV = "rfsentinel_live_matches.csv"
    const val KML = "rfsentinel_live_matches.kml"
    const val GEOJSON = "rfsentinel_live.geojson"
    private const val EVERY_MS = 30_000L

    @Volatile var status: String = ""; private set
    @Volatile private var lastWrite = 0L
    private val uris = HashMap<String, Uri>()

    /** Writes the files when the setting is on and 30 s have passed. Call from a background thread. */
    suspend fun maybeWrite(context: Context, sessionStart: Long, here: Location?) {
        if (!Prefs.liveExport(context)) return
        val now = System.currentTimeMillis()
        if (now - lastWrite < EVERY_MS) return
        lastWrite = now
        val tree = Prefs.liveExportTree(context)?.let(Uri::parse) ?: run { status = "Live export: choose a folder in Settings > Data"; return }
        try {
            val rows = AppDatabase.getInstance(context).detectionDao().since(sessionStart)
            write(context, tree, CSV, "text/csv", Exporter.csv(rows))
            write(context, tree, KML, "application/vnd.google-earth.kml+xml", Exporter.kml(rows))
            write(context, tree, GEOJSON, "application/geo+json", geoJson(DeviceRegistry.snapshot(now), here, now))
            status = "Live export: updated " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(Date(now))
        } catch (e: Exception) {
            synchronized(uris) { uris.clear() }
            status = "Live export failed: ${e.message ?: e.javaClass.simpleName} (pick the folder again in Settings > Data)"
        }
    }

    /** Forget cached file handles (a new folder was picked). */
    fun reset() { synchronized(uris) { uris.clear() }; lastWrite = 0L; status = "" }

    private fun write(context: Context, tree: Uri, name: String, mime: String, text: String) {
        val uri = fileUri(context, tree, name, mime) ?: throw java.io.IOException("can't create $name")
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("can't write $name")
    }

    /** The file [name] in the picked folder, created on first use. */
    private fun fileUri(context: Context, tree: Uri, name: String, mime: String): Uri? {
        synchronized(uris) { uris[name]?.let { return it } }
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val found = context.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            var id: String? = null
            while (c.moveToNext()) if (c.getString(1) == name) { id = c.getString(0); break }
            id
        }
        val uri = found?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeId), mime, name)
        if (uri != null) synchronized(uris) { uris[name] = uri }
        return uri
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Devices where they were pinpointed, else at the spot they were heard strongest, plus "you are here" (pure; unit-tested). */
    fun geoJson(devices: List<DeviceRegistry.Snapshot>, here: Location?, now: Long): String {
        val features = JSONArray()
        for (s in devices) {
            val p = s.place ?: continue
            val best = s.best
            features.put(JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                .put("properties", JSONObject()
                    .put("mac", s.mac)
                    .put("name", s.name ?: JSONObject.NULL)
                    .put("vendor", s.vendor ?: JSONObject.NULL)
                    .put("type", s.deviceType)
                    .put("flagged", best != null)
                    .put("pinpointed_m", s.located?.radiusM?.let { Math.round(it) } ?: JSONObject.NULL)
                    .put("label", best?.label ?: JSONObject.NULL)
                    .put("category", best?.category?.name ?: JSONObject.NULL)
                    .put("confidence", best?.confidence ?: 0)
                    .put("rssi", s.rssi)
                    .put("last_seen", iso.format(Date(s.lastSeen)))))
        }
        if (here != null) features.put(JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(here.longitude).put(here.latitude)))
            .put("properties", JSONObject().put("name", "You (RF Sentinel)").put("updated", iso.format(Date(now)))))
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }
}
