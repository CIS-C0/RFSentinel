package com.rfsentinel.app.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Userspace driver for the HackRF One (Great Scott Gadgets) over USB, receive only: the vendor
 * requests libhackrf sends (hackrf.c, GPL-2.0), with nothing that transmits. The transceiver is
 * only ever switched to RECEIVE or OFF; the RF amplifier and the antenna port's bias power stay off.
 *
 * The HackRF delivers signed 8-bit I/Q; [read] flips the sign bit so the samples match the
 * RTL-SDR's unsigned format and the same spectrum and ADS-B code reads both.
 */
class HackRf(private val conn: UsbDeviceConnection, private val device: UsbDevice, private val log: (String) -> Unit) : SdrDevice {

    override val label = "HackRF"
    override var sampleRate = 0; private set
    override var ctlErrors = 0; private set
    @Volatile override var lost = false; private set
    override var tunerWriteFails = 0; private set
    /** The HackRF keeps streaming through a retune: about 48 KB still holds the old frequency. */
    override val settleReads = 3

    private var intf: UsbInterface? = null
    private var rx: UsbEndpoint? = null
    private var ctlFailRun = 0
    private var centerHz = 0L

    override fun open(): Boolean {
        val i = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { f ->
            (0 until f.endpointCount).any { f.getEndpoint(it).let { e -> e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN } }
        } ?: run { log("HackRF: no bulk IN endpoint"); return false }
        if (!conn.claimInterface(i, true)) { log("HackRF: couldn't claim the USB interface"); return false }
        intf = i
        rx = (0 until i.endpointCount).map { i.getEndpoint(it) }
            .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val id = ByteArray(1)
        val board = if (ctlIn(BOARD_ID_READ, 0, 0, id) == 1) id[0].toInt() and 0xff else -1
        val ver = ByteArray(64).let { b -> val n = ctlIn(VERSION_STRING_READ, 0, 0, b); if (n > 0) String(b, 0, n, Charsets.US_ASCII) else "?" }
        log("HackRF: board ${BOARDS[board] ?: "id $board"}, firmware $ver")
        if (board < 0) return false
        setTransceiver(MODE_OFF)
        ctlOut(AMP_ENABLE, 0, 0)       // RF amplifier off: protects the front end from strong signals nearby
        ctlOut(ANTENNA_ENABLE, 0, 0)   // no bias power on the antenna port
        setSampleRate(2_048_000)
        setGain(297)
        tune(100_000_000L)
        setTransceiver(MODE_RECEIVE)
        return !lost
    }

    override fun close() {
        if (lost) return
        setTransceiver(MODE_OFF)
        intf?.let { runCatching { conn.releaseInterface(it) } }
    }

    /** Sample rate in Hz (2 - 20 MS/s), with the baseband filter set to the narrowest that passes it. */
    override fun setSampleRate(rate: Int) {
        val r = rate.coerceIn(2_000_000, 20_000_000)
        val p = ByteArray(8)
        le32(p, 0, r); le32(p, 4, 1) // freq_hz, divider
        if (ctlOutData(SAMPLE_RATE_SET, 0, 0, p) == p.size) sampleRate = r
        val bw = FILTERS.lastOrNull { it <= r * 3 / 4 } ?: FILTERS.first()
        ctlOut(BASEBAND_FILTER_BANDWIDTH_SET, bw and 0xffff, bw ushr 16)
    }

    override fun tune(hz: Long): Boolean {
        if (hz !in MIN_HZ..MAX_HZ) return false
        val p = ByteArray(8)
        le32(p, 0, (hz / 1_000_000).toInt()); le32(p, 4, (hz % 1_000_000).toInt())
        val ok = ctlOutData(SET_FREQ, 0, 0, p) == p.size
        if (ok) centerHz = hz else tunerWriteFails++
        return ok
    }

