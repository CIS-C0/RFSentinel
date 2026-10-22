package com.rfsentinel.app.link

/**
 * What this Sentinel will send next: devices are heard many times a second, so each one goes out when first heard,
 * then again only every [REPEAT_MS] or when its signal or matches change - about a second's worth per [drain].
 */
class LinkOutbox(private val myId: Long) {
    private class Sent(val at: Long, val rssi: Int, val hits: Int)

    private val lastSent = HashMap<String, Sent>()
    private val sightings = LinkedHashMap<String, LinkCodec.Sighting>()
    private val radios = ArrayList<LinkCodec.Radio>()
    // Starts at the clock (seconds), so a restarted Sentinel's batches never repeat numbers the others already saw.
    private var seq = (System.currentTimeMillis() / 1000).toInt()

    @Synchronized
    fun add(s: LinkCodec.Sighting) {
        val a = s.advert
        val key = "${s.kind}|${a.mac}"
        val hitsKey = s.hits.hashCode() * 31 + s.probed.hashCode()
        val last = lastSent[key]
        if (last != null && a.timestamp - last.at < REPEAT_MS && kotlin.math.abs(a.rssi - last.rssi) < RSSI_STEP && last.hits == hitsKey) return
        lastSent[key] = Sent(a.timestamp, a.rssi, hitsKey)
        sightings[key] = s // the latest for this device replaces one still waiting
        if (lastSent.size > MAX_TRACKED) lastSent.entries.removeAll { a.timestamp - it.value.at > REPEAT_MS }
    }

    @Synchronized
    fun addRadio(r: LinkCodec.Radio) { if (radios.size < MAX_RADIOS) radios += r }

    /** The batch to send now, or null when nothing new was heard. */
    @Synchronized
    fun drain(now: Long, lat: Double, lon: Double): LinkCodec.Batch? {
        if (sightings.isEmpty() && radios.isEmpty()) return null
        val list = sightings.values.take(MAX_PER_BATCH)
        val keys = sightings.keys.take(MAX_PER_BATCH)
        keys.forEach { sightings.remove(it) }
        val b = LinkCodec.Batch(myId, ++seq, 0, now, lat, lon, list, radios.toList())
        radios.clear()
        return b
    }

    @Synchronized
    fun clear() { lastSent.clear(); sightings.clear(); radios.clear() }

    companion object {
        const val REPEAT_MS = 3_000L
        const val RSSI_STEP = 8
        const val MAX_PER_BATCH = 400
        const val MAX_RADIOS = 50
        const val MAX_TRACKED = 5_000
    }
}

/** Batches already handled (heard straight from their Sentinel or relayed by another), so each is used and passed on once. */
class SeenBatches(private val capacity: Int = 4_000) {
    private val seen = object : LinkedHashMap<Pair<Long, Int>, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Int>, Unit>?) = size > capacity
    }

    /** True the first time this batch shows up. */
    @Synchronized
    fun first(origin: Long, seq: Int): Boolean = seen.put(origin to seq, Unit) == null

    @Synchronized
    fun clear() = seen.clear()
}
