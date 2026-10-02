package com.rfsentinel.app.util

/**
 * What to say next, and what to drop (pure; unit-tested). Spoken alerts must
 * be about *now*: a burst of detections shouldn't leave a minute of stale
 * announcements, the same phrase shouldn't repeat back to back, and the most
 * urgent thing (something following you) goes first.
 */
class VoiceQueue(
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val repeatGapMs: Long = REPEAT_GAP_MS
) {
    data class Item(val text: String, val priority: Int, val time: Long)

    companion object {
        const val MAX_AGE_MS = 12_000L
        const val REPEAT_GAP_MS = 30_000L
        /** From this many waiting, they're merged into one announcement. */
        const val MERGE_FROM = 3

        // Priorities.
        const val FOLLOWING = 4
        const val STRONG = 3
        const val PROBABLE = 2
        const val WEAK = 1
    }

    private val waiting = ArrayList<Item>()
    private val lastSaid = HashMap<String, Long>()

    val size get() = waiting.size

    /** Queues [text]; false when it's a repeat of something said (or waiting) recently. */
    fun add(text: String, priority: Int, now: Long): Boolean {
        val key = text.trim().lowercase()
        if (waiting.any { it.text.trim().lowercase() == key }) return false
        lastSaid[key]?.let { if (now - it < repeatGapMs) return false }
        waiting += Item(text, priority, now)
        return true
    }

    /** True when [priority] should interrupt something of [speaking] priority. */
    fun preempts(priority: Int, speaking: Int) = priority >= FOLLOWING && priority > speaking

    /**
     * The next thing to say, or null: stale items are dropped, the most urgent
     * (then oldest) goes first, and a pile-up becomes one sentence.
     */
    fun next(now: Long): Item? {
        waiting.removeAll { now - it.time > maxAgeMs }
        if (waiting.isEmpty()) return null
        val ordered = waiting.sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.time })
        val item = if (ordered.size >= MERGE_FROM && ordered.first().priority < FOLLOWING) {
            // "3 alerts: Axon body camera nearby, Flock camera nearby and Drone overhead"
            val names = ordered.take(3).map { it.text.trim().trimEnd('.') }
            val more = ordered.size - names.size
            val list = names.dropLast(1).joinToString(", ") + " and " + names.last() +
                (if (more > 0) ", and $more more" else "")
            waiting.clear()
            Item("${ordered.size} alerts: $list.", ordered.first().priority, now)
                .also { ordered.forEach { o -> lastSaid[o.text.trim().lowercase()] = now } }
        } else {
            waiting.remove(ordered.first())
            ordered.first()
        }
        lastSaid[item.text.trim().lowercase()] = now
        // Keep the repeat memory small.
        if (lastSaid.size > 64) lastSaid.entries.removeAll { now - it.value > repeatGapMs }
        return item
    }

    fun clear() = waiting.clear()
}
