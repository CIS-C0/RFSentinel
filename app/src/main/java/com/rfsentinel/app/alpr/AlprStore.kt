package com.rfsentinel.app.alpr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Offline cache of known plate, speed and red-light cameras, downloaded for the
 * map area you look at (automatically, unless turned off). The download tells the Overpass server
 * which area you're looking at (like map tiles do) - nothing else is sent.
 */
object AlprStore {

    private const val FILE = "known_alpr.json"
    /**
     * The main Overpass server, then public mirrors for when it's overloaded
     * (it answers with a runtime error under load). Mirrors may lag a few months.
     */
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    /** Largest area one download may cover (degrees of latitude / longitude). */
    const val MAX_SPAN_DEG = 2.0

    private const val AREAS_FILE = "known_alpr_areas.json"
    /** Downloaded areas are fetched again after this long. */
    const val REFRESH_MS = 7 * 24 * 3600_000L
    /** After a failed automatic download, wait this long before trying again. */
    private const val RETRY_MS = 60_000L

    private val gson = Gson()
    private val listType = object : TypeToken<List<KnownCamera>>() {}.type
    private val areaListType = object : TypeToken<List<CameraArea>>() {}.type

    @Volatile var cameras: List<KnownCamera> = emptyList()
        private set
    /** Areas already downloaded, so the map doesn't fetch them again. */
    @Volatile var areas: List<CameraArea> = emptyList()
        private set

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFailedArea: CameraArea? = null
    val isBusy: Boolean get() = busy.get()

    private fun file(context: Context) = File(context.filesDir, FILE)
    private fun areasFile(context: Context) = File(context.filesDir, AREAS_FILE)

    fun load(context: Context) {
        cameras = runCatching {
            file(context).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<KnownCamera>>(it, listType) }
        }.getOrNull().orEmpty()
        areas = runCatching {
            areasFile(context).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<CameraArea>>(it, areaListType) }
        }.getOrNull().orEmpty()
    }

    /** True when the box isn't inside an area downloaded in the last [REFRESH_MS]. */
    fun needsDownload(south: Double, west: Double, north: Double, east: Double, now: Long = System.currentTimeMillis()) =
        CameraArea.needsDownload(areas, south, west, north, east, now, REFRESH_MS)

    /**
     * Fetches the cameras around a box in the background when that area isn't
     * cached yet (one download at a time; quiet back-off after a failure).
     * Returns true when a download ran and changed the cache.
     */
    suspend fun autoDownload(context: Context, south: Double, west: Double, north: Double, east: Double): Boolean {
        val now = System.currentTimeMillis()
        if (!needsDownload(south, west, north, east, now)) return false
        // After a failure, leave that same area alone for a minute (other areas may go ahead).
        if (lastFailedArea?.let { now - it.time < RETRY_MS && it.contains(south, west, north, east) } == true) return false
        if (!busy.compareAndSet(false, true)) return false
        val (s, w, n, e) = CameraArea.expand(south, west, north, east, MAX_SPAN_DEG)
        return try {
            download(context, s, w, n, e)
            true
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex // the screen closed: not a failure, try again next time
        } catch (ex: Exception) {
            android.util.Log.w("AlprStore", "Automatic camera download failed: ${ex.message}")
            lastFailedArea = CameraArea(s, w, n, e, System.currentTimeMillis())
            false
        } finally {
            busy.set(false)
        }
    }

    /**
     * Downloads every known camera within about [radiusKm] of a point, in up to
     * four tiles (small requests get through busy servers more easily). Tiles
     * that fail are skipped - the map fetches them later. Returns how many
     * cameras were found, or throws when no tile could be downloaded.
     */
    suspend fun downloadAround(
        context: Context, lat: Double, lon: Double, radiusKm: Double = 100.0,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        val tiles = CameraArea.tilesAround(lat, lon, radiusKm, MAX_SPAN_DEG / 2)
        var found = 0
        var ok = 0
        var lastError: Exception? = null
        tiles.forEachIndexed { i, t ->
            try {
                found += download(context, t[0], t[1], t[2], t[3])
                ok++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            onProgress(i + 1, tiles.size)
        }
        if (ok == 0) throw lastError ?: java.io.IOException("No answer")
        return found
    }

    /**
     * Downloads the cameras in a bounding box and merges them into the cache
     * (cameras deleted from OSM inside the box are dropped). Returns how many
     * cameras the box contains.
     */
    suspend fun download(context: Context, south: Double, west: Double, north: Double, east: Double): Int =
        withContext(Dispatchers.IO) {
            require(north - south <= MAX_SPAN_DEG + 1e-9 && east - west <= MAX_SPAN_DEG + 1e-9) { "Area too large - zoom in" }
            val body = "data=" + URLEncoder.encode(KnownCameras.query(south, west, north, east), "UTF-8")
            // Public servers often answer 429/504 under load: one more round after a pause.
            val json = try {
                fetchFirst(body)
            } catch (e: java.io.IOException) {
                kotlinx.coroutines.delay(RETRY_PAUSE_MS)
                fetchFirst(body)
            }
            val found = KnownCameras.parse(json)
            synchronized(this@AlprStore) {
                val inBox = { c: KnownCamera -> c.lat in south..north && c.lon in west..east }
                val merged = cameras.filterNot(inBox) + found
                file(context).writeText(gson.toJson(merged))
                cameras = merged
                val area = CameraArea(south, west, north, east, System.currentTimeMillis())
                areas = areas.filterNot { area.contains(it) } + area
                runCatching { areasFile(context).writeText(gson.toJson(areas)) }
            }
            found.size
        }

    private const val STAGGER_MS = 4_000L
    private const val RETRY_PAUSE_MS = 5_000L

    /**
     * Asks the main server first and each mirror a few seconds later (at once
     * if the earlier ones already failed); the first JSON answer wins and the
     * others are abandoned. Throws an IOException when every server failed.
     */
    private suspend fun fetchFirst(body: String): String {
        val requests = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val result = kotlinx.coroutines.CompletableDeferred<String>()
        val failures = kotlinx.coroutines.flow.MutableStateFlow(0)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val open = java.util.Collections.synchronizedList(mutableListOf<HttpURLConnection>())
        ENDPOINTS.forEachIndexed { i, endpoint ->
            requests.launch {
                if (i > 0) kotlinx.coroutines.withTimeoutOrNull(STAGGER_MS * i) { failures.first { it >= i } }
                if (result.isCompleted) return@launch
                try {
                    result.complete(fetch(endpoint, body) { open += it })
                } catch (e: Exception) {
                    errors += "${URL(endpoint).host}: ${e.message}"
                    if (failures.updateAndGet { it + 1 } == ENDPOINTS.size) {
                        result.completeExceptionally(java.io.IOException("OpenStreetMap servers busy - try again later"))
                    }
                }
            }
        }
        return try {
            result.await()
        } finally {
            requests.cancel()
            synchronized(open) { open.forEach { runCatching { it.disconnect() } } }
            if (!result.isCompleted || errors.isNotEmpty()) android.util.Log.i("AlprStore", "Overpass: $errors")
        }
    }

    /** One Overpass request; throws unless the answer is a JSON result. */
    private fun fetch(endpoint: String, body: String, onOpen: (HttpURLConnection) -> Unit): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        onOpen(conn)
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            // An overloaded server can answer 200 with an HTML error page.
            if (!text.trimStart().startsWith("{")) error("server error")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Tests only: set the cache without files or network. */
    @androidx.annotation.VisibleForTesting
    fun setForTest(list: List<KnownCamera>) { cameras = list }

    fun clear(context: Context) {
        file(context).delete()
        areasFile(context).delete()
        cameras = emptyList()
        areas = emptyList()
    }
}

