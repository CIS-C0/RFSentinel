package com.rfsentinel.app.detect

/**
 * Heuristic warning signs of a fake cell tower (IMSI catcher / "Stingray"),
 * from what Android shows any app without root: the cells the phone sees,
 * their technology, network code, area and signal. Android does not expose
 * ciphering or signalling messages, so none of this is proof - each sign has
 * innocent explanations, which is why most are weak on their own.
 *
 * Pure Kotlin: fed with snapshots by the scanner, unit-tested on the JVM.
 */
class CellAnalyzer {

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
    }

    /** What the scanner knows about you at the time of a snapshot. */
    data class Context(
        val time: Long,
        /** SIM's home network MCC+MNC (e.g. "302720"), if known. */
        val simOperator: String?,
        val roaming: Boolean,
        /** True when GPS shows you haven't moved (speed < ~1 m/s, < 50 m over a few minutes). */
        val stationary: Boolean?
    )

    data class Anomaly(val key: String, val title: String, val detail: String, val confidence: Int)

    private data class Seen(val time: Long, val registered: Cell?, val neighbours: Int, val stationary: Boolean?)

    private val history = ArrayDeque<Seen>()

    /** Analyses one snapshot of visible cells; returns the warning signs found in it. */
    fun analyze(cells: List<Cell>, ctx: Context): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        val serving = cells.firstOrNull { it.registered }
        val neighbours = cells.count { !it.registered }
        val window = history.filter { ctx.time - it.time <= WINDOW_MS }

        if (serving != null) {
            // 1. Test / reserved network codes: never used by real public networks.
            if (serving.mcc in TEST_MCC) {
                out += Anomaly(
                    "test-plmn:${serving.key}", "Test network code on your cell",
                    "Your phone is connected to ${serving.rat.label} network ${serving.mcc}-${serving.mnc}. " +
                        "MCC ${serving.mcc} is reserved for test networks and lab equipment - real operators never use it, " +
                        "but some IMSI catchers do.", 85
                )
            }

            // 2. Unexpected country: cell's MCC differs from your SIM's while the phone says it isn't roaming.
            val simMcc = ctx.simOperator?.take(3)
            if (simMcc != null && serving.mcc != null && serving.mcc !in TEST_MCC &&
                serving.mcc != simMcc && !ctx.roaming && simMcc !in SHARED_COUNTRY[serving.mcc].orEmpty()
            ) {
                out += Anomaly(
                    "foreign:${serving.key}", "Unexpected network country code",
                    "Your cell announces country code ${serving.mcc}, but your SIM is from $simMcc and the phone " +
                        "doesn't report roaming. Near a border this can be normal.", 55
                )
            }

            // 3. Forced 2G: dropped to 2G/CDMA recently after 4G/5G, while 4G/5G cells are still in view.
            val wasModern = window.any { (it.registered?.rat?.generation ?: 0) >= 4 }
            val modernStillVisible = cells.any { !it.registered && it.rat.generation >= 4 }
            if (serving.rat.generation <= 2 && wasModern) {
                out += Anomaly(
                    "downgrade:${serving.key}", "Network downgraded to 2G",
                    "Your phone moved from 4G/5G to ${serving.rat.label}" +
                        (if (modernStillVisible) " although 4G/5G cells are still in range" else "") +
                        ". IMSI catchers force phones onto 2G, which has no network authentication. " +
                        "Weak coverage can also cause this.",
                    if (modernStillVisible) 65 else 40
                )
            }

            // 4. Area changed while you stood still: a fake cell forces a location update.
            val prev = window.lastOrNull()?.registered
            if (ctx.stationary == true && prev != null && prev.rat == serving.rat && prev.mcc == serving.mcc &&
                prev.area != null && serving.area != null && prev.area != serving.area
            ) {
                out += Anomaly(
                    "area:${serving.key}", "Location area changed while you weren't moving",
                    "Your cell's area code changed from ${prev.area} to ${serving.area} while GPS shows you standing still. " +
                        "Fake cells often do this to make phones re-register. Area borders and network load can also cause it.",
                    40
                )
            }

            // 5. No neighbours on a cell where several were visible moments ago.
            val hadNeighbours = window.takeLast(4).let { recent -> recent.size >= 2 && recent.all { it.neighbours >= 2 } }
            if (neighbours == 0 && hadNeighbours && prev != null && prev.key != serving.key) {
                out += Anomaly(
                    "isolated:${serving.key}", "New cell with no neighbours",
                    "Your phone switched to a cell that shows no neighbouring cells, where several were visible " +
                        "moments ago. Fake stations often advertise no neighbours.", 35
                )
            }
        }

        history.addLast(Seen(ctx.time, serving, neighbours, ctx.stationary))
        while (history.size > 120 || (history.isNotEmpty() && ctx.time - history.first().time > WINDOW_MS)) history.removeFirst()
        return out
    }

    companion object {
        /** Memory for "recently" (downgrade, area change, neighbours). */
        const val WINDOW_MS = 10 * 60_000L
        /** ITU test / reserved codes. */
        val TEST_MCC = setOf("001", "002", "999")
        /** Countries that share networks with a neighbour's MCC (e.g. US 310-316). */
        private val SHARED_COUNTRY = mapOf(
            "310" to setOf("311", "312", "313", "314", "315", "316"),
            "311" to setOf("310", "312", "313", "314", "315", "316"),
            "312" to setOf("310", "311"), "313" to setOf("310", "311"), "314" to setOf("310", "311"),
            "315" to setOf("310", "311"), "316" to setOf("310", "311"),
            "405" to setOf("404"), "404" to setOf("405")
        )
    }
}
