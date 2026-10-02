package com.rfsentinel.app.alpr

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every plate reader in the US and Canada in one go, from DeFlock's hourly
 * snapshot of OpenStreetMap (`man_made=surveillance` + `surveillance:type=ALPR`
 * nodes, served as 20-degree tiles on cdn.deflock.me). Same data the map gets
 * from Overpass, but complete instead of area by area. Only the tile files are
 * requested - no position is sent. Data © OpenStreetMap contributors, ODbL.
 */
object DeflockBulk {

    private const val INDEX_URL = "https://cdn.deflock.me/regions/index.json"
    /** Refreshed weekly (on Wi-Fi) once you've downloaded it. */
    const val REFRESH_MS = 7 * 24 * 3600_000L

    /**
     * The US (with Alaska) and Canada: south, west, north, east. 20°N keeps the
     * 0-20°N tiles (Central / South America) out; the map's area downloads still
     * cover anywhere else you look.
     */
    val NORTH_AMERICA = doubleArrayOf(20.0, -170.0, 84.0, -52.0)

    data class Index(val regions: List<String>, val tileUrl: String, val tileSizeDeg: Int)

    sealed interface State {
        data object Idle : State
        /** [fraction] of the DeFlock part (by bytes), 0..1. */
        data class Downloading(val done: Int, val total: Int, val found: Int, val fraction: Float = 0f, val detail: String = "") : State
        /** DeFlock's cameras are saved and on the map; OpenStreetMap is being checked for the ones it leaves out. */
        data class CheckingExtras(val cameras: Int) : State
        /** [extrasSkipped]: OpenStreetMap was too busy - those few extras come next time. */
        data class Done(val cameras: Int, val extrasSkipped: Boolean = false) : State
        data class Failed(val reason: String) : State
    }

    /** OpenStreetMap step limit: a busy public server must not hold up the result. */
    private const val EXTRAS_TIMEOUT_MS = 45_000L

    // ---- pure parts (unit-tested) ----

    fun parseIndex(json: String): Index {
        val o = JsonParser.parseString(json).asJsonObject
        return Index(
            regions = o.getAsJsonArray("regions").map { it.asString },
            tileUrl = o.get("tile_url").asString,
            tileSizeDeg = o.get("tile_size_degrees").asInt
        )
    }

    /** The available tiles ("lat/lon" of their south-west corner) that overlap a box. */
    fun tilesFor(index: Index, box: DoubleArray): List<String> {
        val (s, w, n, e) = box.toList()
        val size = index.tileSizeDeg
        return index.regions.filter { key ->
            val (lat, lon) = key.split('/').map { it.toInt() }
            lat < n && lat + size > s && lon < e && lon + size > w
        }
    }

    /** The bounding box of a tile key: south, west, north, east. */
    fun tileBox(key: String, size: Int): DoubleArray {
        val (lat, lon) = key.split('/').map { it.toDouble() }
        return doubleArrayOf(lat, lon, lat + size, lon + size)
    }

    fun tileUrl(index: Index, key: String) = index.tileUrl.replace("{lat}/{lon}", key)

