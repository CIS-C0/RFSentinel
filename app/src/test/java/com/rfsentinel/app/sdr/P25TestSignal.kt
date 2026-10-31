package com.rfsentinel.app.sdr

/** P25 test signals as a transmitter builds them: frames, trellis blocks, C4FM deviation. */
object P25TestSignal {

    fun dibitsOf(v: Long, count: Int) = IntArray(count) { i -> (v ushr (2 * (count - 1 - i)) and 3L).toInt() }

    /** Frame sync 0x5575F5FF77FF as dibits. */
    val SYNC = dibitsOf(0x5575F5FF77FFL, 24)

    /** C4FM deviation per dibit: 01 → +1800 Hz, 00 → +600, 10 → -600, 11 → -1800. */
    fun hz(d: Int) = when (d) { 1 -> 1800.0; 0 -> 600.0; 2 -> -600.0; else -> -1800.0 }

    /** A block as sent: 10 bytes + inverted CRC, trellis coded, interleaved. */
    fun block(data: ByteArray): IntArray {
        val crc = P25Frame.remainder(data + byteArrayOf(0, 0)) xor 0xffff
        val bytes = data + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        val inputs = IntArray(49) { i -> if (i == 48) 0 else bytes[i / 4].toInt() shr (6 - 2 * (i % 4)) and 3 }
        var state = 0
        val sym = IntArray(49) { i -> P25Frame.NEXT[state][inputs[i]].also { state = inputs[i] } }
        val out = IntArray(98)
        for (k in 0 until 49) { out[P25Frame.SYMBOL_AT[k]] = sym[k] shr 2; out[P25Frame.SYMBOL_AT[k] + 1] = sym[k] and 3 }
        return out
    }

    /** NID + blocks with status symbols put in, padded to a demodulator frame (what follows the sync). */
    fun frame(nac: Int, duid: Int, vararg blocks: IntArray): IntArray {
        val nid = dibitsOf(P25Frame.nidCodeword(nac shl 4 or duid) shl 1, 32)
        val data = ArrayDeque((nid + blocks.flatMap { it.toList() }).toList())
        return IntArray(P25Demod.FRAME_DIBITS) { i -> if ((i + 25) % 36 == 0) 2 else data.removeFirstOrNull() ?: 0 }
    }
}
