package com.rfsentinel.app.sdr

/**
 * P25 control channel, last stage: what the site said, kept for the talkgroup screen. Per talkgroup (or
 * radio-to-radio call): calls, the last channel and radio, radios affiliated, emergency / encrypted flags.
 * [add] says when a new call starts, for the watched-talkgroup alerts. Fed from the SDR thread, read by the
 * screen: synchronized. Pure: unit-tested.
 */
class P25Log {

    /** One talkgroup ([tg]) or, with [tg] null, private calls to radio [target]. */
    data class Row(val tg: Int?, val target: Int?, val calls: Int, val lastAt: Long, val lastChannel: Int?,
                   val lastHz: Long?, val lastSource: Int?, val radios: Int, val emergency: Boolean, val encrypted: Boolean)

    /** The site and how much was heard there, for the screen's header. */
    data class Summary(val site: P25Tsbk.Site?, val messages: Long, val registrations: Int, val since: Long)

    private class Group { var calls = 0; var lastAt = 0L; var lastChannel: Int? = null; var lastSource: Int? = null
        val radios = HashSet<Int>(); var emergency = false; var encrypted = false }

    private val groups = HashMap<Pair<Int?, Int?>, Group>()
    private val channels = P25Tsbk.Channels()
    private var site: P25Tsbk.Site? = null
    private var messages = 0L
    private var registrations = 0
    private var since = 0L
    /** Calls started since [takeCalls], for the kept call log ([P25History]). */
    private val started = ArrayList<P25History.Call>()

    /** A new control channel: forget the last site. */
    @Synchronized fun clear(now: Long) {
        groups.clear(); site = null; messages = 0; registrations = 0; since = now
    }

    /** Takes in one message; the talkgroups whose call just started (not heard for [CALL_GAP_MS]). */
    @Synchronized fun add(m: P25Tsbk.Msg, now: Long): List<Int> {
        messages++
        if (since == 0L) since = now
        return when (m) {
            is P25Tsbk.Grant -> listOfNotNull(call(m.tg, m.target, m.channel, m.source, now, m.emergency, m.encrypted))
            is P25Tsbk.GrantUpdate -> m.calls.mapNotNull { (ch, tg) -> call(tg, null, ch, null, now, false, false) }
            is P25Tsbk.Affiliation -> { group(m.tg, null).radios += m.radio; emptyList() }
            is P25Tsbk.Registration -> { if (m.accepted) registrations++; emptyList() }
            is P25Tsbk.Iden -> { channels.learn(m); emptyList() }
            is P25Tsbk.Site -> { site = site?.let { s -> m.copy(wacn = m.wacn ?: s.wacn, rfss = m.rfss ?: s.rfss, site = m.site ?: s.site) } ?: m; emptyList() }
            is P25Tsbk.Neighbor -> emptyList()
        }
    }

    private fun group(tg: Int?, target: Int?) = groups.getOrPut(tg to target) { Group() }

    private fun call(tg: Int?, target: Int?, channel: Int, source: Int?, now: Long, emergency: Boolean, encrypted: Boolean): Int? {
        val g = group(tg, target)
        val new = g.calls == 0 || now - g.lastAt > CALL_GAP_MS
        if (new) g.calls++
        g.lastAt = now; g.lastChannel = channel
        if (source != null) { g.lastSource = source; g.radios += source }
        g.emergency = g.emergency || emergency
        g.encrypted = encrypted || (g.encrypted && source == null) // an update doesn't say: keep the last grant's
        if (new) started += P25History.Call(now, tg, target, source, channel, channels.hz(channel), emergency, g.encrypted,
            site?.let { s -> listOfNotNull("%03X".format(s.sysId), s.rfss?.toString(), s.site?.toString()).joinToString("-") })
        return if (new) tg else null
    }

    /** Most recent first. */
    @Synchronized fun rows(): List<Row> = groups.map { (k, g) ->
        Row(k.first, k.second, g.calls, g.lastAt, g.lastChannel, g.lastChannel?.let(channels::hz), g.lastSource,
            g.radios.size, g.emergency, g.encrypted)
    }.filter { it.calls > 0 || it.radios > 0 }.sortedByDescending { it.lastAt }

    @Synchronized fun summary() = Summary(site, messages, registrations, since)

    /** Where the radios transmit on [channel], or null while unknown. */
    @Synchronized fun inputHz(channel: Int): Long? = channels.inputHz(channel)

    /** The calls started since the last time this was asked, oldest first. */
    @Synchronized fun takeCalls(): List<P25History.Call> = started.toList().also { started.clear() }

    companion object {
        /** A grant or update for a talkgroup heard within this long is the same call going on. */
        const val CALL_GAP_MS = 10_000L

        private val LINE = Regex("""^\s*(\d{1,5})\b\s*[,;:\-]?\s*(.*)$""")

        /** "1234 Sheriff dispatch" lines (Settings): talkgroup to name ("" when unnamed). */
        fun watched(text: String): Map<Int, String> = text.lines().mapNotNull { line ->
            val m = LINE.find(line) ?: return@mapNotNull null
            val tg = m.groupValues[1].toInt().takeIf { it in 1..0xFFFF } ?: return@mapNotNull null
            tg to m.groupValues[2].trim()
        }.toMap()
    }
}
