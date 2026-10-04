package com.rfsentinel.app.usb

/*
 * EXPERIMENTAL receive-only monitor mode for Atheros AR9271 USB WiFi adapters (e.g. ALFA
 * AWUS036NHA, TP-Link TL-WN722N v1), 2.4 GHz 802.11b/g/n, driven from userspace over
 * Android's USB host API - no root, no kernel driver. Loads the open ath9k_htc firmware
 * (assets/usbwifi/htc_9271-1.4.0.fw, from linux-firmware; licence in
 * LICENCE.open-ath9k-htc-firmware), sets the radio up through the firmware's WMI
 * register commands, hops the 2.4 GHz channels and reports what it hears. It never
 * transmits.
 *
 * Ported from Wardrive Go by RocketGod (https://github.com/RocketGod-git/wardrive-go,
 * GPL-3.0), which follows the Linux ath9k_htc driver. Changes here, checked against the
 * kernel: the signal is the radio's reading plus the noise floor (it is relative to it),
 * the FCS is removed from each frame, packets that span two USB transfers are put back
 * together, WMI answers are matched to their command (events are skipped), a firmware
 * that is already running is reused instead of re-uploaded, the official firmware build
 * is used, and its diagnostics-only steps and the handshake / PMKID capture were removed.
 */

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

