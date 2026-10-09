package com.rfsentinel.app.sdr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Names for RTL-SDR hits: who is licensed or known to use a frequency. Two sources:
 *  - a frequency list the user imports (CSV: RadioReference's export, CHIRP, or any file
 *    with a frequency column and a name column), kept on the phone;
 *  - FCC licences near you from RadioReference ([RadioReference]), cached per area.
 * A hit within ± the tolerance set in Settings takes the closest name.
 */
object FreqNames {

    data class Name(val hz: Long, val name: String, val source: String)

    private const val FILE = "freq_names.json"
    private val gson = Gson()
    private val type = object : TypeToken<List<Name>>() {}.type

    @Volatile private var imported: List<Name> = emptyList()
    @Volatile private var loaded = false
    /** RadioReference results for the area around you (replaced when you move on). */
    @Volatile var nearby: List<Name> = emptyList()

    val importedCount: Int get() = imported.size

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        imported = runCatching {
            File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { gson.fromJson<List<Name>>(it, type) }
        }.getOrNull().orEmpty()
        loaded = true
    }

    /** The closest name within [tolHz] of [hz]: the imported list first, then RadioReference. */
    fun lookup(hz: Long, tolHz: Long): Name? =
        closest(imported, hz, tolHz) ?: closest(nearby, hz, tolHz)

    internal fun closest(list: List<Name>, hz: Long, tolHz: Long): Name? =
        list.filter { kotlin.math.abs(it.hz - hz) <= tolHz }.minByOrNull { kotlin.math.abs(it.hz - hz) }

    /** Replaces the imported list; returns how many frequencies were read. */
    @Synchronized
    fun importCsv(context: Context, text: String, source: String): Int {
        val names = parseCsv(text, source)
        imported = names
        loaded = true
        runCatching { File(context.filesDir, FILE).writeText(gson.toJson(names)) }
        return names.size
    }

    @Synchronized
    fun clearImported(context: Context) {
        imported = emptyList()
        File(context.filesDir, FILE).delete()
    }

    /** A frequency list file, at most [max] bytes (a big RadioReference export is well under 1 MB). */
    fun readLimited(input: java.io.InputStream, max: Int = 8_000_000): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ---- CSV (pure; unit-tested) ----

    /** One CSV line into fields (quotes and doubled quotes handled). */
    internal fun fields(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                !q && (c == ',' || c == ';' || c == '\t') -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    private fun mhz(s: String): Long? {
        val v = s.replace(Regex("(?i)\\s*mhz"), "").replace(',', '.').toDoubleOrNull() ?: return null
        // Some exports give Hz or kHz.
        val m = when { v > 1_000_000 -> v / 1e6; v > 30_000 -> v / 1e3; else -> v }
        return if (m in 25.0..1800.0) (m * 1_000_000).toLong() else null
    }

    /**
     * Reads a frequency list. With a header row, the frequency is the "Frequency Output" /
     * "Frequency" / "Freq" column and the name is built from "Alpha Tag", "Description",
     * "Name", "Agency/Category", "FCC Callsign" or "Comment"; without one, the first column
     * that holds a frequency and the first text column.
     */
    fun parseCsv(text: String, source: String): List<Name> {
        val rows = text.lines().filter { it.isNotBlank() }.map { fields(it) }
        if (rows.isEmpty()) return emptyList()
        val head = rows.first().map { it.lowercase() }
        fun col(vararg keys: String) = keys.firstNotNullOfOrNull { k -> head.indexOfFirst { it == k }.takeIf { it >= 0 } }
            ?: keys.firstNotNullOfOrNull { k -> head.indexOfFirst { it.contains(k) }.takeIf { it >= 0 } }
        val freqCol = col("frequency output", "frequency", "freq", "output", "rx frequency")
        val hasHeader = freqCol != null && rows.first().getOrNull(freqCol)?.let { mhz(it) } == null
        val names = if (hasHeader) {
            val nameCols = listOfNotNull(col("description"), col("alpha tag", "alpha"), col("name"),
                col("agency/category", "agency", "category"), col("fcc callsign", "callsign"), col("comment")).distinct()
            rows.drop(1).mapNotNull { r ->
                val hz = r.getOrNull(freqCol!!)?.let { mhz(it) } ?: return@mapNotNull null
                val parts = nameCols.mapNotNull { r.getOrNull(it)?.takeIf { s -> s.isNotBlank() } }.distinct()
                Name(hz, parts.take(2).joinToString(" · ").ifEmpty { "%.4f MHz".format(java.util.Locale.US, hz / 1e6) }, source)
            }
        } else rows.mapNotNull { r ->
            val fi = r.indexOfFirst { mhz(it) != null }.takeIf { it >= 0 } ?: return@mapNotNull null
            val hz = mhz(r[fi])!!
            val name = r.filterIndexed { i, s -> i != fi && s.isNotBlank() && s.replace(',', '.').toDoubleOrNull() == null }.firstOrNull()
            Name(hz, name ?: "%.4f MHz".format(java.util.Locale.US, hz / 1e6), source)
        }
        // Names are shown in the list and spoken: keep them short.
        return names.map { if (it.name.length > 80) it.copy(name = it.name.take(80)) else it }
            .distinctBy { it.hz to it.name }.take(20_000)
    }
}
