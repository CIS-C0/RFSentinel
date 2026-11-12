package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tire-pressure sensors (TPMS) on 315 MHz (North America) and 433.92 MHz (Europe and others): every car's wheels send a
 * fixed sensor ID every minute or so while it drives. The same IDs turning up with you at place after place mean the
 * same car is behind you, whatever its driver's phone does.
 *
 * Pure (no Android): raw RTL-SDR I/Q in, [Reading]s out. The channel [offsetHz] from the tuned centre is mixed down and
 * integrated to [RATE] (256 kS/s, the rate rtl_433 decodes TPMS at); a burst is taken from where the power rises
 * [BURST_DB] above the noise to where it stays down for [GAP_S]. Each burst is sliced two ways, by frequency (FSK
 * sensors) and by power (OOK sensors), into chips of each protocol's width, then the protocol decoders look for their
 * frames. The protocols, field layouts and checks follow rtl_433 (GPL-2.0-or-later, Benjamin Larsson, Christian W.
 * Zuckschwerdt and others): Ford, Toyota (PMV-C210 and PMV-107J), Citroen / Peugeot, Hyundai (VDO) and Schrader
 * (GM / Chrysler, Saab / Opel, Subaru, Nissan / Renault, BMW). Every frame must pass its checksum and Manchester code.
 */
object Tpms {

    /** One sensor message: [id] as the protocol prints it (hex), what the sensor reads when the protocol says. */
    data class Reading(val protocol: String, val id: String, val kpa: Double?, val tempC: Double?) {
        /** One key per sensor, across protocols. */
        val key get() = "$protocol:$id"
    }

    const val RATE = 256_000
    /** Where the 315 MHz and 433.92 MHz sensors sit. */
    val CHANNELS = listOf(315_000_000L, 433_920_000L)
    private const val BURST_DB = 10.0
    private const val GAP_S = 1e-3
    private const val MIN_BURST_S = 2e-3
    private const val MAX_BURST_S = 60e-3
    /** 4 ms at [RATE]. */
    private const val WARM = 1024

    /**
     * Feeds of unsigned 8-bit I/Q at [sampleRate] (a multiple of [RATE]) tuned [offsetHz] below the sensors' channel; each
     * decoded message goes to [onReading]. [offsetHz] should be a multiple of [RATE] so the dongle's centre spike falls
     * on a null of the integrate-and-dump.
     */
    class Demod(sampleRate: Int, offsetHz: Double, private val onReading: (Reading) -> Unit) {
        private val decim = sampleRate / RATE
        init { require(decim >= 1 && decim * RATE == sampleRate) { "sample rate $sampleRate is not a multiple of $RATE" } }
        private var pr = 1.0; private var pi = 0.0
        private val stepR = cos(-2 * PI * offsetHz / sampleRate); private val stepI = sin(-2 * PI * offsetHz / sampleRate)
        private var accR = 0.0; private var accI = 0.0; private var accN = 0
        private var lastR = 0.0; private var lastI = 0.0
        // Power: a short average for the burst edges, the noise floor from outside bursts.
        private var smooth = 0.0; private var noise = 0.0; private var warm = 0
        private val maxLen = (MAX_BURST_S * RATE).toInt()
        private val freq = DoubleArray(maxLen); private val power = DoubleArray(maxLen); private var len = 0
        private var inBurst = false; private var quiet = 0
        private val gap = (GAP_S * RATE).toInt()
        /** Bursts looked at, and frames decoded (for the log). */
        var bursts = 0; private set
        var frames = 0; private set

        fun feed(buf: ByteArray, count: Int) {
            var k = 0
            for (s in 0 until count) {
                val i = (buf[k++].toInt() and 0xff) - 127.5
                val q = (buf[k++].toInt() and 0xff) - 127.5
                accR += i * pr - q * pi; accI += i * pi + q * pr
                val nr = pr * stepR - pi * stepI; pi = pr * stepI + pi * stepR; pr = nr
                if (++accN == decim) {
                    accN = 0
                    sample(accR, accI)
                    accR = 0.0; accI = 0.0
                    val m = 1.5 - 0.5 * (pr * pr + pi * pi); pr *= m; pi *= m
                }
            }
        }

