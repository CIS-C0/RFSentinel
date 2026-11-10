package com.rfsentinel.app.sdr

import kotlin.math.abs

/**
 * Learns how far off the SDR's crystal is, from a P25 control channel: the site broadcasts its control
 * channel's number, which with the site's channel tables is the exact frequency it transmits on, and the
 * decoder's frequency loop says where this dongle actually hears it. The idea follows SDRTrunk's auto-PPM
 * (Dennis Sheirer, TunerFrequencyErrorManager, GPL-3.0): correct the tuner by the measured channel error.
 *
 * [appliedPpm] is the correction the dongle is tuned with now; [add] returns the new total once
 * [NEEDED] readings in a row agree within [SPREAD_PPM] and differ from it by at least [MIN_CHANGE_PPM].
 * Pure: unit-tested.
 */
class FreqCorrection(val appliedPpm: Double) {

    private val readings = ArrayDeque<Double>()

    /** [heardHz]: the carrier as this dongle hears it; [announcedHz]: where the site says it is. */
    fun add(heardHz: Long, announcedHz: Long): Double? {
        if (announcedHz <= 0 || heardHz <= 0) return null
        val residual = (announcedHz - heardHz).toDouble() / announcedHz * 1e6
        // Farther than any crystal is off: not the channel the site means (a secondary control channel, say).
        if (abs(residual) > MAX_PPM) { readings.clear(); return null }
        readings.addLast(residual)
        if (readings.size > NEEDED) readings.removeFirst()
        if (readings.size < NEEDED || readings.max() - readings.min() > SPREAD_PPM) return null
        val median = readings.sorted()[NEEDED / 2]
        if (abs(median) < MIN_CHANGE_PPM) return null
        val next = appliedPpm + median
        return if (abs(next) <= MAX_PPM) next else null
    }

    companion object {
        /** Cheap dongles are within ~±50 ppm, a crystal-oven (TCXO) one within ~1. */
        const val MAX_PPM = 100.0
        const val NEEDED = 5
        const val SPREAD_PPM = 1.0
        /** Smaller errors cost a sweep relearn for nothing (1 ppm is ~850 Hz at 850 MHz, a tenth of a P25 channel). */
        const val MIN_CHANGE_PPM = 1.0
    }
}
