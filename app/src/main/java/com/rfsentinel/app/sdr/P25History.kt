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

    /** A talkgroup much busier than usual: [recent] calls in the last hour against [usual], its average for that same
     *  hour over the [days] before. [new]: never heard before in the kept log. */
    data class Unusual(val tg: Int, val recent: Int, val usual: Double, val days: Int, val new: Boolean)

    const val HEADER = "time,talkgroup,private_to,radio,channel,frequency_hz,emergency,encrypted,site"
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val HOUR_MS = 60 * 60_000L
    /** Days of log needed before anything counts as unusual. */
    const val BASELINE_DAYS = 3
    /** Calls in the last hour below this are never unusual, however quiet the talkgroup usually is. */
    private const val MIN_RECENT = 5
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

    /**
     * Talkgroups at least 3 times (plus 3 calls) busier in the last hour than in the same hour of the previous days.
     * Nothing until the log covers [BASELINE_DAYS] days. Private calls are left out.
     */
    fun unusual(calls: List<Call>, now: Long): List<Unusual> {
        val oldest = calls.minOfOrNull { it.time } ?: return emptyList()
        val days = ((now - oldest) / DAY_MS).toInt()
        if (days < BASELINE_DAYS) return emptyList()
        return calls.filter { it.tg != null }.groupBy { it.tg!! }.mapNotNull { (tg, list) ->
            val recent = list.count { now - it.time in 0 until HOUR_MS }
            if (recent < MIN_RECENT) return@mapNotNull null
            val before = (1..days).sumOf { d -> list.count { now - d * DAY_MS - it.time in 0 until HOUR_MS } }
            val usual = before.toDouble() / days
            if (recent < 3 * usual + 3) null
            else Unusual(tg, recent, usual, days, list.none { now - it.time >= HOUR_MS })
        }.sortedByDescending { it.recent }
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