        private fun sample(r: Double, i: Double) {
            val p = r * r + i * i
            val f = atan2(i * lastR - r * lastI, r * lastR + i * lastI)
            lastR = r; lastI = i
            smooth += (p - smooth) / 8
            // The first 4 ms: the floor is the plain average (one sample alone can be far under it).
            if (warm < WARM) { warm++; noise += (p - noise) / warm; return }
            val on = smooth > noise * ON
            if (!inBurst) {
                // The floor follows the quiet signal (slowly, and quickly down).
                noise = if (smooth < noise) noise + (smooth - noise) / 64 else noise + (smooth - noise) / 4096
                noise = noise.coerceAtLeast(1e-9)
                if (on) { inBurst = true; len = 0; quiet = 0 }
            }
            if (!inBurst) return
            if (len < maxLen) { freq[len] = f; power[len] = p; len++ }
            quiet = if (smooth > noise * OFF) 0 else quiet + 1
            if (quiet >= gap || len >= maxLen) {
                inBurst = false
                val end = len - quiet
                if (end >= MIN_BURST_S * RATE) burst(end)
                // On for longer than any sensor sends: a steady carrier (or a floor that was too low) is the new floor.
                if (quiet < gap) noise = maxOf(noise, smooth)
            }
        }

        private fun burst(n: Int) {
            bursts++
            val found = LinkedHashMap<String, Reading>()
            fun take(r: Reading) { found.putIfAbsent(r.key, r) }
            // FSK: the two tones split at the middle of the burst's frequencies (the dongle's offset doesn't matter).
            val sorted = freq.copyOf(n).also { it.sort() }
            val mid = (sorted[n / 10] + sorted[n * 9 / 10]) / 2
            val fsk = BooleanArray(n) { freq[it] > mid }
            for (polarity in listOf(false, true)) {
                decodeFsk(chips(fsk, FSK_FAST, polarity)).forEach(::take)
                decodePmv(chips(fsk, FSK_SLOW, polarity)).forEach(::take)
            }
            // OOK: on above the middle of the noise and the burst's peak (in dB).
            val p90 = power.copyOf(n).also { it.sort() }[n * 9 / 10]
            val cut = kotlin.math.sqrt(noise * p90)
            val ook = BooleanArray(n) { power[it] > cut }
            decodeOok(chips(ook, OOK_CHIP, false)).forEach(::take)
            frames += found.size
            found.values.forEach(onReading)
        }
    }

    private val ON = Math.pow(10.0, BURST_DB / 10)
    private val OFF = Math.pow(10.0, (BURST_DB - 4) / 10)
    /** Chip widths in samples at [RATE]: 52 µs (most FSK sensors), 100 µs (PMV-107J), 122 µs (Schrader OOK). */
    private const val FSK_FAST = 52e-6 * RATE
    private const val FSK_SLOW = 100e-6 * RATE
    private const val OOK_CHIP = 122e-6 * RATE

    /**
     * Sliced samples to chips [width] samples long: each run of one level is so many chips (rounded). A run shorter than
     * half a chip is a glitch and joins the run before it. [invert] flips every chip.
     */
    internal fun chips(levels: BooleanArray, width: Double, invert: Boolean): IntArray {
        val runLevel = ArrayList<Boolean>(); val runLen = ArrayList<Int>()
        var i = 0
        while (i < levels.size) {
            var j = i; while (j < levels.size && levels[j] == levels[i]) j++
            val l = j - i
            if (l < width / 2 && runLen.isNotEmpty()) runLen[runLen.size - 1] += l
            else if (runLevel.isNotEmpty() && runLevel.last() == levels[i]) runLen[runLen.size - 1] += l
            else { runLevel += levels[i]; runLen += l }
            i = j
        }
        val out = ArrayList<Int>()
        for (k in runLevel.indices) {
            val c = (runLen[k] / width).roundToInt().coerceAtLeast(1)
            repeat(c) { out += if (runLevel[k] != invert) 1 else 0 }
        }
        return out.toIntArray()
    }

    // ---- rtl_433's bit helpers (bitbuffer.c, bit_util.c), on arrays of 0 / 1 ----

    /** Where [pattern] (its first [bits] bits, MSB first) starts at or after [from]; -1 if nowhere. */
    internal fun search(b: IntArray, from: Int, pattern: Long, bits: Int): Int {
        outer@ for (s in from..b.size - bits) {
            for (k in 0 until bits) if (b[s + k] != ((pattern shr (bits - 1 - k)) and 1L).toInt()) continue@outer
            return s
        }
        return -1
    }

    /** Manchester from [start]: "01" is 1, "10" is 0, a pair alike ends it; at most [max] bits. */
    internal fun manchester(b: IntArray, start: Int, max: Int): IntArray {
        val out = ArrayList<Int>()
        var p = start
        while (p + 1 < b.size && out.size < max) {
            if (b[p] == b[p + 1]) break
            out += b[p + 1]; p += 2
        }
        return out.toIntArray()
    }

