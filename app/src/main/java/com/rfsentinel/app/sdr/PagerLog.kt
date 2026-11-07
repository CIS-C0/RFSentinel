package com.rfsentinel.app.sdr

/**
 * Pages heard this session (the Pager messages screen). In memory only, on purpose: pages can hold private or medical
 * details, so they are never saved, exported or sent, and go when the app closes or the setting is turned off. The one
 * exception is the user's: Show page text in alerts puts a watched page on its notification.
 */
class PagerLog {

    enum class Kind { NUMERIC, ALPHA, TONE }
    /** [partial]: part of the page is missing (a garbled word, or a FLEX fragment). */
    data class Page(val protocol: String, val capcode: Long, val kind: Kind, val text: String, val partial: Boolean = false)
    data class Row(val page: Page, val hz: Long, val at: Long)

    private val rows = ArrayDeque<Row>()
    /** Pages heard since the app started, for the main screen's messages-per-second line. */
    @Volatile var total = 0L; private set

    @Synchronized fun add(page: Page, hz: Long, at: Long) {
        rows.addFirst(Row(page, hz, at))
        if (rows.size > MAX) rows.removeLast()
        total++
    }

    /** Newest first. */
    @Synchronized fun rows(): List<Row> = rows.toList()
    @Synchronized fun clear() = rows.clear()

    companion object { const val MAX = 500 }
}
