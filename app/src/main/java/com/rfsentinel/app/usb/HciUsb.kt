package com.rfsentinel.app.usb

/*
 * The Bluetooth HCI transport of a USB Bluetooth controller, as the Linux btusb driver
 * (drivers/bluetooth/btusb.c, GPL-2.0) runs it: commands go out as class control requests on
 * endpoint 0 (alloc_ctrl_urb: bRequestType USB_TYPE_CLASS, bRequest 0, wValue 0, wIndex 0),
 * events come in on interface 0's interrupt IN endpoint one max-packet at a time and are put
 * back together by their length byte (btusb_recv_intr). The interrupt endpoint is read from the
 * moment the interface is open, as btusb_open does, so nothing the controller says is lost.
 * Only the command and event channels are used: no ACL or SCO data, nothing is ever connected to.
 */

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class HciUsb(private val conn: UsbDeviceConnection, private val evtIn: UsbEndpoint, private val tag: String) {

    /** HCI_INIT_TIMEOUT. */
    val cmdTimeoutMs = 10_000L

    /** Complete events other than advertising reports, for the command waiting on them. */
    private val events = LinkedBlockingQueue<ByteArray>(256)
    /** LE Meta events carrying advertising reports (subevents 0x02 and 0x0D), straight from the reader thread. */
    @Volatile var onAdvertising: ((ByteArray) -> Unit)? = null
    @Volatile private var stop = false
    private var reader: Thread? = null
    /** Consecutive failed control transfers: about 25 in a row means the adapter is gone. */
    @Volatile var ctlErrors = 0; private set
    @Volatile var eventsIn = 0L; private set
    /** The event endpoint fails at once, over and over: the adapter is gone or taken. */
    @Volatile private var readDead = false

    fun startReader() {
        stop = false
        reader = Thread({ readLoop() }, "usb-bt-events").also { it.isDaemon = true; it.start() }
    }

    fun close() {
        stop = true
        reader?.let { runCatching { it.join(1_000) } }
        reader = null
    }

    /** btusb_recv_intr: one event = 2-byte header (code, length) + that many bytes; a chunk can hold several. */
    private fun readLoop() {
        val size = evtIn.maxPacketSize.coerceIn(16, 1024)
        val chunk = ByteArray(size)
        var evt: java.io.ByteArrayOutputStream? = null
        var expect = 0
        var fastFails = 0
        while (!stop) {
            val t0 = System.nanoTime()
            val n = conn.bulkTransfer(evtIn, chunk, size, 200)
            if (n <= 0) {
                // A timeout takes the full 200 ms; an error comes back at once, so pause instead of spinning on it.
                if (System.nanoTime() - t0 < 50_000_000L) {
                    if (++fastFails >= 100) { readDead = true; UsbWifi.log("$tag: event endpoint keeps failing - adapter gone"); return }
                    Thread.sleep(50)
                } else fastFails = 0
                continue
            }
            fastFails = 0
            var off = 0
            var count = n
            while (count > 0) {
                if (evt == null) { evt = java.io.ByteArrayOutputStream(260); expect = 2 }
                val len = minOf(expect, count)
                evt.write(chunk, off, len)
                off += len; count -= len; expect -= len
                if (evt.size() == 2 && expect == 0) expect = chunk[off - 1].toInt() and 0xff // the header is complete: its length
                if (expect == 0) {
                    // Each chunk should hold whole events: a leftover too short for a header is a controller bug.
                    if (count in 1 until 2) { UsbWifi.log("$tag: unexpected $count-byte continuation dropped"); count = 0 }
                    deliver(evt.toByteArray())
                    evt = null
                }
            }
        }
    }

    private fun deliver(e: ByteArray) {
        eventsIn++
        if (e.size >= 3 && e[0].toInt() == 0x3E && (e[2].toInt() == 0x02 || e[2].toInt() == 0x0D)) {
            onAdvertising?.invoke(e)
            return
        }
        if (e.size >= 2 && e[0].toInt() == 0x10) UsbWifi.log("$tag: controller reported a hardware error (code ${e.getOrNull(2)?.toInt()?.and(0xff)})")
        if (!events.offer(e)) { events.poll(); events.offer(e) }
    }

    /** Sends a command without waiting for its answer (__hci_cmd_send). */
    fun send(opcode: Int, params: ByteArray = ByteArray(0)) {
        require(params.size <= 255)
        val pkt = ByteArray(3 + params.size)
        pkt[0] = opcode.toByte(); pkt[1] = (opcode shr 8).toByte(); pkt[2] = params.size.toByte()
        params.copyInto(pkt, 3)
        val r = conn.controlTransfer(0x20, 0x00, 0, 0, pkt, pkt.size, 1_000)
        if (r < 0) { ctlErrors++; throw IOException("command %04x not accepted by the adapter".format(opcode)) }
        ctlErrors = 0
    }

    /**
     * Sends a command and waits for its Command Complete (__hci_cmd_sync): returns its return
     * parameters (status first for most commands). A Command Status with an error fails it.
     */
    fun cmd(opcode: Int, params: ByteArray = ByteArray(0), timeoutMs: Long = cmdTimeoutMs): ByteArray {
        send(opcode, params)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!stop) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: break
            val code = e[0].toInt() and 0xff
            if (code == 0x0E && e.size >= 5 && op(e, 3) == opcode) return e.copyOfRange(5, e.size)
            if (code == 0x0F && e.size >= 6 && op(e, 4) == opcode) {
                val status = e[2].toInt() and 0xff
                if (status != 0) throw IOException("command %04x refused (status 0x%02x)".format(opcode, status))
            }
        }
        throw IOException("no answer to command %04x".format(opcode))
    }

    /** cmd, then its status byte must be 0. */
    fun cmdOk(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val r = cmd(opcode, params)
        if (r.isEmpty() || r[0].toInt() != 0) throw IOException("command %04x failed (status 0x%02x)".format(opcode, r.firstOrNull()?.toInt()?.and(0xff) ?: -1))
        return r
    }

    /** Vendor control-IN request on endpoint 0; null when it failed. Counts consecutive failures. */
    fun ctlIn(type: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int = 1_000): ByteArray? {
        val buf = ByteArray(length)
        val n = conn.controlTransfer(type, request, value, index, buf, length, timeoutMs)
        if (n < 0) { ctlErrors++; return null }
        ctlErrors = 0
        return buf.copyOf(n)
    }

    fun ctlOut(type: Int, request: Int, value: Int, index: Int, data: ByteArray): Boolean {
        val n = conn.controlTransfer(type, request, value, index, data, data.size, 1_000)
        if (n < 0) { ctlErrors++; return false }
        ctlErrors = 0
        return true
    }

    val unplugged get() = ctlErrors >= 25 || readDead

    companion object {
        fun op(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        fun le16(b: ByteArray, at: Int) = op(b, at)
        fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
        fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    }
}