    /**
     * Differential Manchester from [start], as rtl_433's: the first long chip sets the clock, then each pair changing
     * from the last pair's level is a 1 when it holds its level and a 0 when it flips; at most [max] bits. The bits and
     * where it stopped.
     */
    internal fun diffManchester(b: IntArray, start: Int, max: Int): Pair<IntArray, Int> {
        val out = ArrayList<Int>()
        val len = minOf(b.size, start + max * 2)
        var p = start; var bit2 = 0
        fun at(i: Int) = if (i < b.size) b[i] else 0
        while (p < len) {
            val bit1 = at(p++); bit2 = at(p++); val bit3 = at(p)
            if (bit1 != bit2) {
                if (bit2 != bit3) out += 0
                else { bit2 = bit1; p -= 1; break }
            } else { bit2 = 1 - bit1; p -= 2; break }
        }
        while (p < len) {
            val bit1 = at(p++)
            if (bit1 == bit2) break
            bit2 = at(p++)
            out += if (bit1 == bit2) 1 else 0
        }
        return out.toIntArray() to p
    }

    /** [bits] bits from [from] as bytes, MSB first (the last one padded with 0). */
    internal fun bytes(b: IntArray, from: Int, bits: Int): IntArray {
        val out = IntArray((bits + 7) / 8)
        for (k in 0 until bits) if (from + k < b.size && b[from + k] == 1) out[k / 8] = out[k / 8] or (0x80 shr (k % 8))
        return out
    }

    /** CRC-8, MSB first, no reflection or final XOR (rtl_433's crc8). */
    internal fun crc8(m: IntArray, n: Int, poly: Int, init: Int): Int {
        var r = init
        for (i in 0 until n) {
            r = r xor m[i]
            repeat(8) { r = if (r and 0x80 != 0) ((r shl 1) xor poly) and 0xff else (r shl 1) and 0xff }
        }
        return r
    }

    private fun hex(v: Long, digits: Int) = "%0${digits}X".format(v)

    // ---- FSK at 52 µs: Ford, Toyota, Citroen, Hyundai VDO ----

    internal fun decodeFsk(b: IntArray): List<Reading> = ford(b) + toyota(b) + citroen(b) + hyundaiVdo(b)