object Ar9271Monitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val HOP = intArrayOf(1, 6, 11, 3, 9, 2, 7, 13, 4, 10, 5, 8, 12)
    private const val DWELL_MS = 500L
    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L

    private const val FW_DOWNLOAD = 0x30; private const val FW_DOWNLOAD_COMP = 0x31
    private const val FW_ADDR = 0x501000; private const val FW_TEXT_ADDR = 0x903000; private const val FW_BLOCK = 4096
    private const val RX_STREAM_TAG = 0x4e00
    private const val RX_BUF = 16384
    private const val HTC_RX_STATUS = 40
    private const val FCS_LEN = 4
    private const val RX_FILTER_MONITOR = 0x0018ffff
    private const val RSSI_BAD = -128

    // WMI commands (wmi.h)
    private const val WMI_GET_FW_VERSION = 3; private const val WMI_DISABLE_INTR = 4; private const val WMI_ENABLE_INTR = 5
    private const val WMI_ATH_INIT = 6; private const val WMI_DRAIN_TXQ_ALL = 11; private const val WMI_START_RECV = 12
    private const val WMI_STOP_RECV = 13; private const val WMI_FLUSH_RECV = 14; private const val WMI_SET_MODE = 15
    private const val WMI_NODE_CREATE = 16; private const val WMI_VAP_CREATE = 19; private const val WMI_REG_READ = 20
    private const val WMI_REG_WRITE = 21; private const val WMI_TARGET_IC_UPDATE = 24; private const val WMI_REG_RMW = 32

    private lateinit var conn: UsbDeviceConnection
    private lateinit var regOut: UsbEndpoint
    private lateinit var regIn: UsbEndpoint
    private var wmiEp = 1
    private var seq = 0
    @Volatile private var curChannel = 6
    /** The receiver's noise floor in dBm: signal readings are relative to it. */
    private var noiseFloor = -95
    @Volatile private var ctlErrors = 0
    private var carry: ByteArray? = null

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, chipName: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear(); ctlErrors = 0; carry = null; noiseFloor = -95
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(device) ?: run { onStatus("$chipName: couldn't reopen adapter"); running = false; return }
        conn = c
        try {
            val intf = device.getInterface(0)
            if (!c.claimInterface(intf, true)) UsbWifi.log("AR9271: couldn't claim the USB interface (continuing)")
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            val rx = eps.firstOrNull { it.address == 0x82 }
            val inEp = eps.firstOrNull { it.address == 0x83 }; val outEp = eps.firstOrNull { it.address == 0x04 }
            UsbWifi.log("=== AR9271 bring-up (ath9k_htc, experimental) ===")
            if (rx == null || inEp == null || outEp == null) { onStatus("$chipName: unexpected USB endpoints"); UsbWifi.log("AR9271: endpoints 0x82 / 0x83 / 0x04 not all present"); return }
            regIn = inEp; regOut = outEp
            val t0 = System.currentTimeMillis()

            // ---- firmware ----
            onStatus("$chipName · starting firmware…")
            var ready = awaitReady(1500)
            var controlEp: Int? = null
            if (ready == null) {
                // No READY: either the boot ROM is waiting for firmware, or the firmware is
                // already running from an earlier scan (it says READY only once, at boot).
                controlEp = connectService(0x0100, 3, 4)
                if (controlEp != null) UsbWifi.log("AR9271: firmware already running (from an earlier scan) - reusing it")
                else {
                    if (!uploadFirmware(ctx)) { onStatus("$chipName: firmware upload failed (replug)"); return }
                    ready = awaitReady(5000)
                    if (ready == null) {
                        UsbWifi.log("AR9271: no HTC READY after the firmware upload")
                        onStatus("$chipName: firmware didn't start (replug)"); return
                    }
                }
            }
            val credits = ready?.let { ((it[10].toInt() and 0xff) shl 8) or (it[11].toInt() and 0xff) } ?: 33
            UsbWifi.log("AR9271: firmware ready${if (ready != null) " (HTC READY, $credits credits)" else ""}")

            // ---- HTC services: WMI control, then the data services in ath9k_htc's order ----
            (controlEp ?: connectService(0x0100, 3, 4))?.let { wmiEp = it }
                ?: UsbWifi.log("AR9271: no answer to the WMI connect - trying endpoint $wmiEp")
            for (svc in intArrayOf(0x0101, 0x0102, 0x0103, 0x0104, 0x0107, 0x0108, 0x0106, 0x0105)) connectService(svc, 2, 1)
            send(byteArrayOf(0, 0, 0, 4, 0, 0, 0, 0, 0, 5, 1, (credits and 0xff).toByte())) // config pipe
            readIn(1000)
            send(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0, 0, 4)) // setup complete
            readIn(1000)
            wmi(WMI_GET_FW_VERSION)?.takeIf { it.size >= 16 }?.let {
                UsbWifi.log("AR9271: firmware version %d.%d, WMI endpoint %d".format(be16(it, 12), be16(it, 14), wmiEp))
            }

            onStatus("$chipName · starting radio…")
            if (!initRadio()) { onStatus("$chipName: radio didn't start (replug)"); return }
            UsbWifi.log("AR9271: started in ${System.currentTimeMillis() - t0} ms, noise floor $noiseFloor dBm, USB errors $ctlErrors - monitor live ch $curChannel")
            onStatus("$chipName · live · 2.4 GHz monitor")
            rxLoop(chipName, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("AR9271: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    // ---- firmware / HTC ----

    private fun uploadFirmware(ctx: Context): Boolean {
        val fw = ctx.assets.open("usbwifi/htc_9271-1.4.0.fw").use { it.readBytes() }
        UsbWifi.log("AR9271: uploading firmware (${fw.size} B)")
        var addr = FW_ADDR; var off = 0
        while (off < fw.size) {
            val len = minOf(FW_BLOCK, fw.size - off)
            if (conn.controlTransfer(0x40, FW_DOWNLOAD, addr ushr 8, 0, fw.copyOfRange(off, off + len), len, 2000) < 0) {
                UsbWifi.log("AR9271: firmware upload failed at $off B"); return false
            }
            off += len; addr += len
        }
        // The chip starts the firmware right away; a negative answer here is normal.
        conn.controlTransfer(0x40, FW_DOWNLOAD_COMP, FW_TEXT_ADDR ushr 8, 0, null, 0, 2000)
        return true
    }

    /** The firmware's HTC READY message (endpoint 0, message 1), or null. */
    private fun awaitReady(ms: Int): ByteArray? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !stop) {
            val m = readIn(300) ?: continue
            if (m.size >= 16 && m[0].toInt() == 0 && be16(m, 8) == 1) return m
        }
        return null
    }

    /** HTC connect-service; returns the endpoint the firmware assigned, or null without an answer. */
    private fun connectService(svc: Int, dlPipe: Int, ulPipe: Int): Int? {
        send(byteArrayOf(0, 0, 0, 10, 0, 0, 0, 0, 0, 2, (svc ushr 8).toByte(), svc.toByte(), 0, 0, dlPipe.toByte(), ulPipe.toByte(), 0, 0))
        val end = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < end) {
            val r = readIn(300) ?: continue
            if (r.size >= 14 && r[0].toInt() == 0 && be16(r, 8) == 3) {
                val status = r[12].toInt() and 0xff; val ep = r[13].toInt() and 0xff
                if (status != 0) UsbWifi.log("AR9271: service 0x%04x: status %d".format(svc, status))
                return ep
            }
        }
        return null
    }

    private fun send(m: ByteArray) { if (conn.bulkTransfer(regOut, m, m.size, 1000) < 0) ctlErrors++ }

    private fun readIn(ms: Int): ByteArray? {
        val buf = ByteArray(maxOf(regIn.maxPacketSize, 512))
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !stop) {
            val n = conn.bulkTransfer(regIn, buf, buf.size, 300)
            if (n > 0) return buf.copyOf(n)
            if (n < 0 && System.currentTimeMillis() + 300 >= end) break
        }
        return null
    }

    /**
     * A WMI command and its answer: the next non-event message (events have bit 0x1000 in
     * their id) on the WMI endpoint that carries this command's sequence number or id.
     */
    private fun wmi(cmd: Int, payload: ByteArray = ByteArray(0), waitMs: Int = 800): ByteArray? {
        seq = (seq + 1) and 0xffff
        val m = ByteArray(12 + payload.size)
        m[0] = wmiEp.toByte()
        val plen = 4 + payload.size
        m[2] = (plen ushr 8).toByte(); m[3] = plen.toByte()
        m[8] = (cmd ushr 8).toByte(); m[9] = cmd.toByte()
        m[10] = (seq ushr 8).toByte(); m[11] = seq.toByte()
        payload.copyInto(m, 12)
        send(m)
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end && !stop) {
            val r = readIn(300) ?: continue
            if (r.size < 12 || r[0].toInt() == 0) continue // HTC control message
            val id = be16(r, 8)
            if (id and 0x1000 == 0 && (be16(r, 10) == seq || id == cmd)) return r
        }
        return null
    }

    private fun regRead(reg: Int): Long? {
        val r = wmi(WMI_REG_READ, be32(reg), 1200) ?: run { ctlErrors++; return null }
        if (r.size < 16) return null
        return ((r[12].toLong() and 0xff) shl 24) or ((r[13].toLong() and 0xff) shl 16) or ((r[14].toLong() and 0xff) shl 8) or (r[15].toLong() and 0xff)
    }
    private fun regWrite(reg: Int, v: Int) { if (wmi(WMI_REG_WRITE, be32(reg) + be32(v)) == null) ctlErrors++ }
    private fun regRmw(reg: Int, set: Int, clr: Int) { if (wmi(WMI_REG_RMW, be32(reg) + be32(set) + be32(clr), 600) == null) ctlErrors++ }
    private fun field(reg: Int, mask: Long, shift: Int, v: Int) = regRmw(reg, ((v.toLong() shl shift) and mask).toInt(), mask.toInt())

    /** Register writes in batches of 16, as ath9k_htc does. */
    private fun writeArray(arr: LongArray) {
        val n = arr.size / 2
        var i = 0
        while (i < n && !stop) {
            val batch = minOf(16, n - i)
            val p = ByteArray(batch * 8)
            for (j in 0 until batch) {
                be32(arr[(i + j) * 2].toInt()).copyInto(p, j * 8)
                be32(arr[(i + j) * 2 + 1].toInt()).copyInto(p, j * 8 + 4)
            }
            if (wmi(WMI_REG_WRITE, p, 400) == null) ctlErrors++
            i += batch
        }
    }

    // ---- radio ----

    private fun initRadio(): Boolean {
        wmi(WMI_STOP_RECV); wmi(WMI_DISABLE_INTR)
        sleep(120)
        val srev = regRead(0x4020) ?: run { UsbWifi.log("AR9271: firmware isn't answering register reads"); return false }
        val id = (srev and 0xFF).toInt()
        val macVer = if (id == 0xFF) ((srev ushr 18) and 0x3FFF).toInt() else ((srev ushr 4) and 0xF).toInt()
        UsbWifi.log("AR9271: SREV 0x%08x, MAC version 0x%x%s".format(srev, macVer, if (macVer == 0x140) " (AR9271)" else " - not an AR9271?"))

        // wake the chip
        regWrite(0x704c, 0x3); regWrite(0x4000, 0x1); regWrite(0x7040, 0x0); sleep(3)
        regWrite(0x4000, 0x0); regWrite(0x7040, 0x1)
        var woke = false
        for (i in 0 until 12) { if ((regRead(0x7044) ?: 0L) and 0xfL == 0x2L) { woke = true; break }; sleep(5) }
        if (!woke) UsbWifi.log("AR9271: RTC didn't report on (continuing)")

        // RF reset, warm reset, PLL
        regWrite(0x50044, 0x20); sleep(50)
        regWrite(0x704c, 0x3)
        if ((regRead(0x4028) ?: 0L) and 0x3000L != 0L) { regWrite(0x402c, 0x0); regWrite(0x4000, 0x101) } else regWrite(0x4000, 0x1)
        regWrite(0x7000, 0x1); sleep(2); regWrite(0x7000, 0x0)
        for (i in 0 until 10) { if ((regRead(0x7000) ?: 3L) and 0x3L == 0L) break; sleep(3) }
        regWrite(0x4000, 0x0)
        regWrite(0x7014, 0x142c); sleep(1)
        regWrite(0x50040, 0x304); regWrite(0x7048, 0x2); sleep(2)
        regWrite(0x50044, 0x4000); sleep(50) // GATE_MAC_CTL

        // PHY / MAC init tables (2.4 GHz HT20)
        writeArray(Ar9271Tables.modes2g)
        writeArray(Ar9271Tables.common)
        writeArray(Ar9271Tables.txGain2g)
        val chk = regRead(0x1030)
        UsbWifi.log("AR9271: init tables written, check 0x1030 = 0x%08x (%s)".format(chk ?: -1L, if (chk == 0x160L) "ok" else "unexpected"))

        // ANI detection thresholds
        for (rv in arrayOf(intArrayOf(0x9850, 0x6d4000e2), intArrayOf(0x985c, 0x3137605e), intArrayOf(0x9858, 0x7ec84d2e),
                intArrayOf(0x986c, 0x06903881), intArrayOf(0x9868, 0x5ac640d0), intArrayOf(0x9924, 0xd00a800d.toInt()),
                intArrayOf(0x99c0, 0x05eea6d4))) regWrite(rv[0], rv[1])
        val cck = regRead(0xa208) ?: 0L
        regWrite(0xa208, ((0x803e68c8L and 0x3fL) or (cck and 0x3fL.inv())).toInt())
        regWrite(0x99a4, 0x1); regWrite(0xa39c, 0x1) // RX / calibration chain masks
        regWrite(0xA200, 0x4); regWrite(0x9804, 0x3c0)
        regWrite(0x8058, 0x0); regWrite(0x0080, -1); regWrite(0x8018, 0x7)

        // board values from the EEPROM (antenna switch, thresholds), only when it's readable
        var mac = ByteArray(6)
        val eepStat = regRead(0x407c)
        if (eepStat != null && eepStat and 0x000F0000L == 0L) {
            readEeprom4k()?.let { e -> mac = e.mac; applyBoardValues(e) } ?: UsbWifi.log("AR9271: EEPROM unreadable - keeping the default board values")
        } else UsbWifi.log("AR9271: EEPROM busy or absent (0x%08x) - keeping the default board values".format(eepStat ?: -1L))

        // channel, queues, DMA, interrupts
        setSynth(curChannel)
        for (i in 0..9) regWrite(0x1000 + (i shl 2), 1 shl i)
        regWrite(0x8004, ((regRead(0x8004) ?: 0L) or 0x20000000L).toInt())
        regWrite(0x4024, ((regRead(0x4024) ?: 0L) or 0x4L).toInt())
        regWrite(0x0030, (((regRead(0x0030) ?: 0L) and 0x7L.inv()) or 5L).toInt())
        regWrite(0x0034, (((regRead(0x0034) ?: 0L) and 0x7L.inv()) or 5L).toInt())
        regWrite(0x8114, 0x200)
        regWrite(0x00a4, 0x010f0000); regWrite(0x00a8, 0x010f0000); regWrite(0x00ac, 0x00800000)
        regWrite(0x00a0, 0x81800964.toInt()); regWrite(0x4028, -1); regWrite(0x402c, 0x00023f60); regWrite(0x4034, 0)
        regWrite(0x981c, 0x1); sleep(5) // activate the PHY

        // AGC offset and noise floor calibration
        regWrite(0x9860, ((regRead(0x9860) ?: 0L) or 0x1L).toInt())
        val agcCal = waitClear(0x9860, 0x1L, 40)
        val agc = regRead(0x9860) ?: 0L
        regWrite(0x9860, ((agc or 0x8000L) and 0x20000L.inv() or 0x2L).toInt())
        val nfCal = waitClear(0x9860, 0x2L, 60)
        sleep(300)
        val cca = regRead(0x9864) ?: 0L
        var nf = ((cca and 0x1FF00000L) shr 20).toInt(); if (nf and 0x100 != 0) nf -= 0x200
        if (nf in -127..-90) noiseFloor = nf
        UsbWifi.log("AR9271: calibration agc %b, noise floor %b: %d dBm%s".format(agcCal, nfCal, nf,
            if (nf > -116) " (high - the receiver may be deaf)" else ""))

        // start receiving
        regWrite(0x8004, (((regRead(0x8004) ?: 0L) and (0x10000L or 0x20000L).inv()) or 0x10000000L).toInt())
        regWrite(0x0014, 0x0a) // USB byte order
        wmi(WMI_FLUSH_RECV); wmi(WMI_SET_MODE, byteArrayOf(0, 1)); wmi(WMI_ATH_INIT); wmi(WMI_START_RECV)
        rxFilter()
        wmi(WMI_TARGET_IC_UPDATE, byteArrayOf(0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 1, 0))
        wmi(WMI_VAP_CREATE, byteArrayOf(0, 1) + mac + byteArrayOf(0, 9, 0, 0))
        wmi(WMI_NODE_CREATE, mac + ByteArray(6) + byteArrayOf(0, 0, 1, 0, 0, 0, 0, 0xff.toByte()))
        wmi(WMI_SET_MODE, byteArrayOf(0, 1)); wmi(WMI_ENABLE_INTR)
        aniSet()
        // ath9k_htc re-arms the receiver around a channel set; doing it twice settles it
        repeat(2) { rearm(); sleep(150) }
        return true
    }

    private fun rxFilter() {
        regWrite(0x0008, 0x4)
        regWrite(0x803c, RX_FILTER_MONITOR)
        regWrite(0x8040, -1); regWrite(0x8044, -1)
        regWrite(0x8048, ((regRead(0x8048) ?: 0L) and (0x20L or 0x02000000L).inv()).toInt())
    }

    private fun rearm() {
        wmi(WMI_DISABLE_INTR); wmi(WMI_DRAIN_TXQ_ALL); wmi(WMI_STOP_RECV)
        rxFilter()
        wmi(WMI_START_RECV); wmi(WMI_SET_MODE, byteArrayOf(0, 1)); wmi(WMI_ENABLE_INTR)
    }

    private fun hop(ch: Int) {
        wmi(WMI_DISABLE_INTR); wmi(WMI_DRAIN_TXQ_ALL); wmi(WMI_STOP_RECV)
        setSynth(ch)
        regWrite(0x9804, 0x3c0)
        rxFilter()
        wmi(WMI_START_RECV); wmi(WMI_SET_MODE, byteArrayOf(0, 1)); wmi(WMI_ENABLE_INTR)
        curChannel = ch
    }

    /** AR9271 synthesizer for a 2.4 GHz channel, and the matching delta slope. */
    private fun setSynth(ch: Int) {
        val freq = (if (ch == 14) 2484 else 2407 + ch * 5).toLong()
        val chanSel = (freq * 0x10000L) / 15L
        val old = regRead(0x9874) ?: 0L
        regWrite(0x9874, ((old and 0xc0000000L) or 0x30000000L or chanSel).toInt())
        setDeltaSlope(freq.toInt())
    }

    private fun setDeltaSlope(mhz: Int) {
        fun vals(cs: Int): Pair<Int, Int> {
            var e = 31
            while (e > 0) { if (((cs shr e) and 1) == 1) break; e-- }
            e = 14 - (e - 24)
            val man = cs + (1 shl (24 - e - 1))
            return Pair(man shr (24 - e), e - 16)
        }
        val cs = (0x64000000L / mhz).toInt()
        val (m1, e1) = vals(cs)
        var v = regRead(0x9814) ?: 0L
        v = (v and 0xFFFE0000L.inv()) or ((m1.toLong() shl 17) and 0xFFFE0000L)
        v = (v and 0x0001E000L.inv()) or ((e1.toLong() shl 13) and 0x0001E000L)
        regWrite(0x9814, v.toInt())
        val (m2, e2) = vals((9 * cs) / 10)
        var h = regRead(0x99D0) ?: 0L
        h = (h and 0x0007FFF0L.inv()) or ((m2.toLong() shl 4) and 0x0007FFF0L)
        h = (h and 0x0000000FL.inv()) or (e2.toLong() and 0x0000000FL)
        regWrite(0x99D0, h.toInt())
    }

    /** ANI: OFDM weak-signal detection on, first-step and spur immunity at their lowest. */
    private fun aniSet() {
        field(0x9858, 0x0003F000L, 12, 0); field(0x9840, 0x00000FC0L, 6, 0)
        field(0x9924, 0x000000FEL, 1, 2); field(0x99BC, 0x0000FE00L, 9, 1)
    }

    private fun waitClear(reg: Int, mask: Long, tries: Int): Boolean {
        for (i in 0 until tries) { val v = regRead(reg) ?: return false; if (v and mask == 0L) return true; sleep(3) }
        return false
    }

    private class Eep4k(val mac: ByteArray, val antCtrlChain0: Long, val antCtrlCommon: Long, val switchSettling: Int,
                        val adcDesiredSize: Int, val txEndToRxOn: Int, val thresh62: Int, val modalVersion: Int,
                        val antdivCtl1: Int, val antdivCtl2: Int, val txGainType: Int)

    /** The 4K EEPROM's base and modal header (words 64-111) through the 0x2000 window. */
    private fun readEeprom4k(): Eep4k? {
        val b = ByteArray(96)
        for (i in 0 until 48) {
            val v = regRead(0x2000 + ((64 + i) shl 2)) ?: return null
            b[i * 2] = v.toByte(); b[i * 2 + 1] = (v ushr 8).toByte()
        }
        fun u8(o: Int) = b[o].toInt() and 0xff
        fun le16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun le32(o: Int) = (le16(o).toLong() or (le16(o + 2).toLong() shl 16)) and 0xffffffffL
        val version = le16(4)
        if ((version shr 12) != 0xE) { UsbWifi.log("AR9271: EEPROM version 0x%04x unexpected".format(version)); return null }
        val m = 32 + 20
        val e = Eep4k(b.copyOfRange(12, 18), le32(m), le32(m + 4), u8(m + 9), b[m + 12].toInt(), u8(m + 16), u8(m + 18),
            u8(m + 37), (u8(m + 39) shr 4) and 0xF, (u8(m + 41) shr 4) and 0xF, u8(31))
        UsbWifi.log("AR9271: EEPROM 4K version 0x%04x, MAC %02x:%02x:%02x:xx:xx:xx, antenna 0x%08x / 0x%08x, modal %d, TX gain %s".format(
            version, e.mac[0], e.mac[1], e.mac[2], e.antCtrlChain0, e.antCtrlCommon, e.modalVersion, if (e.txGainType == 1) "high" else "normal"))
        return e
    }

    private fun applyBoardValues(e: Eep4k) {
        regWrite(0x9964, e.antCtrlCommon.toInt())
        regWrite(0x9960, e.antCtrlChain0.toInt())
        if (e.modalVersion >= 3) {
            val a1 = e.antdivCtl1; val a2 = e.antdivCtl2
            val v = ((a1.toLong() shl 24) and 0x01000000L) or ((a2.toLong() shl 25) and 0x06000000L) or
                (((a2 shr 2).toLong() shl 27) and 0x18000000L) or (((a1 shr 1).toLong() shl 29) and 0x20000000L) or
                (((a1 shr 2).toLong() shl 30) and 0x40000000L)
            regRmw(0x99ac, v.toInt(), 0x7f000000)
            regRmw(0xa208, ((a1 shr 3) shl 13) and 0x2000, 0x2000)
        }
        field(0x9844, 0x00003F80L, 7, e.switchSettling)
        field(0x9850, 0x000000FFL, 0, e.adcDesiredSize and 0xFF)
        field(0x9828, 0x00FF0000L, 16, e.txEndToRxOn)
        field(0x9864, 0x000FF000L, 12, e.thresh62)
        field(0x99b8, 0x000000FFL, 0, e.thresh62)
        if (e.txGainType != 1) writeArray(Ar9271Tables.txGainNormal2g)
    }

    // ---- receive ----

    private class RxState { var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0 }

    private fun rxLoop(chipName: String, ep: UsbEndpoint, onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0
        curChannel = HOP[0]; runCatching { hop(HOP[0]) }
        val stats = BulkRx.Stats("AR9271")
        val total = BulkRx.run("AR9271", conn, ep, 8, RX_BUF, { stop }, { b, n ->
            stats.transfer(n)
            stats.sample("$n bytes, stream header + HTC header + RX status", b, 0, 4 + 8 + 16)
            val data = carry?.let { it + b.copyOf(n) } ?: b.copyOf(n)
            carry = forEachFrame(data, data.size, noiseFloor,
                onBad = { crc -> if (crc) stats.badChecksum++ else stats.malformed++ }) { s, e, rssi ->
                stats.frame(rssi)
                frames.frame(data, s, e, curChannel, rssi)
            }
        }) { now ->
            stats.maybeLog(now) {
                val (aps, clients) = frames.live(now, REPORT_MS)
                "ch $curChannel, $aps access points, $clients devices, USB errors $ctlErrors"
            }
            if (now - st.lastEmit > 2000) { emit(onFrame); st.lastEmit = now }
            if (now - st.hopAt > DWELL_MS) {
                st.hopIdx = (st.hopIdx + 1) % HOP.size
                runCatching { hop(HOP[st.hopIdx]) }
                val (aps, clients) = frames.live(now, REPORT_MS)
                onStatus("$chipName · live · $aps access points · $clients devices · ch $curChannel")
                st.hopAt = now
            }
        }
        emit(onFrame)
        UsbWifi.log("AR9271: RX loop stopped ($total B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    /**
     * The firmware's RX stream: packets of | length (le16) | tag 0x4e00 (le16) | HTC header (8) |
     * RX status (40) | frame + FCS | (HTC trailer) |, each padded to 4 bytes; one can continue in
     * the next transfer. [onFrame] gets each good frame's [start, end) without the FCS and its
     * signal (RX status reading + [noiseFloor]); bad checksums and broken packets go to [onBad].
     * Returns the bytes of an unfinished last packet, to put in front of the next transfer.
     */
    internal fun forEachFrame(b: ByteArray, n: Int, noiseFloor: Int, onBad: (Boolean) -> Unit = {},
                              onFrame: (Int, Int, Int?) -> Unit): ByteArray? {
        fun u8(i: Int) = b[i].toInt() and 0xff
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        var off = 0
        while (off + 4 <= n) {
            if (le16(off + 2) != RX_STREAM_TAG) { onBad(false); return null } // lost sync: drop the rest, like ath9k_htc
            val pktLen = le16(off)
            if (pktLen > 2 * RX_BUF) { onBad(false); return null }
            val next = off + 4 + pktLen + ((4 - (pktLen and 3)) and 3)
            if (off + 4 + pktLen > n) return if (n - off <= 2 * RX_BUF) b.copyOfRange(off, n) else null
            val hs = off + 4
            if (pktLen >= 8 + HTC_RX_STATUS) {
                var plen = (u8(hs + 2) shl 8) or u8(hs + 3)
                if (u8(hs + 1) and 0x02 != 0) plen -= u8(hs + 4) // HTC trailer
                val ps = hs + 8
                val dlen = (u8(ps + 8) shl 8) or u8(ps + 9)
                val status = u8(ps + 10)
                when {
                    plen != HTC_RX_STATUS + dlen || ps + plen > n -> onBad(false)
                    status and 0x01 != 0 -> onBad(true)                    // checksum error
                    status != 0 || dlen < 24 + FCS_LEN -> onBad(false)     // PHY / decrypt error, runt
                    else -> {
                        val raw = b[ps + 12].toInt()
                        val d = ps + HTC_RX_STATUS
                        onFrame(d, d + dlen - FCS_LEN, if (raw == RSSI_BAD) null else noiseFloor + raw)
                    }
                }
            }
            off = next
        }
        return null
    }

    // ---- helpers ----

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
