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
    /** When the last automatic download failed (0 = the last one worked), for the map's status line. */
    @Volatile var lastAutoFailureAt = 0L
        private set
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
            lastAutoFailureAt = 0L
            true
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex // the screen closed: not a failure, try again next time
        } catch (ex: Exception) {
            android.util.Log.w("AlprStore", "Automatic camera download failed: ${ex.message}")
            lastFailedArea = CameraArea(s, w, n, e, System.currentTimeMillis())
            lastAutoFailureAt = System.currentTimeMillis()
            false
        } finally {
            busy.set(false)
        }
    }

    /** Where a [downloadAround] is: [done] of [total] areas finished, [found] cameras so far. */
    data class Progress(val area: Int, val done: Int, val total: Int, val found: Int, val failed: Int, val detail: String)

    /**
     * Downloads every known camera within about [radiusKm] of a point, in tiles
     * of at most a degree (small requests get through busy servers more easily). Tiles
     * that fail are skipped - the map fetches them later. Returns how many
     * cameras were found, or throws when no tile could be downloaded.
     */
    suspend fun downloadAround(
        context: Context, lat: Double, lon: Double, radiusKm: Double = 100.0,
        onProgress: (Progress) -> Unit = {}
    ): Int {
        val tiles = CameraArea.tilesAround(lat, lon, radiusKm, MAX_SPAN_DEG / 2)
        fun name(i: Int) = if (tiles.size == 4) listOf("south-west", "south-east", "north-west", "north-east")[i] + " area"
            else "area ${i + 1} of ${tiles.size}"
        var found = 0
        var ok = 0
        var lastError: Exception? = null
        tiles.forEachIndexed { i, t ->
            val report = { detail: String -> onProgress(Progress(i + 1, i, tiles.size, found, i - ok, "${name(i)}: $detail")) }
            try {
                found += download(context, t[0], t[1], t[2], t[3], report)
                ok++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            onProgress(Progress(i + 1, i + 1, tiles.size, found, i + 1 - ok,
                if (ok == i + 1) "${name(i)} done" else "${name(i)} failed - the map will fetch it later"))
        }
        if (ok == 0) throw lastError ?: java.io.IOException("No answer")
        return found
    }

    /**
     * Downloads the cameras in a bounding box and merges them into the cache
     * (cameras deleted from OSM inside the box are dropped). Returns how many
     * cameras the box contains.
     */
    suspend fun download(
        context: Context, south: Double, west: Double, north: Double, east: Double,
        status: (String) -> Unit = {}
    ): Int =
        withContext(Dispatchers.IO) {
            require(north - south <= MAX_SPAN_DEG + 1e-9 && east - west <= MAX_SPAN_DEG + 1e-9) { "Area too large - zoom in" }
            val body = "data=" + URLEncoder.encode(KnownCameras.query(south, west, north, east), "UTF-8")
            // Public servers often answer 429/504 under load: one more round after a pause.
            val json = try {
                fetchFirst(body, status)
            } catch (e: java.io.IOException) {
                status("every server busy - retrying in ${RETRY_PAUSE_MS / 1000} s")
                kotlinx.coroutines.delay(RETRY_PAUSE_MS)
                fetchFirst(body, status)
            }
            status("reading the answer")
            val found = KnownCameras.parse(json)
            synchronized(this@AlprStore) {
                val inBox = { c: KnownCamera -> c.lat in south..north && c.lon in west..east }
                val merged = cameras.filterNot(inBox) + found
                file(context).writeText(gson.toJson(merged))
                cameras = merged
                val now = System.currentTimeMillis()
                val area = CameraArea(south, west, north, east, now)
                // Long-stale areas only cost space (their cameras stay cached for offline warnings).
                areas = areas.filterNot { area.contains(it) || now - it.time > 4 * REFRESH_MS } + area
                runCatching { areasFile(context).writeText(gson.toJson(areas)) }
            }
            found.size
        }

    private const val RETRY_PAUSE_MS = 5_000L

    /**
     * Asks the main server and every mirror at the same time; the first JSON
     * answer wins and the other requests are cancelled right away (their
     * connections closed). Throws an IOException when every server failed.
     */
    private suspend fun fetchFirst(body: String, status: (String) -> Unit): String {
        val requests = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val result = kotlinx.coroutines.CompletableDeferred<String>()
        val failures = kotlinx.coroutines.flow.MutableStateFlow(0)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val open = java.util.Collections.synchronizedList(mutableListOf<HttpURLConnection>())
        ENDPOINTS.forEachIndexed { i, endpoint ->
            requests.launch {
                if (result.isCompleted) return@launch
                val host = URL(endpoint).host
                status("asking $host")
                try {
                    val text = fetch(endpoint, body, { open += it }) { kb -> if (!result.isCompleted) status("receiving from $host: $kb KB") }
                    result.complete(text)
                } catch (e: Exception) {
                    val why = describe(e)
                    errors += "$host: $why"
                    val failed = failures.updateAndGet { it + 1 }
                    if (failed == ENDPOINTS.size) {
                        result.completeExceptionally(java.io.IOException("OpenStreetMap servers busy - try again later"))
                    } else if (!result.isCompleted) {
                        status("$host $why - trying another server")
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

    /** A short, human reason for a failed request. */
    private fun describe(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "didn't answer in time"
        e is java.net.UnknownHostException -> "unreachable (no internet?)"
        e.message == "HTTP 429" -> "is rate-limiting (HTTP 429)"
        e.message == "HTTP 504" || e.message == "HTTP 503" -> "is overloaded (${e.message})"
        e.message == "server error" -> "returned an error page"
        else -> "failed (${e.message ?: e.javaClass.simpleName})"
    }

    /** One Overpass request; throws unless the answer is a JSON result. Reports KB received. */
    private fun fetch(endpoint: String, body: String, onOpen: (HttpURLConnection) -> Unit, onKb: (Int) -> Unit = {}): String {
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
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var lastKb = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    val kb = out.size() / 1024
                    if (kb - lastKb >= 16) { lastKb = kb; onKb(kb) }
                }
            }
            val text = out.toString("UTF-8")
            // An overloaded server can answer 200 with an HTML error page.
            if (!text.trimStart().startsWith("{")) error("server error")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Swaps in a complete set of plate readers for [boxes] (DeFlock's snapshot):
     * plate-reader nodes inside them are replaced, everything else - speed and
     * red-light cameras, plate readers mapped as ways, other regions - is kept.
     */
    fun mergePlateReaders(context: Context, boxes: List<DoubleArray>, found: List<KnownCamera>) {
        synchronized(this) {
            val merged = mergeCameras(cameras, boxes, found)
            file(context).writeText(gson.toJson(merged))
            cameras = merged
        }
    }

    /**
     * Fetches the plate readers DeFlock's snapshot leaves out for a box (see
     * [KnownCameras.extrasQuery]) from Overpass and adds them. Returns how many.
     */
    suspend fun downloadExtras(context: Context, south: Double, west: Double, north: Double, east: Double): Int =
        withContext(Dispatchers.IO) {
            val body = "data=" + URLEncoder.encode(KnownCameras.extrasQuery(south, west, north, east), "UTF-8")
            val found = KnownCameras.parse(fetchFirst(body) {}).filter { it.type == KnownCamera.Kind.ALPR }
            mergePlateReaders(context, emptyList(), found)
            found.size
        }

    /**
     * Pure part of [mergePlateReaders] (unit-tested): adds the new cameras and
     * updates ones already cached (same OSM id). Nothing is removed - DeFlock's
     * snapshot leaves out some plate readers OpenStreetMap has (no
     * `man_made=surveillance`, mapped as ways, very recent), and the map's area
     * downloads may have fetched those. [boxes] is kept for callers' bookkeeping.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mergeCameras(current: List<KnownCamera>, boxes: List<DoubleArray>, found: List<KnownCamera>): List<KnownCamera> {
        val ids = found.mapTo(HashSet()) { it.osmId }
        return current.filterNot { it.osmId in ids } + found
    }

    /** Tests only: set the cache without files or network. */
    @androidx.annotation.VisibleForTesting
    fun setForTest(list: List<KnownCamera>) { cameras = list }

    // Synchronized with the merges, so a save that was mid-write can't bring cameras back.
    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        areasFile(context).delete()
        com.rfsentinel.app.util.Prefs.setDeflockUpdated(context, 0L)
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
         * The box within [radiusKm] of a point, split into a grid (at least 2x2)
         * of tiles no larger than [maxTileDeg] on a side: [[south, west, north, east], ...],
         * row by row from the south-west.
         */
        fun tilesAround(lat: Double, lon: Double, radiusKm: Double, maxTileDeg: Double): List<DoubleArray> {
            val dLat = radiusKm / 111.0
            val dLon = radiusKm / (111.0 * kotlin.math.max(0.05, kotlin.math.cos(Math.toRadians(lat))))
            val s = (lat - dLat).coerceAtLeast(-90.0); val n = (lat + dLat).coerceAtMost(90.0)
            val w = (lon - dLon).coerceAtLeast(-180.0); val e = (lon + dLon).coerceAtMost(180.0)
            val rows = kotlin.math.max(2, kotlin.math.ceil((n - s) / maxTileDeg - 1e-9).toInt())
            val cols = kotlin.math.max(2, kotlin.math.ceil((e - w) / maxTileDeg - 1e-9).toInt())
            return (0 until rows).flatMap { r ->
                (0 until cols).map { c ->
                    doubleArrayOf(
                        s + (n - s) * r / rows, w + (e - w) * c / cols,
                        s + (n - s) * (r + 1) / rows, w + (e - w) * (c + 1) / cols
                    )
                }
            }
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
