package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Realtek RTL8814AU USB WiFi adapters (e.g. ALFA
 * AWUS1900), 2.4 + 5 GHz, driven from userspace over Android's USB host API - no
 * root, no kernel driver, no firmware. Hops 2.4 GHz and the 5 GHz UNII-1 / UNII-3
 * channels and reports the access points and client devices it hears. It never
 * transmits.
 *
 * Ported from Wardrive Go by RocketGod (https://github.com/RocketGod-git/wardrive-go,
 * GPL-3.0), with its register tables (Rtl8814auTables.kt) from Realtek's 8814au driver.
 * Its handshake / PMKID capture was removed; frames with a bad checksum are dropped.
 * Used under the GNU GPL v3.
 */

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

object Rtl8814auMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val HOP = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 36, 40, 44, 48, 149, 153, 157, 161, 165)
    private const val DWELL_MS = 125L
    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L
    private const val RXDESC_SIZE = 24
    private const val FCS_LEN = 4

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0; private const val VREQ = 0x05
    private const val REG_RX_DRVINFO_SZ = 0x060F
    private val RF_BASE = intArrayOf(0x2800, 0x2c00, 0x3800, 0x3c00)

    private lateinit var conn: UsbDeviceConnection
    private var curBand5g = false
    @Volatile private var ctlErrors = 0

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, chipName: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear(); ctlErrors = 0
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(device) ?: run { onStatus("$chipName: couldn't reopen adapter"); running = false; return }
        conn = c
        try {
            val intf = device.getInterface(0)
            if (!c.claimInterface(intf, true)) UsbWifi.log("RTL8814AU: couldn't claim the USB interface (continuing)")
            val rx = BulkRx.inEndpoint(intf, 0x81)
                ?: run { UsbWifi.log("RTL8814AU: no bulk-IN endpoint"); onStatus("$chipName: no RX endpoint"); return }
            UsbWifi.log("=== RTL8814AU bring-up (8814au) ===")
            val t0 = System.currentTimeMillis()

            onStatus("$chipName · powering on…")
            resetMac()
            val powered = powerOn()
            w16(Rtl8814auTables.REG_CR, 0x0000)
            w16(Rtl8814auTables.REG_CR, Rtl8814auTables.CR_INIT16)
            val cr = r16(Rtl8814auTables.REG_CR)
            UsbWifi.log("RTL8814AU: power-on %s, REG_CR 0x%04x, RX EP 0x%02x".format(if (powered) "ok" else "poll timed out", cr, rx.address))
            if (cr == 0xEAEA) { onStatus("$chipName: MAC didn't power on (replug)"); return }
            initLlt(); initRqpn()

            onStatus("$chipName · loading MAC / BB / RF…")
            for (e in Rtl8814auTables.MAC_REG) if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else w8(e[0], e[1] and 0xff)
            w8(0x02, r8(0x02) or 0x04)
            w8(0x1002, r8(0x1002) or 0x03)
            w8(0x1F, 0x07); w16(0x20, 0x0707); w8(0x76, 0x07)
            sleep(1)
            for (e in Rtl8814auTables.PHY_REG) if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else w32(e[0], e[1])
            for (e in Rtl8814auTables.AGC_TAB) if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else w32(e[0], e[1])
            for ((path, tab) in listOf(Rtl8814auTables.RADIOA, Rtl8814auTables.RADIOB, Rtl8814auTables.RADIOC, Rtl8814auTables.RADIOD).withIndex())
                for (e in tab) if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else rfWrite(path, e[0], e[1])
            w32(0x1000, r32(0x1000) or 0x10000)

            w16(Rtl8814auTables.REG_RXFF_BNDY, Rtl8814auTables.RX_DMA_BOUNDARY)
            w8(0x10C, r8(0x10C) or 0x01)
            w8(REG_RX_DRVINFO_SZ, 0x04)
            w32(Rtl8814auTables.REG_RCR, Rtl8814auTables.RCR_MONITOR)
            w16(Rtl8814auTables.REG_RXFLTMAP0, Rtl8814auTables.RXFLTMAP_ALL)
            w16(Rtl8814auTables.REG_RXFLTMAP1, Rtl8814auTables.RXFLTMAP_ALL)
            w16(Rtl8814auTables.REG_RXFLTMAP2, Rtl8814auTables.RXFLTMAP_ALL)
            w16(Rtl8814auTables.REG_CR, r16(Rtl8814auTables.REG_CR) or Rtl8814auTables.CR_MACEN)

            curBand5g = HOP[0] > 14
            switchBand(curBand5g)
            tune(HOP[0])
            UsbWifi.log("RTL8814AU: started in %d ms - RCR 0x%08x BB(0x800) 0x%08x CR 0x%04x, RF18 %05x %05x %05x %05x, USB errors %d - monitor live ch %d".format(
                System.currentTimeMillis() - t0, r32(Rtl8814auTables.REG_RCR), r32(0x800), r16(Rtl8814auTables.REG_CR),
                rfRead(0, 0x18), rfRead(1, 0x18), rfRead(2, 0x18), rfRead(3, 0x18), ctlErrors, HOP[0]))
            onStatus("$chipName · live · 2.4 + 5 GHz monitor")
            rxLoop(chipName, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("RTL8814AU: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    // ---- bring-up ----

    private fun resetMac() {
        w8(0x05, r8(0x05) or 0x02)
        val end = System.currentTimeMillis() + 100
        while ((r8(0x05) and 0x02) != 0 && System.currentTimeMillis() < end) sleep(1)
    }

    private fun powerOn(): Boolean {
        w8(0x10C2, r8(0x10C2) or 0x02)
        wmask8(0x05, 0x04, 0x00); val a = poll8(0x06, 0x02, 0x02)
        wmask8(0x05, 0x08, 0x00); wmask8(0xF0, 0x80, 0x00)
        wmask8(0x81, 0x30, 0x20); wmask8(0x05, 0x01, 0x01); val b = poll8(0x05, 0x01, 0x00)
        return a && b
    }

    private fun initLlt() {
        w8(Rtl8814auTables.REG_AUTO_LLT, r8(Rtl8814auTables.REG_AUTO_LLT) or 0x01)
        val end = System.currentTimeMillis() + 2000
        while ((r8(Rtl8814auTables.REG_AUTO_LLT) and 0x01) != 0 && System.currentTimeMillis() < end) sleep(2)
        if ((r8(Rtl8814auTables.REG_AUTO_LLT) and 0x01) != 0) UsbWifi.log("RTL8814AU: LLT init timed out (continuing)")
    }

    private fun initRqpn() {
        val txpkt = 2048 - 8; val pub = txpkt - 0x20 * 4
        for (a in intArrayOf(0x230, 0x234, 0x238, 0x23C)) w32(a, 0x20)
        w32(0x240, pub); w32(0x22C, 0x80000000.toInt())
        for (a in intArrayOf(0x424, 0x456, 0x47A, 0x204, 0x206)) w16(a, txpkt and 0xffff)
    }

    private fun setRfe(band5g: Boolean) {
        if (band5g) {
            w32(0xCB0, 0x37173717); w32(0xEB0, 0x37173717); w32(0x18B4, 0x37173717)
            w32(0x1AB4, 0x77177717); bbMask(0x1ABC, 0x0ff00000, 0x37)
        } else {
            w32(0xCB0, 0x54775477); w32(0xEB0, 0x54775477); w32(0x18B4, 0x54775477)
            w32(0x1AB4, 0x54775477); bbMask(0x1ABC, 0x0ff00000, 0x54)
        }
    }

    private fun switchBand(band5g: Boolean) {
        w8(0x1002, r8(0x1002) and 0x01.inv())
        if (band5g) {
            w8(0x454, 0x80)
            bbMask(0xa80, 1 shl 18, 0x1)
            setRfe(true)
            bbMask(0x80c, 0xf0, 0x0)
            bbMask(0xa04, 0x0f000000, 0xF)
            bbMask(0x808, 0x30000000, 0x2)
        } else {
            bbMask(0x958, 0x1F, 0x0)
            setRfe(false)
            bbMask(0x80c, 0xf0, 0x2)
            bbMask(0xa04, 0x0f000000, 0x5)
            bbMask(0x808, 0x30000000, 0x3)
            w8(0x454, 0x00)
            bbMask(0xa80, 1 shl 18, 0x0)
        }
        w8(0x1002, r8(0x1002) or 0x01)
    }

    private fun tune(ch: Int) {
        val band5g = ch > 14
        if (band5g != curBand5g) { switchBand(band5g); curBand5g = band5g }
        val fc = when {
            ch in 36..48 -> 0x494; ch in 50..64 -> 0x453
            ch in 100..116 -> 0x452; ch >= 118 && band5g -> 0x412
            else -> 0x96a
        }
        bbMask(0x860, 0x1ffe0000, fc)
        val rfMod = when { ch in 36..64 -> 0x101; ch in 100..140 -> 0x301; ch > 140 -> 0x501; else -> 0x000 }
        val rfVal = (ch and 0xff) or (rfMod shl 8)
        val mask = (1 shl 18) or (1 shl 17) or (1 shl 16) or (1 shl 9) or (1 shl 8) or 0xff
        for (p in 0 until 4) rfMask(p, 0x18, mask, rfVal)
        if (band5g) bbMask(0x958, 0x1F, when { ch in 36..64 -> 1; ch in 100..144 -> 2; else -> 3 })
        when (ch) {
            in 1..11 -> { w32(0xa20, 0x1a1b0030); w32(0xa24, 0x090e1317); w32(0xa28, 0x00000204) }
            in 12..13 -> { w32(0xa20, 0x1a1b0030); w32(0xa24, 0x090e1217); w32(0xa28, 0x00000305) }
        }
    }

    // ---- receive ----

    private class RxState { var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0 }

    private fun rxLoop(chipName: String, ep: android.hardware.usb.UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0
        val stats = BulkRx.Stats("RTL8814AU")
        val total = BulkRx.run("RTL8814AU", conn, ep, 24, 16384, { stop }, { b, n ->
            stats.transfer(n)
            stats.sample("$n bytes, first RX descriptor", b, 0, RXDESC_SIZE)
            forEachFrame(b, n, onBad = { crc -> if (crc) stats.badChecksum++ else stats.malformed++ }) { s, e, rssi ->
                stats.frame(rssi)
                frames.frame(b, s, e, HOP[st.hopIdx], rssi)
            }
        }) { now ->
            stats.maybeLog(now) {
                val (aps, clients) = frames.live(now, REPORT_MS)
                "ch ${HOP[st.hopIdx]}, $aps access points, $clients devices, USB errors $ctlErrors"
            }
            if (now - st.lastEmit > 2000) { emit(onFrame); st.lastEmit = now }
            if (now - st.hopAt > DWELL_MS) {
                st.hopIdx = (st.hopIdx + 1) % HOP.size
                runCatching { tune(HOP[st.hopIdx]) }
                val (aps, clients) = frames.live(now, REPORT_MS)
                onStatus("$chipName · live · $aps access points · $clients devices · ch ${HOP[st.hopIdx]}")
                st.hopAt = now
            }
        }
        emit(onFrame)
        UsbWifi.log("RTL8814AU: RX loop stopped ($total B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    /**
     * Frames in one bulk transfer, each | RX descriptor (24) | PHY status | shift | frame + FCS |,
     * padded to 8 bytes. [onFrame] gets the 802.11 frame's [start, end) without the FCS and its
     * signal (null without a PHY status); [onBad] hears about skipped ones (true: bad checksum).
     */
    internal fun forEachFrame(b: ByteArray, n: Int, onBad: (Boolean) -> Unit = {}, onFrame: (Int, Int, Int?) -> Unit) {
        var off = 0
        while (off + RXDESC_SIZE <= n) {
            val d0 = le32(b, off)
            val pkt = d0 and 0x3fff
            if (pkt == 0 || pkt > 12000) { if (off == 0) onBad(false); break }
            val crc = (d0 ushr 14) and 1
            val drv = ((d0 ushr 16) and 0xf) * 8
            val shift = (d0 ushr 24) and 3
            val fs = off + RXDESC_SIZE + drv + shift
            val fe = fs + pkt - FCS_LEN
            when {
                crc != 0 -> onBad(true)
                pkt < 24 + FCS_LEN || fe > n -> onBad(false)
                else -> {
                    val rate = b[off + 12].toInt() and 0x7f
                    val rssi = if (drv >= 8) rtlRssi(b, off + RXDESC_SIZE, rate) else null
                    onFrame(fs, fe, rssi)
                }
            }
            off += (RXDESC_SIZE + drv + shift + pkt + 7) and 7.inv()
        }
    }

    /** Signal from the PHY status: CCK (rates 0-3) from the LNA / VGA gain, OFDM from the power report. */
    private fun rtlRssi(b: ByteArray, di: Int, rate: Int): Int {
        fun u8(i: Int) = b[i].toInt() and 0xff
        return if (rate <= 3) {
            val agc = u8(di + 5); val lna = (agc and 0xE0) shr 5; val vga = agc and 0x1F
            when (lna) {
                7 -> if (vga <= 27) -94 + 2 * (27 - vga) else -94
                6 -> -42 + 2 * (2 - vga); 5 -> -36 + 2 * (7 - vga); 4 -> -30 + 2 * (7 - vga)
                3 -> -18 + 2 * (7 - vga); 2 -> 2 * (5 - vga); 1 -> 14 - 2 * vga; else -> 20 - 2 * vga
            }
        } else ((u8(di + 4) shr 1) and 0x7f) - 110
    }

    // ---- register access ----

    private fun r(addr: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        if (conn.controlTransfer(IN_VENDOR, VREQ, addr, 0, b, len, 500) != len) { ctlErrors++; return ByteArray(len) }
        return b
    }
    private fun w(addr: Int, data: ByteArray) {
        if (conn.controlTransfer(OUT_VENDOR, VREQ, addr, 0, data, data.size, 500) < 0) ctlErrors++
    }
    private fun r8(a: Int) = r(a, 1)[0].toInt() and 0xff
    private fun r16(a: Int): Int { val b = r(a, 2); return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) }
    private fun r32(a: Int) = le32(r(a, 4), 0)
    private fun w8(a: Int, v: Int) = w(a, byteArrayOf(v.toByte()))
    private fun w16(a: Int, v: Int) = w(a, byteArrayOf(v.toByte(), (v ushr 8).toByte()))
    private fun w32(a: Int, v: Int) = w(a, byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
    private fun wmask8(a: Int, msk: Int, v: Int) { val cur = r8(a); w8(a, (cur and msk.inv()) or (v and msk)) }
    private fun poll8(a: Int, msk: Int, v: Int): Boolean {
        val end = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < end) { if ((r8(a) and msk) == (v and msk)) return true; sleep(1) }
        return false
    }
    private fun bbMask(a: Int, mask: Int, v: Int) { val cur = r32(a); w32(a, (cur and mask.inv()) or ((v shl sh(mask)) and mask)) }

    private fun rfWrite(path: Int, reg: Int, v: Int) = w32(Rtl8814auTables.LSSI[path], (((reg and 0xff) shl 20) or (v and 0xfffff)) and 0x0fffffff)
    private fun rfRead(path: Int, reg: Int) = r32(RF_BASE[path] + (reg and 0xff) * 4) and 0xfffff
    private fun rfMask(path: Int, reg: Int, mask: Int, v: Int) {
        val cur = rfRead(path, reg); rfWrite(path, reg, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }

    private fun sh(mask: Int) = Integer.numberOfTrailingZeros(mask)
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
