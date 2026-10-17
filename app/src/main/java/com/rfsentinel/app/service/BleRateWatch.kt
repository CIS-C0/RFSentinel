package com.rfsentinel.app.service

/**
 * Spots a Bluetooth scan Android has quietly throttled (after Wardrive Go's BLE watchdog): the
 * scan still runs and a few results still trickle in, so the silence watchdog never fires, but
 * the rate has collapsed to a fraction of what it was a moment ago. Restarting the scan brings
 * it back. Fed only while the screen stays on (screen-off rates are lower by design).
 */
class BleRateWatch {
    /** Results per second while the scan is healthy (a slow average); null while learning. */
    private var baseline: Double? = null
    private var lowTicks = 0
    private var lastRestartAt = -MIN_GAP_MS

    /** [count] results over [ms]. True: restart the scan now. */
    fun tick(count: Int, ms: Long, now: Long): Boolean {
        if (ms <= 0) return false
        val rate = count * 1000.0 / ms
        val b = baseline ?: run { baseline = rate; return false }
        if (b >= MIN_BASELINE && rate < b * COLLAPSED) {
            if (++lowTicks < LOW_TICKS || now - lastRestartAt < MIN_GAP_MS) return false
            // Relearn after the restart: if the area really is this quiet, that's the new normal.
            lowTicks = 0; lastRestartAt = now; baseline = null
            return true
        }
        lowTicks = 0
        baseline = b * 0.8 + rate * 0.2
        return false
    }

    fun reset() { baseline = null; lowTicks = 0 }

    companion object {
        /** Below this many results a second there's too little to tell a collapse from a quiet street. */
        const val MIN_BASELINE = 3.0
        /** A tenth of the usual rate or less... */
        const val COLLAPSED = 0.1
        /** ...for this many ticks in a row. */
        const val LOW_TICKS = 2
        const val MIN_GAP_MS = 5 * 60_000L
    }
}
