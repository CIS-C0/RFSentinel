package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class P25HuntTest {

    /** A transmitter: C4FM [dibits] on repeat at [hz], [amp] in dongle units. */
    private class Carrier(val hz: Long, val amp: Double, val dibits: IntArray) { var phase = 0.0 }

    /** A dongle whose crystal is [ERROR_HZ] off, hearing [carriers] (within its bandwidth) in noise. Time runs on across retunes. */
    private class FakeDongle(val carriers: List<Carrier>) : SdrDevice {
        override val label = "fake"
        override val sampleRate = P25Demod.SAMPLE_RATE
        override val ctlErrors = 0
        override val lost = false
        override val tunerWriteFails = 0
        override val settleReads = 1
        var tuned = 0L
        var p25On = true
        private var t = 0L
        private val rnd = java.util.Random(5)
        override fun open() = true
        override fun close() {}
        override fun setSampleRate(rate: Int) {}
        override fun tune(hz: Long): Boolean { tuned = hz; return true }
        override fun setGain(tenthsDb: Int) {}
        override fun resetBuffer() {}
        override fun read(buf: ByteArray, len: Int): Int {
            val perSymbol = sampleRate / P25Demod.SYMBOL_RATE
            val heard = carriers.filter { abs(it.hz + ERROR_HZ - tuned) < 950_000 && (p25On || it !== carriers[0]) }
            var k = 0
            while (k < len) {
                var i = rnd.nextGaussian() * 3; var q = rnd.nextGaussian() * 3
                for (c in heard) {
                    val d = c.dibits[((t / perSymbol) % c.dibits.size).toInt()]
                    c.phase += 2 * PI * (c.hz + ERROR_HZ - tuned + P25TestSignal.hz(d)) / sampleRate
                    i += c.amp * cos(c.phase); q += c.amp * sin(c.phase)
                }
                buf[k++] = (127.5 + i).roundToInt().coerceIn(0, 255).toByte()
                buf[k++] = (127.5 + q).roundToInt().coerceIn(0, 255).toByte()
                t++
            }
            return len
        }
    }

    private val network = byteArrayOf(0x3B, 0x00, 0x01, 0xBE.toByte(), 0xE0.toByte(), 0x03, 0xA1.toByte(), 0x10, 0x40, 0x70)
    private val grant = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x10, 0x05, 0x01, 0x23, 0x0A, 0x0B, 0x0C)

    @Test
    fun findsTheControlChannelPastAStrongerOneAndNoticesWhenItStops() {
        val control = P25TestSignal.SYNC + P25TestSignal.frame(0x293, P25Frame.DUID_TSDU,
            P25TestSignal.block(network), P25TestSignal.block(grant))
        val rnd = java.util.Random(2)
        val other = IntArray(2000) { rnd.nextInt(4) } // 4-level FSK that isn't P25: no sync, no NID
        val dongle = FakeDongle(listOf(Carrier(771_106_250L, 20.0, control), Carrier(772_400_000L, 40.0, other)))
        val logs = ArrayList<String>()
        val hunt = P25Hunt(dongle) { logs += it; println(it) }

        val lock = hunt.hunt { false }!!
        assertEquals(2, hunt.tried) // the louder decoy first, then the control channel
        assertEquals(0x293, lock.nac)
        assertTrue("locked at ${lock.hz}", abs(lock.hz - (771_106_250L + ERROR_HZ)) < 1_500)

        val heard = ArrayList<P25Tsbk.Msg>()
        // The control channel goes off the air after a few messages: listen gives up on it.
        val lost = hunt.listen(lock, { dongle.p25On = heard.size < 6; false }) { heard += it }
        assertTrue(lost)
        assertTrue(heard.contains(P25Tsbk.Site(0xBEE00, 0x3A1, null, null, 0x1040)))
        assertTrue(heard.contains(P25Tsbk.Grant(0x1005, 0x0123, null, 0x0A0B0C, false, false)))
        // The log tells each stage apart, and keeps the frequency and network code out.
        assertTrue(logs.any { it.startsWith("P25 try 1/2") && it.endsWith("not a control channel") })
        assertTrue(logs.any { it.startsWith("P25 try 2/2") && it.contains("control channel (1 NAC seen)") })
        assertTrue(logs.any { it.startsWith("P25 control channel quiet") && it.contains("Sitex") })
        assertTrue(logs.none { it.contains("771.") || it.contains("293") })
    }

    @Test
    fun everyChannelIsSeenAwayFromTheTunedCentre() {
        val plan = P25Hunt.plan()
        for (b in P25Hunt.BANDS) for (f in b step 6_250L)
            assertTrue("$f", plan.any { abs(f - it) in RadioWatch.DC_GUARD_HZ + 6_000..RadioWatch.USABLE_HZ.toLong() - 6_000 })
    }

    companion object { const val ERROR_HZ = 18_000L }
}
