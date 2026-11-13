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
                   val lastHz: Long?, val lastSource: Int?, val radios: Int, val emergency: Boolean, val encrypted: Boolean,
                   /** The last time one of its radios was heard on the site's input (close to you), and how strong; 0 never. */
                   val inputAt: Long = 0L, val inputSnr: Int = 0,
                   /** Its last call was on a Phase 2 (TDMA) channel: seen, but its voice can't be played or recorded. */
                   val phase2: Boolean = false,
                   /** A patch supergroup: the talkgroups it carries together; empty for a plain talkgroup. */
                   val patch: List<Int> = emptyList())

    /** A [P25Tsbk.RadioEvent] or data channel grant heard at [at]; [tg] the target radio's last talkgroup when known. */
    data class Event(val at: Long, val kind: String, val source: Int?, val target: Int, val code: Int?, val tg: Int?)

    /** The site and how much was heard there, for the screen's header. */
    data class Summary(val site: P25Tsbk.Site?, val messages: Long, val registrations: Int, val since: Long)

    private class Group { var calls = 0; var lastAt = 0L; var lastChannel: Int? = null; var lastSource: Int? = null
        val radios = HashSet<Int>(); var emergency = false; var encrypted = false; var inputAt = 0L; var inputSnr = 0; var phase2 = false }

    private val groups = HashMap<Pair<Int?, Int?>, Group>()
    private val channels = P25Tsbk.Channels()
    private var site: P25Tsbk.Site? = null
    private var messages = 0L
    private var registrations = 0
    private var since = 0L
    /** This site's other control channels and its neighbours', as channel numbers, newest last. */
    private val alternates = LinkedHashSet<Int>()
    /** Each radio's last talkgroup (affiliation or a call it started), oldest first. */
    private val joined = LinkedHashMap<Int, Int>()
    /** Calls started since [takeCalls], for the kept call log ([P25History]). */
    private val started = ArrayList<P25History.Call>()
    /** Patch supergroup -> the talkgroups it carries. */
    private val patches = HashMap<Int, List<Int>>()
    /** Newest last, at most [MAX_EVENTS]. */
    private val events = ArrayDeque<Event>()

    /** A new control channel: forget the last site. */
    @Synchronized fun clear(now: Long) {
        groups.clear(); site = null; messages = 0; registrations = 0; since = now; alternates.clear(); joined.clear()
        patches.clear(); events.clear()
    }

    /** Takes in one message; the talkgroups whose call just started (not heard for [CALL_GAP_MS]). */
    @Synchronized fun add(m: P25Tsbk.Msg, now: Long): List<Int> {
        messages++
        if (since == 0L) since = now
        return when (m) {
            is P25Tsbk.Grant -> listOfNotNull(call(m.tg, m.target, m.channel, m.source, now, m.emergency, m.encrypted))
            // Talkgroup 0 is no talkgroup: an update's unused second slot (a tester's log showed "call talkgroup 0").
            is P25Tsbk.GrantUpdate -> m.calls.filter { it.second != 0 }.mapNotNull { (ch, tg) -> call(tg, null, ch, null, now, false, false) }
            is P25Tsbk.Affiliation -> { group(m.tg, null).radios += m.radio; joined(m.radio, m.tg); emptyList() }
            is P25Tsbk.Registration -> { if (m.accepted) registrations++; emptyList() }
            is P25Tsbk.Iden -> { channels.learn(m); emptyList() }
            is P25Tsbk.Site -> { site = site?.let { s -> m.copy(wacn = m.wacn ?: s.wacn, rfss = m.rfss ?: s.rfss, site = m.site ?: s.site) } ?: m; emptyList() }
            is P25Tsbk.Neighbor -> { alternate(m.channel); emptyList() }
            is P25Tsbk.Secondary -> { m.channels.forEach(::alternate); emptyList() }
            is P25Tsbk.Patch -> {
                if (m.add) patches[m.sg] = (patches[m.sg].orEmpty() + m.members).distinct().take(MAX_PATCH)
                else (patches[m.sg].orEmpty() - m.members.toSet()).let { if (it.isEmpty() || m.members.isEmpty()) patches.remove(m.sg) else patches[m.sg] = it }
                emptyList()
            }
            is P25Tsbk.RadioEvent -> { event(Event(now, m.kind, m.source.takeIf { it != 0 }, m.target, m.code, joined[m.target])); emptyList() }
            is P25Tsbk.DataGrant -> { event(Event(now, "data channel", null, m.target, null, joined[m.target])); emptyList() }
        }
    }

    private fun event(e: Event) {
        // The site repeats each one a few times: once is enough.
        if (events.any { it.kind == e.kind && it.source == e.source && it.target == e.target && it.code == e.code && e.at - it.at < CALL_GAP_MS }) return
        events.addLast(e); while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun alternate(channel: Int) { alternates.remove(channel); alternates += channel; while (alternates.size > MAX_ALTERNATES) alternates.remove(alternates.first()) }

    private fun group(tg: Int?, target: Int?) = groups.getOrPut(tg to target) { Group() }

    private fun call(tg: Int?, target: Int?, channel: Int, source: Int?, now: Long, emergency: Boolean, encrypted: Boolean): Int? {
        val g = group(tg, target)
        val new = g.calls == 0 || now - g.lastAt > CALL_GAP_MS
        if (new) g.calls++
        g.lastAt = now; g.lastChannel = channel; g.phase2 = channels.tdma(channel)
        if (source != null) { g.lastSource = source; g.radios += source; if (tg != null) joined(source, tg) }
        g.emergency = g.emergency || emergency
        g.encrypted = encrypted || (g.encrypted && source == null) // an update doesn't say: keep the last grant's
        if (new) started += P25History.Call(now, tg, target, source, channel, channels.hz(channel), emergency, g.encrypted,
            site?.let { s -> listOfNotNull("%03X".format(s.sysId), s.rfss?.toString(), s.site?.toString()).joinToString("-") }, g.phase2)
        return if (new) tg else null
    }

    /** A radio on [tg]'s call (or the private call to [target]) heard on the site's input, [snr] dB above the noise. */
    @Synchronized fun heardInput(tg: Int?, target: Int?, snr: Int, now: Long) {
        group(tg, target).apply { inputAt = now; inputSnr = snr }
    }

    /**
     * Radio [radio] heard on the control channel's input, [snr] dB above the noise, nobody talking: marks the talkgroup
     * it last joined (an affiliation, or a call it started) as near, and gives that talkgroup; null when not known.
     */
    @Synchronized fun heardRadio(radio: Int, snr: Int, now: Long): Int? {
        val tg = joined[radio] ?: return null
        groups[tg to null]?.apply { inputAt = now; inputSnr = snr }
        return tg
    }

    private fun joined(radio: Int, tg: Int) {
        joined.remove(radio); joined[radio] = tg
        if (joined.size > MAX_JOINED) joined.remove(joined.keys.first())
    }

    /** Most recent first. */
    @Synchronized fun rows(): List<Row> = groups.map { (k, g) ->
        Row(k.first, k.second, g.calls, g.lastAt, g.lastChannel, g.lastChannel?.let(channels::hz), g.lastSource,
            g.radios.size, g.emergency, g.encrypted, g.inputAt, g.inputSnr, g.phase2, k.first?.let { patches[it] }.orEmpty())
    }.filter { it.calls > 0 || it.radios > 0 }.sortedByDescending { it.lastAt }

    @Synchronized fun summary() = Summary(site, messages, registrations, since)

    /** Radio events (status, messages, call alerts, monitoring...), newest first. */
    @Synchronized fun events(): List<Event> = events.reversed()

    /** The talkgroups patch supergroup [tg] carries; empty when it isn't one. */
    @Synchronized fun members(tg: Int?): List<Int> = tg?.let { patches[it] }.orEmpty()

    /** Where the radios transmit on [channel], or null while unknown. */
    @Synchronized fun inputHz(channel: Int): Long? = channels.inputHz(channel)
    /** The downlink frequency of [channel], once its table has been heard. */
    @Synchronized fun hz(channel: Int): Long? = channels.hz(channel)

    /**
     * Where else this system's control channel may be heard, best first: this site's own channel and its secondary ones,
     * then the neighbouring sites'. Downlink frequencies, for when the control channel goes quiet; only those whose
     * channel table has been heard.
     */
    @Synchronized fun alternates(): List<Long> =
        (listOfNotNull(site?.channel) + alternates.reversed()).distinct().mapNotNull(channels::hz).distinct()

    /** The calls started since the last time this was asked, oldest first. */
    @Synchronized fun takeCalls(): List<P25History.Call> = started.toList().also { started.clear() }

    companion object {
        /** A grant or update for a talkgroup heard within this long is the same call going on. */
        const val CALL_GAP_MS = 10_000L
        private const val MAX_ALTERNATES = 12
        private const val MAX_JOINED = 4096
        private const val MAX_EVENTS = 100
        private const val MAX_PATCH = 32

        private val LINE = Regex("""^\s*(\d{1,5}\*?|\*)(?!\d)\s*[,;:\-]?\s*(.*)$""")

        /** "1234 Sheriff dispatch" lines (Settings), and "23*" (2300, 2301, 2307...) or "*" (every talkgroup) ones. */
        fun watched(text: String): Watched {
            val exact = HashMap<Int, String>(); val prefixes = ArrayList<Pair<String, String>>()
            for (line in text.lines()) {
                val m = LINE.find(line) ?: continue
                val key = m.groupValues[1]; val name = m.groupValues[2].trim()
                if (key.endsWith("*")) prefixes += key.dropLast(1) to name
                else key.toInt().takeIf { it in 1..0xFFFF }?.let { exact[it] = name }
            }
            return Watched(exact, prefixes.sortedByDescending { it.first.length })
        }
    }

    /** Watched talkgroups: a number's name ("" when unnamed), or null when not watched. Its exact line wins over a wildcard, a longer wildcard over a shorter. */
    class Watched(private val exact: Map<Int, String> = emptyMap(), private val prefixes: List<Pair<String, String>> = emptyList()) {
        operator fun get(tg: Int?): String? = tg?.let { exact[it] ?: it.toString().let { s -> prefixes.firstOrNull { (p, _) -> s.startsWith(p) }?.second } }
        operator fun contains(tg: Int?) = get(tg) != null
    }
}
