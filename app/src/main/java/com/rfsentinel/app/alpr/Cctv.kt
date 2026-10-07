package com.rfsentinel.app.alpr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * An ordinary surveillance camera mapped in OpenStreetMap (`man_made=surveillance`,
 * `surveillance:type=camera`): city street cameras, shop and building cameras. Mostly wired,
 * so no radio can detect them - the map is the only way to see them. The same data the
 * "Surveillance under Surveillance" map (sunders) shows.
 */
data class CctvCamera(
    val osmId: String,
    val lat: Double,
    val lon: Double,
    /** `surveillance`: public, outdoor (private, facing outside), indoor - or null if not mapped. */
    val zone: String?,
    /** `camera:type`: fixed, dome, panning. */
    val cameraType: String?,
    /** `camera:direction` in degrees (0 = north), if mapped. */
    val direction: Int?,
    /** `camera:angle`: the view's width in degrees, if mapped. */
    val angle: Int?,
    /** Height above the ground in metres, if mapped. */
    val height: Double?,
    val operator: String?,
    /** `camera:mount`: pole, wall, ceiling... */
    val mount: String?,
    /** `surveillance:zone`: town, traffic, shop, parking... */
    val area: String?
) {
    /** Public street cameras, or unmapped (shown by default); private outdoor / indoor ones only on request. */
    val isPrivate get() = zone.equals("outdoor", true) || zone.equals("indoor", true)
}

object Cctv {

    /** Overpass query for the surveillance cameras in a box (plate readers have their own layer). */
    fun query(south: Double, west: Double, north: Double, east: Double): String {
        val b = "($south,$west,$north,$east)"
        return "[out:json][timeout:60];" +
            "nwr[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"camera\"]$b;" +
            "out tags center qt;"
    }

    fun parse(json: String): List<CctvCamera> {
        val root = JsonParser.parseString(json).asJsonObject
        val elements = root.getAsJsonArray("elements") ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el.asJsonObject
            val type = o.get("type")?.asString ?: return@mapNotNull null
            val id = o.get("id")?.asLong ?: return@mapNotNull null
            val pos = if (o.has("lat")) o else o.getAsJsonObject("center") ?: return@mapNotNull null
            val lat = pos.get("lat")?.asDouble ?: return@mapNotNull null
            val lon = pos.get("lon")?.asDouble ?: return@mapNotNull null
            val tags = o.getAsJsonObject("tags")
            fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { k -> tags?.get(k)?.asString?.trim()?.takeIf { it.isNotEmpty() } }
            CctvCamera(
                osmId = "$type/$id", lat = lat, lon = lon,
                zone = tag("surveillance")?.lowercase(),
                cameraType = tag("camera:type")?.lowercase(),
                direction = KnownCameras.parseDirection(tag("camera:direction", "direction")),
                angle = tag("camera:angle")?.let(::parseAngle),
                height = tag("height", "camera:height")?.let(::parseHeight),
                operator = tag("operator", "surveillance:operator"),
                mount = tag("camera:mount")?.lowercase(),
                area = tag("surveillance:zone")?.lowercase()
            )
        }
    }

    /** "60", "60°" -> 60; a range "40-90" -> its upper end. Null if not a sensible width. */
    fun parseAngle(v: String): Int? =
        Regex("(\\d{1,3})").findAll(v).lastOrNull()?.value?.toIntOrNull()?.takeIf { it in 5..360 }

    /** "4", "4.5 m", "4,5" -> metres. */
    fun parseHeight(v: String): Double? =
        Regex("(\\d+(?:[.,]\\d+)?)").find(v)?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.5..100.0 }

    /**
     * How far the drawn view reaches (m): higher cameras see further. Like sunders: about
     * 5x the mounting height, 15-60 m; 25 m when the height isn't mapped.
     */
    fun viewRangeM(c: CctvCamera): Double = c.height?.let { (it * 5).coerceIn(15.0, 60.0) } ?: 25.0

    /** The drawn view's width (degrees): the mapped angle, else 60° for fixed / 90° for dome cameras. */
    fun viewAngle(c: CctvCamera): Int = c.angle ?: if (c.cameraType == "dome") 90 else 60

    /** A short title for the tap dialog: "Public dome camera", "Private camera (facing outside)"... */
    fun title(c: CctvCamera): String {
        val kind = when (c.cameraType) { "dome" -> "dome camera"; "panning" -> "panning camera"; "fixed" -> "fixed camera"; else -> "camera" }
        return when (c.zone) {
            "public" -> "Public $kind"
            "outdoor" -> "Private $kind (facing outside)"
            "indoor" -> "Indoor $kind"
            else -> "Surveillance $kind"
        }
    }
}

