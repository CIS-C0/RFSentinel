package com.rfsentinel.app.detect

/**
 * Heuristic warning signs of a fake cell tower (IMSI catcher / "Stingray"),
 * from what Android shows any app without root: the cells the phone sees,
 * their technology, network code, area and signal. Android does not expose
 * ciphering or signalling messages, so none of this is proof - each sign has
 * innocent explanations, which is why most are weak on their own.
 *
 * Several checks follow EFF Rayhunter's analyzers as far as Android lets an
 * app see them: Rayhunter reads the modem's signalling (a "release with
 * redirect to 2G", incomplete system information); here only their visible
 * effects can be checked (a sudden 4G-to-2G jump, a cell announcing reserved
 * identities). To keep false alarms down, a sign must show in two snapshots in
 * a row before it's reported (except a test network code), drops to 2G around
 * phone calls are ignored (many networks fall back to 2G/3G for calls), and an
 * area code that flips back and forth at a coverage border isn't a sign.
 *
 * Pure Kotlin: fed with snapshots by the scanner, unit-tested on the JVM.
 *
 * @param knownAreas the area each cell was seen in before ("RAT mcc-mnc cellId" -> area),
 *   kept across sessions by the caller, for spotting a cloned cell identity.
 */
class CellAnalyzer(private val knownAreas: MutableMap<String, Int> = LinkedHashMap()) {

    enum class Rat(val label: String, val generation: Int) {
        GSM("2G (GSM)", 2), CDMA("2G/3G (CDMA)", 2), WCDMA("3G (UMTS)", 3), TDSCDMA("3G (TD-SCDMA)", 3),
        LTE("4G (LTE)", 4), NR("5G (NR)", 5)
    }

    /** One cell as seen in a snapshot. Unknown fields are null. */
    data class Cell(
        val rat: Rat,
        val registered: Boolean,
        val mcc: String?,
        val mnc: String?,
        /** LAC (2G/3G) or TAC (4G/5G). */
        val area: Int?,
        val cellId: Long?,
        val dbm: Int?
    ) {
        val key get() = "${rat.name} ${mcc ?: "?"}-${mnc ?: "?"} ${area ?: "?"}/${cellId ?: "?"}"
        /** The cell's identity without its area: a cloned cell keeps this and changes the area. */
        val idKey get() = if (mcc == null || mnc == null || cellId == null) null else "${rat.name} $mcc-$mnc $cellId"
    }

    /** What the scanner knows about you at the time of a snapshot. */
    data class Context(
        val time: Long,
        /** SIM's home network MCC+MNC (e.g. "302720"), if known. */
        val simOperator: String?,
        val roaming: Boolean,
        /** True when GPS shows you haven't moved (speed < ~1 m/s, < 50 m over a few minutes). */
        val stationary: Boolean?,
        /** A phone call is going on (or just ended): networks without 4G calling drop to 2G/3G for it. */
        val inCall: Boolean = false
    )

    data class Anomaly(val key: String, val title: String, val detail: String, val confidence: Int)

    private data class Seen(
        val time: Long, val registered: Cell?, val neighbours: Int, val stationary: Boolean?,
        val inCall: Boolean, val modernDbm: Int?
    )

    private val history = ArrayDeque<Seen>()
    /** Signs found in the previous snapshot: a sign is reported once it shows twice in a row. */
    private var lastRaw: Set<String> = emptySet()
    /** Consecutive snapshots in which a known cell showed up with another area. */
    private val areaMismatch = HashMap<String, Int>()

    /** Analyses one snapshot of visible cells; returns the warning signs found in it. */
    fun analyze(cells: List<Cell>, ctx: Context): List<Anomaly> {
        val serving = cells.firstOrNull { it.registered }
        val neighbours = cells.count { !it.registered }
        val modernDbm = cells.filter { !it.registered && it.rat.generation >= 4 }.mapNotNull { it.dbm }.maxOrNull()
        val now = Seen(ctx.time, serving, neighbours, ctx.stationary, ctx.inCall, modernDbm)
        val window = history.filter { ctx.time - it.time <= WINDOW_MS } + now

        val raw = mutableListOf<Pair<Anomaly, Boolean>>() // anomaly to "report right away"
        if (serving != null) {
            testNetwork(serving)?.let { raw += it to true }
            foreignCountry(serving, ctx)?.let { raw += it to false }
            downgrade(serving, window)?.let { raw += it to false }
            areaChange(serving, window)?.let { raw += it to false }
            isolated(serving, window)?.let { raw += it to false }
            reservedIdentity(serving)?.let { raw += it to false }
            clonedIdentity(serving)?.let { raw += it to false }
        }
        learnAreas(cells)

        val out = raw.filter { (a, immediate) -> immediate || a.key in lastRaw }.map { it.first }.toMutableList()
        // Two or more signs on the same cell at once weigh more than either alone.
        if (serving != null && out.size >= 2) {
            out += Anomaly(
                "combined:${serving.key}", "Several fake-cell signs at once",
                "Your cell shows ${out.size} warning signs together: " + out.joinToString("; ") { it.title.lowercase() } +
                    ". Each alone has innocent explanations; together they're more telling.",
                minOf(90, out.maxOf { it.confidence } + 15)
            )
        }
        lastRaw = raw.map { it.first.key }.toSet()

        history.addLast(now)
        while (history.size > 120 || (history.isNotEmpty() && ctx.time - history.first().time > WINDOW_MS)) history.removeFirst()
        return out
    }

