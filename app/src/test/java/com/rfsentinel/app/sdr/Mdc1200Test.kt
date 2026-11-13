package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Mdc1200Test {

    /** A packet's 112 bits as sent: CRC, parity, interleave. */
    private fun packetBits(op: Int, arg: Int, unit: Int): IntArray {
        val data = IntArray(14)
        data[0] = op; data[1] = arg; data[2] = unit shr 8; data[3] = unit and 0xff
        val crc = Mdc1200.crc(data); data[4] = crc and 0xff; data[5] = crc shr 8
        Mdc1200.parity(data)
        val sent = IntArray(112)
        for (l in 0 until 112) sent[(l % 7) * 16 + l / 7] = data[l / 8] shr (l % 8) and 1
        return sent
    }

    /** Preamble, sync, packet as audio: 1800 Hz where the bit changes, 1200 Hz where it stays, continuous phase. */
    private fun audio(bits: IntArray, rate: Int, startBit: Int = 0, noise: Double = 0.0, seed: Long = 1): DoubleArray {
        val all = ArrayList<Int>()
        repeat(24) { all += it and 1 }
        for (i in 39 downTo 0) all += (0x07092A446FL shr i and 1).toInt()
        all += bits.toList()
        repeat(16) { all += 0 }
        val rnd = java.util.Random(seed)
        val out = ArrayList<Double>()
        repeat(rate / 10) { out += noise * rnd.nextGaussian() }
        var state = startBit; var phase = 0.0; var t = 0.0
        val perBit = rate / 1200.0
        for (b in all) {
            val f = if (b != state) 1800.0 else 1200.0
            state = b
            t += perBit
            while (out.size < rate / 10 + t.toInt()) { phase += 2 * PI * f / rate; out += 0.5 * sin(phase) + noise * rnd.nextGaussian() }
        }
        repeat(rate / 10) { out += noise * rnd.nextGaussian() }
        return out.toDoubleArray()
    }

    private fun run(samples: DoubleArray, rate: Int): List<Mdc1200.Packet> {
        val got = ArrayList<Mdc1200.Packet>()
        val d = Mdc1200(rate) { got += it }
        for (s in samples) d.sample(s)
        return got
    }

    @Test
    fun pttIdDecodesOnce() {
        val bits = packetBits(Mdc1200.OP_PTT_ID, 0x80, 0x1A2B)
        val got = run(audio(bits, 32_000), 32_000)
        assertEquals(listOf(Mdc1200.Packet(0x01, 0x80, 0x1A2B)), got)
        assertEquals("unit 1A2B", got[0].label)
        assertFalse(got[0].emergency)
    }

    @Test
    fun invertedAndNoisyAndEmergency() {
        val bits = packetBits(Mdc1200.OP_EMERGENCY, 0x81, 0x0042)
        val got = run(audio(bits, 32_000, startBit = 1, noise = 0.25, seed = 7), 32_000)
        assertEquals(listOf(Mdc1200.Packet(0x00, 0x81, 0x0042)), got)
        assertTrue(got[0].emergency)
        assertEquals("EMERGENCY, unit 0042", got[0].label)
        // Also at 8 kHz.
        assertEquals(1, run(audio(packetBits(1, 0, 0x1234), 8_000), 8_000).size)
    }

    @Test
    fun bitErrorsCorrectedNoiseRejected() {
        val bits = packetBits(Mdc1200.OP_PTT_ID, 0x00, 0xBEEF)
        val two = bits.copyOf(); two[3] = 1 - two[3]; two[70] = 1 - two[70]
        assertEquals(Mdc1200.Packet(1, 0, 0xBEEF), Mdc1200.decode(two))
        val parityHit = bits.copyOf(); parityHit[111] = 1 - parityHit[111]
        assertEquals(Mdc1200.Packet(1, 0, 0xBEEF), Mdc1200.decode(parityHit))
        // Plain noise: nothing, ever.
        val rnd = java.util.Random(3)
        repeat(2000) { assertNull(Mdc1200.decode(IntArray(112) { rnd.nextInt(2) })) }
        assertTrue(run(DoubleArray(32_000 * 3) { rnd.nextGaussian() * 0.5 }, 32_000).isEmpty())
    }
}
