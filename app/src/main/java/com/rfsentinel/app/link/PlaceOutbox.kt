package com.rfsentinel.app.link

/**
 * Watch this place's reports waiting to go to the linked Sentinels: each way ([BLUETOOTH], [MESH], [NET]) sends each
 * report once, oldest first, while it's fresh ([KEEP_MS]). [urgent] ones (a device back or moving) don't wait the mesh's turn.
 */
class PlaceOutbox {
    private class Entry(val note: MeshCodec.PlaceNote, val at: Long, val urgent: Boolean) {
        val sent = BooleanArray(3)
    }

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun add(note: MeshCodec.PlaceNote, urgent: Boolean, now: Long) {
        if (MeshCodec.size(note) == null) return
        entries += Entry(note, now, urgent)
        trim(now)
    }

    @Synchronized
    fun waiting(way: Int, now: Long) = entries.any { !it.sent[way] && now - it.at <= KEEP_MS }

    @Synchronized
    fun urgent(way: Int, now: Long) = entries.any { !it.sent[way] && it.urgent && now - it.at <= KEEP_MS }

    /** The reports not sent [way] yet that fit [room] bytes, oldest first; they count as sent. */
    @Synchronized
    fun take(way: Int, room: Int, now: Long): List<MeshCodec.PlaceNote> {
        trim(now)
        val out = ArrayList<MeshCodec.PlaceNote>()
        var left = room
        for (e in entries) {
            if (e.sent[way]) continue
            val size = MeshCodec.size(e.note) ?: continue
            if (size > left) break
            out += e.note
            left -= size
            e.sent[way] = true
        }
        return out
    }

    private fun trim(now: Long) {
        entries.removeAll { now - it.at > KEEP_MS || it.sent.all { s -> s } }
        while (entries.size > MAX) entries.removeAt(0)
    }

    companion object {
        const val BLUETOOTH = 0
        const val MESH = 1
        const val NET = 2
        const val KEEP_MS = 10 * 60_000L
        const val MAX = 64
    }
}
