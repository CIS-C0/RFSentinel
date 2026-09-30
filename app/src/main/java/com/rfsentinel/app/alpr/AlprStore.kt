package com.rfsentinel.app.alpr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Offline cache of known plate-reader cameras, downloaded only when the user
 * asks for a map area. The download tells the OpenStreetMap Overpass server
 * which area you're looking at (like map tiles do) - nothing else is sent.
 */
object AlprStore {

    private const val FILE = "known_alpr.json"
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    /** Largest area one download may cover (degrees of latitude / longitude). */
    const val MAX_SPAN_DEG = 2.0

    private val gson = Gson()
    private val listType = object : TypeToken<List<KnownCamera>>() {}.type

    @Volatile var cameras: List<KnownCamera> = emptyList()
        private set

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context) {
        cameras = runCatching {
            file(context).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<KnownCamera>>(it, listType) }
        }.getOrNull().orEmpty()
    }

    /**
     * Downloads the cameras in a bounding box and merges them into the cache
     * (cameras deleted from OSM inside the box are dropped). Returns how many
     * cameras the box contains.
     */
    suspend fun download(context: Context, south: Double, west: Double, north: Double, east: Double): Int =
        withContext(Dispatchers.IO) {
            require(north - south <= MAX_SPAN_DEG && east - west <= MAX_SPAN_DEG) { "Area too large - zoom in" }
            val body = "data=" + URLEncoder.encode(KnownCameras.query(south, west, north, east), "UTF-8")
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) error("OpenStreetMap server busy (HTTP ${conn.responseCode}) - try again later")
                val found = KnownCameras.parse(conn.inputStream.bufferedReader().use { it.readText() })
                val inBox = { c: KnownCamera -> c.lat in south..north && c.lon in west..east }
                val merged = cameras.filterNot(inBox) + found
                file(context).writeText(gson.toJson(merged))
                cameras = merged
                found.size
            } finally {
                conn.disconnect()
            }
        }

    fun clear(context: Context) {
        file(context).delete()
        cameras = emptyList()
    }
}
