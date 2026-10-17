package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Mode S / ADS-B on 1090 MHz from an RTL-SDR at 2 MS/s (one sample per half microsecond),
 * written from the published format (ICAO Annex 10 vol. IV, "The 1090 MHz Riddle") with
 * SkyPulse and dump1090 as references for the preamble test. Pure Kotlin, unit-tested.
 *
 *  - [Demod] finds messages in raw 8-bit I/Q: the 8 µs preamble, then 56 or 112 bits of pulse
 *    position (a 1 is high then low). Messages that fail the CRC are dropped, a single wrong bit
 *    in an extended squitter is corrected.
 *  - [decode] reads what's useful here: the aircraft address (DF11 all-call replies, DF17/18
 *    extended squitters), its call sign, barometric altitude, position (CPR) and ground speed.
 */
object ModeS {

    const val SAMPLE_RATE = 2_000_000
    const val FREQ_HZ = 1_090_000_000L
    /** Samples of preamble (8 µs) and of a long message (112 bits × 2). */
    private const val PREAMBLE = 16
    private const val LONG_SAMPLES = 224
    /** Samples a buffer must keep from the previous read so a message across two reads is found. */
    const val OVERLAP = PREAMBLE + LONG_SAMPLES

    /** Message magnitude per I/Q byte pair: |(i - 127.5, q - 127.5)|, scaled to 0..~360. */
    private val MAG = IntArray(256 * 256).also { t ->
        for (i in 0 until 256) for (q in 0 until 256) {
            val di = i - 127.5; val dq = q - 127.5
            t[(i shl 8) or q] = (sqrt(di * di + dq * dq) * 2).toInt()
        }
    }

    /** Magnitudes of [samples] I/Q pairs from [iq] into [out] at [at]. */
    fun magnitudes(iq: ByteArray, samples: Int, out: IntArray, at: Int) {
        for (s in 0 until samples) out[at + s] = MAG[((iq[2 * s].toInt() and 0xff) shl 8) or (iq[2 * s + 1].toInt() and 0xff)]
    }

    // ---- CRC ------------------------------------------------------------------------------

    private const val POLY = 0xFFF409

    /** Mode S parity (CRC-24) of the first [bits] - 24 bits of [msg]. */
    fun parity(msg: ByteArray, bits: Int): Int {
        var crc = 0
        for (i in 0 until (bits - 24) / 8) {
            crc = crc xor ((msg[i].toInt() and 0xff) shl 16)
            repeat(8) { crc = if (crc and 0x800000 != 0) ((crc shl 1) xor POLY) and 0xffffff else (crc shl 1) and 0xffffff }
        }
        return crc
    }

    /** The parity field's syndrome: 0 for an intact DF17/18/11 (IID 0) message, the aircraft address for DF4/5/20/21. */
    fun syndrome(msg: ByteArray, bits: Int): Int {
        val n = bits / 8
        val p = ((msg[n - 3].toInt() and 0xff) shl 16) or ((msg[n - 2].toInt() and 0xff) shl 8) or (msg[n - 1].toInt() and 0xff)
        return parity(msg, bits) xor p
    }