    // ---- The signs ----------------------------------------------------------------

    /** Test / reserved network codes: never used by real public networks. */
    private fun testNetwork(serving: Cell): Anomaly? {
        if (serving.mcc !in TEST_MCC) return null
        return Anomaly(
            "test-plmn:${serving.key}", "Test network code on your cell",
            "Your phone is connected to ${serving.rat.label} network ${serving.mcc}-${serving.mnc}. " +
                "MCC ${serving.mcc} is reserved for test networks and lab equipment - real operators never use it, " +
                "but some IMSI catchers do.", 85
        )
    }

    /** The cell's country differs from your SIM's while the phone says it isn't roaming. */
    private fun foreignCountry(serving: Cell, ctx: Context): Anomaly? {
        val simMcc = ctx.simOperator?.take(3) ?: return null
        val mcc = serving.mcc ?: return null
        if (mcc in TEST_MCC || mcc == simMcc || ctx.roaming || simMcc in SHARED_COUNTRY[mcc].orEmpty()) return null
        return Anomaly(
            "foreign:${serving.key}", "Unexpected network country code",
            "Your cell announces country code $mcc, but your SIM is from $simMcc and the phone " +
                "doesn't report roaming. Near a border this can be normal.", 55
        )
    }

    /**
     * On 2G now after 4G/5G. A jump straight from 4G/5G to 2G with a strong 4G
     * cell still in view is the visible side of a "release with redirect to 2G"
     * (Rayhunter's connection-redirect check); a slow slide is usually coverage.
     * Ignored around phone calls (circuit-switched fallback).
     */
    private fun downgrade(serving: Cell, window: List<Seen>): Anomaly? {
        if (serving.rat.generation > 2) return null
        val start = runStart(window) { (it.registered?.rat?.generation ?: 9) <= 2 }
        val run = window.subList(start, window.size)
        val before = window.getOrNull(start - 1)
        if (run.any { it.inCall } || before?.inCall == true) return null
        val wasModern = window.subList(0, start).any { (it.registered?.rat?.generation ?: 0) >= 4 }
        if (!wasModern) return null
        val sudden = before != null && (before.registered?.rat?.generation ?: 0) >= 4 &&
            run.first().time - before.time <= SUDDEN_MS
        val strongModern = (window.last().modernDbm ?: Int.MIN_VALUE) >= STRONG_DBM
        val (title, confidence) = when {
            sudden && strongModern -> "Sudden switch from 4G/5G to 2G" to 70
            sudden -> "Sudden switch from 4G/5G to 2G" to 50
            strongModern -> "Network downgraded to 2G" to 50
            else -> "Network downgraded to 2G" to 35
        }
        return Anomaly(
            "downgrade:${serving.key}", title,
            "Your phone went from 4G/5G to ${serving.rat.label}" +
                (if (sudden) " in one step" else "") +
                (if (strongModern) " although a strong 4G/5G cell is still in range" else "") +
                ". IMSI catchers push phones onto 2G, which has no network authentication " +
                "(like the redirect EFF's Rayhunter watches for). Weak coverage can also cause this.",
            confidence
        )
    }

    /**
     * The area code changed while you stood still: a fake cell forces a location
     * update. Not when the area just flips back to one seen minutes ago (a border).
     */
    private fun areaChange(serving: Cell, window: List<Seen>): Anomaly? {
        val area = serving.area ?: return null
        val start = runStart(window) { it.registered?.area == area && it.registered?.rat == serving.rat }
        val prev = window.getOrNull(start - 1)?.registered ?: return null
        val run = window.subList(start, window.size)
        if (prev.rat != serving.rat || prev.mcc != serving.mcc || prev.area == null) return null
        if (window.last().time - run.first().time > RECENT_MS) return null
        if (run.any { it.stationary != true } || window[start - 1].stationary != true) return null
        // Ping-pong at an area border: this area was already in use a few minutes ago.
        if (window.subList(0, start).any { it.registered?.area == area }) return null
        return Anomaly(
            "area:${serving.key}", "Location area changed while you weren't moving",
            "Your cell's area code changed from ${prev.area} to $area while GPS shows you standing still. " +
                "Fake cells often do this to make phones re-register. Area borders and network load can also cause it.",
            40
        )
    }

