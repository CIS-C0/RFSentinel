package com.rfsentinel.app.sdr

/*
 * Userspace driver for RTL2832U "RTL-SDR" dongles with a Rafael Micro R820T / R820T2 /
 * R860 or R828D tuner (RTL-SDR Blog V3 / V4, Nooelec NESDR and most generic DVB-T
 * sticks), over Android's USB host API - no root. Receive only: it tunes, sets the gain
 * and reads raw I/Q samples, which RF Sentinel only turns into a power spectrum
 * (nothing is demodulated, decoded or recorded).
 *
 * Ported from librtlsdr (osmocom rtl-sdr: Steve Markgraf, Dimitri Stolnikov and others;
 * R82xx tuner code by Mauro Carvalho Chehab and Steve Markgraf; RTL-SDR Blog V4 support
 * by RTL-SDR Blog), GPL-2.0-or-later, used under the GNU GPL v3.
 */

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

class RtlSdr(private val conn: UsbDeviceConnection, private val device: UsbDevice, private val log: (String) -> Unit) {

    enum class Tuner { R820T, R828D }

    var tuner: Tuner? = null; private set
    /** RTL-SDR Blog V4: R828D with a 28.8 MHz crystal, its own band inputs and notch filters. */
    var blogV4 = false; private set
    var sampleRate = 0; private set
    var ctlErrors = 0; private set
    private var centerHz = 0L
    private lateinit var rx: UsbEndpoint

    // ---- R82xx tuner state ----
    private var i2cAddr = R820T_I2C_ADDR
    private var tunerXtal = RTL_XTAL
    private val regs = IntArray(NUM_REGS)
    private var intFreq = 3_570_000
    private var hasLock = false
    private var input = -1

