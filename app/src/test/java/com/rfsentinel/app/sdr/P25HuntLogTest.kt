package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scan log's levels: "none" instead of "strongest  dB", and a highest point that leaves the centre spike out. */
class P25HuntLogTest {

    @Test fun levelsSayNoneWhenNothingStoodOut() {
        assertEquals("none", P25Hunt.levels(emptyList()))
        assertEquals("17, 16 dB", P25Hunt.levels(listOf(P25Hunt.Candidate(1L, 17.2f), P25Hunt.Candidate(2L, 15.6f))))
    }

    @Test fun logEverythingListsFrequenciesStrongestFirstOnceEach() {
        assertEquals("nothing 10 dB above the noise", P25Hunt.heard(emptyList()))
        val found = listOf(P25Hunt.Candidate(155_475_000L, 17.2f), P25Hunt.Candidate(462_637_500L, 26f), P25Hunt.Candidate(155_476_000L, 15f))
        // The same channel seen twice counts once; a dot decimal on every phone.
        assertEquals("462.6375 MHz 26 dB, 155.4750 MHz 17 dB", P25Hunt.heard(found))
        assertEquals("462.6375 MHz 26 dB and 1 weaker", P25Hunt.heard(found, max = 1))
    }

    @Test fun highestPointIgnoresTheCentreSpike() {
        val psd = FloatArray(1024) { -60f }
        psd[512] = -10f // the receiver's own DC spike
        assertEquals(0f, P25Hunt.peakAboveNoise(psd, 2_048_000), 0.01f)
        psd[700] = -40f // a real signal in the usable middle
        assertEquals(20f, P25Hunt.peakAboveNoise(psd, 2_048_000), 0.01f)
        psd[1020] = -5f // past the usable middle: the filter edge, not a signal
        assertEquals(20f, P25Hunt.peakAboveNoise(psd, 2_048_000), 0.01f)
    }
}
