package com.rfsentinel.app.util

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.rfsentinel.app.data.KnownDeviceDao
import com.rfsentinel.app.data.KnownDeviceEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Device history import: reads the file the app's own export writes - JSON (the history alone, or the "everything"
 * file) or CSV - and merges the devices into what the phone remembers. Merging never loses anything and importing the
 * same file twice changes nothing: the earliest first sighting and the latest last one are kept, the larger counts,
 * a star or note already there stays, and a name or vendor fills in only where there is none.
 *
 * Reading is streamed and rows are written in batches, so a history of a hundred thousand devices does not need to fit
 * in memory. Pure Kotlin apart from the DAO, so it is unit-tested.
 */
object DeviceHistoryImport {

    const val MAX_BYTES = 120L * 1024 * 1024
    /** The byte-order mark a spreadsheet may put before the first column name. */
    private val BOM = 0xFEFF.toChar().toString()
    private const val BATCH = 500
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    class Result(val added: Int, val updated: Int, val unchanged: Int, val skipped: Int) {
        val total get() = added + updated + unchanged
    }

    class NotHistory(message: String) : Exception(message)

    // ---- times -------------------------------------------------------------------------------------

    private fun parseTime(s: String?): Long {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return 0L
        t.toLongOrNull()?.let { return it }
        runCatching {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(t)!!.time
        }
        runCatching { return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(t)!!.time }
        return 0L
    }

    // ---- one row -----------------------------------------------------------------------------------

