package com.rfsentinel.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Bulk-IN receive loop for the RTL8187 and RT3070 drivers: keeps [count] transfers of
 * [size] bytes in flight (async [UsbRequest]), or falls back to synchronous transfers
 * where the platform can't queue them. [onData] gets each completed transfer; [onTick]
 * runs at least every ~100 ms (channel hops, reports) until [stopped] says so.
 * Returns the bytes received.
 */
internal object BulkRx {

    fun run(tag: String, conn: UsbDeviceConnection, ep: UsbEndpoint, count: Int, size: Int,
            stopped: () -> Boolean, onData: (ByteArray, Int) -> Unit, onTick: (Long) -> Unit): Long {
        val buffers = Array(count) { ByteBuffer.allocateDirect(size) }
        val reqs = ArrayList<UsbRequest>(count)
        var async = true
        for (i in 0 until count) {
            val r = UsbRequest()
            if (!r.initialize(conn, ep)) { async = false; break }
            r.clientData = i
            if (!r.queue(buffers[i])) { runCatching { r.close() }; async = false; break }
            reqs.add(r)
        }
        if (!async) {
            UsbWifi.log("$tag: async RX unavailable (${reqs.size}/$count) - synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            return runSync(conn, ep, size, stopped, onData, onTick)
        }
        UsbWifi.log("$tag: async RX live - $count buffers in flight")
        val scratch = ByteArray(size)
        var total = 0L
        try {
            while (!stopped()) {
                val req = try { conn.requestWait(100L) } catch (_: TimeoutException) { null }
                if (req != null) {
                    val idx = req.clientData as? Int ?: -1
                    if (idx in reqs.indices) {
                        val b = buffers[idx]
                        val n = b.position()
                        if (n in 1..size) { b.rewind(); b.get(scratch, 0, n) }
                        b.clear()
                        if (!runCatching { req.queue(b) }.getOrDefault(false)) {
                            runCatching { req.close() }
                            val nr = UsbRequest()
                            if (runCatching { nr.initialize(conn, ep) }.getOrDefault(false)) {
                                nr.clientData = idx
                                if (runCatching { nr.queue(b) }.getOrDefault(false)) reqs[idx] = nr
                            }
                        }
                        if (n in 1..size) { total += n; runCatching { onData(scratch, n) } }
                    }
                }
                onTick(System.currentTimeMillis())
            }
        } finally {
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
        }
        return total
    }

    private fun runSync(conn: UsbDeviceConnection, ep: UsbEndpoint, size: Int, stopped: () -> Boolean,
                        onData: (ByteArray, Int) -> Unit, onTick: (Long) -> Unit): Long {
        val buf = ByteArray(size)
        var total = 0L
        while (!stopped()) {
            val n = conn.bulkTransfer(ep, buf, size, 200)
            if (n > 0) { total += n; runCatching { onData(buf, n) } }
            onTick(System.currentTimeMillis())
        }
        return total
    }

    /**
     * Receive counters for the driver log, so a tester's exported log shows what arrived,
     * what was thrown away and the signal range. Only descriptor bytes are ever logged,
     * never a frame's contents (addresses, network names). Used from the RX thread only.
     */
    class Stats(private val tag: String) {
        private val started = System.currentTimeMillis()
        private var lastLog = started
        var transfers = 0L; private set
        private var bytes = 0L; private var frames = 0L; private var noSignal = 0L
        var badChecksum = 0L
        var malformed = 0L
        private var minRssi = Int.MAX_VALUE; private var maxRssi = Int.MIN_VALUE
        private var samples = 0
        private var warnedSilent = false

        fun transfer(n: Int) { transfers++; bytes += n }

        fun frame(rssi: Int?) {
            frames++
            if (rssi == null) noSignal++ else { if (rssi < minRssi) minRssi = rssi; if (rssi > maxRssi) maxRssi = rssi }
        }

        /** The first few transfers' descriptor bytes, to check the parsing from a log. */
        fun sample(what: String, b: ByteArray, from: Int, len: Int) {
            if (samples >= 3 || from < 0) return
            samples++
            UsbWifi.log("$tag: RX sample $samples, $what: " +
                (from until minOf(from + len, b.size)).joinToString(" ") { "%02x".format(b[it]) })
        }

        /** Every 5 s for the first minute, then every 30 s. */
        fun maybeLog(now: Long, extra: () -> String) {
            if (now - lastLog < if (now - started < 60_000) 5_000 else 30_000) return
            lastLog = now
            if (transfers == 0L && !warnedSilent && now - started > 10_000) {
                warnedSilent = true
                UsbWifi.log("$tag: nothing received from the adapter in 10 s (radio or USB receive not running)")
            }
            val signal = if (maxRssi >= minRssi) "signal $minRssi..$maxRssi dBm" else "no signal readings"
            UsbWifi.log("$tag: RX $transfers transfers, ${bytes / 1024} KB: $frames frames ($noSignal without signal), " +
                "$badChecksum bad checksum, $malformed malformed, $signal | ${extra()}")
            minRssi = Int.MAX_VALUE; maxRssi = Int.MIN_VALUE
        }
    }

    /** The bulk-IN endpoint at [address], else the interface's first bulk-IN endpoint. */
    fun inEndpoint(intf: android.hardware.usb.UsbInterface, address: Int): UsbEndpoint? {
        var first: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK ||
                ep.direction != android.hardware.usb.UsbConstants.USB_DIR_IN) continue
            if (ep.address == address) return ep
            if (first == null) first = ep
        }
        return first
    }
}