/**
 * Offline cache of the CCTV cameras. With the layer on, everything within the camera
 * download radius (Settings, 100 km by default) around you is fetched in the background,
 * in tiles; panning further away fetches the area on screen. Overpass, like the plate
 * cameras: only the areas are sent.
 */
object CctvStore {

    private const val FILE = "cctv.json"
    private const val AREAS_FILE = "cctv_areas.json"
    /** Largest box one request covers (degrees, ~55 km): city regions hold tens of thousands of cameras. */
    const val MAX_SPAN_DEG = 0.5
    private const val MIN_SPAN_DEG = 0.1
    /** Downloaded areas are fetched again after this long. */
    private const val REFRESH_MS = 14 * 24 * 3600_000L
    private const val RETRY_MS = 60_000L
    /** Keeps the cache bounded. */
    private const val MAX_CAMERAS = 150_000

    private val gson = Gson()
    private val listType = object : TypeToken<List<CctvCamera>>() {}.type
    private val areaListType = object : TypeToken<List<CameraArea>>() {}.type
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    @Volatile var cameras: List<CctvCamera> = emptyList()
        private set
    @Volatile private var areas: List<CameraArea> = emptyList()
    @Volatile private var loaded = false
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFailed: CameraArea? = null
    /** When the last download failed (0 = the last one worked), for the map's status line. */
    @Volatile var lastFailureAt = 0L
        private set
    val isBusy get() = busy.get()

    /** The download around you while it runs ("4/24 areas, 1234 found"), else null. */
    @Volatile var progress: String? = null
        private set
    /** Called on the main thread while cameras are being added (the map redraws). */
    @Volatile var onChanged: (() -> Unit)? = null

    /** True when the box isn't covered by a fresh download yet. */
    fun needsDownload(south: Double, west: Double, north: Double, east: Double) =
        CameraArea.needsDownload(areas, south, west, north, east, System.currentTimeMillis(), REFRESH_MS)

