package com.rfsentinel.app.sdr

/**
 * The RTL-SDR transmissions heard recently, one entry per frequency, for the main list: last
 * signal strength, strongest, and whether the transmitter is getting closer or farther. A
 * trend is called when the signal moved by at least the step set in Settings (in dB) since the
 * last call - signal strength only, so it's a rough guide (antenna, terrain and power all count).
 */
object RadioLog {

    enum class Trend { NEW, CLOSER, FARTHER, STEADY }

    data class Entry(
        val freqHz: Long,
        val bandLabel: String,
        val kind: RadioWatch.Kind,
        val firstSeen: Long,
        val lastSeen: Long,
        val snrDb: Int,
        val peakSnrDb: Int,
        val confidence: Int,
        /** From the imported frequency list or RadioReference, when one matched. */
        val name: String?,
        val trend: Trend,
        /** The signal when the trend was last called: the next call compares to it. */
        val refSnrDb: Int
    ) {
        val mhz: String get() = "%.4f".format(java.util.Locale.US, freqHz / 1e6)
    }

    /** A gap this long starts a new episode (a fresh "new", no trend from last time). */
    private const val EPISODE_GAP_MS = 90_000L
    /** Entries leave the list this long after they were last heard. */
    const val KEEP_MS = 5 * 60_000L

    private val entries = LinkedHashMap<Long, Entry>()

    /** Records a reading; returns CLOSER / FARTHER when that should be called out, else null. */
    @Synchronized
    fun observe(e: RadioWatch.Event, name: String?, stepDb: Int, now: Long): Trend? {
        val prev = entries[e.freqHz]
        if (prev == null || now - prev.lastSeen > EPISODE_GAP_MS) {
            entries[e.freqHz] = Entry(e.freqHz, e.band.label, e.band.kind, now, now, e.snrDb, e.snrDb, e.confidence,
                name, Trend.NEW, e.snrDb)
            prune(now)
            return null
        }
        val delta = e.snrDb - prev.refSnrDb
        val trend = when {
            stepDb > 0 && delta >= stepDb -> Trend.CLOSER
            stepDb > 0 && delta <= -stepDb -> Trend.FARTHER
            else -> null
        }
        entries[e.freqHz] = prev.copy(
            lastSeen = now, snrDb = e.snrDb, peakSnrDb = maxOf(prev.peakSnrDb, e.snrDb),
            confidence = maxOf(prev.confidence, e.confidence), name = name ?: prev.name,
            trend = trend ?: (if (prev.trend == Trend.NEW) Trend.STEADY else prev.trend),
            refSnrDb = if (trend != null) e.snrDb else prev.refSnrDb
        )
        return trend
    }

    /** Heard within [KEEP_MS], most recent first. */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): List<Entry> {
        prune(now)
        return entries.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun prune(now: Long) {
        entries.values.removeAll { now - it.lastSeen > KEEP_MS }
    }
}
