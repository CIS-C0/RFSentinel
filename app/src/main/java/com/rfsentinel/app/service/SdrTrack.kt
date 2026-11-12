package com.rfsentinel.app.service

import android.content.Context
import com.rfsentinel.app.util.ExportText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SDR's and USB WiFi adapter's activity with when and where: every line of their logs, stamped with the time and
 * your GPS position then.
 * Kept in memory for [KEEP_MS] (a snapshot keeps the last [SNAPSHOT_MS]) and, while a trace records, in a file of
 * its own (files/trip_sdr/<id>.csv, out of cloud backups), exported with the trace as CSV. Lines carry what the
 * SDR log does: frequencies and IDs only with "Log everything the RTL-SDR hears" on.
 */
object SdrTrack {
    class Event(val time: Long, val lat: Double?, val lon: Double?, val text: String)

    const val KEEP_MS = 30 * 60_000L
    const val SNAPSHOT_MS = 10 * 60_000L
    private const val MAX_EVENTS = 5_000
    private const val HEADER = "time,latitude,longitude,activity\n"

    @Volatile private var dir: File? = null
    private val recent = ArrayDeque<Event>()

    fun init(context: Context) { dir = File(context.applicationContext.filesDir, "trip_sdr") }

    /** One SDR log line, now, where you are (the service's last fix, as the SDR uses). */
    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val at = runCatching { com.rfsentinel.app.sdr.SdrRadio.location?.invoke() }.getOrNull()
        val e = Event(now, at?.first, at?.second, text)
        synchronized(recent) {
            recent.addLast(e)
            while (recent.isNotEmpty() && (now - recent.first().time > KEEP_MS || recent.size > MAX_EVENTS)) recent.removeFirst()
        }
        TripRecorder.activeTripId?.let { append(it, listOf(e)) }
    }

    /** The lines kept from [from] on. */
    fun since(from: Long): List<Event> = synchronized(recent) { recent.filter { it.time >= from } }

    private fun file(id: Long) = dir?.let { File(it, "$id.csv") }

    @Synchronized fun append(id: Long, events: List<Event>) {
        if (events.isEmpty()) return
        val f = file(id) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText((if (f.exists()) "" else HEADER) + events.joinToString("") { line(it) })
        }
    }

    /** The trace's or snapshot's SDR activity as CSV, or null when none was recorded. */
    fun read(id: Long): String? = read(file(id))

    /** The trace or snapshot that recorded SDR activity last, for adb ([com.rfsentinel.app.util.LogProvider]). */
    fun latest(): Long? = ids().firstOrNull()?.first

    /** Every trace and snapshot with SDR activity, newest first: id and size. */
    fun ids(): List<Pair<Long, Long>> = dir?.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f.length() } }

    private fun read(f: File?): String? = f?.takeIf { it.exists() && it.length() > HEADER.length }?.let { runCatching { it.readText() }.getOrNull() }

    fun delete(id: Long) { file(id)?.delete() }

    internal fun line(e: Event): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.time)) + "," +
            (e.lat?.let { "%.6f".format(Locale.US, it) } ?: "") + "," + (e.lon?.let { "%.6f".format(Locale.US, it) } ?: "") + "," +
            ExportText.csv(e.text) + "\n"
}
