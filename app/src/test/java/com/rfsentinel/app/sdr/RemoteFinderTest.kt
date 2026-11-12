package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteFinderTest {
    private fun c(hz: Long, db: Float = 20f) = P25Hunt.Candidate(hz, db)
    private val at = 315_400_000L

    @Test fun backgroundCarrierIgnoredRemoteFound() {
        val f = RemoteFinder(learnRounds = 1)
        assertNull(f.look(at, listOf(listOf(c(315_800_000L)), listOf(c(315_800_000L))))) // learning: a carrier already on
        f.endRound()
        val remote = f.look(at, listOf(listOf(c(315_800_000L, 40f), c(315_910_000L, 25f)), listOf(c(315_800_000L, 40f), c(315_912_000L, 31f))))
        assertEquals(315_912_000L, remote?.hz)
    }

    @Test fun noiseSpikeInOneSpectrumNeverCounts() {
        val f = RemoteFinder(learnRounds = 0)
        assertNull(f.look(at, listOf(listOf(c(315_000_000L, 30f)), emptyList(), listOf(c(315_500_000L, 30f)))))
    }
}