    /** Switched to a cell that shows no neighbours, where several were visible just before. */
    private fun isolated(serving: Cell, window: List<Seen>): Anomaly? {
        val start = runStart(window) { it.registered?.key == serving.key }
        val run = window.subList(start, window.size)
        if (run.any { it.neighbours > 0 } || window.last().time - run.first().time > RECENT_MS) return null
        val before = window.subList(0, start).takeLast(4)
        if (before.size < 2 || before.any { it.neighbours < 2 }) return null
        return Anomaly(
            "isolated:${serving.key}", "New cell with no neighbours",
            "Your phone switched to a cell that shows no neighbouring cells, where several were visible " +
                "moments ago. Fake stations often advertise no neighbours.", 35
        )
    }

    /**
     * The cell announces a reserved area or cell number (0, or the "deleted"
     * values 0xFFFE / 0xFFFF) - real cells don't; minimally configured fake ones
     * can (compare Rayhunter's incomplete-broadcast check).
     */
    private fun reservedIdentity(serving: Cell): Anomaly? {
        val badArea = serving.area?.takeIf { it in RESERVED_AREA }
        val badId = serving.cellId?.takeIf { it == 0L }
        if (badArea == null && badId == null) return null
        return Anomaly(
            "reserved:${serving.key}", "Cell announces a reserved identity",
            "Your cell reports " + listOfNotNull(badArea?.let { "area code $it" }, badId?.let { "cell number 0" }).joinToString(" and ") +
                ", which real networks don't hand out. Misconfigured test or fake base stations can. " +
                "Some phones also report this briefly while switching cells.", 45
        )
    }

    /** A cell number seen before in another area: a fake cell copying a real cell's identity. */
    private fun clonedIdentity(serving: Cell): Anomaly? {
        val id = serving.idKey ?: return null
        val area = serving.area ?: return null
        val known = knownAreas[id] ?: return null
        if (known == area) return null
        return Anomaly(
            "clone:${serving.key}", "Known cell number in a different area",
            "Your cell uses the same network and cell number as one seen before in area $known, but now " +
                "announces area $area. A fake station may copy a real cell's identity. Operators re-planning " +
                "their network can also cause this (it stops after a while).", 50
        )
    }

    /** Remembers each cell's area; a changed area is adopted once it has persisted (a re-planned network). */
    private fun learnAreas(cells: List<Cell>) {
        for (c in cells) {
            val id = c.idKey ?: continue
            val area = c.area?.takeIf { it !in RESERVED_AREA } ?: continue
            val known = knownAreas[id]
            if (known == null || known == area) {
                areaMismatch.remove(id)
                if (known == null) knownAreas[id] = area
            } else {
                val n = (areaMismatch[id] ?: 0) + 1
                areaMismatch[id] = n
                if (n >= ADOPT_AFTER) { knownAreas[id] = area; areaMismatch.remove(id) }
            }
        }
        while (knownAreas.size > MAX_KNOWN) knownAreas.remove(knownAreas.keys.first())
    }

    /** Index in [window] where the trailing run of snapshots matching [f] starts ([window].size if none). */
    private inline fun runStart(window: List<Seen>, f: (Seen) -> Boolean): Int {
        var i = window.size
        while (i > 0 && f(window[i - 1])) i--
        return i
    }

    companion object {
        /** Memory for "recently" (downgrade, area change, neighbours). */
        const val WINDOW_MS = 10 * 60_000L
        /** A switch counts as sudden when the snapshots on either side are this close. */
        const val SUDDEN_MS = 40_000L
        /** A transition is only a sign for this long after it happened. */
        const val RECENT_MS = 3 * 60_000L
        /** A 4G/5G neighbour at least this strong means coverage wasn't the reason for 2G. */
        const val STRONG_DBM = -105
        /** Snapshots in a row after which a cell's new area is taken as real. */
        const val ADOPT_AFTER = 8
        const val MAX_KNOWN = 5000
        /** ITU test / reserved codes. */
        val TEST_MCC = setOf("001", "002", "999")
        /** Area codes reserved in 3GPP (0, and 0xFFFE / 0xFFFF "deleted / no valid area"). */
        val RESERVED_AREA = setOf(0, 0xFFFE, 0xFFFF)
        /** Countries that share networks with a neighbour's MCC (e.g. US 310-316). */
        private val SHARED_COUNTRY = mapOf(
            "310" to setOf("311", "312", "313", "314", "315", "316"),
            "311" to setOf("310", "312", "313", "314", "315", "316"),
            "312" to setOf("310", "311"), "313" to setOf("310", "311"), "314" to setOf("310", "311"),
            "315" to setOf("310", "311"), "316" to setOf("310", "311"),
            "405" to setOf("404", "406"), "404" to setOf("405", "406"), "406" to setOf("404", "405")
        )
    }
}
