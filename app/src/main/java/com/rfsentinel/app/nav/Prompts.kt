package com.rfsentinel.app.nav

/**
 * What to show and say as you drive (pure; unit-tested). Valhalla words each prompt and says how far
 * before the turn it's due ("In 500 meters, keep right" from about 500 m); this decides which is due
 * now, says it a moment early at speed (speech takes a second to start), never says a distance that is
 * no longer true (joining a stretch at 100 m, the "In 300 meters" prompt is skipped), and adds a heads-up
 * before a turn at the end of a long stretch, as navigation apps do.
 */
class Prompts(private val miles: Boolean = false) {

    /** A prompt to speak; [urgent] for the last one before a turn (it may cut into other speech). */
    data class Say(val text: String, val urgent: Boolean)

    /** Prompts already handled, as "step:metres before the turn". */
    private val handled = HashSet<String>()

    /** Forget what was said (a new route). */
    fun reset() = handled.clear()

    /**
     * The prompt due now on step [stepIndex] of [route], with the turn [toManeuverM] away, driving
     * at [speedMs]; null when nothing is due. Each prompt is used once; of several due at once only
     * the nearest that is still true is spoken.
     */
    fun next(route: Route, stepIndex: Int, toManeuverM: Double, speedMs: Double): Say? {
        val step = route.steps.getOrNull(stepIndex) ?: return null
        val all = withHeadsUp(step).sortedByDescending { it.beforeM }
        if (all.isEmpty()) return null
        // About a second and a half of travel early: speech doesn't start the instant it's asked for.
        val lead = (speedMs * LEAD_S).coerceIn(0.0, MAX_LEAD_M)
        val due = all.filter { toManeuverM <= it.beforeM + lead && key(stepIndex, it) !in handled }
        if (due.isEmpty()) return null
        due.forEach { handled += key(stepIndex, it) }
        val last = all.last()
        // The nearest due prompt is the one to say - if it still holds: "In 300 meters" at 100 m doesn't.
        val say = due.last()
        val holds = say === last || toManeuverM >= say.beforeM * STILL_TRUE
        if (!holds) return null
        // The route server words the end of every leg "your destination"; before the last leg it is a stop.
        val lastLeg = route.steps.lastOrNull()?.leg ?: 0
        val text = if (step.leg < lastLeg) say.text.replace("arrived at your destination", "arrived at your stop")
            .replace("at your destination", "at your stop") else say.text
        return Say(text, urgent = say === last && say.beforeM <= URGENT_WITHIN_M)
    }

    private fun key(stepIndex: Int, v: Step.Voice) = "$stepIndex:${v.beforeM}"

    /** The step's prompts, plus a heads-up well before the turn at the end of a long stretch. */
    internal fun withHeadsUp(step: Step): List<Step.Voice> {
        val v = step.voice
        val at = if (miles) HEADS_UP_MILE_M else HEADS_UP_KM_M
        if (step.endM - step.startM < at * 1.6 || v.any { it.beforeM in at * 0.75..at * 1.3 }) return v
        // Worded like Valhalla's own nearer prompt ("In 500 meters, Keep right..."), else its last one.
        val base = v.firstOrNull { IN_DISTANCE.containsMatchIn(it.text) } ?: v.minByOrNull { it.beforeM } ?: return v
        val far = if (miles) "1 mile" else "2 kilometers"
        val text = if (IN_DISTANCE.containsMatchIn(base.text)) base.text.replace(IN_DISTANCE, "In $far, ")
            else "In $far, " + base.text
        return v + Step.Voice(at, text)
    }

    companion object {
        private const val LEAD_S = 1.5
        private const val MAX_LEAD_M = 60.0
        private const val URGENT_WITHIN_M = 300.0
        /** A prompt is still true while you're at least this share of its distance from the turn. */
        private const val STILL_TRUE = 0.6
        private const val HEADS_UP_KM_M = 2_000.0
        private const val HEADS_UP_MILE_M = 1_609.0
        private val IN_DISTANCE = Regex("^In [^,]+, ")

        /** The banner to show on step [stepIndex] with the turn [toManeuverM] away (its lanes appear when they're near). */
        fun banner(route: Route, stepIndex: Int, toManeuverM: Double): Step.Banner? {
            val b = route.steps.getOrNull(stepIndex)?.banners ?: return null
            return b.filter { it.beforeM >= toManeuverM - 1.0 }.minByOrNull { it.beforeM } ?: b.firstOrNull()
        }

        /**
         * "Then" what, when the turn after the coming one follows closely (under 400 m): its banner.
         * Null when there's room between them, or nothing after.
         */
        fun thenBanner(route: Route, stepIndex: Int): Step.Banner? {
            val following = route.steps.getOrNull(stepIndex + 1) ?: return null
            if (following.isArrival || following.endM - following.startM > THEN_WITHIN_M) return null
            return following.banners.maxByOrNull { it.beforeM }?.takeIf { it.type != "arrive" }
        }

        private const val THEN_WITHIN_M = 400.0
    }
}
