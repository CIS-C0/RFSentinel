package com.rfsentinel.app.link

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

/**
 * What goes out over the mesh next. LoRa carries one small packet now and then, so devices take turns:
 * watchlist matches first (again every minute while heard), then flagged devices (every 3 minutes),
 * then every other device (once, then every 15 minutes), newest first.
 * [maxRepeatMs]: the internet relay has no air time to save, so it repeats every device still heard this often:
 * the receiver's "off the list after" time then works below the mesh's 15 minutes.
 */
class MeshOutbox(private val maxRepeatMs: Long = Long.MAX_VALUE) {
    private class Entry(var source: Advert.Source, var rssi: Int, var name: String?, var hits: List<Hit>, var seen: Long) {
        var sent = 0L
        var sentHits: List<Hit> = emptyList()
        val priority get() = when {
            hits.any { it.category == Category.CUSTOM } -> 2
            hits.isNotEmpty() -> 1
            else -> 0
        }
    }

    private val devices = LinkedHashMap<String, Entry>()

    /** A device heard here (or by a Sentinel linked over Bluetooth), with what it matched. */
    @Synchronized
    fun add(a: Advert, hits: List<Hit>, now: Long) {
        // Very weak matches don't alert: not worth the air time.
        val keep = hits.filter { !it.listOnly }
        val watch = keep.firstOrNull { it.category == Category.CUSTOM }
        val send = listOfNotNull(watch) + keep.filter { it !== watch }.take(if (watch == null) 2 else 1)
        val e = devices[a.mac]
        if (e == null) {
            if (devices.size >= MAX_DEVICES) trim(now)
            devices[a.mac] = Entry(a.source, a.rssi, a.name, send, now)
        } else {
            e.source = a.source; e.rssi = a.rssi; e.seen = now
            if (a.name != null) e.name = a.name
            e.hits = send
        }
    }

    /** A watchlist match not sent yet (or newly matched): worth sending before the usual wait. */
    @Synchronized
    fun urgent(now: Long) = devices.values.any {
        it.priority == 2 && now - it.seen < FRESH_MS && (it.sent == 0L || it.hits != it.sentHits)
    }

    /** The devices due, most important first, filling one packet; they count as sent. */
    @Synchronized
    fun take(now: Long, room: Int): List<MeshCodec.Device> {
        trim(now)
        val due = devices.entries.filter { (_, e) ->
            now - e.seen < FRESH_MS && (e.sent == 0L || e.hits != e.sentHits || now - e.sent >= minOf(repeatMs(e.priority), maxRepeatMs))
        }.sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.priority }
            // Never sent first, then the longest waiting: when a packet can't hold them all, each gets its turn.
            .thenBy { it.value.sent }
            .thenByDescending { it.value.seen })
        val out = ArrayList<MeshCodec.Device>()
        var left = room
        for ((mac, e) in due) {
            val d = MeshCodec.Device(mac, e.source, e.rssi, ((now - e.seen) / 1000).toInt(), e.name, e.hits)
            val size = MeshCodec.size(d)
            if (size > left) continue
            out += d
            left -= size
            e.sent = now
            e.sentHits = e.hits
            if (left < 9) break
        }
        return out
    }

    private fun trim(now: Long) {
        devices.entries.removeAll { now - it.value.seen > FORGET_MS }
        while (devices.size >= MAX_DEVICES) devices.remove(devices.keys.first())
    }

    companion object {
        /** Only devices heard this recently go out. */
        const val FRESH_MS = 2 * 60_000L
        const val FORGET_MS = 5 * 60_000L
        const val MAX_DEVICES = 2_000
        /** The internet relay's repeat: under the receiver's shortest "off the list after" (30 s) with a tick to spare. */
        const val NET_REPEAT_MS = 20_000L
        fun repeatMs(priority: Int) = when (priority) {
            2 -> 60_000L
            1 -> 3 * 60_000L
            else -> 15 * 60_000L
        }
    }
}
