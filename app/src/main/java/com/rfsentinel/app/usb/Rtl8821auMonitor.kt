package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Realtek RTL8811AU / RTL8821AU USB WiFi adapters
 * (e.g. ALFA AWUS036ACS), driven from userspace over Android's USB host API -
 * no root, no kernel driver. Hops the 2.4 GHz channels and reports the access
 * points (beacons, probe responses) and client devices (probe requests, data
 * frames) it hears. It never transmits.
 *
 * Ported from Wardrive Go by RocketGod (https://github.com/RocketGod-git/wardrive-go,
 * GPL-3.0) and adapted to RF Sentinel: its handshake / PMKID capture was removed.
 * Used under the GNU GPL v3.
 */

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

class Rtl8821auMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private var HOP = HOP_24

    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)

    private val rfShadow = HashMap<Int, Int>()

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear(); rfShadow.clear()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("RTL8821AU: couldn't reopen adapter"); running = false; return }
        try {
            val intf = device.getInterface(0)
            BulkRx.claim("RTL8821AU", conn, intf) { stop }
            val rx = BulkRx.inEndpoint(intf) ?: run { UsbWifi.log("RTL8821AU: no bulk-IN endpoint"); onStatus("RTL8821AU: no RX endpoint"); return }
            UsbWifi.log("=== RTL8821AU bring-up (88xxau) ===")
            UsbWifi.log("RTL8821AU: RX EP=0x%02x".format(rx.address))

            onStatus("RTL8821AU · powering on…")
            resetMac(conn)
            if (!powerOn(conn)) UsbWifi.log("RTL8821AU: power-on poll timed out (continuing)")
            w16(conn, Rtl8821auTables.REG_CR, 0x0000)
            w16(conn, Rtl8821auTables.REG_CR, Rtl8821auTables.CR_INIT16)
            w16(conn, Rtl8821auTables.REG_RXFF_BNDY, Rtl8821auTables.RX_DMA_BOUNDARY)
            w16(conn, Rtl8821auTables.REG_CR, r16(conn, Rtl8821auTables.REG_CR) or Rtl8821auTables.CR_MACEN8)
            val cr = r16(conn, Rtl8821auTables.REG_CR)
            UsbWifi.log("RTL8821AU: MAC on, REG_CR=0x%04x".format(cr))
            if (cr == 0xEAEA) { onStatus("RTL8821AU: MAC didn't power on - unplug the adapter and plug it back in"); return }

            onStatus("RTL8821AU · loading MAC/BB/RF…")
            applyRegTable(conn, Rtl8821auTables.MAC_REG, rf = false)

            run { val v = r8(conn, 0x02); w8(conn, 0x02, v or 0x04); w8(conn, 0x02, v or 0x07); w8(conn, 0x1F, 0x07) }
            sleep(1)
            applyRegTable(conn, Rtl8821auTables.PHY_REG, rf = false)
            applyRegTable(conn, Rtl8821auTables.AGC_TAB, rf = false)
            applyRegTable(conn, Rtl8821auTables.RADIOA, rf = true)

            w8(conn, REG_RX_DRVINFO_SZ, 0x04)
            w32(conn, Rtl8821auTables.REG_RCR, Rtl8821auTables.RCR_MONITOR or RCR_APP_PHYST_RXFF)
            w16(conn, Rtl8821auTables.REG_RXFLTMAP0, Rtl8821auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8821auTables.REG_RXFLTMAP1, Rtl8821auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8821auTables.REG_RXFLTMAP2, Rtl8821auTables.RXFLTMAP_ALL)
            HOP = round(0)
            if (scan5g) UsbWifi.log("RTL8821AU: 5 GHz hopping on (experimental): ${HOP.joinToString()}")
            channelTune(conn, HOP[0])
            UsbWifi.log("RTL8821AU: RCR=0x%08x BB(0x800)=0x%08x — monitor live ch ${HOP[0]}".format(
                r32(conn, Rtl8821auTables.REG_RCR), r32(conn, 0x800)))
            onStatus("RTL8821AU · live · 2.4 GHz monitor")
            runCatching { led(conn, lightEnabled) }
            UsbWifi.log(if (lightEnabled) "RTL8821AU: light on, blinks with received traffic" else "RTL8821AU: light off (Settings)")

            rxLoop(conn, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("RTL8821AU: bring-up error — ${e.message}")
            onStatus("RTL8821AU: error · ${e.message}")
        } finally {
            runCatching { led(conn, false) }
            // Scanning stopped, adapter still plugged in: radio and receiver off, so it stops drawing on the battery.
            if (stop && !UsbCtl.gone) runCatching { w16(conn, Rtl8821auTables.REG_CR, 0x0000); w8(conn, 0x1F, 0x00) }
            runCatching { conn.close() }
            running = false
        }
    }

    /** Light on or off under software control; the band-switch bit and the rest of the byte are kept. */
    private fun led(conn: UsbDeviceConnection, on: Boolean) {
        val v = r8(conn, REG_LEDCFG2)
        w8(conn, REG_LEDCFG2, if (on) (v and 0x08.inv()) or 0x20 else v or 0x28)
    }

    private fun resetMac(conn: UsbDeviceConnection) {
        runCatching {
            val v = r8(conn, 0x05); w8(conn, 0x05, v or 0x02)
            val end = System.currentTimeMillis() + 100
            while ((r8(conn, 0x05) and 0x02) != 0 && System.currentTimeMillis() < end) sleep(1)
        }
    }

    private fun powerOn(conn: UsbDeviceConnection): Boolean {
        var ok = true
        for (s in Rtl8821auTables.PWR_ON) {
            val cmd = s[0]; val off = s[1]; val msk = s[2]; val v = s[3]
            when (cmd) {
                Rtl8821auTables.E -> return ok
                Rtl8821auTables.D -> sleep(v)
                Rtl8821auTables.W -> { val cur = r8(conn, off); w8(conn, off, (cur and msk.inv()) or (v and msk)) }
                Rtl8821auTables.P -> {
                    var hit = false; val end = System.currentTimeMillis() + 500
                    while (System.currentTimeMillis() < end) { if ((r8(conn, off) and msk) == (v and msk)) { hit = true; break }; sleep(1) }
                    if (!hit) ok = false
                }
            }
        }
        return ok
    }

    private fun applyRegTable(conn: UsbDeviceConnection, tab: Array<IntArray>, rf: Boolean) {
        for (e in tab) {
            if (e[0] == Rtl8821auTables.DELAY) { sleep(e[1]); continue }
            if (rf) rfWrite(conn, e[0], e[1]) else w32(conn, e[0], e[1])
        }
    }

    /** Hop round [k]: 2.4 GHz, and with 5 GHz on the common 5 GHz channels plus two radar (DFS) ones in turn, as the RTL8822BU driver. */
    private fun round(k: Int) = UsbWifi.hops(if (scan5g) Rtl8822buMonitor.hopCycle(k) else HOP_24)

    private fun channelTune(conn: UsbDeviceConnection, ch: Int) {
        for (op in Rtl8821auTables.channelTune(ch, bandSwitch = scan5g)) {
            val kind = op[0]; val addr = op[1]; val mask = op[2]; val v = op[3]
            when (kind) {
                Rtl8821auTables.RF -> rfMask(conn, addr, mask, v)
                Rtl8821auTables.MAC8 -> macMask(conn, addr, mask, v, 1)
                Rtl8821auTables.MAC16 -> macMask(conn, addr, mask, v, 2)
                else -> bbMask(conn, addr, mask, v)
            }
        }
    }

    private class RxState {
        var total = 0L; var bufs = 0L; var lastLog = 0L; var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0; var round = 0
        /** Good frames received, and how many there were at the light's last change. */
        var frames = 0L; var ledFrames = 0L; var ledAt = 0L; var ledOn = lightEnabled
        fun start() { val t = System.currentTimeMillis(); lastLog = t; hopAt = t; lastEmit = t; ledAt = t }
    }

    private fun rxTick(conn: UsbDeviceConnection, now: Long, st: RxState,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        if (now - st.lastLog > 3000) {
            val (aps, clients) = frames.live(now, FRESH_MS)
            UsbWifi.log("RTL8821AU: RX ${st.bufs} bufs / ${st.total} B ch ${HOP[st.hopIdx]} | ${aps + clients} live ($aps AP · $clients client, ${frames.tracked} tracked)")
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { frames.emit(FRESH_MS, onFrame); st.lastEmit = now }
        // Blinks while frames come in; steady on when the air is quiet (still running); dark when switched off.
        if (now - st.ledAt >= BLINK_MS) {
            val want = nextLight(lightEnabled, st.frames != st.ledFrames, st.ledOn)
            if (want != st.ledOn && runCatching { led(conn, want) }.isSuccess) st.ledOn = want
            st.ledFrames = st.frames; st.ledAt = now
        }
        val lock = AntennaCheck.lockIndex(HOP) // antenna check: hold the reference's channel
        if (lock >= 0 && lock == st.hopIdx) AntennaCheck.holding = HOP[lock]
        if (if (lock >= 0) lock != st.hopIdx else now - st.hopAt > DWELL_MS) {
            if (lock < 0 && st.hopIdx + 1 >= HOP.size) HOP = round(++st.round) // same length every round
            val ni = if (lock >= 0) lock else (st.hopIdx + 1) % HOP.size
            if (ni != st.hopIdx) { st.hopIdx = ni; runCatching { channelTune(conn, HOP[st.hopIdx]) } }
            val (aps, clients) = frames.live(now, FRESH_MS)
            onStatus("RTL8811AU / 8821AU · live · $aps access points · $clients devices · ch ${HOP[st.hopIdx]}")
            st.hopAt = System.currentTimeMillis() // the dwell starts once the retune is done
        }
    }

    private fun rxLoop(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState().apply { start() }
        BulkRx.run("RTL8821AU", conn, epRx, 24, 16384, { stop || UsbCtl.gone }, { b, n ->
            st.total += n; st.bufs++
            st.frames += parseRxBuf(b, n, HOP[st.hopIdx])
        }, syncTimeoutMs = 500) { now -> rxTick(conn, now, st, onFrame, onStatus) }
        frames.emit(FRESH_MS, onFrame)
        UsbWifi.log("RTL8821AU: RX loop stopped (${st.total} B)")
    }

    /** Parses one USB buffer; returns how many good frames it held. */
    private fun parseRxBuf(b: ByteArray, n: Int, ch: Int): Int {
        val size = Rtl8821auTables.RXDESC_SIZE
        var off = 0
        var good = 0
        while (off + size <= n) {
            val d0 = le32(b, off)
            val pkt = d0 and 0x3fff
            // up to the largest 802.11ac MPDU (11454 B), as the 8814AU parser allows
            if (pkt == 0 || pkt > MAX_PKT) break
            val crc = (d0 ushr 14) and 1
            val drv = ((d0 ushr 16) and 0xf) * 8
            val shift = (d0 ushr 24) and 3
            val fs = off + size + drv + shift
            // A frame cut off at the end of the transfer isn't reported: its tail (and so a
            // complete header / IEs) is missing, and what follows in the next transfer isn't a descriptor.
            if (fs + pkt > n) break
            val fe = fs + pkt - FCS_LEN // RCR appends the checksum; its bytes aren't part of the frame
            val physt = (d0 ushr 26) and 1 != 0
            if (crc == 0 && fe - fs >= 24) {
                val rate = b[off + 12].toInt() and 0x7f
                // Only a PHY status the chip filled (bit 26): on other frames of an aggregate those bytes aren't a signal report.
                val rssi = if (physt && drv >= 8) rtlRssi(b, off + size, rate) else null
                frames.frame(b, fs, fe, ch, rssi)
                good++
            }
            off += (size + drv + shift + pkt + 7) and 7.inv()
        }
        return good
    }

    private fun rtlRssi(b: ByteArray, di: Int, rate: Int): Int {
        fun u8(i: Int) = b[i].toInt() and 0xff
        return if (rate <= 3) {
            val agc = u8(di + 5); val lna = (agc and 0xE0) shr 5; val vga = agc and 0x1F
            when (lna) {
                7 -> if (vga <= 27) -94 + 2 * (27 - vga) else -94
                6 -> -42 + 2 * (2 - vga)
                5 -> -36 + 2 * (7 - vga)
                4 -> -30 + 2 * (7 - vga)
                3 -> -18 + 2 * (7 - vga)
                2 -> 2 * (5 - vga)
                1 -> 14 - 2 * vga
                else -> 20 - 2 * vga
            }
        } else {
            ((u8(di + 4) shr 1) and 0x7f) - 110
        }
    }

    private fun r(conn: UsbDeviceConnection, addr: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        return if (conn.ctl3(IN_VENDOR, VREQ, addr, 0, b, len, 500) == len) b else ByteArray(len)
    }
    private fun w(conn: UsbDeviceConnection, addr: Int, data: ByteArray) {
        conn.ctl3(OUT_VENDOR, VREQ, addr, 0, data, data.size, 500)
    }
    private fun r8(conn: UsbDeviceConnection, a: Int) = r(conn, a, 1)[0].toInt() and 0xff
    private fun r16(conn: UsbDeviceConnection, a: Int): Int { val b = r(conn, a, 2); return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) }
    private fun r32(conn: UsbDeviceConnection, a: Int) = le32(r(conn, a, 4), 0)
    private fun w8(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte()))
    private fun w16(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte(), (v ushr 8).toByte()))
    private fun w32(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))

    private fun bbMask(conn: UsbDeviceConnection, a: Int, mask: Int, v: Int) {
        val cur = r32(conn, a); w32(conn, a, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }
    private fun macMask(conn: UsbDeviceConnection, a: Int, mask: Int, v: Int, width: Int) {
        val s = sh(mask)
        if (width == 1) { val cur = r8(conn, a); val m = mask and 0xff; w8(conn, a, (cur and m.inv()) or ((v shl s) and m)) }
        else { val cur = r16(conn, a); val m = mask and 0xffff; w16(conn, a, (cur and m.inv()) or ((v shl s) and m)) }
    }

    private fun rfWrite(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val d = v and 0xfffff
        w32(conn, rA_LSSIWrite, (((addr and 0xff) shl 20) or d) and 0x0fffffff)
        rfShadow[addr] = d
    }
    private fun rfRead(conn: UsbDeviceConnection, addr: Int): Int {
        rfShadow[addr]?.let { return it }
        bbMask(conn, rHSSIRead, 0xff, addr and 0xff); sleep(1)
        return r32(conn, rA_PIRead) and 0xfffff
    }
    private fun rfMask(conn: UsbDeviceConnection, addr: Int, mask: Int, v: Int) {
        val cur = rfRead(conn, addr); rfWrite(conn, addr, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }

    companion object {
        private val HOP_24 = UsbWifi.HOP_24
        /** Settings > External hardware: also hop 5 GHz (experimental); read when the adapter starts. */
        @Volatile var scan5g = false
        /** The adapter's light blinks with traffic (Settings > External hardware); off = it stays dark. Changes apply live. */
        @Volatile var lightEnabled = true
        private const val MAX_PKT = 12000
        private const val DWELL_MS = 350L
        private const val FCS_LEN = 4

        private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
        private const val VREQ = 0x05
        private const val REG_RX_DRVINFO_SZ = 0x060F; private const val RCR_APP_PHYST_RXFF = 0x10000000

        /**
         * The adapter's light: LED pin 2 on the RTL8811AU / 8821AU (as Linux's rtw88 drives it).
         * Bit 5 = software control, bit 3 = off. Bit 7 belongs to the 5 GHz band switch: kept as is.
         */
        private const val REG_LEDCFG2 = 0x4E
        /** How often the light may toggle while frames arrive (a visible blink, a few writes a second). */
        private const val BLINK_MS = 150L

        private const val rA_LSSIWrite = 0xC90; private const val rHSSIRead = 0x8B0; private const val rA_PIRead = 0xD04

        /** The light's next state: dark when switched off; else it flips while frames arrive and stays on when the air is quiet. */
        internal fun nextLight(enabled: Boolean, framesArrived: Boolean, on: Boolean): Boolean =
            if (!enabled) false else if (framesArrived) !on else true

        private fun sh(mask: Int) = Integer.numberOfTrailingZeros(mask)
        private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)
        private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
    }
}