    private fun file(c: Context) = File(c.filesDir, FILE)
    private fun areasFile(c: Context) = File(c.filesDir, AREAS_FILE)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        cameras = runCatching { file(context).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<CctvCamera>>(it, listType) } }
            .getOrNull().orEmpty()
        areas = runCatching { areasFile(context).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<CameraArea>>(it, areaListType) } }
            .getOrNull().orEmpty()
        loaded = true
    }

    /**
     * Makes sure everything within [radiusKm] of a point is cached: downloads the missing
     * tiles in the background, one run at a time.
     */
    fun ensureAround(context: Context, lat: Double, lon: Double, radiusKm: Double) {
        val app = context.applicationContext
        if (busy.get()) return
        scope.launch {
            withContext(Dispatchers.IO) { load(app) }
            val tiles = CameraArea.tilesAround(lat, lon, radiusKm, MAX_SPAN_DEG)
                .filter { needsDownload(it[0], it[1], it[2], it[3]) }
            if (tiles.isEmpty()) return@launch
            runCatching { downloadTiles(app, tiles) }
        }
    }

    /** "Download cameras around me" (Settings): the CCTV within the radius; how many were found. */
    suspend fun downloadAround(context: Context, lat: Double, lon: Double, radiusKm: Double, onProgress: (String) -> Unit = {}): Int {
        withContext(Dispatchers.IO) { load(context) }
        return downloadTiles(context, CameraArea.tilesAround(lat, lon, radiusKm, MAX_SPAN_DEG), onProgress)
    }

    /** Areas still missing after the last download around you (retried the next time the map opens). */
    @Volatile var missingAreas = 0
        private set

    /**
     * Downloads the tiles one by one, 2 s apart (the public servers rate-limit bursts), waiting
     * while a plate-camera download uses them. Failed tiles get two more rounds after 30 s and
     * 60 s; any still missing stay un-cached, so the next run picks them up.
     */
    private suspend fun downloadTiles(context: Context, tiles: List<DoubleArray>, onProgress: (String) -> Unit = {}): Int {
        if (!busy.compareAndSet(false, true)) return 0
        var found = 0
        var pending = tiles
        try {
            for (round in 0 until 3) {
                if (pending.isEmpty()) break
                if (round > 0) {
                    val text = "retrying ${pending.size} area${if (pending.size == 1) "" else "s"} the servers refused, $found found"
                    progress = text; onProgress(text); onChanged?.invoke()
                    kotlinx.coroutines.delay(30_000L * round)
                }
                val failed = mutableListOf<DoubleArray>()
                pending.forEachIndexed { i, t ->
                    // Let a plate-camera download finish first: both hit the same servers.
                    var waited = 0
                    while ((AlprStore.isBusy || DeflockBulk.isRunning) && waited < 120) { kotlinx.coroutines.delay(1_000); waited++ }
                    val text = (if (round == 0) "${i + 1}/${pending.size} areas" else "retry ${i + 1}/${pending.size}") + ", $found found"
                    progress = text; onProgress(text); onChanged?.invoke()
                    try {
                        val json = AlprStore.fetchFirst("data=" + URLEncoder.encode(Cctv.query(t[0], t[1], t[2], t[3]), "UTF-8")) { }
                        val cams = withContext(Dispatchers.Default) { Cctv.parse(json) }
                        found += cams.size
                        withContext(Dispatchers.Default) { mergeInMemory(CameraArea(t[0], t[1], t[2], t[3], System.currentTimeMillis()), cams) }
                        onChanged?.invoke()
                    } catch (ex: kotlinx.coroutines.CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        failed += t
                        android.util.Log.w("CctvStore", "CCTV tile failed (round ${round + 1}): ${ex.message}")
                    }
                    if (i < pending.size - 1) kotlinx.coroutines.delay(2_000)
                }
                pending = failed
            }
            missingAreas = pending.size
            lastFailureAt = if (pending.size == tiles.size && tiles.isNotEmpty()) System.currentTimeMillis() else 0L
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { save(context) }
            progress = null
            busy.set(false)
            onChanged?.invoke()
        }
        if (pending.size == tiles.size && tiles.isNotEmpty()) throw java.io.IOException("OpenStreetMap servers busy - try again later")
        return found
    }

    /** Fetches the area on screen if it isn't cached (outside the area around you); true when the cache changed. */
    suspend fun autoDownload(context: Context, south: Double, west: Double, north: Double, east: Double): Boolean {
        withContext(Dispatchers.IO) { load(context) }
        val now = System.currentTimeMillis()
        if (!CameraArea.needsDownload(areas, south, west, north, east, now, REFRESH_MS)) return false
        if (lastFailed?.let { now - it.time < RETRY_MS && it.contains(south, west, north, east) } == true) return false
        if (!busy.compareAndSet(false, true)) return false
        val (s, w, n, e) = expand(south, west, north, east)
        return try {
            val json = AlprStore.fetchFirst("data=" + URLEncoder.encode(Cctv.query(s, w, n, e), "UTF-8")) { }
            val found = withContext(Dispatchers.Default) { Cctv.parse(json) }
            withContext(Dispatchers.Default) { mergeInMemory(CameraArea(s, w, n, e, System.currentTimeMillis()), found) }
            withContext(Dispatchers.IO) { save(context) }
            lastFailureAt = 0L
            true
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            android.util.Log.w("CctvStore", "CCTV download failed: ${ex.message}")
            lastFailed = CameraArea(s, w, n, e, System.currentTimeMillis())
            lastFailureAt = System.currentTimeMillis()
            false
        } finally {
            busy.set(false)
        }
    }

    /** The visible box doubled, between [MIN_SPAN_DEG] and [MAX_SPAN_DEG] a side. */
    fun expand(s: Double, w: Double, n: Double, e: Double): List<Double> {
        fun pad(span: Double) = (((span * 2).coerceIn(MIN_SPAN_DEG, MAX_SPAN_DEG) - span) / 2).coerceAtLeast(0.0)
        val lat = pad(n - s); val lon = pad(e - w)
        return listOf(s - lat, w - lon, n + lat, e + lon)
    }

    @Synchronized
    private fun mergeInMemory(area: CameraArea, found: List<CctvCamera>) {
        val outside = cameras.filterNot { it.lat in area.south..area.north && it.lon in area.west..area.east }
        cameras = (found + outside).distinctBy { it.osmId }.take(MAX_CAMERAS)
        areas = (areas.filterNot { area.contains(it) } + area).takeLast(600)
    }

    @Synchronized
    private fun save(context: Context) {
        runCatching {
            withTmp(file(context)) { it.writeText(gson.toJson(cameras)) }
            withTmp(areasFile(context)) { it.writeText(gson.toJson(areas)) }
        }
    }

    private fun withTmp(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        write(tmp)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete(); areasFile(context).delete()
        cameras = emptyList(); areas = emptyList()
    }

    @androidx.annotation.VisibleForTesting
    fun setForTest(list: List<CctvCamera>) { cameras = list; loaded = true }
}
