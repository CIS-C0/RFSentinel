package com.rfsentinel.app.detect

import kotlin.math.sqrt

/**
 * Spots a likely police vehicle from the *combination* of equipment around it.
 * A lone Motorola radio, Zebra printer or Cradlepoint router is weak evidence
 * (stores and couriers use them too), but several different kinds of
 * police-type gear that arrive together and whose signals rise and fall
 * together are most likely installed in the same vehicle.
 *
 * Pure Kotlin: the registry feeds it recent tracks, it returns one extra
 * [Hit] per device that belongs to a group.
 */
object PatrolCluster {

    /** One device that can be part of a patrol-vehicle kit. */
    class Member(
        val mac: String,
        val role: String,
        val firstSeen: Long,
        val lastSeen: Long,
        /** RSSI samples (time ms, dBm), roughly one per second. */
        val samples: List<Pair<Long, Int>>
    )

    class Group(val members: List<Member>, val roles: Set<String>, val correlated: Boolean) {
        val confidence: Int
            get() = (24 + 12 * roles.size + if (correlated) 10 else 0).coerceAtMost(88)
    }

    const val SOURCE = "RF Sentinel co-location analysis; each device alone is only weak evidence"

    private const val RECENT_MS = 60_000L
    private const val ARRIVAL_WINDOW_MS = 30_000L
    private const val WINDOW_MS = 120_000L
    private const val MIN_OVERLAP = 8
    private const val MIN_CORRELATION = 0.6
    private const val MIN_SPREAD_DB = 2.0

    private val ROLE_BY_VENDOR = listOf(
        Regex("axon|taser", RegexOption.IGNORE_CASE) to "Axon / TASER gear",
        Regex("motorola solutions|harris corp|l3harris", RegexOption.IGNORE_CASE) to "two-way radio",
        Regex("cradlepoint|sierra wireless", RegexOption.IGNORE_CASE) to "vehicle cellular router",
        Regex("zebra|ruggedjet|pocketjet", RegexOption.IGNORE_CASE) to "mobile printer",
        Regex("cyberkar", RegexOption.IGNORE_CASE) to "in-car computer / console",
        Regex("getac", RegexOption.IGNORE_CASE) to "rugged laptop / body cam",
        Regex("genetec", RegexOption.IGNORE_CASE) to "plate reader",
        Regex("utility,? inc|digital ally|watchguard|i-pro|zepcam", RegexOption.IGNORE_CASE) to "police camera"
    )

    private val ROLE_BY_NAME = listOf(
        Regex("^(RJ-4|PJ-[78])", RegexOption.IGNORE_CASE) to "mobile printer",
        Regex("^(BC-0\\d|HS-01)", RegexOption.IGNORE_CASE) to "rugged laptop / body cam"
    )

    /** The patrol-kit role of a device, or null if it's ordinary. */
    fun roleOf(allHits: List<Hit>, vendor: String?, name: String?): String? {
        val hits = allHits.filter { it.source != SOURCE }
        hits.firstOrNull { it.category == Category.BODY_CAM }?.let { return "body camera" }
        hits.firstOrNull { it.category == Category.ALPR }?.let { return "plate reader" }
        val text = listOfNotNull(vendor) + hits.filter { it.category == Category.PUBLIC_SAFETY }.map { it.label }
        text.forEach { t -> ROLE_BY_VENDOR.firstOrNull { it.first.containsMatchIn(t) }?.let { return it.second } }
        if (name != null) ROLE_BY_NAME.firstOrNull { it.first.containsMatchIn(name) }?.let { return it.second }
        return null
    }

    /** Groups of at least two different roles that are travelling / parked together. */
    fun groups(members: List<Member>, now: Long): List<Group> {
        val live = members.filter { now - it.lastSeen <= RECENT_MS }
        if (live.size < 2) return emptyList()
        val parent = IntArray(live.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val correlatedPairs = HashSet<Int>()
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]; val b = live[j]
            if (a.role == b.role) continue
            val m = motion(a.samples, b.samples, now)
            val together = when (m) {
                is Motion.Moving -> m.r >= MIN_CORRELATION
                // Both parked: signals can't correlate, so arriving together is the only clue.
                Motion.BothFlat -> kotlin.math.abs(a.firstSeen - b.firstSeen) <= ARRIVAL_WINDOW_MS
                // One moves while the other sits still: not the same vehicle. Too little
                // overlap: no evidence either way, so don't group.
                Motion.OneFlat, Motion.TooShort -> false
            }
            if (together) {
                parent[find(i)] = find(j)
                if (m is Motion.Moving) { correlatedPairs += i; correlatedPairs += j }
            }
        }
        return live.indices.groupBy { find(it) }.values
            .map { idx -> Group(idx.map { live[it] }, idx.map { live[it].role }.toSet(), idx.any { it in correlatedPairs }) }
            .filter { it.roles.size >= 2 }
    }

    /** The cluster hit for [mac], if it belongs to a group. */
    fun hitFor(mac: String, groups: List<Group>): Hit? {
        val g = groups.firstOrNull { grp -> grp.members.any { it.mac == mac } } ?: return null
        val me = g.members.first { it.mac == mac }
        val others = g.members.filter { it.mac != mac }
        return Hit(
            Category.PUBLIC_SAFETY,
            me.role.replaceFirstChar { it.uppercase() } + " in a possible police vehicle (${g.roles.size} kinds of gear together)",
            g.confidence,
            "Heard together with " + others.joinToString { "${it.role} ${it.mac}" } +
                if (g.correlated) " - their signals rise and fall together (same vehicle)"
                else " - they arrived within 30 s of each other",
            SOURCE
        )
    }

    /** How two devices' signals behave over the same seconds. */
    sealed interface Motion {
        /** Both signals vary: [r] is their Pearson correlation. */
        data class Moving(val r: Double) : Motion
        /** Both signals are flat: both parked relative to you. */
        data object BothFlat : Motion
        /** One varies while the other is flat: they aren't moving together. */
        data object OneFlat : Motion
        /** Fewer than [MIN_OVERLAP] shared seconds. */
        data object TooShort : Motion
    }

    /**
     * Compares two RSSI series over the last [WINDOW_MS], aligned by second.
     * A signal whose standard deviation is under [MIN_SPREAD_DB] counts as flat.
     */
    fun motion(a: List<Pair<Long, Int>>, b: List<Pair<Long, Int>>, now: Long): Motion {
        fun bucket(s: List<Pair<Long, Int>>) =
            s.filter { now - it.first <= WINDOW_MS }.associate { it.first / 1000 to it.second.toDouble() }
        val ba = bucket(a); val bb = bucket(b)
        val keys = ba.keys.intersect(bb.keys)
        if (keys.size < MIN_OVERLAP) return Motion.TooShort
        val xs = keys.map { ba.getValue(it) }; val ys = keys.map { bb.getValue(it) }
        val mx = xs.average(); val my = ys.average()
        val sx = sqrt(xs.sumOf { (it - mx) * (it - mx) } / xs.size)
        val sy = sqrt(ys.sumOf { (it - my) * (it - my) } / ys.size)
        val flatX = sx < MIN_SPREAD_DB; val flatY = sy < MIN_SPREAD_DB
        if (flatX && flatY) return Motion.BothFlat
        if (flatX || flatY) return Motion.OneFlat
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.size
        return Motion.Moving(cov / (sx * sy))
    }
}
