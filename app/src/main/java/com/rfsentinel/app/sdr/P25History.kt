package com.rfsentinel.app.sdr

import android.content.Context
import com.rfsentinel.app.util.Prefs
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Every P25 call the control channel granted, kept on the phone for [Prefs.p25LogDays] days (Settings › RTL-SDR
 * radio) so talkgroup activity can be looked back on and exported without internet or a RadioReference list.
 * One CSV line per call start, written off the SDR thread. Signalling only: no voice.
 */
object P25History {

    /** One call: [tg] null for a private call to radio [target]. [site] is "system-rfss-site" when the site was heard. */
    data class Call(val time: Long, val tg: Int?, val target: Int?, val source: Int?, val channel: Int?, val hz: Long?,
                    val emergency: Boolean, val encrypted: Boolean, val site: String?)

    /** Per talkgroup over the kept days, for the history screen. [byHour]: calls in each hour of the day (0-23). */
    data class Summary(val tg: Int?, val target: Int?, val lastDay: Int, val lastWeek: Int, val all: Int, val lastAt: Long,
                       val radios: Int, val emergency: Boolean, val encrypted: Boolean, val byHour: IntArray) {
        /** The hour of the day with the most calls, or null with none. */
        val busiestHour get() = byHour.indices.maxByOrNull { byHour[it] }?.takeIf { byHour[it] > 0 }
    }

    const val HEADER = "time,talkgroup,private_to,radio,channel,frequency_hz,emergency,encrypted,site"
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val PRUNE_EVERY_MS = 60 * 60_000L

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastPrune = 0L

    fun file(context: Context) = File(context.filesDir, "p25/calls.csv")

    /** Adds [calls] in the background; trims to the kept days about once an hour. Off (0 days): nothing is kept. */
    fun append(context: Context, calls: List<Call>) {
        if (calls.isEmpty()) return
        val app = context.applicationContext
        io.execute {
            runCatching {
                val days = Prefs.p25LogDays(app)
                val f = file(app)
                if (days <= 0) { f.delete(); return@runCatching }
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText(HEADER + "\n")
                f.appendText(calls.joinToString("") { line(it) + "\n" })
                val now = System.currentTimeMillis()
                if (now - lastPrune > PRUNE_EVERY_MS) { lastPrune = now; prune(f, now - days * DAY_MS) }
            }
        }
    }

    /** Every kept call, oldest first. Call off the main thread. */
    fun read(context: Context): List<Call> = file(context).takeIf { it.exists() }?.useLines { lines ->
        lines.drop(1).mapNotNull(::parse).toList()
    }.orEmpty()

    private fun prune(f: File, cutoff: Long) {
        val kept = f.readLines().drop(1).filter { (it.substringBefore(',').toLongOrNull() ?: 0L) >= cutoff }
        f.writeText((listOf(HEADER) + kept).joinToString("\n", postfix = "\n"))
    }

    internal fun line(c: Call) = listOf(c.time, c.tg ?: "", c.target ?: "", c.source ?: "", c.channel ?: "", c.hz ?: "",
        if (c.emergency) 1 else 0, if (c.encrypted) 1 else 0, c.site.orEmpty()).joinToString(",")

    internal fun parse(line: String): Call? {
        val f = line.split(',')
        if (f.size < 9) return null
        return Call(f[0].toLongOrNull() ?: return null, f[1].toIntOrNull(), f[2].toIntOrNull(), f[3].toIntOrNull(),
            f[4].toIntOrNull(), f[5].toLongOrNull(), f[6] == "1", f[7] == "1", f[8].ifEmpty { null })
    }

    /** Talkgroups (and private calls) by most recent call, counted over the last day, week and everything kept. */
    fun summarize(calls: List<Call>, now: Long): List<Summary> = calls.groupBy { it.tg to (if (it.tg == null) it.target else null) }
        .map { (k, list) ->
            val hours = IntArray(24)
            val cal = Calendar.getInstance()
            for (c in list) { cal.timeInMillis = c.time; hours[cal.get(Calendar.HOUR_OF_DAY)]++ }
            Summary(k.first, k.second, list.count { now - it.time <= DAY_MS }, list.count { now - it.time <= 7 * DAY_MS }, list.size,
                list.maxOf { it.time }, list.mapNotNull { it.source }.toSet().size, list.any { it.emergency }, list.any { it.encrypted }, hours)
        }
        .sortedByDescending { it.lastAt }
}
