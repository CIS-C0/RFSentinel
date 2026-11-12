package com.rfsentinel.app.sdr

import kotlin.math.abs

/**
 * Picks a pressed remote out of the waterfall finder's looks ([SdrRadio.finder]). Each look at a centre is a run of
 * short spectra (a few ms each): a remote's burst lasts tens of ms, so it shows in [minReads] of them or more, while a
 * noise spike shows in one. Whatever was on the air during the first [learnRounds] rounds (before the button is
 * pressed: a carrier, the dongle's own spurs) is ignored from then on.
 */
internal class RemoteFinder(private val learnRounds: Int = 2, private val minReads: Int = 2) {
    private val background = HashMap<Long, MutableList<Long>>()
    private var rounds = 0
    val ready get() = rounds >= learnRounds

    /** One look at [center], the signals of each short spectrum in turn: the remote heard there, or null. */
    fun look(center: Long, reads: List<List<P25Hunt.Candidate>>): P25Hunt.Candidate? {
        // Signals by frequency, with the number of spectra each showed in and its strongest showing.
        val seen = ArrayList<Pair<P25Hunt.Candidate, Int>>()
        for (found in reads) for (c in found.distinctBy { it.hz / NEAR_HZ }) {
            val i = seen.indexOfFirst { abs(it.first.hz - c.hz) <= NEAR_HZ }
            if (i < 0) seen += c to 1
            else seen[i] = (if (c.snrDb > seen[i].first.snrDb) c else seen[i].first) to seen[i].second + 1
        }
        val real = seen.filter { it.second >= minReads }.map { it.first }
        if (!ready) { background.getOrPut(center) { ArrayList() } += real.map { it.hz }; return null }
        val bg = background[center].orEmpty()
        return real.filter { c -> bg.none { abs(it - c.hz) <= NEAR_HZ } }.maxByOrNull { it.snrDb }
    }

    fun endRound() { rounds++ }

    private companion object { const val NEAR_HZ = 30_000L }
}
