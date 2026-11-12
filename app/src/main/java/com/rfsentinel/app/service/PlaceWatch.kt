package com.rfsentinel.app.service

/**
 * Settings › Trackers & followers › Watch this place (AntiHunter's baseline anomaly; the idea, not code). With the phone left
 * in one place (home, a hotel room), it first learns for [LEARN_MS] what is around, then reports:
 *  - a new device: one it didn't hear while learning, heard close by (at least [MIN_RSSI] dBm) without a break for [STAY_MS]
 *    ([STAY_RANDOM_MS] on a random address). A car or a person walking by is gone long before;
 *  - one gone and back: a device on a fixed address heard for [RESIDENT_MS] or more while learning that went quiet for
 *    [MISSING_MS], then its return.
 * Phones and tags change their random address every 15 minutes or so. A new address linked to its earlier one ([previous]:
 * [DeviceRegistry.linkedFrom]) counts as that device; one that wasn't linked never stays [STAY_RANDOM_MS] on one address, so the
 * household's own phones don't set it off (nor does a visitor's phone that does the same). Paused while you are more than
 * [RADIUS_M] from the place. Memory only: a restart learns again. Pure, not thread-safe (the service's housekeeping loop).
 */
class PlaceWatch {

    /** One device heard: its address, whether that's random, its strongest signal, when last heard, how ("BLE", "WIFI"...). */
    class Device(val mac: String, val random: Boolean, val bestRssi: Int, val lastSeen: Long, val source: String = "BLE")

    enum class Kind { NEW, GONE, BACK }

    /** Something to report about [mac]: [minutes] it stayed (new), was last heard ago (gone) or was away (back). */
    class Event(val kind: Kind, val mac: String, val minutes: Long, val source: String)

    /** Watching, since [startedAt]. */
    var on = false; private set
    var startedAt = 0L; private set
    /** Where (your position when it started, or your first fix after), or null without a fix yet. */
    var place: Pair<Double, Double>? = null; private set
    /** You are more than [RADIUS_M] from [place]: nothing is checked. */
    var away = false; private set

    private val known = HashSet<String>()
    /** Fixed addresses heard while learning: when first heard. */
    private val learnFirst = HashMap<String, Long>()
    /** Devices on a fixed address heard for [RESIDENT_MS] while learning: when last heard. */
    private val resident = HashMap<String, Long>()
    private val residentSource = HashMap<String, String>()
    private val missing = HashSet<String>()
    /** Devices not learned, heard since (first, last), forgotten after a [BREAK_MS] break. */
    private val seen = HashMap<String, LongArray>()
    private val alerted = HashSet<String>()

    fun learning(now: Long) = on && now - startedAt < LEARN_MS
    /** Devices learned (known) and among them those on a fixed address that stay (resident). */
    val learned get() = known.size to resident.size

    fun start(now: Long, here: Pair<Double, Double>?) {
        stop()
        on = true; startedAt = now; place = here
    }

    fun stop() {
        on = false; startedAt = 0L; place = null; away = false
        known.clear(); learnFirst.clear(); resident.clear(); residentSource.clear(); missing.clear(); seen.clear(); alerted.clear()
    }

    /** One look at [devices] (from the device list) at [now], you being [here]; [previous] gives an address's earlier one. */
    fun check(now: Long, here: Pair<Double, Double>?, devices: List<Device>, previous: (String) -> String?): List<Event> {
        if (!on) return emptyList()
        if (place == null) place = here
        val p = place
        if (here != null && p != null && metres(here, p) > RADIUS_M) { away = true; seen.clear(); return emptyList() }
        if (away) { away = false; resident.keys.forEach { resident[it] = now } } // back: the residents get a fresh start
        val heard = devices.filter { now - it.lastSeen <= HEARD_MS }
        if (learning(now)) {
            for (d in heard) {
                known += d.mac
                if (d.random) continue
                val first = learnFirst.getOrPut(d.mac) { now }
                if (now - first >= RESIDENT_MS) { resident[d.mac] = now; residentSource[d.mac] = d.source }
            }
            return emptyList()
        }
        val out = ArrayList<Event>()
        for (d in heard) {
            resident[d.mac]?.let { last ->
                if (missing.remove(d.mac)) out += Event(Kind.BACK, d.mac, (now - last) / 60_000, d.source)
                resident[d.mac] = now
            }
            if (d.mac in known || d.mac in alerted) continue
            val chain = chain(d.mac, previous)
            if (chain.any { it in known }) { known += d.mac; continue } // a learned device on its new address
            if (chain.any { it in alerted }) { alerted += d.mac; continue }
            if (d.bestRssi < MIN_RSSI) continue
            val first = seen[d.mac]?.get(0) ?: chain.mapNotNull { seen[it]?.get(0) }.minOrNull() ?: now
            seen[d.mac] = longArrayOf(first, now)
            if (now - first >= if (d.random) STAY_RANDOM_MS else STAY_MS) {
                alerted += d.mac
                out += Event(Kind.NEW, d.mac, (now - first) / 60_000, d.source)
            }
        }
        seen.entries.removeAll { now - it.value[1] > BREAK_MS }
        for ((mac, last) in resident) if (mac !in missing && now - last > MISSING_MS) {
            missing += mac
            out += Event(Kind.GONE, mac, (now - last) / 60_000, residentSource[mac] ?: "BLE")
        }
        return out
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

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second) * Math.cos(Math.toRadians((a.first + b.first) / 2))
        return 6_371_000.0 * Math.sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        const val LEARN_MS = 10 * 60_000L
        const val STAY_MS = 10 * 60_000L
        const val STAY_RANDOM_MS = 20 * 60_000L
        const val RESIDENT_MS = 5 * 60_000L
        const val MISSING_MS = 30 * 60_000L
        const val MIN_RSSI = -80
        const val RADIUS_M = 300.0
        /** Heard this recently counts as there now. */
        private const val HEARD_MS = 60_000L
        /** A device not heard this long left: if it comes back, its stay starts over. */
        private const val BREAK_MS = 3 * 60_000L
    }
}
