package com.rfsentinel.app.usb

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Records the raw 802.11 frames a USB WiFi adapter (or a V2X board) hears into a standard
 * .pcap file for Wireshark: radiotap link type with the channel and signal of each frame.
 * Started and stopped from Settings > External hardware; it also stops when scanning does.
 * Receive-only like the rest: it saves what was heard, nothing is ever sent.
 *
 * Frames come from each driver's own RX thread, so writes are synchronized.
 */
object PcapRecorder {

    /** Wireshark: LINKTYPE_IEEE802_11_RADIOTAP. */
    private const val LINKTYPE_RADIOTAP = 127
    /** A recording stops itself at this size (a busy street fills ~1 MB a minute). */
    const val MAX_BYTES = 200L * 1024 * 1024

    @Volatile var active = false; private set
    @Volatile var frames = 0L; private set
    @Volatile var bytes = 0L; private set
    /** The file being written, or the last one finished. */
    @Volatile var file: File? = null; private set
    /** Why the last recording stopped on its own (size limit, write error), for Settings. */
    @Volatile var stoppedBecause: String? = null; private set

    private var out: OutputStream? = null
    private var lastFlush = 0L
    private val crc = CRC32()

    @Synchronized
    fun start(f: File): Boolean {
        stop()
        return runCatching {
            f.parentFile?.mkdirs()
            val o = BufferedOutputStream(FileOutputStream(f), 64 * 1024)
            o.write(globalHeader())
            out = o; file = f; frames = 0; bytes = 24; stoppedBecause = null
            active = true
        }.isSuccess
    }

    @Synchronized
    fun stop() {
        active = false
        out?.let { runCatching { it.flush() }; runCatching { it.close() } }
        out = null
    }

    /**
     * One frame from [d] to [end] in [b], heard on [channel] (2.4 / 5 GHz channel number, or a
     * frequency in MHz for 802.11p) at [rssi] dBm (null when the adapter gave no reading).
     */
    fun frame(b: ByteArray, d: Int, end: Int, channel: Int, rssi: Int?, now: Long = System.currentTimeMillis()) {
        if (!active || end <= d || end > b.size) return
        synchronized(this) {
            val o = out ?: return
            val len = end - d
            val radiotap = radiotap(frequencyOf(channel), rssi, hasFcs(b, d, end))
            val rec = radiotap.size + len
            runCatching {
                o.write(recordHeader(now, rec))
                o.write(radiotap)
                o.write(b, d, len)
                frames++; bytes += 16 + rec
                if (now - lastFlush > 2_000) { o.flush(); lastFlush = now }
            }.onFailure { stoppedBecause = "write failed: ${it.message}"; stop(); return }
            if (bytes >= MAX_BYTES) { stoppedBecause = "reached ${MAX_BYTES / (1024 * 1024)} MB"; stop() }
        }
    }

    /** Channel number (or MHz for 802.11p) to MHz. */
    fun frequencyOf(channel: Int): Int = when {
        channel >= 2000 -> channel
        channel == 14 -> 2484
        channel in 1..13 -> 2407 + 5 * channel
        channel in 32..196 -> 5000 + 5 * channel
        else -> 0
    }

    /** Drivers that keep the frame's checksum leave 4 bytes that match its CRC-32: say so, or Wireshark calls it malformed. */
    internal fun hasFcs(b: ByteArray, d: Int, end: Int): Boolean {
        if (end - d < 28) return false
        crc.reset(); crc.update(b, d, end - d - 4)
        val v = crc.value
        return (b[end - 4].toLong() and 0xff) == (v and 0xff) &&
            (b[end - 3].toLong() and 0xff) == ((v shr 8) and 0xff) &&
            (b[end - 2].toLong() and 0xff) == ((v shr 16) and 0xff) &&
            (b[end - 1].toLong() and 0xff) == ((v shr 24) and 0xff)
    }

    internal fun globalHeader(): ByteArray = le(
        0xa1b2c3d4L to 4, // magic, microsecond timestamps
        2L to 2, 4L to 2, // version 2.4
        0L to 4, 0L to 4, // time zone, accuracy
        65535L to 4, // snapshot length
        LINKTYPE_RADIOTAP.toLong() to 4
    )

    private fun recordHeader(now: Long, len: Int): ByteArray = le(
        (now / 1000) to 4, ((now % 1000) * 1000) to 4, len.toLong() to 4, len.toLong() to 4
    )

    /** Radiotap: flags (FCS present), channel (MHz + band), and the signal in dBm when known. */
    internal fun radiotap(mhz: Int, rssi: Int?, fcs: Boolean): ByteArray {
        val present = (1L shl 1) or (1L shl 3) or (if (rssi != null) 1L shl 5 else 0L)
        val len = if (rssi != null) 15 else 14
        val chFlags = when { mhz >= 5000 -> 0x0140; mhz > 0 -> 0x00A0; else -> 0 } // 5 GHz + OFDM / 2.4 GHz + CCK
        val head = le(0L to 1, 0L to 1, len.toLong() to 2, present to 4, (if (fcs) 0x10L else 0L) to 1, 0L to 1,
            mhz.toLong() to 2, chFlags.toLong() to 2)
        return if (rssi != null) head + byteArrayOf(rssi.coerceIn(-128, 127).toByte()) else head
    }

    private fun le(vararg fields: Pair<Long, Int>): ByteArray {
        val out = ByteArray(fields.sumOf { it.second })
        var i = 0
        for ((v, n) in fields) for (k in 0 until n) out[i++] = ((v shr (8 * k)) and 0xff).toByte()
        return out
    }
}