/** A downloaded bounding box and when it was fetched (pure; unit-tested). */
data class CameraArea(val south: Double, val west: Double, val north: Double, val east: Double, val time: Long) {

    fun contains(o: CameraArea) = contains(o.south, o.west, o.north, o.east)

    fun contains(s: Double, w: Double, n: Double, e: Double) =
        s >= south && w >= west && n <= north && e <= east

    companion object {
        /**
         * True unless the box is covered by fresh areas. Several areas together
         * count (e.g. the tiles downloaded around you at setup): a 3x3 grid of
         * points across the box must each fall inside one of them.
         */
        fun needsDownload(areas: List<CameraArea>, s: Double, w: Double, n: Double, e: Double, now: Long, maxAgeMs: Long): Boolean {
            val fresh = areas.filter { now - it.time < maxAgeMs }
            if (fresh.any { it.contains(s, w, n, e) }) return false
            for (i in 0..2) for (j in 0..2) {
                val lat = s + (n - s) * i / 2
                val lon = w + (e - w) * j / 2
                if (fresh.none { it.contains(lat, lon, lat, lon) }) return true
            }
            return false
        }

        /**
         * The box within [radiusKm] of a point, split into 2x2 tiles no larger
         * than [maxTileDeg] on a side: [[south, west, north, east], ...].
         */
        fun tilesAround(lat: Double, lon: Double, radiusKm: Double, maxTileDeg: Double): List<DoubleArray> {
            val dLat = (radiusKm / 111.0).coerceAtMost(maxTileDeg)
            val dLon = (radiusKm / (111.0 * kotlin.math.max(0.05, kotlin.math.cos(Math.toRadians(lat))))).coerceAtMost(maxTileDeg)
            val s = (lat - dLat).coerceAtLeast(-90.0); val n = (lat + dLat).coerceAtMost(90.0)
            val w = (lon - dLon).coerceAtLeast(-180.0); val e = (lon + dLon).coerceAtMost(180.0)
            return listOf(
                doubleArrayOf(s, w, lat, lon), doubleArrayOf(s, lon, lat, e),
                doubleArrayOf(lat, w, n, lon), doubleArrayOf(lat, lon, n, e)
            )
        }

        /** Smallest box one automatic download covers (degrees, ~40 km). */
        const val MIN_SPAN_DEG = 0.4

        /**
         * Grows the visible box to twice its size, and at least [MIN_SPAN_DEG],
         * so panning around doesn't fetch again right away - without exceeding
         * [maxSpan] degrees.
         */
        fun expand(s: Double, w: Double, n: Double, e: Double, maxSpan: Double): List<Double> {
            fun pad(span: Double) = (((span * 2).coerceIn(MIN_SPAN_DEG, maxSpan) - span) / 2).coerceAtLeast(0.0)
            val latPad = pad(n - s)
            val lonPad = pad(e - w)
            return listOf(
                (s - latPad).coerceAtLeast(-90.0), (w - lonPad).coerceAtLeast(-180.0),
                (n + latPad).coerceAtMost(90.0), (e + lonPad).coerceAtMost(180.0)
            )
        }
    }
}