    /** A history row from a file's fields; null when the address is not a Bluetooth / WiFi address. */
    fun row(mac: String?, name: String?, vendor: String?, type: String?, first: String?, last: String?,
            sessions: String?, detects: String?, favorite: String?, note: String?, alias: String? = null,
            now: Long = System.currentTimeMillis()): KnownDeviceEntity? {
        val m = mac?.trim().orEmpty()
        if (!MAC.matches(m)) return null
        val f = parseTime(first); val l = parseTime(last)
        val seenFirst = if (f > 0) f else if (l > 0) l else now
        val seenLast = if (l > 0) maxOf(l, seenFirst) else seenFirst
        fun clean(s: String?, max: Int) = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }?.take(max)
        return KnownDeviceEntity(
            mac = m.uppercase(Locale.US), firstSeen = seenFirst, lastSeen = seenLast,
            sessions = (sessions?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 1_000_000),
            detectCount = (detects?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 100_000_000),
            name = clean(name, 128), vendor = clean(vendor, 128), deviceType = clean(type, 64),
            favorite = favorite?.trim()?.equals("true", true) == true, note = clean(note, 1000),
            alias = clean(alias, 64)
        )
    }

    /** The two rows as one: nothing is lost, and merging the same row again gives the same result. */
    fun merge(mine: KnownDeviceEntity?, theirs: KnownDeviceEntity): KnownDeviceEntity {
        if (mine == null) return theirs
        return KnownDeviceEntity(
            mac = mine.mac,
            firstSeen = minOf(mine.firstSeen, theirs.firstSeen),
            lastSeen = maxOf(mine.lastSeen, theirs.lastSeen),
            sessions = maxOf(mine.sessions, theirs.sessions),
            detectCount = maxOf(mine.detectCount, theirs.detectCount),
            name = mine.name ?: theirs.name, vendor = mine.vendor ?: theirs.vendor, deviceType = mine.deviceType ?: theirs.deviceType,
            favorite = mine.favorite || theirs.favorite,
            note = mine.note?.takeIf { it.isNotBlank() } ?: theirs.note,
            alias = mine.alias?.takeIf { it.isNotBlank() } ?: theirs.alias
        )
    }

    // ---- reading the files -----------------------------------------------------------------------------

    /**
     * The devices of a history file, one at a time as they are read (null for an entry that was not a device); the file
     * is only read as far as the sequence is taken. Throws [NotHistory] for another kind of file.
     */
    fun rows(input: InputStream): Sequence<KnownDeviceEntity?> = sequence {
        val buffered = input.buffered()
        buffered.mark(8)
        // The first character that is not a space (or a byte-order mark) says which kind of file it is.
        var first = -1
        while (true) {
            val b = buffered.read()
            if (b < 0) break
            if (b.toChar().isWhitespace() || b == 0xEF || b == 0xBB || b == 0xBF) continue
            first = b; break
        }
        buffered.reset()
        val reader = BufferedReader(InputStreamReader(buffered, Charsets.UTF_8))
        // A byte-order mark (Windows editors save one) would read as a stray first character.
        reader.mark(1); if (reader.read() != 0xFEFF) reader.reset()
        if (first == '['.code || first == '{'.code) jsonRows(reader) else csvRows(reader)
    }

    private suspend fun SequenceScope<KnownDeviceEntity?>.jsonRows(reader: BufferedReader) {
        val r = JsonReader(reader).apply { isLenient = true }
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> arrayRows(r)
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                var found = false
                while (r.hasNext()) {
                    if (r.nextName() == "deviceHistory" && r.peek() == JsonToken.BEGIN_ARRAY) { found = true; arrayRows(r) } else r.skipValue()
                }
                r.endObject()
                if (!found) throw NotHistory("That file has no device history in it")
            }
            else -> throw NotHistory("That isn't a device history file")
        }
    }

    private suspend fun SequenceScope<KnownDeviceEntity?>.arrayRows(r: JsonReader) {
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); yield(null); continue }
            val f = HashMap<String, String?>()
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                f[k] = when (r.peek()) {
                    JsonToken.NULL -> { r.nextNull(); null }
                    JsonToken.BOOLEAN -> r.nextBoolean().toString()
                    JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> { r.skipValue(); null }
                    else -> r.nextString()
                }
            }
            r.endObject()
            yield(row(f["mac"], f["name"], f["vendor"], f["deviceType"], f["firstSeen"], f["lastSeen"], f["sessions"], f["detectCount"], f["favorite"], f["note"], f["customName"]))
        }
        r.endArray()
    }

    private suspend fun SequenceScope<KnownDeviceEntity?>.csvRows(reader: BufferedReader) {
        val it = csvRecords(reader).iterator()
        if (!it.hasNext()) throw NotHistory("That file is empty")
        val header = it.next().map { h -> h.trim().lowercase(Locale.US).removePrefix(BOM) }
        if ("mac" !in header) throw NotHistory("That isn't a device history file (no \"mac\" column)")
        fun col(r: List<String>, vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { i -> i >= 0 } }?.let { i -> r.getOrNull(i) }
        while (it.hasNext()) {
            val r = it.next()
            if (r.all { f -> f.isBlank() }) continue
            yield(row(col(r, "mac"), col(r, "name"), col(r, "vendor"), col(r, "device_type", "devicetype"), col(r, "first_seen", "firstseen"),
                col(r, "last_seen", "lastseen"), col(r, "sessions"), col(r, "detect_count", "detectcount"), col(r, "favorite"), col(r, "note"),
                col(r, "custom_name", "customname")))
        }
    }

    /** The CSV's records, quotes and quoted line breaks included (fields are quoted with doubled quotes). */
    internal fun csvRecords(reader: BufferedReader): Sequence<List<String>> = sequence {
        val field = StringBuilder(); var row = ArrayList<String>(); var quoted = false
        var c = reader.read()
        while (c >= 0) {
            val ch = c.toChar()
            when {
                quoted && ch == '"' -> { reader.mark(1); val n = reader.read(); if (n == '"'.code) field.append('"') else { quoted = false; if (n >= 0) reader.reset() } }
                quoted -> field.append(ch)
                ch == '"' && field.isEmpty() -> quoted = true
                ch == ',' -> { row.add(field.toString()); field.setLength(0) }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r') { reader.mark(1); if (reader.read() != '\n'.code) reader.reset() }
                    row.add(field.toString()); field.setLength(0)
                    if (row.size > 1 || row[0].isNotEmpty()) yield(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            c = reader.read()
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); yield(row) }
    }

    // ---- into the database -------------------------------------------------------------------------------

    /** Merges the file into the history; [onProgress] gets the rows handled so far. Throws [NotHistory] for another kind of file. */
    suspend fun importInto(dao: KnownDeviceDao, input: InputStream, onProgress: (Int) -> Unit = {}): Result {
        var added = 0; var updated = 0; var same = 0; var skipped = 0; var seen = 0
        val batch = ArrayList<KnownDeviceEntity>()
        suspend fun flush() {
            if (batch.isEmpty()) return
            // A file can list a device twice: the later rows merge into the earlier ones first.
            val byMac = LinkedHashMap<String, KnownDeviceEntity>()
            for (b in batch) byMac[b.mac] = merge(byMac[b.mac], b)
            val have = dao.getAll(byMac.keys.toList()).associateBy { it.mac }
            val write = ArrayList<KnownDeviceEntity>()
            for ((mac, incoming) in byMac) {
                val old = have[mac]
                val merged = merge(old, incoming)
                when {
                    old == null -> { added++; write += merged }
                    merged != old -> { updated++; write += merged }
                    else -> same++
                }
            }
            if (write.isNotEmpty()) dao.upsertAll(write)
            batch.clear()
        }
        for (row in rows(input)) {
            seen++
            if (row == null) skipped++ else batch += row
            if (batch.size >= BATCH) { flush(); onProgress(seen) }
        }
        flush(); onProgress(seen)
        return Result(added, updated, same, skipped)
    }

    /** Passes the file on, but stops it past [limit] bytes: a wrong, huge file is refused, not read to the end. */
    class Limited(private val inner: InputStream, private val limit: Long = MAX_BYTES) : InputStream() {
        private var n = 0L
        override fun read(): Int { val b = inner.read(); if (b >= 0 && ++n > limit) throw NotHistory("That file is too big to be a device history"); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = inner.read(b, off, len); if (r > 0) { n += r; if (n > limit) throw NotHistory("That file is too big to be a device history") }; return r
        }
        override fun close() = inner.close()
    }

    fun fileName(now: Long = System.currentTimeMillis()) =
        "rf_sentinel_device_history_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(now)) + ".json"
}
