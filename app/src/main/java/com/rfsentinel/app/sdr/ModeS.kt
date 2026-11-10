package com.rfsentinel.app.sdr

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Mode S / ADS-B on 1090 MHz from an RTL-SDR at 2.4 MS/s (6 samples per 5 half-microsecond
 * symbols), written from the published format (ICAO Annex 10 vol. IV, "The 1090 MHz Riddle").
 * The demodulator is a port of dump1090-fa's demod_2400.c (Oliver Jowett, GPL-2.0-or-later).
 * Pure Kotlin, unit-tested.
 *
 *  - [Demod] finds messages in raw 8-bit I/Q: the 8 µs preamble, then 56 or 112 bits of pulse
 *    position (a 1 is high then low), sliced at each of the five possible sample phases and the
 *    best-scoring one kept. Messages that fail the CRC are dropped, a single wrong bit in an
 *    extended squitter is corrected.
 *  - [decode] reads what's useful here: the aircraft address (DF11 all-call replies, DF17/18
 *    extended squitters), its call sign, barometric altitude, position (CPR) and ground speed.
 */
object ModeS {

    const val SAMPLE_RATE = 2_400_000
    const val FREQ_HZ = 1_090_000_000L
    /** Samples of preamble (8 µs) and of a long message's bits (112 µs). */
    private const val PREAMBLE = 19
    /** Samples a buffer must keep from the previous read so a message across two reads is found
     *  (the slicer reads up to 289 samples past a preamble's start). */
    const val OVERLAP = 300

    /** Magnitude of a full-scale signal (an I/Q amplitude of 127.5) in [magnitudes]' units. */
    const val FULL_SCALE = 255
    /** Magnitudes run 0 until this. */
    const val LEVELS = 362

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
     * Finds messages in a stream of magnitudes. Feed it consecutive buffers whose first [OVERLAP]
     * samples repeat the end of the previous buffer; a new stream needs a new Demod.
     */
    class Demod {
        var preambles = 0L; private set
        var good = 0L; private set
        var fixed = 0L; private set
        /** Where the next buffer's scan starts: past the end of a message that ran into the overlap. */
        private var resume = 0
        /** Addresses heard in a message whose CRC proves them: an all-call reply from one of them may
         *  carry an interrogator code (dump1090 does the same; from an unknown address it is too likely noise). */
        private val known = HashSet<Int>()
        private val msg = ByteArray(14)
        private val best = ByteArray(14)

