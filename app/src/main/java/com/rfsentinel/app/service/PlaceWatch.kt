package com.rfsentinel.app.service

/**
 * Settings › Trackers & followers › Watch this place, with AntiHunter's baseline anomaly rules and settings (the idea and
 * its numbers, not code); the numbers can be changed ([settings]), the defaults below are AntiHunter's. With the phone left in one place (home, a hotel room), it first learns for [LEARN_MS] every
 * device around, then reports, counting only what is heard at [MIN_RSSI] dBm or stronger:
 *  - [Kind.NEW]: a device it didn't learn, heard twice within [NEW_WINDOW_MS]. From then on it is known;
 *  - [Kind.GONE]: a device heard during the watch, then quiet for [ABSENCE_MS];
 *  - [Kind.BACK]: one gone that is heard again within [REAPPEAR_MS] of going (later, it just counts as there again);
 *  - [Kind.MOVED]: one whose signal jumped by [RSSI_CHANGE_DB] or more from one look to the next, the third time.
 * Phones and tags change their random address now and then: a new address linked to an earlier one ([previous]:
 * [DeviceRegistry.linkedFrom]) counts as that device, and the address it left isn't reported gone. Paused while you are
 * more than [RADIUS_M] from the place. What it learned is kept ([save] / [restore]) so a restart of the app or the phone
 * doesn't learn again around someone already there. Pure, not thread-safe (the service's housekeeping loop).
 */
class PlaceWatch {

    /** One device heard: its address, its signal now, when last heard, how ("BLE", "WIFI"...). */
    class Device(val mac: String, val rssi: Int, val lastSeen: Long, val source: String = "BLE")

    enum class Kind { NEW, GONE, BACK, MOVED }

    /** Settings › Trackers & followers › Watch this place: AntiHunter's numbers by default, and ours for [radiusM]. */
    data class Settings(
        val learnMs: Long = LEARN_MS,
        val minRssi: Int = MIN_RSSI,
        val newWindowMs: Long = NEW_WINDOW_MS,
        val absenceMs: Long = ABSENCE_MS,
        val reappearMs: Long = REAPPEAR_MS,
        val rssiChangeDb: Int = RSSI_CHANGE_DB,
        val movedAfter: Int = MOVED_AFTER,
        val radiusM: Double = RADIUS_M,
    )

    /** May change while it watches: the next look uses the new numbers. */
    var settings = Settings()

    /**
     * Something to report about [mac], heard at [rssi]: [value] is the seconds since it was last heard (gone), the seconds
     * it was away (back) or its signal the look before (moved).
     */
    class Event(val kind: Kind, val mac: String, val value: Int, val rssi: Int, val source: String)

    /** Watching, since [startedAt]. */
    var on = false; private set
    var startedAt = 0L; private set
    /** Where (your position when it started, or your first fix after), or null without a fix yet. */
    var place: Pair<Double, Double>? = null; private set
    /** You are more than [RADIUS_M] from [place]: nothing is checked. */
    var away = false; private set
    /** Reports since it started (or was restored), for the summary when it's turned off. */
    var reports = 0; private set

    /** Learned, or reported new since: not new again. */
    private val known = HashSet<String>()

    /** A device heard during the watch. [present]: known, or reported new; until then [sightings] since [firstAt]. */
    private class Track(var rssi: Int, var lastSeen: Long, var present: Boolean, val source: String) {
        var firstAt = 0L
        var sightings = 0
        var goneAt = 0L
        var changes = 0
    }
    private val tracks = HashMap<String, Track>()

    fun learning(now: Long) = on && now - startedAt < settings.learnMs
    /** Devices known (learned, and reported new since). */
    val learned get() = known.size

    fun start(now: Long, here: Pair<Double, Double>?) {
        stop()
        on = true; startedAt = now; place = here
    }

    fun stop() {
        on = false; startedAt = 0L; place = null; away = false; reports = 0
        known.clear(); tracks.clear()
    }