    /** Opens the dongle: baseband, tuner detection and init. False when no supported tuner answers. */
    fun open(): Boolean {
        val intf = device.getInterface(0)
        if (!conn.claimInterface(intf, true)) log("RTL-SDR: couldn't claim the USB interface (continuing)")
        rx = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.address == 0x81 }
            ?: run { log("RTL-SDR: no bulk-IN endpoint 0x81"); return false }
        // A dummy write first, as librtlsdr does.
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        initBaseband()
        val maker = runCatching { device.manufacturerName }.getOrNull().orEmpty()
        val product = runCatching { device.productName }.getOrNull().orEmpty()
        setI2cRepeater(true)
        tuner = when {
            i2cReadReg(R820T_I2C_ADDR, 0x00) == R82XX_CHECK_VAL -> Tuner.R820T
            i2cReadReg(R828D_I2C_ADDR, 0x00) == R82XX_CHECK_VAL -> Tuner.R828D
            else -> null
        }
        blogV4 = tuner == Tuner.R828D && maker == "RTLSDRBlog" && product == "Blog V4"
        log("RTL-SDR: \"$maker $product\", tuner ${tuner ?: "not supported (E4000 / FC001x / FC2580?)"}${if (blogV4) " (RTL-SDR Blog V4)" else ""}")
        if (tuner == null) { setI2cRepeater(false); return false }
        if (tuner == Tuner.R828D) { i2cAddr = R828D_I2C_ADDR; if (!blogV4) tunerXtal = R828D_XTAL }
        // R82xx: low IF, not zero-IF; only the in-phase ADC; spectrum inversion
        demodWrite(1, 0xb1, 0x1a, 1)
        demodWrite(0, 0x08, 0x4d, 1)
        setIfFreq(R82XX_IF_FREQ)
        demodWrite(1, 0x15, 0x01, 1)
        val ok = r82xxInit()
        setI2cRepeater(false)
        if (!ok) log("RTL-SDR: tuner init failed")
        return ok
    }

    fun close() {
        runCatching {
            setI2cRepeater(true); r82xxStandby(); setI2cRepeater(false)
            writeReg(SYSB, DEMOD_CTL, 0x20, 1) // power off the demodulator and ADCs
        }
    }

    // ---- RTL2832U ----

    private fun initBaseband() {
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)
        demodWrite(1, 0x01, 0x14, 1); demodWrite(1, 0x01, 0x10, 1) // soft reset
        demodWrite(1, 0x15, 0x00, 1); demodWrite(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWrite(1, 0x16 + i, 0x00, 1)
        setFir()
        demodWrite(0, 0x19, 0x05, 1)                         // SDR mode, no DAGC
        demodWrite(1, 0x93, 0xf0, 1); demodWrite(1, 0x94, 0x0f, 1)
        demodWrite(1, 0x11, 0x00, 1)                         // no AGC
        demodWrite(1, 0x04, 0x00, 1)                         // no RF / IF AGC loop
        demodWrite(0, 0x61, 0x60, 1)                         // no PID filter
        demodWrite(0, 0x06, 0x80, 1)
        demodWrite(1, 0xb1, 0x1b, 1)                         // zero-IF, DC and IQ correction
        demodWrite(0, 0x0d, 0x83, 1)                         // no 4.096 MHz clock output
    }

    private fun setFir() {
        val fir = IntArray(20)
        for (i in 0 until 8) fir[i] = FIR_DEFAULT[i] and 0xff
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]; val v1 = FIR_DEFAULT[8 + i + 1]
            fir[8 + i * 3 / 2] = (v0 shr 4) and 0xff
            fir[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)) and 0xff
            fir[8 + i * 3 / 2 + 2] = v1 and 0xff
            i += 2
        }
        for (k in fir.indices) demodWrite(1, 0x1c + k, fir[k], 1)
    }

    private fun setIfFreq(freq: Int) {
        val ifFreq = -((freq.toLong() * (1L shl 22)) / RTL_XTAL).toInt()
        demodWrite(1, 0x19, (ifFreq shr 16) and 0x3f, 1)
        demodWrite(1, 0x1a, (ifFreq shr 8) and 0xff, 1)
        demodWrite(1, 0x1b, ifFreq and 0xff, 1)
    }

    /** Sample rate in Hz (0.9 - 3.2 MS/s), with the tuner's IF filter set to match. */
    fun setSampleRate(rate: Int) {
        var ratio = ((RTL_XTAL.toLong() * (1L shl 22)) / rate).toInt() and 0x0ffffffc
        val real = (ratio.toLong() or ((ratio.toLong() and 0x08000000L) shl 1))
        sampleRate = ((RTL_XTAL.toLong() * (1L shl 22)) / real).toInt()
        setI2cRepeater(true)
        val ifHz = r82xxSetBandwidth(sampleRate)
        setIfFreq(ifHz)
        setI2cRepeater(false)
        demodWrite(1, 0x9f, (ratio shr 16) and 0xffff, 2)
        demodWrite(1, 0xa1, ratio and 0xffff, 2)
        demodWrite(1, 0x3f, 0, 1); demodWrite(1, 0x3e, 0, 1) // no ppm correction
        demodWrite(1, 0x01, 0x14, 1); demodWrite(1, 0x01, 0x10, 1)
        if (centerHz > 0) tune(centerHz)
    }

    /** Tunes to [hz]; false when the tuner's PLL doesn't lock. */
    fun tune(hz: Long): Boolean {
        setI2cRepeater(true)
        val ok = r82xxSetFreq(hz)
        setI2cRepeater(false)
        if (ok) centerHz = hz
        return ok
    }

    /** Fixed tuner gain in tenths of a dB (e.g. 297 = 29.7 dB): a steady scale for comparing channels over time. */
    fun setGain(tenthsDb: Int) {
        setI2cRepeater(true); r82xxSetGain(tenthsDb); setI2cRepeater(false)
    }

    fun resetBuffer() {
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x0000, 2)
    }

    /** Reads raw 8-bit I/Q samples; bytes read, or a negative value on error. */
    fun read(buf: ByteArray, len: Int = buf.size): Int = conn.bulkTransfer(rx, buf, len, 1000)

    // ---- register access ----

    private fun writeArray(block: Int, addr: Int, data: ByteArray, len: Int = data.size): Int =
        conn.controlTransfer(0x40, 0, addr, (block shl 8) or 0x10, data, len, 300).also { if (it < 0) ctlErrors++ }
    private fun readArray(block: Int, addr: Int, data: ByteArray, len: Int = data.size): Int =
        conn.controlTransfer(0xC0, 0, addr, block shl 8, data, len, 300).also { if (it < 0) ctlErrors++ }

    private fun writeReg(block: Int, addr: Int, v: Int, len: Int) {
        val d = if (len == 1) byteArrayOf(v.toByte(), v.toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        writeArray(block, addr, d, len)
    }
    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val d = ByteArray(2); readArray(block, addr, d, len)
        return ((d[1].toInt() and 0xff) shl 8) or (d[0].toInt() and 0xff)
    }

    private fun demodWrite(page: Int, addr: Int, v: Int, len: Int) {
        val d = if (len == 1) byteArrayOf(v.toByte(), v.toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        if (conn.controlTransfer(0x40, 0, (addr shl 8) or 0x20, 0x10 or page, d, len, 300) < 0) ctlErrors++
        demodRead(0x0a, 0x01, 1)
    }
    private fun demodRead(page: Int, addr: Int, len: Int): Int {
        val d = ByteArray(2)
        if (conn.controlTransfer(0xC0, 0, (addr shl 8) or 0x20, page, d, len, 300) < 0) ctlErrors++
        return ((d[1].toInt() and 0xff) shl 8) or (d[0].toInt() and 0xff)
    }

    private fun setI2cRepeater(on: Boolean) = demodWrite(1, 0x01, if (on) 0x18 else 0x10, 1)

    private fun i2cReadReg(addr: Int, reg: Int): Int {
        writeArray(IICB, addr, byteArrayOf(reg.toByte()))
        val d = ByteArray(1); readArray(IICB, addr, d)
        return d[0].toInt() and 0xff
    }

    private fun setGpioOutput(gpio: Int) {
        val bit = 1 shl gpio
        writeReg(SYSB, GPD, readReg(SYSB, GPD, 1) and bit.inv(), 1)
        writeReg(SYSB, GPOE, readReg(SYSB, GPOE, 1) or bit, 1)
    }
    private fun setGpioBit(gpio: Int, on: Boolean) {
        val bit = 1 shl gpio; val r = readReg(SYSB, GPO, 1)
        writeReg(SYSB, GPO, if (on) r or bit else r and bit.inv(), 1)
    }

    // ---- R82xx (tuner_r82xx.c) ----

    private fun r82xxWrite(reg: Int, vals: IntArray) {
        // skip writes the shadow registers already hold (I2C is slow)
        val r0 = reg - REG_SHADOW_START
        if (r0 >= 0 && r0 + vals.size <= NUM_REGS && (vals.indices).all { regs[r0 + it] == vals[it] }) return
        for (i in vals.indices) { val r = r0 + i; if (r in 0 until NUM_REGS) regs[r] = vals[i] }
        var pos = 0; var addr = reg
        while (pos < vals.size) {
            val size = minOf(MAX_I2C_MSG - 1, vals.size - pos)
            val buf = ByteArray(size + 1)
            buf[0] = addr.toByte()
            for (k in 0 until size) buf[1 + k] = vals[pos + k].toByte()
            if (writeArray(IICB, i2cAddr, buf) != size + 1) log("RTL-SDR: tuner write failed at reg 0x%02x".format(addr))
            addr += size; pos += size
        }
    }
    private fun r82xxWriteReg(reg: Int, v: Int) = r82xxWrite(reg, intArrayOf(v and 0xff))
    private fun r82xxWriteMask(reg: Int, v: Int, mask: Int) {
        val cur = regs.getOrElse(reg - REG_SHADOW_START) { 0 }
        r82xxWriteReg(reg, (cur and mask.inv()) or (v and mask))
    }
    private fun r82xxRead(len: Int): IntArray {
        writeArray(IICB, i2cAddr, byteArrayOf(0))
        val d = ByteArray(len); readArray(IICB, i2cAddr, d)
        return IntArray(len) { bitrev(d[it].toInt() and 0xff) }
    }
    private fun bitrev(b: Int): Int {
        val lut = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
        return (lut[b and 0xf] shl 4) or lut[b shr 4]
    }

    private fun r82xxInit(): Boolean {
        regs.fill(0)
        r82xxWrite(0x05, INIT_ARRAY)
        if (!setTvStandard()) return false
        sysfreqSel()
        return true
    }

    private fun r82xxStandby() {
        for ((r, v) in listOf(0x06 to 0xb1, 0x05 to 0xa0, 0x07 to 0x3a, 0x08 to 0x40, 0x09 to 0xc0, 0x0a to 0x36,
                0x0c to 0x35, 0x0f to 0x68, 0x11 to 0x03, 0x17 to 0xf4, 0x19 to 0x0c)) r82xxWriteReg(r, v)
    }

    /** r82xx_set_tv_standard for the 6 MHz digital mode librtlsdr uses, including the IF filter calibration. */
    private fun setTvStandard(): Boolean {
        INIT_ARRAY.copyInto(regs)
        r82xxWriteMask(0x0c, 0x00, 0x0f)
        r82xxWriteMask(0x13, VER_NUM, 0x3f)
        r82xxWriteMask(0x1d, 0x00, 0x38)
        intFreq = 3_570_000
        var filCal = 0
        for (i in 0 until 2) {
            r82xxWriteMask(0x0b, 0x6b, 0x60)
            r82xxWriteMask(0x0f, 0x04, 0x04)
            r82xxWriteMask(0x10, 0x00, 0x03)
            setPll(56_000_000L)
            if (!hasLock) { log("RTL-SDR: tuner PLL didn't lock for the filter calibration (continuing, as librtlsdr does)"); return true }
            r82xxWriteMask(0x0b, 0x10, 0x10)
            r82xxWriteMask(0x0b, 0x00, 0x10)
            r82xxWriteMask(0x0f, 0x00, 0x04)
            filCal = r82xxRead(5)[4] and 0x0f
            if (filCal != 0 && filCal != 0x0f) break
        }
        if (filCal == 0x0f) filCal = 0
        r82xxWriteMask(0x0a, 0x10 or filCal, 0x1f)
        r82xxWriteMask(0x0b, 0x6b, 0xef)
        r82xxWriteMask(0x07, 0x00, 0x80)
        r82xxWriteMask(0x06, 0x10, 0x30)
        r82xxWriteMask(0x1e, 0x60, 0x60)
        r82xxWriteMask(0x05, 0x01, 0x80)
        r82xxWriteMask(0x1f, 0x00, 0x80)
        r82xxWriteMask(0x0f, 0x00, 0x80)
        r82xxWriteMask(0x19, 0x60, 0x60)
        return true
    }

    /** r82xx_sysfreq_sel for digital TV / SDR use. */
    private fun sysfreqSel() {
        val mixerTop = 0x24; val lnaTop = 0xe5
        r82xxWriteMask(0x1d, lnaTop, 0xc7)
        r82xxWriteMask(0x1c, mixerTop, 0xf8)
        r82xxWriteReg(0x0d, 0x53)
        r82xxWriteReg(0x0e, 0x75)
        input = 0x00
        r82xxWriteMask(0x05, 0x00, 0x60)
        r82xxWriteMask(0x06, 0x00, 0x08)
        r82xxWriteMask(0x11, 0x38, 0x38)
        r82xxWriteMask(0x17, 0x30, 0x30)
        r82xxWriteMask(0x0a, 0x40, 0x60)
        r82xxWriteMask(0x1d, 0, 0x38)
        r82xxWriteMask(0x1c, 0, 0x04)
        r82xxWriteMask(0x06, 0, 0x40)
        r82xxWriteMask(0x1a, 0x30, 0x30)
        r82xxWriteMask(0x1d, 0x18, 0x38)
        r82xxWriteMask(0x1c, mixerTop, 0x04)
        r82xxWriteMask(0x1e, 14, 0x1f)
        r82xxWriteMask(0x1a, 0x20, 0x30)
    }

    private fun setMux(loHz: Long) {
        val mhz = (loHz / 1_000_000).toInt()
        var i = 0
        while (i < FREQ_RANGES.size - 1 && mhz >= FREQ_RANGES[i + 1][0]) i++
        val r = FREQ_RANGES[i]
        r82xxWriteMask(0x17, r[1], 0x08)
        r82xxWriteMask(0x1a, r[2], 0xc3)
        r82xxWriteReg(0x1b, r[3])
        r82xxWriteMask(0x10, r[6] or 0x00, 0x0b) // XTAL_HIGH_CAP_0P, as r82xx_init selects
        r82xxWriteMask(0x08, 0x00, 0x3f)
        r82xxWriteMask(0x09, 0x00, 0x3f)
    }

    private fun setPll(freqHz: Long) {
        val freqKhz = (freqHz + 500) / 1000
        val vcoMin = 1_770_000L; val vcoMax = vcoMin * 2
        r82xxWriteMask(0x1a, 0x00, 0x0c)
        val p = IntArray(7) { regs[0x10 - REG_SHADOW_START + it] }
        p[0] = (p[0] and 0x10.inv())          // refdiv2 = 0
        p[2] = (p[2] and 0xe0.inv()) or 0x80
        var mixDiv = 2; var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv in vcoMin until vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }
        val data = r82xxRead(5)
        val vcoPowerRef = if (tuner == Tuner.R828D) 1 else 2
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum-- else if (vcoFineTune < vcoPowerRef) divNum++
        p[0] = (p[0] and 0xe0.inv()) or ((divNum shl 5) and 0xe0)
        val vcoFreq = freqHz * mixDiv
        val vcoDiv = (tunerXtal + 65536L * vcoFreq) / (2L * tunerXtal)
        val nint = (vcoDiv / 65536).toInt(); val sdm = (vcoDiv % 65536).toInt()
        if (nint > 128 / vcoPowerRef - 1) { log("RTL-SDR: no PLL values for $freqHz Hz"); hasLock = false; return }
        val ni = (nint - 13) / 4; val si = nint - 4 * ni - 13
        p[4] = (ni + (si shl 6)) and 0xff
        p[2] = (p[2] and 0x08.inv()) or (if (sdm == 0) 0x08 else 0x00)
        p[5] = sdm and 0xff; p[6] = sdm shr 8
        r82xxWrite(0x10, p)
        var lock = false
        for (i in 0 until 2) {
            if (r82xxRead(3)[2] and 0x40 != 0) { lock = true; break }
            if (i == 0) r82xxWriteMask(0x12, 0x60, 0xe0)
        }
        hasLock = lock
        if (lock) r82xxWriteMask(0x1a, 0x08, 0x08)
    }

    private fun r82xxSetFreq(hz: Long): Boolean {
        val lo = hz + intFreq // VHF / UHF only here: no HF upconversion
        setMux(lo)
        setPll(lo)
        if (!hasLock) return false
        if (blogV4) {
            // notch filters off inside the FM / DAB bands, on elsewhere; VHF or UHF input
            val openD = if (hz in 85_000_000L..112_000_000L || hz in 172_000_000L..242_000_000L) 0x00 else 0x08
            r82xxWriteMask(0x17, openD, 0x08)
            val band = if (hz < 250_000_000L) VHF else UHF
            if (band != input) {
                input = band
                r82xxWriteMask(0x06, 0x00, 0x08)               // cable 2 (HF) off
                setGpioOutput(5); setGpioBit(5, true)          // upconverter switch: not HF
                r82xxWriteMask(0x05, if (band == VHF) 0x40 else 0x00, 0x40)
                r82xxWriteMask(0x05, if (band == UHF) 0x00 else 0x20, 0x20)
            }
        } else if (tuner == Tuner.R828D) {
            val airCable1 = if (hz > 345_000_000L) 0x00 else 0x60
            if (airCable1 != input) { input = airCable1; r82xxWriteMask(0x05, airCable1, 0x60) }
        }
        return true
    }

    private fun r82xxSetGain(gain: Int) {
        r82xxWriteMask(0x05, 0x10, 0x10) // LNA auto off
        r82xxWriteMask(0x07, 0x00, 0x10) // mixer auto off
        r82xxRead(4)
        r82xxWriteMask(0x0c, 0x08, 0x9f) // fixed VGA gain (16.3 dB)
        var total = 0; var lna = 0; var mix = 0
        for (i in 0 until 15) {
            if (total >= gain) break
            total += LNA_STEPS[++lna]
            if (total >= gain) break
            total += MIXER_STEPS[++mix]
        }
        r82xxWriteMask(0x05, lna, 0x0f)
        r82xxWriteMask(0x07, mix, 0x0f)
    }

    /** r82xx_set_bandwidth; returns the IF frequency to program in the RTL2832U. */
    private fun r82xxSetBandwidth(bwIn: Int): Int {
        var bw = bwIn
        val reg0a: Int; var reg0b: Int
        if (bw > 7_000_000) { reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000 }
        else if (bw > 6_000_000) { reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000 }
        else if (bw > LPF_BW[0] + HP_BW1 + HP_BW2) { reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000 }
        else {
            reg0a = 0x00; reg0b = 0x80; intFreq = 2_300_000
            var realBw = 0
            if (bw > LPF_BW[0] + HP_BW1) { bw -= HP_BW2; intFreq += HP_BW2; realBw += HP_BW2 } else reg0b = reg0b or 0x20
            if (bw > LPF_BW[0]) { bw -= HP_BW1; intFreq += HP_BW1; realBw += HP_BW1 } else reg0b = reg0b or 0x40
            var i = 0
            while (i < LPF_BW.size && bw <= LPF_BW[i]) i++
            i--
            if (i < 0) i = 0
            reg0b = reg0b or (15 - i)
            realBw += LPF_BW[i]
            intFreq -= realBw / 2
        }
        r82xxWriteMask(0x0a, reg0a, 0x10)
        r82xxWriteMask(0x0b, reg0b, 0xef)
        return intFreq
    }

    companion object {
        /** RTL2832U dongles librtlsdr knows (the common ones; DVB-T sticks with other tuners are declined at open). */
        private val IDS = setOf(0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f,
            0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133, 0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393,
            0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
            0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103,
            0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286, 0x1f4d to 0xd803)

        fun isRtlSdr(vid: Int, pid: Int) = (vid to pid) in IDS

        private const val RTL_XTAL = 28_800_000
        private const val R828D_XTAL = 16_000_000
        private const val R820T_I2C_ADDR = 0x34
        private const val R828D_I2C_ADDR = 0x74
        private const val R82XX_CHECK_VAL = 0x69
        private const val R82XX_IF_FREQ = 3_570_000
        private const val REG_SHADOW_START = 5
        private const val NUM_REGS = 30
        private const val VER_NUM = 49
        private const val MAX_I2C_MSG = 8
        private const val VHF = 2; private const val UHF = 3
        private const val HP_BW1 = 350_000; private const val HP_BW2 = 380_000

        private const val USBB = 1; private const val SYSB = 2; private const val IICB = 6
        private const val USB_SYSCTL = 0x2000; private const val USB_EPA_CTL = 0x2148; private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000; private const val GPO = 0x3001; private const val GPOE = 0x3003
        private const val GPD = 0x3004; private const val DEMOD_CTL_1 = 0x300b

        private val FIR_DEFAULT = intArrayOf(-54, -36, -41, -40, -32, -14, 14, 53, 101, 156, 215, 273, 327, 372, 404, 421)

        private val INIT_ARRAY = intArrayOf(
            0x83, 0x32, 0x75, 0xc0, 0x40, 0xd6, 0x6c, 0xf5, 0x63, 0x75, 0x68, 0x6c, 0x83, 0x80, 0x00,
            0x0f, 0x00, 0xc0, 0x30, 0x48, 0xcc, 0x60, 0x00, 0x54, 0xae, 0x4a, 0xc0, 0x00, 0x00, 0x00) // 0x05-0x1f, then 0x20-0x22 = 0

        /** freq_ranges: start MHz, open_d, rf_mux_ploy, tf_c, xtal_cap20p, xtal_cap10p, xtal_cap0p. */
        private val FREQ_RANGES = arrayOf(
            intArrayOf(0, 0x08, 0x02, 0xdf, 0x02, 0x01, 0x00), intArrayOf(50, 0x08, 0x02, 0xbe, 0x02, 0x01, 0x00),
            intArrayOf(55, 0x08, 0x02, 0x8b, 0x02, 0x01, 0x00), intArrayOf(60, 0x08, 0x02, 0x7b, 0x02, 0x01, 0x00),
            intArrayOf(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00), intArrayOf(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            intArrayOf(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00), intArrayOf(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00), intArrayOf(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00), intArrayOf(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00), intArrayOf(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00), intArrayOf(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            intArrayOf(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00), intArrayOf(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00), intArrayOf(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00))

        private val LNA_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)
        private val LPF_BW = intArrayOf(1700000, 1600000, 1550000, 1450000, 1200000, 900000, 700000, 550000, 450000, 350000)
    }
}
