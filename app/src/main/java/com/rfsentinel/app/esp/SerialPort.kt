package com.rfsentinel.app.esp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** A USB serial line to an ESP32 board (115200 8N1). */
interface SerialPort {
    fun write(text: String)
    /** Bytes read into [buf], 0 on timeout, negative on error. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun close()

    /** The USB-serial chips found on ESP32 boards. */
    enum class Chip { NATIVE_USB, CP210X, CH34X }

    companion object {
        fun chipOf(vid: Int, pid: Int): Chip? = when {
            vid == 0x303A -> Chip.NATIVE_USB                                    // Espressif native USB (S2, S3, C3, C5, C6...)
            vid == 0x0483 && pid == 0x5740 -> Chip.NATIVE_USB                   // Flipper Zero (USB-UART bridge to its ESP32 board)
            vid == 0x10C4 && pid == 0xEA60 -> Chip.CP210X                       // Silicon Labs CP2102 / CP2104
            vid == 0x1A86 && pid in setOf(0x7523, 0x5523, 0x55D4) -> Chip.CH34X // WCH CH340 / CH341 / CH9102
            else -> null
        }

        /** Opens [device] at 115200 baud, or returns null. */
        fun open(conn: UsbDeviceConnection, device: UsbDevice): SerialPort? =
            when (chipOf(device.vendorId, device.productId)) {
                Chip.NATIVE_USB -> CdcAcmPort(conn, device).takeIf { it.open() }
                Chip.CP210X -> Cp210xPort(conn, device).takeIf { it.open() }
                Chip.CH34X -> Ch34xPort(conn, device).takeIf { it.open() }
                null -> null
            }
    }
}

/** Shared bulk-endpoint handling. */
abstract class BulkPort(protected val conn: UsbDeviceConnection) : SerialPort {
    protected var epIn: UsbEndpoint? = null
    protected var epOut: UsbEndpoint? = null
    private val claimed = mutableListOf<UsbInterface>()

    protected fun claim(intf: UsbInterface): Boolean = conn.claimInterface(intf, true).also { if (it) claimed += intf }

    protected fun findBulk(intf: UsbInterface) {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
    }

    override fun write(text: String) {
        val out = epOut ?: return
        val b = text.toByteArray()
        conn.bulkTransfer(out, b, b.size, 1000)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val inp = epIn ?: return -1
        return conn.bulkTransfer(inp, buf, buf.size, timeoutMs).coerceAtLeast(0)
    }

    override fun close() {
        claimed.forEach { runCatching { conn.releaseInterface(it) } }
        runCatching { conn.close() }
    }
}

/** USB CDC-ACM: ESP32-S2/S3/C3/C6 native USB. DTR is raised so the firmware sees a host. */
class CdcAcmPort(conn: UsbDeviceConnection, private val device: UsbDevice) : BulkPort(conn) {
    fun open(): Boolean {
        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (comm == null) comm = intf
                UsbConstants.USB_CLASS_CDC_DATA -> if (data == null) data = intf
            }
        }
        val d = data ?: return false
        if (comm != null && !claim(comm)) return false
        if (!claim(d)) return false
        findBulk(d)
        if (epIn == null || epOut == null) return false
        val index = (comm ?: d).id
        // SET_LINE_CODING 115200 8N1, then SET_CONTROL_LINE_STATE with DTR.
        val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)
        conn.controlTransfer(0x21, 0x20, 0, index, coding, coding.size, 1000)
        conn.controlTransfer(0x21, 0x22, 0x01, index, null, 0, 1000)
        return true
    }
}

/** Silicon Labs CP210x. DTR / RTS stay low so the board's auto-reset circuit leaves it running. */
class Cp210xPort(conn: UsbDeviceConnection, private val device: UsbDevice) : BulkPort(conn) {
    fun open(): Boolean {
        val intf = device.getInterface(0)
        if (!claim(intf)) return false
        findBulk(intf)
        if (epIn == null || epOut == null) return false
        val i = intf.id
        conn.controlTransfer(0x41, 0x00, 0x0001, i, null, 0, 1000)                       // IFC_ENABLE
        val baud = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00)                            // 115200
        conn.controlTransfer(0x41, 0x1E, 0, i, baud, baud.size, 1000)                     // SET_BAUDRATE
        conn.controlTransfer(0x41, 0x03, 0x0800, i, null, 0, 1000)                        // SET_LINE_CTL 8N1
        conn.controlTransfer(0x41, 0x07, 0x0300, i, null, 0, 1000)                        // SET_MHS: DTR, RTS low
        return true
    }
}

/**
 * WCH CH340 / CH341, with the vendor requests the Linux ch341 driver uses.
 * DTR / RTS stay released so the ESP32 isn't held in reset.
 */
class Ch34xPort(conn: UsbDeviceConnection, private val device: UsbDevice) : BulkPort(conn) {
    fun open(): Boolean {
        val intf = device.getInterface(0)
        if (!claim(intf)) return false
        findBulk(intf)
        if (epIn == null || epOut == null) return false
        conn.controlTransfer(0xC0, 0x5F, 0, 0, ByteArray(2), 2, 1000)  // read version
        out(0xA1, 0, 0)                                               // serial init
        setBaud115200()
        out(0x9A, 0x2518, 0xC3)                                       // LCR: RX + TX on, 8N1
        setBaud115200()
        out(0xA4, 0xFF, 0)                                            // modem control: DTR, RTS released
        return true
    }

    private fun out(request: Int, value: Int, index: Int) =
        conn.controlTransfer(0x40, request, value, index, null, 0, 1000)

    private fun setBaud115200() {
        // ch341: factor = 0x10000 - 1532620800 / 115200 with divisor 3 (+0x80 = don't wait for a full buffer).
        val factor = 0x10000 - (1532620800L / 115200).toInt()
        out(0x9A, 0x1312, (factor and 0xFF00) or 0x83)
        out(0x9A, 0x0F2C, factor and 0xFF)
    }
}