    /** One look at [devices] (from the device list) at [now], you being [here]; [previous] gives an address's earlier one. */
    fun check(now: Long, here: Pair<Double, Double>?, devices: List<Device>, previous: (String) -> String?): List<Event> {
        if (!on) return emptyList()
        val s = settings
        if (place == null) place = here
        val p = place
        if (here != null && p != null && metres(here, p) > s.radiusM) { away = true; tracks.clear(); return emptyList() }
        away = false
        val heard = devices.filter { now - it.lastSeen <= HEARD_MS }
        if (learning(now)) { heard.forEach { known += it.mac }; return emptyList() }
        val out = ArrayList<Event>()
        for (d in heard) {
            val t = tracks[d.mac]
            if (t != null && d.lastSeen <= t.lastSeen) continue // nothing new from it since the last look
            if (d.rssi < s.minRssi) continue
            if (t == null) {
                val wasKnown = d.mac in known || chain(d.mac, previous).any { it in known }
                if (wasKnown) known += d.mac
                tracks[d.mac] = Track(d.rssi, d.lastSeen, wasKnown, d.source).also { if (!wasKnown) { it.firstAt = now; it.sightings = 1 } }
                continue
            }
            if (!t.present) {
                if (now - t.firstAt > s.newWindowMs) { t.firstAt = now; t.sightings = 1 } else t.sightings++
                t.rssi = d.rssi; t.lastSeen = d.lastSeen
                if (t.sightings < 2) continue
                t.present = true; known += d.mac; reports++
                out += Event(Kind.NEW, d.mac, secs(now - t.firstAt), d.rssi, d.source)
                continue
            }
            if (t.goneAt > 0) {
                if (now - t.goneAt <= s.reappearMs) { reports++; out += Event(Kind.BACK, d.mac, secs(now - t.lastSeen), d.rssi, d.source) }
                t.goneAt = 0
            }
            if (kotlin.math.abs(d.rssi - t.rssi) >= s.rssiChangeDb && ++t.changes >= s.movedAfter) {
                t.changes = 0; reports++
                out += Event(Kind.MOVED, d.mac, t.rssi, d.rssi, d.source)
            }
            t.rssi = d.rssi; t.lastSeen = d.lastSeen
        }
        // Gone: quiet for absenceMs, unless it went on with a new address (heard now, linked to this one).
        var rotated: Set<String>? = null
        for ((mac, t) in tracks) {
            if (!t.present || t.goneAt > 0 || now - t.lastSeen <= s.absenceMs) continue
            t.goneAt = now
            val r = rotated ?: heard.flatMap { chain(it.mac, previous) }.toSet().also { rotated = it }
            if (mac !in r) out += Event(Kind.GONE, mac, secs(now - t.lastSeen), t.rssi, t.source)
        }
        // Forgotten: gone past the time a return is reported, or heard once and not again in time.
        tracks.entries.removeAll { (_, t) ->
            if (t.present) t.goneAt > 0 && now - t.goneAt > s.reappearMs else now - t.firstAt > s.newWindowMs
        }
        return out
    }

    /** What it learned, for [restore]: lines of start time, place, known devices. */
    fun save(): String = listOf(startedAt.toString(), place?.let { "${it.first},${it.second}" }.orEmpty(), known.joinToString(" "))
        .joinToString("\n")

    /** Back to what [save] kept, watching on; false (and off) when [text] isn't one or is from later than [now]. */
    fun restore(text: String, now: Long): Boolean {
        stop()
        val l = text.split("\n")
        val started = l.getOrNull(0)?.toLongOrNull()
        if (l.size != 3 || started == null || started > now) return false
        val p = l[1].split(",").mapNotNull { it.toDoubleOrNull() }
        on = true; startedAt = started; place = if (p.size == 2) p[0] to p[1] else null
        known += l[2].split(" ").filter { it.isNotEmpty() }
        return true
    }

    /** [mac]'s earlier addresses, newest first (at most 8). */
    private fun chain(mac: String, previous: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        var cur = mac
        repeat(8) {
            val p = previous(cur)?.takeIf { it != mac && it !in out } ?: return out
            out += p; cur = p
        }
        return out
    }

    private fun secs(ms: Long) = (ms / 1000).coerceAtMost(Short.MAX_VALUE.toLong()).toInt()

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second) * Math.cos(Math.toRadians((a.first + b.first) / 2))
        return 6_371_000.0 * Math.sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        // AntiHunter's defaults (baseline.cpp, scanner.cpp), for [Settings].
        const val LEARN_MS = 5 * 60_000L
        const val MIN_RSSI = -60
        const val NEW_WINDOW_MS = 60_000L
        const val ABSENCE_MS = 2 * 60_000L
        const val REAPPEAR_MS = 5 * 60_000L
        const val RSSI_CHANGE_DB = 20
        const val MOVED_AFTER = 3
        /** Ours: paused this far from the place. */
        const val RADIUS_M = 300.0
        /** Heard this recently counts as there now. */
        private const val HEARD_MS = 60_000L
    }
}