    /** Split over the LNA (0-40 dB, 8 dB steps) and the baseband VGA (0-62 dB, 2 dB steps). */
    override fun setGain(tenthsDb: Int) {
        val db = tenthsDb / 10
        val lna = ((db - 10) / 8 * 8).coerceIn(0, 40)
        val vga = ((db - lna) / 2 * 2).coerceIn(0, 62)
        val r = ByteArray(1)
        if (ctlIn(SET_LNA_GAIN, 0, lna, r) != 1 || r[0].toInt() == 0) tunerWriteFails++
        if (ctlIn(SET_VGA_GAIN, 0, vga, r) != 1 || r[0].toInt() == 0) tunerWriteFails++
    }

    override fun resetBuffer() {
        setTransceiver(MODE_OFF)
        setTransceiver(MODE_RECEIVE)
    }

    override fun read(buf: ByteArray, len: Int): Int {
        val n = conn.bulkTransfer(rx ?: return -1, buf, len, 1000)
        for (k in 0 until n) buf[k] = (buf[k].toInt() xor 0x80).toByte()
        return n
    }

    private fun setTransceiver(mode: Int) { ctlOut(SET_TRANSCEIVER_MODE, mode, 0) }

    /** Counts failed control transfers; a long unbroken run of them means the HackRF is gone. */
    private fun ctl(result: Int): Int {
        if (result < 0) { ctlErrors++; if (++ctlFailRun >= LOST_AFTER_FAILS) lost = true } else ctlFailRun = 0
        return result
    }
    private fun ctlOut(req: Int, value: Int, index: Int) = ctl(conn.controlTransfer(OUT, req, value, index, null, 0, TIMEOUT))
    private fun ctlOutData(req: Int, value: Int, index: Int, data: ByteArray) =
        ctl(conn.controlTransfer(OUT, req, value, index, data, data.size, TIMEOUT))
    private fun ctlIn(req: Int, value: Int, index: Int, data: ByteArray) =
        ctl(conn.controlTransfer(IN, req, value, index, data, data.size, TIMEOUT))

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte(); b[at + 2] = (v shr 16).toByte(); b[at + 3] = (v shr 24).toByte()
    }

    companion object {
        /** HackRF One, Jawbreaker and rad1o (Great Scott Gadgets' USB vendor ID). */
        private val IDS = setOf(0x1d50 to 0x6089, 0x1d50 to 0x604b, 0x1d50 to 0xcc15)
        fun isHackRf(vid: Int, pid: Int) = (vid to pid) in IDS

        const val MIN_HZ = 1_000_000L
        const val MAX_HZ = 6_000_000_000L

        // libhackrf vendor requests (hackrf_vendor_request).
        private const val SET_TRANSCEIVER_MODE = 1
        private const val SAMPLE_RATE_SET = 6
        private const val BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val BOARD_ID_READ = 14
        private const val VERSION_STRING_READ = 15
        private const val SET_FREQ = 16
        private const val AMP_ENABLE = 17
        private const val SET_LNA_GAIN = 19
        private const val SET_VGA_GAIN = 20
        private const val ANTENNA_ENABLE = 23
        private const val MODE_OFF = 0
        private const val MODE_RECEIVE = 1

        private const val OUT = 0x40 // vendor, host to device
        private const val IN = 0xC0  // vendor, device to host
        private const val TIMEOUT = 500
        private const val LOST_AFTER_FAILS = 6

        /** MAX2837 baseband filter bandwidths (libhackrf max2837_ft). */
        private val FILTERS = listOf(1_750_000, 2_500_000, 3_500_000, 5_000_000, 5_500_000, 6_000_000, 7_000_000, 8_000_000,
            9_000_000, 10_000_000, 12_000_000, 14_000_000, 15_000_000, 20_000_000, 24_000_000, 28_000_000)
        private val BOARDS = mapOf(0 to "Jellybean", 1 to "Jawbreaker", 2 to "HackRF One", 3 to "rad1o",
            4 to "HackRF One r9", 5 to "HackRF Pro")
    }
}