    /** One tile: `[{"id":..,"lat":..,"lon":..,"tags":{..}}, ...]`, all plate readers. */
    fun parseTile(json: String): List<KnownCamera> =
        JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asLong ?: return@mapNotNull null
            val lat = o.get("lat")?.asDouble ?: return@mapNotNull null
            val lon = o.get("lon")?.asDouble ?: return@mapNotNull null
            val tags = o.getAsJsonObject("tags")
            fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { k -> tags?.get(k)?.asString?.takeIf { it.isNotBlank() } }
            KnownCamera(
                osmId = "node/$id", lat = lat, lon = lon,
                brand = tag("manufacturer", "surveillance:manufacturer", "brand", "surveillance:brand"),
                operator = tag("operator", "surveillance:operator"),
                direction = KnownCameras.parseDirection(tag("camera:direction", "direction")),
                kind = KnownCamera.Kind.ALPR
            )
        }

    // ---- download ----

    // Lazy: the pure parts above are unit-tested on the JVM, which has no main looper.
    private val scope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var job: Job? = null
    private val listeners = mutableSetOf<(State) -> Unit>()

    @Volatile var state: State = State.Idle
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    private fun set(s: State) = main.post {
        // Progress posted just before a cancel must not show up after it.
        if ((s is State.Downloading || s is State.CheckingExtras) && job?.isActive != true) return@post
        state = s
        listeners.toList().forEach { it(s) }
    }

    /** A square of about [radiusKm] around a point: south, west, north, east. */
    fun boxAround(lat: Double, lon: Double, radiusKm: Double): DoubleArray {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return doubleArrayOf(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    fun inBox(c: KnownCamera, b: DoubleArray) = c.lat >= b[0] && c.lon >= b[1] && c.lat < b[2] && c.lon < b[3]

    /**
     * Downloads (or refreshes) every plate reader in the US and Canada in the
     * background - or, with [around], only those within ~[radiusKm] of that point
     * (same tiles, filtered: fast, and the rest of the cache is left alone).
     */
    fun start(context: Context, around: Pair<Double, Double>? = null, radiusKm: Double = 100.0) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val area = around?.let { boxAround(it.first, it.second, radiusKm) }
        job = scope.launch {
            try {
                val index = parseIndex(withContext(Dispatchers.IO) { get(INDEX_URL) })
                val keys = tilesFor(index, area ?: NORTH_AMERICA)
                if (keys.isEmpty()) error(if (area != null) "No plate cameras are mapped around here" else "DeFlock has no tiles for this region")
                var found = 0
                val boxes = ArrayList<DoubleArray>()
                val cams = ArrayList<KnownCamera>()
                // Progress by bytes across all tiles; sizes come from each answer's Content-Length.
                var bytesBefore = 0L
                set(State.Downloading(0, keys.size, 0, 0f, "connecting to DeFlock"))
                keys.forEachIndexed { i, key ->
                    val text = withContext(Dispatchers.IO) {
                        get(tileUrl(index, key)) { read, total ->
                            // Unknown (or compressed, so smaller than what's read) sizes: keep the bar moving.
                            val perTile = if (total > 0 && read <= total) total else read + 1_000_000L
                            val frac = ((i + read.toFloat() / perTile) / keys.size).coerceIn(0f, 1f)
                            set(State.Downloading(i, keys.size, found, frac,
                                "region ${i + 1}/${keys.size}: ${mb(bytesBefore + read)} MB"))
                        }
                    }
                    bytesBefore += text.length
                    var tile = withContext(Dispatchers.Default) { parseTile(text) }
                    if (area != null) tile = tile.filter { inBox(it, area) }
                    cams += tile
                    boxes += area ?: tileBox(key, index.tileSizeDeg)
                    found += tile.size
                    set(State.Downloading(i + 1, keys.size, found, (i + 1f) / keys.size, "region ${i + 1}/${keys.size} done"))
                }
                withContext(Dispatchers.IO) { AlprStore.mergePlateReaders(app, boxes, cams) }
                if (area == null) Prefs.setDeflockUpdated(app, System.currentTimeMillis())
                refreshScanner(app)
                // DeFlock's cameras are on the map now. Then the plate readers it leaves out (other
                // tagging, ways, Flock cameras mapped as ordinary cameras), straight from OpenStreetMap -
                // best effort and time-limited, since the public servers are often busy.
                set(State.CheckingExtras(found))
                val b = area ?: NORTH_AMERICA
                val extras = runCatching {
                    kotlinx.coroutines.withTimeout(EXTRAS_TIMEOUT_MS) { AlprStore.downloadExtras(app, b[0], b[1], b[2], b[3]) }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it
                    android.util.Log.w("DeflockBulk", "extras skipped: ${it.message}")
                }.getOrNull()
                if (extras != null && extras > 0) refreshScanner(app)
                set(State.Done(found + (extras ?: 0), extrasSkipped = extras == null))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("DeflockBulk", "download failed: ${e.message}")
                set(State.Failed(when (e) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "DeFlock didn't answer in time - try again later"
                    else -> "Download failed (${e.message ?: e.javaClass.simpleName})"
                }))
            }
        }
    }

    /** Stops a running download (e.g. the downloaded cameras are being deleted). */
    fun cancel() {
        job?.cancel()
        job = null
        set(State.Idle)
    }

    /** Weekly refresh, only after a first manual download and only on an unmetered network. */
    fun refreshIfDue(context: Context) {
        val last = Prefs.deflockUpdated(context)
        if (last == 0L || System.currentTimeMillis() - last < REFRESH_MS || isRunning) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        if (cm.isActiveNetworkMetered) return
        start(context)
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / 1_000_000.0)

    /** A running scan starts watching for the new cameras. */
    private fun refreshScanner(app: Context) {
        if (com.rfsentinel.app.service.ScanForegroundService.isRunning) runCatching {
            app.startService(android.content.Intent(app, com.rfsentinel.app.service.ScanForegroundService::class.java)
                .setAction(com.rfsentinel.app.service.ScanForegroundService.ACTION_REFRESH_LOCATION))
        }
    }

    /** GET as text; [onProgress] gets (bytes read, total or -1) about every 64 KB. */
    private fun get(url: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val out = java.io.ByteArrayOutputStream(if (total in 1..64_000_000) total.toInt() else 1 shl 16)
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() - last >= 64 * 1024) { last = out.size().toLong(); onProgress(last, total) }
                }
            }
            onProgress(out.size().toLong(), total)
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }
}
