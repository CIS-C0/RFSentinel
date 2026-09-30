package com.rfsentinel.app.history

import java.util.Calendar
import java.util.TimeZone

/**
 * Past detections, for the history map and timeline. Built from the match log
 * (positions only when GPS tagging is on) and from recorded traces. Pure
 * Kotlin, so it's unit-tested on the JVM; all data stays on the phone.
 */
data class HistoryEvent(
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    /** A detect.Category name. */
    val category: String,
    val label: String,
    val mac: String
) {
    val positioned get() = lat != null && lon != null
}

object History {

    /**
     * The match log writes a row every ~30 s while a device stays in range, so a
     * parked camera would dominate the counts. Rows of the same device less than
     * [gapMs] apart are merged into one encounter (keeping the first position).
     */
    fun encounters(events: List<HistoryEvent>, gapMs: Long = 10 * 60_000L): List<HistoryEvent> {
        val out = mutableListOf<HistoryEvent>()
        events.groupBy { it.mac }.values.forEach { perDevice ->
            var last = Long.MIN_VALUE / 2
            var current: HistoryEvent? = null
            for (e in perDevice.sortedBy { it.time }) {
                if (current == null || e.time - last > gapMs) {
                    current?.let(out::add)
                    current = e
                } else if (!current.positioned && e.positioned) {
                    current = current.copy(lat = e.lat, lon = e.lon)
                }
                last = e.time
            }
            current?.let(out::add)
        }
        return out.sortedByDescending { it.time }
    }

    /** Encounters per hour of day (0-23), in [zone]. */
    fun byHour(events: List<HistoryEvent>, zone: TimeZone = TimeZone.getDefault()): IntArray {
        val out = IntArray(24)
        val cal = Calendar.getInstance(zone)
        events.forEach { cal.timeInMillis = it.time; out[cal.get(Calendar.HOUR_OF_DAY)]++ }
        return out
    }

    /** Encounters per weekday, Monday first (0 = Monday ... 6 = Sunday). */
    fun byWeekday(events: List<HistoryEvent>, zone: TimeZone = TimeZone.getDefault()): IntArray {
        val out = IntArray(7)
        val cal = Calendar.getInstance(zone)
        events.forEach {
            cal.timeInMillis = it.time
            out[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]++ // SUNDAY=1 -> 6, MONDAY=2 -> 0
        }
        return out
    }

    /** The most frequent labels, e.g. "Flock Safety camera" to 12. */
    fun topLabels(events: List<HistoryEvent>, n: Int = 3): List<Pair<String, Int>> =
        events.groupingBy { it.label }.eachCount().entries
            .sortedByDescending { it.value }.take(n).map { it.key to it.value }

    private val DAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** One line such as "Busiest: Fri, 17:00-18:00". */
    fun busiest(events: List<HistoryEvent>, zone: TimeZone = TimeZone.getDefault()): String? {
        if (events.isEmpty()) return null
        val h = byHour(events, zone).withIndex().maxBy { it.value }.index
        val d = byWeekday(events, zone).withIndex().maxBy { it.value }.index
        return String.format(java.util.Locale.US, "Busiest: %s, %02d:00-%02d:00", DAYS[d], h, (h + 1) % 24)
    }
}
