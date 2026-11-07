package com.rfsentinel.app.sdr

import android.content.Context
import com.rfsentinel.app.util.Prefs

/**
 * Settings > RTL-SDR radio, turned into a [RadioWatch.Config]: which built-in bands to sweep,
 * the user's own bands, excluded ranges and watched frequencies (typed one per line, in MHz),
 * and the sensitivity. Parsing is pure and unit-tested.
 */
object RadioSettings {

    /** Built-in bands, by Settings key. */
    val BUILT_IN = listOf(
        "700" to RadioWatch.BANDS[0], "800" to RadioWatch.BANDS[1],
        "vhf" to RadioWatch.BANDS[2], "uhf" to RadioWatch.BANDS[3]
    )

    /** The RTL-SDR tunes about 24 - 1766 MHz; more than this much custom spectrum makes a sweep crawl. */
    private const val MIN_HZ = 24_000_000L
    private const val MAX_HZ = 1_766_000_000L
    private const val MAX_CUSTOM_SPAN_HZ = 120_000_000L
    const val MAX_TARGETS = 50

    fun config(context: Context): RadioWatch.Config {
        val off = Prefs.radioBandsOff(context)
        return RadioWatch.Config(
            bands = BUILT_IN.filter { it.first !in off }.map { it.second } + customBands(Prefs.radioCustomBands(context)) +
                (if (Prefs.radioCellOn(context)) RadioWatch.LTE_UPLINK_BANDS else emptyList()) +
                (if (Prefs.radioTetraOn(context)) listOf(RadioWatch.TETRA_BAND) else emptyList()),
            excluded = ranges(Prefs.radioExcluded(context)).map { it.first },
            targets = targets(Prefs.radioTargets(context)),
            minSnrDb = Prefs.radioMinSnr(context)
        )
    }

    private val RANGE = Regex("""^\s*(\d{2,4}(?:[.,]\d+)?)\s*(?:-|–|to)\s*(\d{2,4}(?:[.,]\d+)?)\s*(?:mhz)?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val PLAIN_LINE = Regex("""^\s*\d{2,4}(?:\.\d+)?(?:\s|$)""")
    private val FREQ = Regex("""^\s*(\d{2,4}(?:[.,]\d+)?)\s*(?:mhz)?\s*[,;:\-]?\s*(.*)$""", RegexOption.IGNORE_CASE)

    private fun mhzToHz(s: String): Long? = s.replace(',', '.').toDoubleOrNull()?.let { (it * 1_000_000).toLong() }

    /** "462-469 business band" lines → (range, label); lines that don't parse are skipped. */
    fun ranges(text: String): List<Pair<LongRange, String>> = text.lines().mapNotNull { line ->
        val m = RANGE.find(line) ?: return@mapNotNull null
        val a = mhzToHz(m.groupValues[1]) ?: return@mapNotNull null
        val b = mhzToHz(m.groupValues[2]) ?: return@mapNotNull null
        val lo = minOf(a, b).coerceAtLeast(MIN_HZ); val hi = maxOf(a, b).coerceAtMost(MAX_HZ)
        if (hi <= lo) null else (lo..hi) to m.groupValues[3].trim()
    }

    /** The user's bands, swept like the shared land-mobile bands; capped so a sweep stays quick. */
    fun customBands(text: String): List<RadioWatch.Band> {
        var span = 0L
        return ranges(text).mapNotNull { (r, label) ->
            val len = r.last - r.first
            if (span + len > MAX_CUSTOM_SPAN_HZ) return@mapNotNull null
            span += len
            RadioWatch.Band(label.ifEmpty { "Custom band %.3f-%.3f MHz".format(java.util.Locale.US, r.first / 1e6, r.last / 1e6) },
                r.first, r.last, RadioWatch.Kind.CUSTOM)
        }
    }

    /** "154.4300 County fire dispatch" lines → watched frequencies. */
    fun targets(text: String): List<RadioWatch.Target> = text.lines().mapNotNull { line ->
        if (RANGE.containsMatchIn(line)) return@mapNotNull null
        val m = FREQ.find(line) ?: return@mapNotNull null
        val hz = mhzToHz(m.groupValues[1])?.takeIf { it in MIN_HZ..MAX_HZ } ?: return@mapNotNull null
        RadioWatch.Target(hz, m.groupValues[2].trim().ifEmpty { "%.4f MHz".format(java.util.Locale.US, hz / 1e6) })
    }.distinctBy { it.freqHz }.take(MAX_TARGETS)

    /** [addTargets] from a file's text: a CSV, or plain "466.075 Fire dispatch" lines. */
    fun addPagerFile(current: String, text: String): Triple<String, Int, Int> {
        val plain = text.lines().filter { it.isNotBlank() }.all { PLAIN_LINE.containsMatchIn(it) }
        return addTargets(current, if (plain) targets(text).map { FreqNames.Name(it.freqHz, it.label, "") } else FreqNames.parseCsv(text, ""))
    }

    /** [current] watched lines plus [names], up to the limit: the new text, how many were added, how many did not fit. */
    fun addTargets(current: String, names: List<FreqNames.Name>): Triple<String, Int, Int> {
        val have = targets(current).mapTo(HashSet()) { it.freqHz }
        val fresh = names.filter { it.hz in MIN_HZ..MAX_HZ && it.hz !in have }.distinctBy { it.hz }
        val added = fresh.take(MAX_TARGETS - have.size)
        val lines = added.map { "${java.math.BigDecimal(it.hz).movePointLeft(6).stripTrailingZeros().toPlainString()} ${it.name}" }
        val text = (listOf(current.trimEnd()).filter { it.isNotEmpty() } + lines).joinToString("\n")
        return Triple(text, added.size, fresh.size - added.size)
    }
}
