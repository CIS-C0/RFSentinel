package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * MDC-1200: the short data burst many analog FM radios (Motorola and others) send when keyed up, carrying the radio's
 * unit ID, or an emergency. 1200 baud, 1200 / 1800 Hz tones: a bit period on 1800 Hz flips the bit, on 1200 Hz keeps it.
 * A 40-bit sync, then 112 interleaved bits: 4 data bytes, a CRC, and convolutional parity that fixes scattered errors.
 * Feed it demodulated audio before any squelch ([sample], about ±1); [onPacket] gets each packet whose CRC checks, once.
 * Each bit is judged by the energy of both tones over one bit period, at [PHASES] timings at once (no clock recovery).
 * Sync word, interleave, tone rule and fields as in SDRTrunk (Dennis Sheirer, GPL-3.0: MDCDecoder, MDCMessage); SDRTrunk
 * doesn't check the CRC or parity, done here so noise can't make up a unit ID. Pure: unit-tested.
 */
class Mdc1200(private val rate: Int, private val onPacket: (Packet) -> Unit) {

    /** [op] what it is ([OP_PTT_ID] key-up ID, [OP_EMERGENCY]...), [arg] its argument, [unit] the radio's ID. */
    data class Packet(val op: Int, val arg: Int, val unit: Int) {
        /** Opcode 0 with the argument's emergency flag (SDRTrunk's MDCMessage). */
        val emergency get() = op == OP_EMERGENCY && arg and 1 != 0
        /** Unit IDs are written in hex, as radios and dispatch show them. */
        val unitText get() = "%04X".format(unit)
        val label get() = if (emergency) "EMERGENCY, unit $unitText" else "unit $unitText"
    }

    private val n = (rate / BAUD.toDouble()).roundToInt()
    private val ring = DoubleArray(n)
    private var at = 0
    private var t = 0L
    private val c12 = DoubleArray(n) { cos(2 * PI * 1200 * it / rate) }; private val s12 = DoubleArray(n) { sin(2 * PI * 1200 * it / rate) }
    private val c18 = DoubleArray(n) { cos(2 * PI * 1800 * it / rate) }; private val s18 = DoubleArray(n) { sin(2 * PI * 1800 * it / rate) }
    private val step = BAUD.toDouble() / rate

    private class Dec(var th: Double) {
        var xorb = false; var sh = 0L; var collecting = false; var invert = false
        val bits = IntArray(112); var nb = 0
    }
    private val decs = List(PHASES) { Dec(it / PHASES.toDouble()) }
    private var last: Packet? = null
    private var lastAt = Long.MIN_VALUE / 2

    fun feed(pcm: ShortArray, count: Int) { for (i in 0 until count) sample(pcm[i] / 32768.0) }

    fun sample(v: Double) {
        ring[at] = v; if (++at == n) at = 0
        t++
        for (d in decs) {
            d.th += step
            if (d.th >= 1.0) { d.th -= 1.0; bit(d, high()) }
        }
    }

    /** The last bit period was more 1800 Hz than 1200 Hz. */
    private fun high(): Boolean {
        var r12 = 0.0; var i12 = 0.0; var r18 = 0.0; var i18 = 0.0
        var k = at
        for (i in 0 until n) {
            val x = ring[k]; if (++k == n) k = 0
            r12 += x * c12[i]; i12 += x * s12[i]; r18 += x * c18[i]; i18 += x * s18[i]
        }
        return r18 * r18 + i18 * i18 > r12 * r12 + i12 * i12
    }

    private fun bit(d: Dec, high: Boolean) {
        if (high) d.xorb = !d.xorb
        val b = if (d.xorb) 1 else 0
        if (d.collecting) {
            d.bits[d.nb++] = if (d.invert) 1 - b else b
            if (d.nb == 112) { d.collecting = false; decode(d.bits)?.let(::found) }
            return
        }
        d.sh = (d.sh shl 1 or b.toLong()) and MASK40
        val errors = java.lang.Long.bitCount(d.sh xor SYNC)
        if (errors <= SYNC_ERRORS || 40 - errors <= SYNC_ERRORS) {
            d.collecting = true; d.invert = errors > SYNC_ERRORS; d.nb = 0; d.sh = 0L
        }
    }