    /** Syndrome of a single flipped bit in a 112-bit message: lets one bad bit be found and fixed. */
    private val ONE_BIT: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        for (bit in 0 until 112) {
            val b = ByteArray(14); b[bit / 8] = (0x80 ushr (bit % 8)).toByte()
            m[syndrome(b, 112)] = bit
        }
        m
    }

    /** Length in bits of a message with downlink format [df]. */
    fun lengthOf(df: Int) = if (df >= 16) 112 else 56

    // ---- Demodulator -----------------------------------------------------------------------

    /** A message that passed its CRC: the raw bytes, its address and the signal it came in at (0..~360). */
    class Frame(val bytes: ByteArray, val icao: Int, val signal: Int) {
        val df get() = (bytes[0].toInt() and 0xff) ushr 3
    }

    /**
     * Finds messages in a stream of magnitudes. Feed it buffers whose first [OVERLAP] samples
     * repeat the end of the previous buffer.
     */
    class Demod {
        var preambles = 0L; private set
        var good = 0L; private set
        var fixed = 0L; private set

        /** Messages in [mag] from 0 until [count] - [OVERLAP] (later starts are in the next buffer). */
        fun scan(mag: IntArray, count: Int, out: MutableList<Frame>) {
            var j = 0
            val last = count - OVERLAP
            val msg = ByteArray(14)
            while (j < last) {
                if (!preamble(mag, j)) { j++; continue }
                preambles++
                val frame = bits(mag, j + PREAMBLE, msg)
                if (frame == null) { j++; continue }
                good++
                out += frame
                j += PREAMBLE + 2 * lengthOf(frame.df)
            }
        }

        /** Pulses at 0, 1, 3.5 and 4.5 µs and quiet in between and after (sample units: 0, 2, 7, 9). */
        private fun preamble(m: IntArray, j: Int): Boolean {
            val m0 = m[j]; val m1 = m[j + 1]; val m2 = m[j + 2]; val m3 = m[j + 3]
            val m7 = m[j + 7]; val m8 = m[j + 8]; val m9 = m[j + 9]
            if (!(m0 > m1 && m1 < m2 && m2 > m3 && m3 < m0 && m[j + 4] < m0 && m[j + 5] < m0 && m[j + 6] < m0 &&
                    m7 > m8 && m8 < m9 && m9 > m[j + 6])) return false
            val high = (m0 + m2 + m7 + m9) / 6
            if (m[j + 4] >= high || m[j + 5] >= high) return false
            for (k in 11..14) if (m[j + k] >= high) return false
            return true
        }

        private fun bits(m: IntArray, from: Int, msg: ByteArray): Frame? {
            java.util.Arrays.fill(msg, 0)
            var prev = 0; var weak = 0; var sum = 0L
            for (i in 0 until 112) {
                val a = m[from + 2 * i]; val b = m[from + 2 * i + 1]
                val bit = when {
                    a > b -> 1
                    a < b -> 0
                    else -> { weak++; prev }
                }
                if (abs(a - b) < 4) weak++
                if (bit == 1) msg[i / 8] = (msg[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                prev = bit
                sum += maxOf(a, b)
                // Short messages are judged on their first 56 bits only.
                if (i == 55 && ((msg[0].toInt() and 0xff) ushr 3) < 16) break
            }
            val df = (msg[0].toInt() and 0xff) ushr 3
            val len = lengthOf(df)
            if (weak > len / 8) return null
            return accept(msg, len, (sum / (len)).toInt())
        }

        private fun accept(msg: ByteArray, len: Int, signal: Int): Frame? {
            val df = (msg[0].toInt() and 0xff) ushr 3
            val s = syndrome(msg, len)
            val icao = icaoOf(msg)
            return when (df) {
                17, 18 -> when {
                    s == 0 -> Frame(msg.copyOf(14), icao, signal)
                    else -> ONE_BIT[s]?.takeIf { it >= 5 }?.let { bit ->
                        val c = msg.copyOf(14)
                        c[bit / 8] = (c[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
                        fixed++
                        Frame(c, icaoOf(c), signal)
                    }
                }
                // All-call reply: parity is the interrogator code (II / SI) in the low 7 bits.
                11 -> if (s and 0xffff80 == 0) Frame(msg.copyOf(7), icao, signal) else null
                else -> null
            }
        }
    }

    private fun icaoOf(msg: ByteArray) =
        ((msg[1].toInt() and 0xff) shl 16) or ((msg[2].toInt() and 0xff) shl 8) or (msg[3].toInt() and 0xff)

    // ---- Decoding --------------------------------------------------------------------------

    /** What one message said about an aircraft. CPR fields are raw 17-bit values. */
    data class Info(
        val icao: Int,
        val callsign: String? = null,
        val altitudeFt: Int? = null,
        val cprOdd: Boolean? = null,
        val cprLat: Int = 0,
        val cprLon: Int = 0,
        val speedKt: Double? = null,
        val trackDeg: Double? = null,
        val onGround: Boolean = false
    ) {
        val hasCpr get() = cprOdd != null
    }

    private const val CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

    /** Reads [f]; DF11 gives the address only. */
    fun decode(f: Frame): Info {
        if (f.df != 17 && f.df != 18) return Info(f.icao)
        val me = f.bytes.copyOfRange(4, 11)
        fun bitsAt(from: Int, n: Int): Int { // 1-based ME bit positions, as the specs number them
            var v = 0
            for (k in 0 until n) {
                val i = from - 1 + k
                v = (v shl 1) or (((me[i / 8].toInt() and 0xff) ushr (7 - i % 8)) and 1)
            }
            return v
        }
        val tc = bitsAt(1, 5)
        return when (tc) {
            in 1..4 -> {
                val sb = StringBuilder()
                for (k in 0 until 8) sb.append(CHARS[bitsAt(9 + 6 * k, 6)])
                Info(f.icao, callsign = sb.toString().replace("#", "").trim().ifEmpty { null })
            }
            in 5..8 -> Info(f.icao, onGround = true, altitudeFt = 0)
            in 9..18 -> Info(f.icao, altitudeFt = altitude(bitsAt(9, 12)), cprOdd = bitsAt(22, 1) == 1,
                cprLat = bitsAt(23, 17), cprLon = bitsAt(40, 17))
            in 20..22 -> Info(f.icao, cprOdd = bitsAt(22, 1) == 1, cprLat = bitsAt(23, 17), cprLon = bitsAt(40, 17))
            19 -> velocity(f.icao, bitsAt(6, 3), ::bitsAt)
            else -> Info(f.icao)
        }
    }

    /** 12-bit altitude field with the Q bit set: 25 ft steps from -1000 ft. Gillham (Q = 0) is left out. */
    internal fun altitude(a: Int): Int? {
        if (a == 0 || a and 0x10 == 0) return null
        val n = ((a and 0xfe0) ushr 1) or (a and 0x0f)
        return n * 25 - 1000
    }

    private fun velocity(icao: Int, sub: Int, bitsAt: (Int, Int) -> Int): Info = when (sub) {
        1, 2 -> {
            val scale = if (sub == 2) 4 else 1
            val ew = bitsAt(15, 10); val ns = bitsAt(26, 10)
            if (ew == 0 || ns == 0) Info(icao)
            else {
                val vx = (ew - 1) * scale * (if (bitsAt(14, 1) == 1) -1 else 1)
                val vy = (ns - 1) * scale * (if (bitsAt(25, 1) == 1) -1 else 1)
                Info(icao, speedKt = hypot(vx.toDouble(), vy.toDouble()),
                    trackDeg = (Math.toDegrees(atan2(vx.toDouble(), vy.toDouble())) + 360) % 360)
            }
        }
        3, 4 -> {
            val heading = if (bitsAt(14, 1) == 1) bitsAt(15, 10) * 360.0 / 1024 else null
            val air = bitsAt(26, 10).takeIf { it != 0 }?.let { (it - 1) * (if (sub == 4) 4 else 1).toDouble() }
            Info(icao, speedKt = air, trackDeg = heading)
        }
        else -> Info(icao)
    }

    // ---- CPR position ----------------------------------------------------------------------

    private const val CPR_MAX = 131072.0

    /** Number of longitude zones at [lat] (NZ = 15). */
    internal fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a < 1e-9) return 59
        if (abs(a - 87) < 1e-9) return 2
        if (a > 87) return 1
        val t = 1 - (1 - cos(Math.PI / 30)) / (cos(Math.PI / 180 * a).let { it * it })
        return floor(2 * Math.PI / acos(t)).toInt()
    }

    private fun mod(a: Double, b: Double) = ((a % b) + b) % b

    /**
     * Global decode from an even and an odd airborne frame (at most 10 s apart); [oddNewer]
     * picks the newer one's latitude. Null when they straddle a longitude-zone boundary.
     */
    fun globalPosition(evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, oddNewer: Boolean): Pair<Double, Double>? {
        val le = evenLat / CPR_MAX; val lo = oddLat / CPR_MAX
        val j = floor(59 * le - 60 * lo + 0.5)
        var latE = 360.0 / 60 * (mod(j, 60.0) + le)
        var latO = 360.0 / 59 * (mod(j, 59.0) + lo)
        if (latE >= 270) latE -= 360
        if (latO >= 270) latO -= 360
        if (nl(latE) != nl(latO)) return null
        val lat = if (oddNewer) latO else latE
        val nlLat = nl(lat)
        val ni = maxOf(nlLat - (if (oddNewer) 1 else 0), 1)
        val m = floor(evenLon / CPR_MAX * (nlLat - 1) - oddLon / CPR_MAX * nlLat + 0.5)
        var lon = 360.0 / ni * (mod(m, ni.toDouble()) + (if (oddNewer) oddLon else evenLon) / CPR_MAX)
        if (lon >= 180) lon -= 360
        return lat to lon
    }

    /** Local decode of one airborne frame against a reference within ~300 km (the receiver). */
    fun localPosition(cprLat: Int, cprLon: Int, odd: Boolean, refLat: Double, refLon: Double): Pair<Double, Double> {
        val i = if (odd) 1 else 0
        val dLat = 360.0 / (60 - i)
        val la = cprLat / CPR_MAX
        val j = floor(refLat / dLat) + floor(0.5 + mod(refLat, dLat) / dLat - la)
        val lat = dLat * (j + la)
        val dLon = 360.0 / maxOf(nl(lat) - i, 1)
        val lo = cprLon / CPR_MAX
        val m = floor(refLon / dLon) + floor(0.5 + mod(refLon, dLon) / dLon - lo)
        var lon = dLon * (m + lo)
        if (lon >= 180) lon -= 360
        return lat to lon
    }
}
