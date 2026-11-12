package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tire sensors: frames built the way the sensors send them, through the radio path (I/Q in, readings out). */
class TpmsTest {

    private val rate = 2_048_000
    private val offset = 512_000.0

    /** [chips] (1 high tone / on) at [chipS] each, between 10 ms of noise on each side, as the dongle's 8-bit I/Q. */
    private fun iq(chips: IntArray, chipS: Double, fsk: Boolean, seed: Long = 1): ByteArray {
        val pad = (0.010 * rate).toInt()
        val n = pad * 2 + (chips.size * chipS * rate).toInt()
        val out = ByteArray(n * 2)
        val rnd = java.util.Random(seed)
        var phase = 0.0
        for (s in 0 until n) {
            val k = ((s - pad) / (chipS * rate)).toInt()
            val inside = s >= pad && k < chips.size
            val on = inside && (fsk || chips[k] == 1)
            phase += 2 * PI * (offset + if (fsk && inside) (if (chips[k] == 1) 30_000.0 else -30_000.0) else 0.0) / rate
            val amp = if (on) 40.0 else 0.0
            out[2 * s] = (127.5 + amp * cos(phase) + rnd.nextGaussian() * 2).roundToInt().coerceIn(0, 255).toByte()
            out[2 * s + 1] = (127.5 + amp * sin(phase) + rnd.nextGaussian() * 2).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun decode(iq: ByteArray): List<Tpms.Reading> {
        val out = ArrayList<Tpms.Reading>()
        val d = Tpms.Demod(rate, offset) { out += it }
        var at = 0
        while (at < iq.size) { val len = minOf(32_768, iq.size - at); d.feed(iq.copyOfRange(at, at + len), len / 2); at += len }
        return out
    }

    private fun bitsOf(bytes: IntArray) = bytes.flatMap { b -> (7 downTo 0).map { (b shr it) and 1 } }

    @Test
    fun crc8MatchesTheStandardCheck() {
        assertEquals(0xF4, Tpms.crc8("123456789".map { it.code }.toIntArray(), 9, 0x07, 0x00))
    }

    private fun fordChips(b: IntArray): IntArray {
        // Sent: preamble 55 55 55 56 and Manchester; decoded inverted (rtl_433's ford.c), so this is the inverse.
        val decoded = bitsOf(intArrayOf(0xaa, 0xaa, 0xaa, 0xa9)) + bitsOf(b).flatMap { listOf(1 - it, it) }
        return decoded.map { 1 - it }.toIntArray()
    }

    private fun ford(): IntArray {
        val b = intArrayOf(0x12, 0x34, 0x56, 0x78, 0x80, 56 + 20, 0x08, 0)
        b[7] = b.take(7).sum() and 0xff
        return b
    }

    @Test
    fun fordSensor() {
        val r = decode(iq(fordChips(ford()), 52e-6, fsk = true)).single()
        assertEquals("Ford", r.protocol)
        assertEquals("12345678", r.id)
        assertEquals(32 * 6.894757, r.kpa!!, 0.01)
        assertEquals(20.0, r.tempC!!, 0.01)
    }

    @Test
    fun aBadChecksumIsNothing() {
        val b = ford(); b[2] = b[2] xor 0x01
        assertEquals(emptyList<Tpms.Reading>(), decode(iq(fordChips(b), 52e-6, fsk = true)))
    }

    @Test
    fun toyotaSensor() {
        val p1 = 0x9C; val temp = 60
        val b = intArrayOf(0x0A, 0x1B, 0x2C, 0x3D, p1 shr 1, ((p1 and 1) shl 7) or (temp shr 1), (temp and 1) shl 7, p1 xor 0xff, 0)
        b[8] = Tpms.crc8(b, 8, 0x07, 0x80)
        // Differential Manchester: a level change at every bit's start, and another mid-bit for a 0. 8 bits of 1 after.
        val chips = ArrayList<Int>()
        "0101010101010101001111".forEach { chips += it - '0' }
        var level = 1
        for (bit in bitsOf(b) + List(8) { 1 }) {
            val first = 1 - level
            val second = if (bit == 1) first else 1 - first
            chips += first; chips += second; level = second
        }
        val r = decode(iq(chips.toIntArray(), 52e-6, fsk = true)).single()
        assertEquals("Toyota", r.protocol)
        assertEquals("0A1B2C3D", r.id)
        assertEquals((0x9C * 0.25 - 7) * 6.894757, r.kpa!!, 0.01)
        assertEquals(20.0, r.tempC!!, 0.01)
    }

    @Test
    fun schraderSensor() {
        // 68 bits: a sync nibble (starting with 0: the first rise), then 8 bytes, CRC-8 last.
        val b = intArrayOf(0x31, 0x23, 0x45, 0x67, 0x89, 0x50, 70, 0)
        b[7] = Tpms.crc8(b, 7, 0x07, 0xf0)
        val bits = listOf(0, 1, 1, 1) + bitsOf(b)
        // OOK Manchester: a 0 is off then on, a 1 on then off; the row starts on its first "on".
        val chips = bits.flatMap { if (it == 0) listOf(0, 1) else listOf(1, 0) }.drop(1).toIntArray()
        val r = decode(iq(chips, 122e-6, fsk = false)).single()
        assertEquals("Schrader", r.protocol)
        assertEquals("3456789", r.id)
        assertEquals(0x50 * 2.5, r.kpa!!, 0.01)
        assertEquals(20.0, r.tempC!!, 0.01)
    }

    @Test
    fun noiseAloneIsNothing() {
        // 2 s of noise only, and a steady carrier for 200 ms: no bursts decode to a sensor.
        val quiet = iq(IntArray(0), 52e-6, fsk = true, seed = 3)
        val carrier = iq(IntArray(1600) { 1 }, 125e-6, fsk = false, seed = 4)
        val all = java.io.ByteArrayOutputStream().apply { repeat(100) { write(quiet) }; write(carrier) }.toByteArray()
        assertEquals(emptyList<Tpms.Reading>(), decode(all))
    }

    @Test
    fun diffManchesterIsRtl433s() {
        // 0 1 0 0 1: a change at each bit start, a 0 also changes mid-bit.
        val (bits, _) = Tpms.diffManchester(intArrayOf(0, 1, 0, 0, 1, 0, 1, 0, 1, 1, 1), 0, 10)
        assertTrue(bits.contentToString(), bits.take(5) == listOf(0, 1, 0, 0, 1))
    }
}
