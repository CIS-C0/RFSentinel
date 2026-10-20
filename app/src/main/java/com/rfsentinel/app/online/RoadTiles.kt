package com.rfsentinel.app.online

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The roads around you, from the same OpenFreeMap vector tiles the map draws (OpenStreetMap data,
 * OpenMapTiles schema, zoom 14): each road's line, class (motorway ... service), one-way flag and
 * whether it's a bridge or tunnel. Used to tell whether a Waze report is on the road you're on
 * ([RoadMatch]). Tiles are fetched in the background as you go and kept on the phone (they don't
 * change until OpenFreeMap publishes a new map); fetching them reveals the area, like the map does.
 */
object RoadTiles {

    const val Z = 14
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"
    private const val MEMORY_TILES = 24
    private const val DISK_TILES = 400

    /** One road line: points as latitude / longitude, OpenMapTiles attributes. */
    class Road(val lat: DoubleArray, val lon: DoubleArray, val cls: String, val oneway: Int, val brunnel: String?, val layer: Int)

    /** Classes a car drives on (not paths, rail, ferries...). */
    private val DRIVABLE = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track", "busway", "raceway")

    data class Tile(val x: Int, val y: Int)

    fun tileOf(lat: Double, lon: Double): Tile {
        val n = 1 shl Z
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = ((1.0 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Tile(x, y)
    }

    /** A point of tile [t], in its own units (0..[extent] across), as latitude / longitude. */
    internal fun toLatLon(t: Tile, px: Double, py: Double, extent: Int): Pair<Double, Double> {
        val n = (1 shl Z).toDouble()
        val lon = (t.x + px / extent) / n * 360.0 - 180.0
        val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (t.y + py / extent) / n))))
        return lat to lon
    }

    // ---- the vector tile format (Mapbox Vector Tile 2: protocol buffers) -------------------------

    /** The drivable roads of one tile; malformed data gives what was read before it. */
    internal fun decode(bytes: ByteArray, t: Tile): List<Road> {
        val out = ArrayList<Road>()
        runCatching {
            val r = Pb(bytes, 0, bytes.size)
            while (r.more()) {
                val (f, w) = r.key()
                if (f == 3 && w == 2) { val (s, e) = r.bytes(); layer(bytes, s, e, t, out) } else r.skip(w)
            }
        }
        return out
    }

    private fun layer(b: ByteArray, start: Int, end: Int, t: Tile, out: MutableList<Road>) {
        // First pass: the name, keys, values and extent; features come back in a second pass.
        var name = ""; val keys = ArrayList<String>(); val values = ArrayList<Any?>(); var extent = 4096
        val features = ArrayList<IntArray>()
        val r = Pb(b, start, end)
        while (r.more()) {
            val (f, w) = r.key()
            when {
                f == 1 && w == 2 -> { val (s, e) = r.bytes(); name = String(b, s, e - s, Charsets.UTF_8); if (name != "transportation") return }
                f == 2 && w == 2 -> { val (s, e) = r.bytes(); features += intArrayOf(s, e) }
                f == 3 && w == 2 -> { val (s, e) = r.bytes(); keys += String(b, s, e - s, Charsets.UTF_8) }
                f == 4 && w == 2 -> { val (s, e) = r.bytes(); values += value(b, s, e) }
                f == 5 && w == 0 -> extent = r.varint().toInt()
                else -> r.skip(w)
            }
        }
        if (name != "transportation") return
        for ((s, e) in features.map { it[0] to it[1] }) feature(b, s, e, keys, values, extent, t, out)
    }

    private fun value(b: ByteArray, start: Int, end: Int): Any? {
        val r = Pb(b, start, end)
        while (r.more()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { val (s, e) = r.bytes(); return String(b, s, e - s, Charsets.UTF_8) }
                4, 5 -> return r.varint()
                6 -> return Pb.zigzag(r.varint())
                7 -> return r.varint() != 0L
                else -> r.skip(w)
            }
        }
        return null
    }

    private fun feature(b: ByteArray, start: Int, end: Int, keys: List<String>, values: List<Any?>, extent: Int, t: Tile, out: MutableList<Road>) {
        var tags = IntArray(0); var type = 0; var geom = LongArray(0)
        val r = Pb(b, start, end)
        while (r.more()) {
            val (f, w) = r.key()
            when {
                f == 2 && w == 2 -> tags = r.packed().map { it.toInt() }.toIntArray()
                f == 3 && w == 0 -> type = r.varint().toInt()
                f == 4 && w == 2 -> geom = r.packed()
                else -> r.skip(w)
            }
        }
        if (type != 2) return // lines only
        val attrs = HashMap<String, Any?>()
        for (i in 0 until tags.size - 1 step 2) keys.getOrNull(tags[i])?.let { attrs[it] = values.getOrNull(tags[i + 1]) }
        val cls = attrs["class"] as? String ?: return
        if (cls !in DRIVABLE) return
        val oneway = (attrs["oneway"] as? Long)?.toInt() ?: 0 // 1 = drawn direction only, -1 = against it
        val brunnel = attrs["brunnel"] as? String
        val layer = (attrs["layer"] as? Long)?.toInt() ?: 0
        // Geometry: MoveTo / LineTo commands with zigzag-encoded relative steps; one road per MoveTo.
        var i = 0; var x = 0L; var y = 0L
        var lat = ArrayList<Double>(); var lon = ArrayList<Double>()
        fun flush() {
            if (lat.size >= 2) out += Road(lat.toDoubleArray(), lon.toDoubleArray(), cls, oneway.coerceIn(-1, 1), brunnel, layer)
            lat = ArrayList(); lon = ArrayList()
        }
        while (i < geom.size) {
            val cmd = (geom[i] and 7).toInt(); val count = (geom[i] ushr 3).toInt(); i++
            if (cmd == 1) flush()
            if (cmd == 1 || cmd == 2) repeat(count) {
                if (i + 1 >= geom.size) return@repeat
                x += Pb.zigzag(geom[i]); y += Pb.zigzag(geom[i + 1]); i += 2
                val (la, lo) = toLatLon(t, x.toDouble(), y.toDouble(), extent)
                lat += la; lon += lo
            }
        }
        flush()
    }

    /** A minimal protocol-buffer reader over a slice of [b]. */
    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (pos < end) { val x = b[pos++].toInt(); r = r or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 }
            throw IllegalStateException("truncated varint")
        }
        fun key(): Pair<Int, Int> { val k = varint(); return (k ushr 3).toInt() to (k and 7).toInt() }
        fun bytes(): Pair<Int, Int> { val n = varint().toInt(); val s = pos; pos += n; check(n >= 0 && pos <= end); return s to pos }
        fun packed(): LongArray { val (s, e) = bytes(); val r = Pb(b, s, e); val out = ArrayList<Long>(); while (r.more()) out += r.varint(); return out.toLongArray() }
        fun skip(w: Int) { when (w) { 0 -> varint(); 1 -> pos += 8; 2 -> bytes(); 5 -> pos += 4; else -> throw IllegalStateException("wire type $w") } }
        companion object { fun zigzag(n: Long) = (n ushr 1) xor -(n and 1) }
    }

    // ---- fetching and keeping them ------------------------------------------------------------------

    private val memory = object : LinkedHashMap<Tile, List<Road>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Tile, List<Road>>) = size > MEMORY_TILES
    }
    private val pending = ConcurrentHashMap.newKeySet<Tile>()
    private val failedAt = ConcurrentHashMap<Tile, Long>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "road-tiles").apply { isDaemon = true } }
    @Volatile private var template: String? = null
    @Volatile private var templateAt = 0L

    /** The roads of [tiles] already at hand; the others are fetched in the background (null until then). */
    fun roads(context: Context, tiles: Collection<Tile>): Map<Tile, List<Road>> {
        val have = HashMap<Tile, List<Road>>()
        val missing = ArrayList<Tile>()
        synchronized(memory) { for (t in tiles) memory[t]?.let { have[t] = it } ?: missing.add(t) }
        val now = System.currentTimeMillis()
        for (t in missing) {
            if (now - (failedAt[t] ?: 0L) < 60_000) continue // wait a minute after a failure
            if (pending.add(t)) worker.execute { try { load(context.applicationContext, t) } finally { pending.remove(t) } }
        }
        return have
    }

    /** Tests: tiles as if already fetched. */
    internal fun put(t: Tile, roads: List<Road>) = synchronized(memory) { memory[t] = roads }
    internal fun clear() = synchronized(memory) { memory.clear() }

    private fun load(context: Context, t: Tile) {
        if (failedAt.size > 500) failedAt.entries.removeAll { System.currentTimeMillis() - it.value >= 60_000 } // offline all day
        val url = tileUrl(context) ?: run { failedAt[t] = System.currentTimeMillis(); return }
        val version = url.substringAfter("/planet/").substringBefore('/').ifEmpty { "x" }
        val dir = File(context.cacheDir, "road-tiles/$version")
        val file = File(dir, "${Z}_${t.x}_${t.y}.pbf")
        val bytes = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
            ?: download(url.replace("{z}", "$Z").replace("{x}", "${t.x}").replace("{y}", "${t.y}"))?.also { b ->
                runCatching { dir.mkdirs(); file.writeBytes(b); trim(context, dir) }
            }
        if (bytes == null) { failedAt[t] = System.currentTimeMillis(); return }
        val roads = decode(bytes, t)
        synchronized(memory) { memory[t] = roads }
    }

    /** The tile address of the current OpenFreeMap map (asked once a day). */
    private fun tileUrl(context: Context): String? {
        val now = System.currentTimeMillis()
        template?.takeIf { now - templateAt < 24 * 3_600_000L }?.let { return it }
        val json = download(TILEJSON)?.toString(Charsets.UTF_8) ?: return template
        val url = runCatching { org.json.JSONObject(json).getJSONArray("tiles").getString(0) }.getOrNull() ?: return template
        template = url; templateAt = now
        // An older map's tiles aren't needed any more.
        runCatching {
            val version = url.substringAfter("/planet/").substringBefore('/')
            File(context.cacheDir, "road-tiles").listFiles()?.filter { it.name != version }?.forEach { it.deleteRecursively() }
        }
        return url
    }

    private fun download(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "RFSentinel/${com.rfsentinel.app.BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) null
            else c.inputStream.use { s ->
                val b = s.readBytes()
                // Served plain; gzip only if a server ever sends it without saying so.
                if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) java.util.zip.GZIPInputStream(b.inputStream()).readBytes() else b
            }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun trim(context: Context, dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_TILES) return
        files.sortedBy { it.lastModified() }.take(files.size - DISK_TILES).forEach { it.delete() }
    }
}