    /** Ford: preamble 55 55 55 56, 64 bits Manchester: ID 4, pressure, temperature, flags, sum of bytes 0-6. */
    internal fun ford(raw: IntArray): List<Reading> {
        val b0 = inv(raw); val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaa9L, 16)
        while (at >= 0 && at + 144 <= b0.size) {
            val bits = manchester(b0, at + 16, 160)
            if (bits.size >= 64) {
                val b = bytes(bits, 0, 64)
                val sum = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6]) and 0xff
                val flags = b[6] and 0x4c
                val unknown = (if (flags == 0x08 || flags == 0x04 || flags == 0x44) 0 else flags) or (b[6] and 0x90)
                if (sum == b[7] && unknown == 0) {
                    val id = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
                    val psi = ((((b[6] and 0x20) shl 3) or b[4]) * 0.25)
                    val temp = if (b[5] and 0x80 == 0) ((b[5] and 0x7f) - 56).toDouble() else null
                    out += Reading("Ford", hex(id, 8), psi * PSI_KPA, temp)
                }
            }
            at = search(b0, at + 15, 0xaaa9L, 16)
        }
        return out
    }

    /** Toyota (PMV-C210): sync 55 3C, 72 bits differential Manchester, pressure sent twice, CRC-8 (0x07, init 0x80). */
    internal fun toyota(b0: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xa9eL, 12)
        while (at >= 0 && at + 156 <= b0.size) {
            val (bits, end) = diffManchester(b0, at + 11, 80)
            if (end - (at + 11) >= 144 && bits.size >= 72) {
                val b = bytes(bits, 0, 72)
                val p1 = ((b[4] and 0x7f) shl 1) or (b[5] shr 7)
                val temp = ((b[5] and 0x7f) shl 1) or (b[6] shr 7)
                if (crc8(b, 8, 0x07, 0x80) == b[8] && p1 == (b[7] xor 0xff)) {
                    val id = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
                    out += Reading("Toyota", hex(id, 8), (p1 * 0.25 - 7.0) * PSI_KPA, temp - 40.0)
                }
            }
            at = search(b0, at + 2, 0xa9eL, 12)
        }
        return out
    }

    /** Citroen / Peugeot (and other VDO-built): preamble 55 55 55 56, 80 bits Manchester, XOR of bytes 1-9 is 0. */
    internal fun citroen(raw: IntArray): List<Reading> {
        val b0 = inv(raw); val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaa9L, 16)
        while (at >= 0 && at + 178 <= b0.size) {
            val bits = manchester(b0, at + 16, 88)
            if (bits.size >= 80) {
                val b = bytes(bits, 0, 80)
                var x = 0; for (k in 1..9) x = x xor b[k]
                if (x == 0 && b[6] != 0 && b[7] != 0) {
                    val id = (b[1].toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
                    out += Reading("Citroen", hex(id, 8), b[6] * 1.364, b[7] - 50.0)
                }
            }
            at = search(b0, at + 2, 0xaaa9L, 16)
        }
        return out
    }

    /** Hyundai / Kia (VDO): preamble 55 55 55 56, 80 bits Manchester, CRC-8 (0x07, init 0xAA) over bytes 0-8. */
    internal fun hyundaiVdo(raw: IntArray): List<Reading> {
        val b0 = inv(raw); val out = ArrayList<Reading>()
        var at = search(b0, 0, 0xaaaaaaa9L, 32)
        while (at >= 0 && at + 80 <= b0.size) {
            val bits = manchester(b0, at + 32, 80)
            if (bits.size >= 80) {
                val b = bytes(bits, 0, 80)
                if (crc8(b, 9, 0x07, 0xaa) == b[9]) {
                    val id = (b[1].toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
                    out += Reading("Hyundai", hex(id, 8), b[6] * 1.375, b[7] - 50.0)
                }
            }
            at = search(b0, at + 2, 0xaaaaaaa9L, 32)
        }
        return out
    }

    // ---- FSK at 100 µs: PMV-107J ----

    /** Toyota PMV-107J: sync 111110, 66 bits differential Manchester, pressure twice, CRC-8 (0x13) over the realigned bytes. */
    internal fun decodePmv(b0: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        var at = search(b0, 0, 0x3eL, 6)
        while (at >= 0 && at + 67 * 2 <= b0.size) {
            val (bits, end) = diffManchester(b0, at + 6, 70)
            if (end - (at + 6) >= 67 * 2 && bits.size >= 66) {
                // 6 bits of 0 before the 66 decoded: 9 bytes.
                val b = bytes(IntArray(6) + bits, 0, 72)
                if (crc8(b, 8, 0x13, 0x00) == b[8] && b[5] == (b[6] xor 0xff)) {
                    val id = (b[0].toLong() shl 26) or (b[1].toLong() shl 18) or (b[2].toLong() shl 10) or (b[3].toLong() shl 2) or (b[4].toLong() shr 6)
                    out += Reading("PMV-107J", hex(id, 7), (b[5] - 40.0) * 2.48, b[7] - 40.0)
                }
            }
            at = search(b0, at + 2, 0x3eL, 6)
        }
        return out
    }

    // ---- OOK at ~122 µs: Schrader ----

    internal fun decodeOok(chips: IntArray): List<Reading> {
        val out = ArrayList<Reading>()
        for (row in rows(chips, ROW_GAP_PCM)) out += schraderSmd(row)
        for (row in rows(chips, ROW_GAP_MANCHESTER)) {
            val bits = zeroBit(row)
            when (bits.size) {
                68 -> out += schraderGg4(bits)
                120 -> out += schraderEg53(bits)
                61 -> out += schraderBmw(bits)
            }
        }
        return out
    }

    /** Off chips that end a message: 480 µs for the PCM ones, 3 chips for Manchester (which never stays off that long). */
    private const val ROW_GAP_PCM = 4
    private const val ROW_GAP_MANCHESTER = 3

    /** The chip stream cut where it stays off [gap] chips or more; each row starts on its first "on" chip. */
    internal fun rows(c: IntArray, gap: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < c.size) {
            while (i < c.size && c[i] == 0) i++
            if (i >= c.size) break
            var j = i; var off = 0
            while (j < c.size) { if (c[j] == 0) { if (++off >= gap) break } else off = 0; j++ }
            // Up to the last "on" chip and one "off" after it (a Manchester pair can end on it).
            val r = c.copyOfRange(i, minOf(j, c.size))
            out += r.copyOf(minOf(r.lastIndexOf(1) + 2, r.size))
            i = j + 1
        }
        return out
    }

    /**
     * rtl_433's OOK Manchester "zero bit": the first rise is a 0; then a rise mid-bit is a 0, a fall a 1. On chips:
     * an off chip before the row, then pairs (off, on) = 0, (on, off) = 1, until a pair is alike.
     */
    internal fun zeroBit(row: IntArray): IntArray {
        val c = IntArray(1) + row + IntArray(2)
        val out = ArrayList<Int>()
        var p = 0
        while (p + 1 < c.size) {
            if (c[p] == c[p + 1]) break
            out += if (c[p] == 0) 0 else 1; p += 2
        }
        return out.toIntArray()
    }

    /** Schrader (FCC MRXGG4; GM, Chrysler and others): 68 bits, a sync nibble, then flags, 28-bit ID, pressure, temperature, CRC-8 (0x07, init 0xF0). */
    internal fun schraderGg4(bits: IntArray): List<Reading> {
        val b = bytes(bits, 4, 64)
        if (crc8(b, 7, 0x07, 0xf0) != b[7]) return emptyList()
        val id = ((b[1] and 0x0f).toLong() shl 24) or (b[2].toLong() shl 16) or (b[3].toLong() shl 8) or b[4].toLong()
        return listOf(Reading("Schrader", hex(id, 7), b[5] * 2.5, b[6] - 50.0))
    }

    /** Schrader EG53MA4 (Saab, Opel, Vauxhall, Chevrolet): 120 bits, 40 sync, 24-bit ID, pressure, °F, sum of bytes 0-8. */
    internal fun schraderEg53(bits: IntArray): List<Reading> {
        val b = bytes(bits, 40, 80)
        if (b[1] == 0 && b[2] == 0 && b[4] == 0 && b[5] == 0 && b[7] == 0 && b[8] == 0) return emptyList()
        if ((b.take(9).sum() and 0xff) != b[9]) return emptyList()
        val id = (b[4].toLong() shl 16) or (b[5].toLong() shl 8) or b[6].toLong()
        return listOf(Reading("Schrader EG53MA4", hex(id, 6), b[7] * 2.5, (b[8] - 32) / 1.8))
    }

    /** Schrader BMW (MRXBC5A4): 61 bits after 0x7FFF, 24-bit ID, 9-bit kPa, a 2-bit check, temperature. */
    internal fun schraderBmw(bits: IntArray): List<Reading> {
        if (search(bits, 0, 0x7fffL, 16) != 0) return emptyList()
        val b = bytes(bits, 16, 46)
        val id = ((b[0] and 0x1f).toLong() shl 19) or (b[1].toLong() shl 11) or (b[2].toLong() shl 3) or (b[3].toLong() shr 5)
        if (id == 0L || id == 0xffffffL) return emptyList()
        var even = 0; var n = 0
        for (i in 3 until 38) if ((b[i / 8] shr (7 - i % 8)) and 1 == 1) { n++; if ((i - 3) % 2 == 0) even++ }
        if ((even + 2 * n - 1) and 3 != (((b[4] shr 3) and 1) shl 1 or ((b[4] shr 2) and 1))) return emptyList()
        val kpa = ((b[3] and 0x1f) shl 4) or (b[4] shr 4)
        val temp = (((b[4] and 0x03) shl 5) or (b[5] shr 3)) - 50
        if (kpa > 450 || temp < -40 || temp > 85) return emptyList()
        return listOf(Reading("Schrader BMW", hex(id, 6), kpa.toDouble(), temp.toDouble()))
    }

    /** Schrader SMD3MA4 (Subaru) / MRXNIS315G3 (Nissan, Infiniti, Renault): PCM, preamble tail 55 5E, 38 bits Manchester, 2-bit sum check. */
    internal fun schraderSmd(row: IntArray): List<Reading> {
        if (row.size < 94 || row.size >= 120) return emptyList()
        val at = search(row, 0, 0x555eL, 16)
        if (at < 0) return emptyList()
        val from = at + 14
        if (from + 38 * 2 > row.size) return emptyList()
        val raw = manchester(row, from, 38)
        if (raw.size != 38) return emptyList()
        val b = bytes(IntArray(38) { 1 - raw[it] }, 0, 40)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return emptyList()
        var sum = 0
        for (i in 0 until 5) sum += (b[i] and 3) + ((b[i] shr 2) and 3) + ((b[i] shr 4) and 3) + ((b[i] shr 6) and 3)
        if (sum and 3 != 1) return emptyList()
        val id = ((b[0] and 0x0f).toLong() shl 20) or (b[1].toLong() shl 12) or (b[2].toLong() shl 4) or (b[3].toLong() shr 4)
        // Subaru sends PSI × 5, Nissan PSI × 4, and nothing tells them apart: no pressure.
        return listOf(Reading("Schrader SMD3MA4", hex(id, 6), null, null))
    }

    private fun inv(b: IntArray) = IntArray(b.size) { 1 - b[it] }
    private const val PSI_KPA = 6.894757
}
