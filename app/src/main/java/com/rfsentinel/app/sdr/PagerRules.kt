package com.rfsentinel.app.sdr

/** Which pages to alert on or drop (Settings › RTL-SDR radio › Pagers). Pure: unit-tested. */
object PagerRules {

    /** A list line: a capcode with an optional name, or else words to find in a page. */
    data class Watch(val capcode: Long?, val words: String?, val name: String)

    // shortcut: a line starting with a number is always a capcode, so "2 alarm fire" can't be a keyword; add a prefix if asked.
    private val CAPCODE = Regex("""^(\d{1,10})(?:\s+(.*))?$""")

    fun watches(text: String): List<Watch> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        CAPCODE.find(line)?.let { Watch(it.groupValues[1].toLong(), null, it.groupValues[2].trim()) } ?: Watch(null, line, line)
    }

    fun ignored(text: String): Set<Long> = watches(text).mapNotNullTo(HashSet()) { it.capcode }

    fun match(watches: List<Watch>, page: PagerLog.Page): Watch? = watches.firstOrNull { w ->
        w.capcode == page.capcode || (w.words != null && page.text.contains(w.words, ignoreCase = true))
    }
}
