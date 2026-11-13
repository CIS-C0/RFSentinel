package com.rfsentinel.app.link

import com.rfsentinel.app.ui.StatusPanel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Which external hardware runs on a Sentinel, for the others in its group: per adapter only its kind, a state and
 * the chip name in its status ("MT7612U"). Never serials, addresses, hosts, frequencies or places.
 * Sent only when it changes, plus a slow heartbeat ([Beat]); kept per Sentinel on the receiving phone ([received]).
 */
object LinkHardware {
    /** Wire order: never reorder, only add at the end (an unknown kind is skipped by the receiver). */
    enum class Kind(val label: String) { WIFI("USB WiFi"), BT("USB Bluetooth"), SDR("SDR"), ESP("ESP32"), OUISPY("OUI-SPY"), MESH("Mesh radio") }

    /** [wide]: a USB WiFi adapter that tunes 5 GHz too, so linked phones sharing the channels give it some of those. */
    data class Item(val kind: Kind, val state: StatusPanel.State, val chip: String?, val wide: Boolean = false) {
        /** A status line [StatusPanel] reads back with the same state ("USB WiFi (MT7612U) · running"). */
        val status get() = kind.label + (chip?.let { " ($it)" } ?: "") + " · " + when (state) {
            StatusPanel.State.LIVE -> "running"
            StatusPanel.State.WAIT -> "waiting"
            StatusPanel.State.ERROR -> "error"
            StatusPanel.State.INFO -> "has no Bluetooth"
        }
    }

    private const val CHIP_MAX = 12
    // Chip names only ("MT7612U", "Intel"): brackets holding anything else (an address, a host) aren't sent.
    private val CHIP = Regex("""\(([A-Za-z0-9][A-Za-z0-9 _+-]{0,11})\)""")

    /** This phone's hardware from its status lines (blank: not there). */
    fun items(statuses: List<Pair<Kind, String>>, wifiWide: Boolean = false): List<Item> = statuses.filter { it.second.isNotBlank() }.map { (kind, s) ->
        Item(kind, StatusPanel.stateOf(s), CHIP.find(StatusPanel.name(s))?.groupValues?.get(1)?.takeIf { c -> c.any { it.isLetter() } },
            kind == Kind.WIFI && wifiWide)
    }

    /** One byte per adapter (kind, state, chip flag, 5 GHz flag: older versions ignore it), then its chip name. */
    fun write(out: ByteArrayOutputStream, items: List<Item>) {
        for (i in items) {
            val chip = i.chip?.let { MeshCodec.utf8(it, CHIP_MAX) }
            out.write(i.kind.ordinal or (i.state.ordinal shl 4) or (if (chip != null) 0x40 else 0) or (if (i.wide) 0x80 else 0))
            chip?.let { out.write(it.size); out.write(it) }
        }
    }

    fun read(b: ByteBuffer): List<Item> {
        val out = ArrayList<Item>()
        while (b.hasRemaining()) {
            val head = b.get().toInt() and 0xFF
            val chip = if (head and 0x40 != 0) String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }, Charsets.UTF_8) else null
            val kind = Kind.entries.getOrNull(head and 0x0F) ?: continue
            out += Item(kind, StatusPanel.State.entries[(head shr 4) and 3], chip, head and 0x80 != 0)
        }
        return out
    }

    /**
     * When to send on one way of linking: on a change (spaced by [minGapMs]), else every [heartbeatMs] while there is
     * hardware. A phone with none never sends; the last adapter unplugged sends one empty summary.
     */
    class Beat(private val minGapMs: Long, private val heartbeatMs: Long) {
        private var sent: List<Item> = emptyList()
        private var at = 0L

        fun due(items: List<Item>, now: Long) =
            if (items != sent) now - at >= minGapMs else items.isNotEmpty() && now - at >= heartbeatMs

        fun sent(items: List<Item>, now: Long) { sent = items; at = now }
    }

    /** Another Sentinel's hardware as last heard ([time] on its clock, [heardAt] on this phone's). */
    class Remote(val time: Long, val heardAt: Long, val items: List<Item>)

    val remote = ConcurrentHashMap<Long, Remote>()

    /**
     * A summary from Sentinel [origin]; the adapters that were running and now are gone or in error (to notify).
     * An older summary arriving late (another way of linking) changes nothing.
     */
    fun received(origin: Long, time: Long, items: List<Item>, now: Long): List<Item> {
        val before = remote[origin]
        if (before != null && time < before.time) return emptyList()
        remote[origin] = Remote(time, now, items)
        if (before == null) return emptyList()
        val after = items.associateBy { it.kind }
        return before.items.filter { it.state == StatusPanel.State.LIVE }.filter {
            val next = after[it.kind]
            next == null || next.state == StatusPanel.State.ERROR
        }
    }

    /** What to show for [origin]: nothing once its summaries stopped coming for [STALE_MS]. */
    fun of(origin: Long, now: Long): List<Item> = remote[origin]?.takeIf { now - it.heardAt < STALE_MS }?.items.orEmpty()

    /** Heartbeats: the mesh's air time is precious. */
    const val MESH_GAP_MS = 60_000L
    const val MESH_HEARTBEAT_MS = 15 * 60_000L
    const val NET_HEARTBEAT_MS = 2 * 60_000L
    const val BT_HEARTBEAT_MS = 60_000L
    /** Two mesh heartbeats missed and a bit. */
    const val STALE_MS = 2 * MESH_HEARTBEAT_MS + 5 * 60_000L
}
