package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.log10

/**
 * The ADS-B receiver's gain, steered by the noise floor: a port of the dynamic-range mode of
 * dump1090-fa's adaptive.c (FlightAware, GPL-2.0-or-later). The noise is the 40th percentile
 * of the samples outside messages; the gain goes up while it stays [TARGET_DB] under full scale and
 * comes down when it doesn't. That is as much gain as helps faint aircraft without the noise taking
 * the room loud ones need. One decision a second ([endOfBlock]), none for [CHANGE_DELAY] seconds
 * after a change while the noise settles; once settled, one step up is tried again an hour later.
 */
class AdsbGain(startTenths: Int = STEPS.last()) {

    /** Magnitudes outside messages since the last block, by level: [ModeS.Demod.scan] fills it. */
    val quiet = IntArray(ModeS.LEVELS)
    private var step = STEPS.indices.minBy { abs(STEPS[it] - startTenths) }
    /** The gain to set, in tenths of a dB. */
    val tenths get() = STEPS[step]
    /** Smoothed noise level in [ModeS.magnitudes] units; negative before the first block. */
    private var noise = -1.0
    private var state = State.RESCAN_UP
    private var changeTimer = CHANGE_DELAY
    private var rescanTimer = 0

    private enum class State { IDLE, SCAN_UP, SCAN_DOWN, RESCAN_UP, RESCAN_DOWN }

    /** dB from the noise floor up to full scale: the room left for loud signals. */
    val rangeDb get() = if (noise <= 0) MAX_RANGE_DB else minOf(MAX_RANGE_DB, 20 * log10(ModeS.FULL_SCALE / noise))

    class Change(val tenths: Int, val why: String)

    /** Ends a block of about a second of samples; the gain to set when it should change. */
    fun endOfBlock(): Change? {
        val total = quiet.sum()
        if (total == 0) return null // all of it messages, or nothing read: nothing to measure
        val nth = total.toLong() * PERCENTILE / 100
        var n = 0L; var i = 0
        while (i < quiet.size && n <= nth) n += quiet[i++]
        val level = (i - 1).toDouble()
        quiet.fill(0)
        noise = if (noise < 0) level else noise * (1 - ALPHA) + level * ALPHA
        if (rescanTimer > 0) rescanTimer--
        if (changeTimer > 0) { changeTimer--; return null }
        val range = rangeDb
        val fits = range >= TARGET_DB
        var up = false; var down = false; var why = ""
        when (state) {
            State.SCAN_UP, State.RESCAN_UP -> when {
                !fits -> { down = true; why = "probing the lowest gain that keeps the range"
                    state = if (state == State.RESCAN_UP) State.RESCAN_DOWN else State.SCAN_DOWN }
                step >= STEPS.lastIndex -> { state = State.IDLE; rescanTimer = RESCAN_DELAY }
                else -> { up = true; why = "probing the highest gain that keeps the range" }
            }
            State.SCAN_DOWN, State.RESCAN_DOWN -> when {
                fits -> { rescanTimer = if (state == State.SCAN_DOWN) SCAN_DELAY else RESCAN_DELAY; state = State.IDLE }
                step == 0 -> { state = State.IDLE; rescanTimer = RESCAN_DELAY }
                else -> { down = true; why = "probing the lowest gain that keeps the range" }
            }
            State.IDLE -> when {
                // Noise up by more than half a step: back down.
                step > 0 && range + (STEPS[step] - STEPS[step - 1]) / 20.0 < TARGET_DB -> {
                    down = true; why = "the noise rose"; state = State.SCAN_DOWN
                }
                rescanTimer == 0 -> if (fits && step < STEPS.lastIndex) {
                    up = true; why = "trying a step more gain again"; state = State.RESCAN_UP
                } else rescanTimer = RESCAN_DELAY
            }
        }
        val to = when { down -> step - 1; up -> step + 1; else -> return null }.coerceIn(0, STEPS.lastIndex)
        if (to == step) return null
        step = to
        changeTimer = CHANGE_DELAY
        return Change(tenths, why)
    }

    companion object {
        /** The R820T / R828D gain steps in tenths of a dB (librtlsdr's list). */
        val STEPS = intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297,
            328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)
        /** dump1090-fa's defaults for an RTL-SDR. */
        const val TARGET_DB = 30.0
        const val PERCENTILE = 40
        const val ALPHA = 2.0 / 6
        /** In blocks, about seconds. */
        const val CHANGE_DELAY = 10
        const val SCAN_DELAY = 300
        const val RESCAN_DELAY = 3600
        private const val MAX_RANGE_DB = 99.0
    }
}
