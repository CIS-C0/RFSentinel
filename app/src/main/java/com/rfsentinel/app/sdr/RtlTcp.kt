package com.rfsentinel.app.sdr

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * An RTL-SDR on another device, over WiFi: the rtl_tcp protocol (librtlsdr's rtl_tcp.c, also served by
 * ESP32 boards). The server opens with a 12-byte header ("RTL0", tuner type, gain count) and then streams
 * the same unsigned 8-bit I/Q as a USB dongle; each command is 1 byte plus a big-endian 32-bit value.
 * Receive only: nothing here makes the server transmit.
 */
class RtlTcp(private val host: String, private val port: Int, private val log: (String) -> Unit) : SdrDevice {

    override val label = "Network SDR"
    override var sampleRate = 0; private set
    override var ctlErrors = 0; private set
    @Volatile override var lost = false; private set
    override val tunerWriteFails = 0
    override val settleReads = 2

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    /** An odd byte left over from a read cut short: kept so I and Q never swap. */
    private var pending = -1

    override fun open(): Boolean {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.soTimeout = READ_MS
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
            socket = s; input = s.getInputStream(); output = s.getOutputStream()
            val h = ByteArray(HEADER)
            if (fill(h, 0, HEADER) < HEADER || String(h, 0, 4, Charsets.US_ASCII) != "RTL0") {
                log("Network SDR: $host:$port doesn't answer like an rtl_tcp server")
                return fail()
            }
            log("Network SDR: connected to $host:$port, tuner ${TUNERS[be32(h, 4)] ?: "type ${be32(h, 4)}"}, ${be32(h, 8)} gain steps")
        } catch (e: IOException) {
            log("Network SDR: can't connect to $host:$port (${e.javaClass.simpleName})")
            return fail()
        }
        command(AGC, 0)
        command(BIAS_TEE, 0) // no power on the antenna port
        setSampleRate(2_048_000)
        setGain(297)
        tune(100_000_000L)
        return !lost
    }

    private fun fail(): Boolean { lost = true; close(); return false }

    override fun close() {
        if (biasTee) command(BIAS_TEE, 0) // the server keeps the dongle open: no power left on its antenna
        biasTee = false
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    @Volatile override var ppm = 0.0

    /** rtl_tcp's bias-tee command (servers built from librtlsdr 0.6 or later; older ones ignore it). */
    override fun setBiasTee(on: Boolean): Boolean = command(BIAS_TEE, if (on) 1 else 0).also { if (it) biasTee = on }
    private var biasTee = false

    /** librtlsdr picks the nearest rate the dongle can make; close enough for the spectrum. */
    override fun setSampleRate(rate: Int) {
        if (command(SAMPLE_RATE, rate)) sampleRate = rate
    }

    /** Tunes to [hz] (corrected by [ppm]); the server doesn't say whether the PLL locked, so only the tuner's range is checked. */
    override fun tune(hz: Long): Boolean {
        if (hz !in MIN_HZ..MAX_HZ || !command(FREQUENCY, SdrDevice.corrected(hz, ppm).toInt())) return false
        // shortcut: skips a fixed amount for the samples still on their way from before the retune (~50 ms at 2 MS/s); measure the round trip if sweeps over slow WiFi still show the old frequency.
        skip(available() + STALE_BYTES)
        return true
    }

    /** Fixed tuner gain in tenths of a dB; the server rounds it to the tuner's nearest step. */
    override fun setGain(tenthsDb: Int) {
        command(GAIN_MODE, 1)
        command(GAIN, tenthsDb)
    }

    /** Drops what has already arrived, so the next read is fresh. */
    override fun resetBuffer() = skip(available())

    /** Fills [buf] with I/Q; fewer bytes (always an even count) if the stream stalls, -1 when nothing came or the link is gone. */
    override fun read(buf: ByteArray, len: Int): Int {
        if (lost || len < 2) return -1
        var got = 0
        if (pending >= 0) { buf[0] = pending.toByte(); pending = -1; got = 1 }
        got += fill(buf, got, len - got)
        if (got % 2 == 1) pending = buf[--got].toInt() and 0xff
        return if (got > 0) got else -1
    }

    /** Reads up to [len] bytes into [buf] at [off]; stops early on a timeout, marks [lost] when the link drops. */
    private fun fill(buf: ByteArray, off: Int, len: Int): Int {
        val inp = input ?: return 0
        var got = 0
        try {
            while (got < len) {
                val n = inp.read(buf, off + got, len - got)
                if (n < 0) { lost = true; break }
                got += n
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
            lost = true
        }
        return got
    }

    /** Bytes already received, rounded down to whole samples. */
    private fun available(): Int = runCatching { input?.available() ?: 0 }.getOrDefault(0) and 1.inv()

    private fun skip(bytes: Int) {
        val scratch = ByteArray(16_384)
        var left = bytes - if (pending >= 0) 1 else 0
        pending = -1
        while (left > 0 && !lost) {
            val n = fill(scratch, 0, minOf(left, scratch.size))
            if (n == 0) break
            left -= n
        }
    }

    private fun command(cmd: Int, value: Int): Boolean {
        val out = output ?: return false
        val b = byteArrayOf(cmd.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        return try { out.write(b); out.flush(); true } catch (_: IOException) { ctlErrors++; lost = true; false }
    }

    companion object {
        const val DEFAULT_PORT = 1234
        private const val HEADER = 12
        private const val CONNECT_MS = 5_000
        private const val READ_MS = 2_000
        internal const val STALE_BYTES = 262_144 // one rtl_tcp buffer
        private const val MIN_HZ = 24_000_000L
        private const val MAX_HZ = 1_766_000_000L
        internal const val FREQUENCY = 0x01
        internal const val SAMPLE_RATE = 0x02
        internal const val GAIN_MODE = 0x03
        internal const val GAIN = 0x04
        internal const val AGC = 0x08
        internal const val BIAS_TEE = 0x0e
        private val TUNERS = mapOf(1 to "E4000", 2 to "FC0012", 3 to "FC0013", 4 to "FC2580", 5 to "R820T", 6 to "R828D")

        private fun be32(b: ByteArray, at: Int) =
            ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

        /** "host" or "host:port" from Settings; null when empty or the port isn't a number. */
        fun parse(address: String): Pair<String, Int>? {
            val a = address.trim()
            if (a.isEmpty() || a.any { it.isWhitespace() }) return null
            val colon = a.lastIndexOf(':')
            if (colon < 0) return a to DEFAULT_PORT
            val port = a.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return a.substring(0, colon).takeIf { it.isNotEmpty() }?.let { it to port }
        }
    }
}