    /** The decoders at other timings read the same packet: once a second is enough. */
    private fun found(p: Packet) {
        if (p == last && t - lastAt < rate) return
        last = p; lastAt = t
        onPacket(p)
    }

    companion object {
        const val BAUD = 1200
        const val OP_PTT_ID = 0x01
        const val OP_EMERGENCY = 0x00
        private const val PHASES = 8
        private const val SYNC = 0x07092A446FL
        private const val MASK40 = (1L shl 40) - 1
        private const val SYNC_ERRORS = 2

        /** 112 bits as received (after the sync) to a packet, or null when it can't be made to pass its CRC and parity. */
        internal fun decode(bits: IntArray): Packet? {
            val data = IntArray(14)
            for (l in 0 until 112) if (bits[(l % 7) * 16 + l / 7] == 1) data[l / 8] = data[l / 8] or (1 shl (l % 8))
            if (!correct(data)) return null
            return Packet(data[0], data[1], data[2] shl 8 or data[3])
        }

        /**
         * Bytes 0-3 the packet, 4-5 its CRC, 6 spare, 7-13 the parity of bytes 0-6. Takes [data] as it came if its CRC
         * checks and its parity mostly agrees; else tries each one- and two-bit change in bytes 0-6 and keeps the one
         * whose CRC checks with the fewest parity bits off. False when none does.
         */
        internal fun correct(data: IntArray): Boolean {
            fun good(d: IntArray) = crc(d) == (d[5] shl 8 or d[4])
            if (good(data) && parityOff(data) <= MAX_PARITY_OFF) return true
            var best: IntArray? = null; var bestOff = MAX_PARITY_OFF + 1
            val d = data.copyOf()
            fun tryIt() { if (good(d)) { val off = parityOff(d); if (off < bestOff) { bestOff = off; best = d.copyOf() } } }
            for (a in 0 until 56) {
                d[a / 8] = d[a / 8] xor (1 shl (a % 8))
                tryIt()
                for (b in a + 1 until 56) { d[b / 8] = d[b / 8] xor (1 shl (b % 8)); tryIt(); d[b / 8] = d[b / 8] xor (1 shl (b % 8)) }
                d[a / 8] = d[a / 8] xor (1 shl (a % 8))
            }
            best?.copyInto(data) ?: return false
            return true
        }

        /** Rate 1/2 convolutional parity: bit n of bytes 7-13 is data bits n, n-2, n-5 and n-6 added (bits LSB first). */
        internal fun parity(data: IntArray) {
            for (i in 7 until 14) data[i] = 0
            for (n in 0 until 56) {
                var p = 0
                for (tap in TAPS) if (n - tap >= 0) p = p xor (data[(n - tap) / 8] shr ((n - tap) % 8) and 1)
                data[7 + n / 8] = data[7 + n / 8] or (p shl (n % 8))
            }
        }

        /** How many parity bits disagree with bytes 0-6. */
        internal fun parityOff(data: IntArray): Int {
            val p = data.copyOf(); parity(p)
            var off = 0
            for (i in 7 until 14) off += Integer.bitCount(p[i] xor data[i])
            return off
        }

        /** CRC-16 of bytes 0-3: polynomial 0x1021 bit-reflected (0x8408), starting at 0, inverted at the end. */
        internal fun crc(data: IntArray): Int {
            var crc = 0
            for (i in 0 until 4) {
                crc = crc xor (data[i] and 0xff)
                repeat(8) { crc = if (crc and 1 != 0) crc ushr 1 xor 0x8408 else crc ushr 1 }
            }
            return crc xor 0xffff
        }

        private val TAPS = intArrayOf(0, 2, 5, 6)
        /** Parity bits allowed off once the CRC checks: a few bit errors in the parity itself. */
        private const val MAX_PARITY_OFF = 4
    }
}
