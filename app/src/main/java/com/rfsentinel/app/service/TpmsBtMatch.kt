package com.rfsentinel.app.service

import java.util.Locale

/**
 * Ties a tire-pressure sensor to a flagged Bluetooth device in the same vehicle: each time a tire sensor is heard,
 * it's paired with the flagged Bluetooth devices heard within [WINDOW_MS]. A pair heard together again after a gap
 * of [GAP_MS] (a second encounter), or at [SPOTS] places [SPOT_M] apart in one encounter (travelling together),
 * is a match. A Bluetooth device or tire sensor heard with more than [MAX_PARTNERS] partners in a day is with you
 * everywhere (your own gear, a car you didn't teach as yours): it never matches.
 */
class TpmsBtMatch {
    class Pair(val tpms: String, val mac: String, var encounters: Int, var spots: Int, var lastCo: Long,
               var lat: Double, var lon: Double, var alerted: Boolean)

    private val pairs = HashMap<String, Pair>()
    /** Something worth saving changed (a new pair, encounter or place) since [save]. */
    var changed = false
        private set

    val size get() = pairs.size

    /** Tire sensor [tpms] heard now, with these flagged Bluetooth devices in the window. Returns the pairs that just matched. */
    fun heard(tpms: String, bt: List<String>, now: Long, lat: Double, lon: Double): List<Pair> {
        val out = ArrayList<Pair>()
        for (mac in bt) {
            val id = "$tpms\t$mac"
            val p = pairs[id]
            if (p == null) {
                pairs[id] = Pair(tpms, mac, 1, 1, now, lat, lon, false); changed = true; continue
            }
            if (now - p.lastCo >= GAP_MS) {
                p.encounters++; p.spots = 1; p.lat = lat; p.lon = lon; changed = true
            } else if (com.rfsentinel.app.detect.GnssAnalyzer.distanceM(p.lat, p.lon, lat, lon) >= SPOT_M) {
                p.spots++; p.lat = lat; p.lon = lon; changed = true
            }
            p.lastCo = now
            if (!p.alerted && (p.encounters >= 2 || p.spots >= SPOTS) && !everywhere(p, now)) {
                p.alerted = true; changed = true; out += p
            }
        }
        if (pairs.size > MAX_PAIRS) prune(now)
        return out
    }

    private fun everywhere(p: Pair, now: Long): Boolean {
        var byMac = 0; var byTpms = 0
        for (q in pairs.values) {
            if (now - q.lastCo > DAY_MS) continue
            if (q.mac == p.mac) byMac++
            if (q.tpms == p.tpms) byTpms++
        }
        return byMac > MAX_PARTNERS || byTpms > MAX_PARTNERS
    }

    /** Drops pairs not heard for [KEEP_MS], then the oldest beyond [MAX_PAIRS]. */
    fun prune(now: Long) {
        val before = pairs.size
        pairs.values.removeAll { now - it.lastCo > KEEP_MS }
        if (pairs.size > MAX_PAIRS) pairs.values.sortedBy { it.lastCo }.take(pairs.size - MAX_PAIRS)
            .forEach { pairs.remove("${it.tpms}\t${it.mac}") }
        if (pairs.size != before) changed = true
    }

    /** One pair per line; places rounded to ~10 m (enough to tell 2 km apart). */
    fun save(): String {
        changed = false
        return pairs.values.joinToString("\n") {
            String.format(Locale.US, "%s\t%s\t%d\t%d\t%d\t%.4f\t%.4f\t%d", it.tpms, it.mac, it.encounters, it.spots, it.lastCo,
                it.lat, it.lon, if (it.alerted) 1 else 0)
        }
    }

    fun load(text: String) {
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            if (f.size != 8) continue
            val p = runCatching {
                Pair(f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toLong(), f[5].toDouble(), f[6].toDouble(), f[7] == "1")
            }.getOrNull() ?: continue
            pairs["${p.tpms}\t${p.mac}"] = p
        }
    }

    companion object {
        /** Tire sensors send about once a minute while rolling: a Bluetooth device heard this close in time counts. */
        const val WINDOW_MS = 2 * 60_000L
        const val GAP_MS = 10 * 60_000L
        const val SPOT_M = 2_000.0
        const val SPOTS = 3
        /** 4 wheels (some cars send a spare), so one device has several sensors as partners. */
        const val MAX_PARTNERS = 8
        const val MAX_PAIRS = 2_000
        const val KEEP_MS = 30 * 24 * 3_600_000L
        private const val DAY_MS = 24 * 3_600_000L
    }
}
