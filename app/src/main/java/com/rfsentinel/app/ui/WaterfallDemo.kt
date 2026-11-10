package com.rfsentinel.app.ui

import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.sdr.SdrWaterfall
import kotlin.math.abs
import kotlin.random.Random

/**
 * Debug builds only: a made-up SDR for the Radio waterfall, to test the screen without a dongle
 * (`am start -n com.rfsentinel.app.debug/com.rfsentinel.app.MainActivity --es open waterfall`).
 * It copies what a real first run shows: VHF noisier than UHF, a chunk that gives no reading, the
 * receiver's ripple per chunk, steady stations and short bursts. Generic frequencies, no place.
 */
internal class WaterfallDemo(private val emit: (SdrWaterfall.Row) -> Unit) {

    /** The frequency zoomed in on, or null for the sweep. */
    @Volatile var tuneHz: Long? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "waterfall-demo").apply { isDaemon = true; start() }
    }

    fun stop() { thread?.interrupt(); thread = null }

    /** Answers like the real check, after the couple of seconds a real one takes. */
    fun antennaCheck(answer: (SdrRadio.AntennaReading) -> Unit) {
        Thread({
            try { Thread.sleep(2_000L) } catch (_: InterruptedException) { return@Thread }
            answer(SdrRadio.AntennaReading(98_100_000L, 31, 162_475_000L, 2))
        }, "waterfall-demo-check").apply { isDaemon = true; start() }
    }

    private fun run() {
        val rnd = Random(7)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val t = tuneHz
                if (t != null) { emit(tuned(t, rnd)); Thread.sleep(TUNED_MS) } else { emit(sweep(rnd)); Thread.sleep(SWEEP_MS) }
            }
        } catch (_: InterruptedException) { }
    }

    private fun sweep(rnd: Random): SdrWaterfall.Row {
        val now = System.currentTimeMillis()
        val segs = BANDS.flatMap { b ->
            (0 until b.chunks).map { i ->
                val center = b.startHz + CHUNK_HZ / 2 + i * CHUNK_HZ
                if (i in b.dead) SdrWaterfall.blank(center, RATE, HALF_HZ, b.name)
                else {
                    val n = 2 * HALF_BINS + 1
                    val db = FloatArray(n) { k ->
                        val hz = center - HALF_HZ.toLong() + (k * 2 * HALF_HZ / (n - 1)).toLong()
                        // The receiver's filter: a little lower towards each chunk's edges (the comb in a real sweep).
                        val edge = abs(k - HALF_BINS).toFloat() / HALF_BINS
                        b.noiseDb - 4f * edge * edge + rnd.nextFloat() * 3f + signal(hz, now)
                    }
                    SdrWaterfall.Segment(center - HALF_HZ.toLong(), center + HALF_HZ.toLong(), db, b.name)
                }
            }
        }
        return SdrWaterfall.Row(segs, tuned = false, at = now)
    }

    private fun tuned(center: Long, rnd: Random): SdrWaterfall.Row {
        val now = System.currentTimeMillis()
        val n = 1024
        val span = RATE.toLong()
        val db = FloatArray(n) { k ->
            val hz = center - span / 2 + k * span / n
            -60f + rnd.nextFloat() * 3f + signal(hz, now)
        }
        return SdrWaterfall.Row(listOf(SdrWaterfall.Segment(center - span / 2, center + span / 2, db, null)), tuned = true, at = now)
    }

    /** dB above the noise at [hz] from the made-up stations, on or off by the clock. */
    private fun signal(hz: Long, now: Long): Float {
        var best = 0f
        for (s in SIGNALS) {
            val d = abs(hz - s.hz)
            if (d > s.halfWidthHz) continue
            if (s.periodMs > 0 && (now / s.periodMs + s.hz / 1000) % 3 != 0L) continue
            val v = s.db * (1f - d.toFloat() / s.halfWidthHz * 0.5f)
            if (v > best) best = v
        }
        return best
    }

    private class Band(val name: String, val startHz: Long, val chunks: Int, val noiseDb: Float, val dead: Set<Int> = emptySet())
    /** [periodMs] 0: always on; otherwise on for one period in three, like someone talking. */
    private class Sig(val hz: Long, val halfWidthHz: Long, val db: Float, val periodMs: Long = 0)

    private companion object {
        const val RATE = 2_048_000
        const val CHUNK_HZ = 1_600_000L
        const val HALF_HZ = 800_000.0
        val HALF_BINS = (HALF_HZ / (RATE / 1024.0)).toInt()
        const val SWEEP_MS = 1_200L
        const val TUNED_MS = 60L
        /** Like a first run: VHF ~15 dB noisier than UHF, and one chunk in each of two bands that gives nothing. */
        val BANDS = listOf(
            Band("VHF", 150_000_000L, 15, -45f),
            Band("UHF", 450_000_000L, 13, -62f, dead = setOf(4, 9)),
            Band("800 MHz", 799_000_000L, 10, -60f, dead = setOf(2))
        )
        val SIGNALS = listOf(
            Sig(97_100_000L, 90_000L, 35f), Sig(98_100_000L, 90_000L, 38f), Sig(98_900_000L, 90_000L, 28f),
            Sig(119_100_000L, 6_000L, 25f, 2_000L),
            Sig(146_520_000L, 6_000L, 22f, 3_000L),
            Sig(155_475_000L, 6_000L, 24f, 2_500L),
            Sig(162_475_000L, 6_000L, 20f),
            Sig(433_920_000L, 15_000L, 18f, 700L),
            Sig(456_250_000L, 6_000L, 20f, 2_200L),
            Sig(462_637_500L, 6_000L, 26f, 1_800L),
            Sig(806_500_000L, 6_000L, 22f),
            Sig(811_000_000L, 6_000L, 18f, 1_500L),
            Sig(1_090_000_000L, 500_000L, 12f, 400L)
        )
    }
}