        /**
         * Messages starting in [mag] from 0 until [count] - [OVERLAP] (later starts are in the next
         * buffer). [quiet], when given, counts the magnitudes of the samples outside messages, the
         * noise floor [AdsbGain] steers by.
         */
        fun scan(mag: IntArray, count: Int, out: MutableList<Frame>, quiet: IntArray? = null) {
            val last = count - OVERLAP
            if (last <= 0) return
            var lastEnd = minOf(resume, last)
            var j = lastEnd
            while (j < last) {
                if (!preamble(mag, j)) { j++; continue }
                preambles++
                // Slice the bits at each of the five sample phases they can start at; keep the best.
                var bestScore = 0; var bestLen = 0
                for (phase in 4..8) {
                    val len = slice(mag, (j + PREAMBLE) * 5 + phase, msg)
                    if (len == 0) continue
                    val s = score(msg, len)
                    if (s > bestScore) { bestScore = s; bestLen = len; System.arraycopy(msg, 0, best, 0, 14) }
                }
                if (bestScore == 0) { j++; continue }
                if (bestScore == SCORE_FIXED) {
                    val bit = ONE_BIT.getValue(syndrome(best, 112))
                    best[bit / 8] = (best[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
                    fixed++
                }
                good++
                val icao = icaoOf(best)
                if (bestScore >= SCORE_ACQ) { if (known.size >= MAX_KNOWN) known.clear(); known += icao }
                out += Frame(best.copyOf(bestLen / 8), icao, signal(mag, j + PREAMBLE, bestLen * 12 / 5))
                if (quiet != null && j > lastEnd) tally(mag, lastEnd, j, quiet)
                lastEnd = j + (bestLen + 8) * 12 / 5
                // Carry on 8 bits before its end: the next preamble can overlap a message's last bits.
                j = lastEnd - 8 * 12 / 5 + 1
            }
            if (lastEnd < last) {
                if (quiet != null) tally(mag, lastEnd, last, quiet)
                resume = 0
            } else resume = lastEnd - last
        }

        private fun tally(m: IntArray, from: Int, to: Int, into: IntArray) { for (k in from until to) into[m[k]]++ }

        /**
         * A preamble starting a fraction of a sample after [j]: pulses at 0, 1, 3.5 and 4.5 µs and quiet
         * in between and after, matched at each phase dump1090's demod_2400 checks (3 to 7 fifths).
         */
        private fun preamble(p: IntArray, j: Int): Boolean {
            val p0 = p[j]; val p1 = p[j + 1]; val p2 = p[j + 2]; val p3 = p[j + 3]; val p4 = p[j + 4]; val p5 = p[j + 5]
            val p8 = p[j + 8]; val p9 = p[j + 9]; val p10 = p[j + 10]; val p11 = p[j + 11]; val p12 = p[j + 12]
            // A rising edge 0 -> 1 and a falling edge 12 -> 13.
            if (!(p0 < p1 && p12 > p[j + 13])) return false
            val high: Int; val signal: Int; val noise: Int
            if (p1 > p2 && p2 < p3 && p3 > p4 && p8 < p9 && p9 > p10 && p10 < p11) {
                high = (p1 + p3 + p9 + p11 + p12) / 4; signal = p1 + p3 + p9; noise = p5 + p[j + 6] + p[j + 7]
            } else if (p1 > p2 && p2 < p3 && p3 > p4 && p8 < p9 && p9 > p10 && p11 < p12) {
                high = (p1 + p3 + p9 + p12) / 4; signal = p1 + p3 + p9 + p12; noise = p5 + p[j + 6] + p[j + 7] + p8
            } else if (p1 > p2 && p2 < p3 && p4 > p5 && p8 < p9 && p10 > p11 && p11 < p12) {
                high = (p1 + p3 + p4 + p9 + p10 + p12) / 4; signal = p1 + p12; noise = p[j + 6] + p[j + 7]
            } else if (p1 > p2 && p3 < p4 && p4 > p5 && p9 < p10 && p10 > p11 && p11 < p12) {
                high = (p1 + p4 + p10 + p12) / 4; signal = p1 + p4 + p10 + p12; noise = p5 + p[j + 6] + p[j + 7] + p8
            } else if (p2 > p3 && p3 < p4 && p4 > p5 && p9 < p10 && p10 > p11 && p11 < p12) {
                high = (p1 + p2 + p4 + p10 + p12) / 4; signal = p4 + p10 + p12; noise = p[j + 6] + p[j + 7] + p8
            } else return false
            if (signal * 2 < 3 * noise) return false // about 3.5 dB above the noise
            for (k in QUIET) if (p[j + k] >= high) return false
            return true
        }

        /**
         * Bits from position [u0] (in fifths of a sample) into [msg]: 56 or 112 of them, by the downlink
         * format in the first 5. Returns the length, or 0 for a format this receiver doesn't read.
         */
        private fun slice(m: IntArray, u0: Int, msg: ByteArray): Int {
            java.util.Arrays.fill(msg, 0)
            var len = 8
            var i = 0
            while (i < len) {
                // Each bit is two symbols, 12 fifths of a sample.
                val u = u0 + 12 * i
                if (bit(m, u / 5, u % 5) > 0) msg[i / 8] = (msg[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                if (++i == 8) {
                    len = when ((msg[0].toInt() and 0xff) ushr 3) { 17, 18 -> 112; 11 -> 56; else -> return 0 }
                }
            }
            return len
        }

        /** The 1-0 symbol pair starting [phase] fifths into sample [k], correlated: above 0 is a 1. */
        private fun bit(m: IntArray, k: Int, phase: Int): Int = when (phase) {
            0 -> 5 * m[k] - 3 * m[k + 1] - 2 * m[k + 2]
            1 -> 4 * m[k] - m[k + 1] - 3 * m[k + 2]
            2 -> 3 * m[k] + m[k + 1] - 4 * m[k + 2]
            3 -> 2 * m[k] + 3 * m[k + 1] - 5 * m[k + 2]
            else -> m[k] + 5 * m[k + 1] - 5 * m[k + 2] - m[k + 3]
        }

        /** How sure [msg] is, 0 for no message (dump1090's ranking, for the formats read here). */
        private fun score(msg: ByteArray, len: Int): Int {
            val s = syndrome(msg, len)
            return if (len == 112) when {
                s == 0 -> SCORE_SQUITTER
                (ONE_BIT[s] ?: -1) >= 5 -> SCORE_FIXED // a wrong bit in the format field would change the length
                else -> 0
            } else when {
                // All-call reply: parity is the interrogator code (II / SI) in the low 7 bits.
                s == 0 -> SCORE_ACQ
                s and 0xffff80 == 0 && icaoOf(msg) in known -> SCORE_IID
                else -> 0
            }
        }

        /** The pulse level of a message: its RMS, times √2 as half its samples are quiet. */
        private fun signal(m: IntArray, from: Int, n: Int): Int {
            var sum = 0L
            for (k in from until from + n) sum += m[k].toLong() * m[k]
            return sqrt(2.0 * sum / n).toInt()
        }

        private companion object {
            const val SCORE_SQUITTER = 4
            const val SCORE_FIXED = 3
            const val SCORE_ACQ = 2
            const val SCORE_IID = 1
            const val MAX_KNOWN = 20_000
            /** Preamble samples that must be quiet: between the pulse pairs and after them. */
            val QUIET = intArrayOf(5, 6, 7, 8, 14, 15, 16, 17, 18)
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
     * picks the newer one's latitude. Null when they straddle a longitude-zone boundary or don't
     * give a latitude on the globe.
     */
    fun globalPosition(evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, oddNewer: Boolean): Pair<Double, Double>? {
        val le = evenLat / CPR_MAX; val lo = oddLat / CPR_MAX
        val j = floor(59 * le - 60 * lo + 0.5)
        var latE = 360.0 / 60 * (mod(j, 60.0) + le)
        var latO = 360.0 / 59 * (mod(j, 59.0) + lo)
        if (latE >= 270) latE -= 360
        if (latO >= 270) latO -= 360
        // A bad frame pair can land off the globe (dump1090 drops these too).
        if (latE !in -90.0..90.0 || latO !in -90.0..90.0) return null
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
