package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Realtek RTL8812AU USB WiFi adapters (2 x 2 dual band 802.11ac, e.g. ALFA
 * AWUS036ACH), driven from userspace over Android's USB host API - no root, no kernel driver. Hops the 2.4 and
 * 5 GHz channels and reports the access points (beacons, probe responses) and client devices (probe requests,
 * data frames) it hears. It never transmits.
 *
 * Follows the Realtek rtl8812au Linux driver as maintained in aircrack-ng/rtl8812au (GPL-2.0): the card enable
 * flow, MAC / baseband / AGC / radio A and B tables (Rtl8812auTables), the RFE pin set-up read from the adapter's
 * efuse, and the band, channel and 20 MHz bandwidth switching. The USB plumbing and 802.11 frame handling are those
 * of the RTL8811AU / 8821AU driver (Wardrive Go by RocketGod, GPL-3.0). Transmit, firmware download, power tracking
 * and calibration for sending are left out: nothing is ever sent. Used under the GNU GPL v3.
 */

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

object Rtl8812auMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val HOP_24 = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    /** UNII-1 and UNII-3, like the RTL8814AU driver (where most 5 GHz networks are). */
    private val HOP_5 = intArrayOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    private val HOP = HOP_24 + HOP_5
    /** The board type the efuse says (which antenna switch and LNA lines the baseband drives); 0 when it can't be read. */
    private var rfe = 0
    /** The chip's revision: 0 = A, 1 = B, 2 = C... */
    private var cut = 0
    private const val DWELL_MS = 350L

    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L
    private const val NOMINAL_RSSI = -60

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
    private const val VREQ = 0x05
    private const val REG_RX_DRVINFO_SZ = 0x060F; private const val RCR_APP_PHYST_RXFF = 0x10000000
    private const val REG_SYS_FUNC_EN = 0x02; private const val FEN_USBA = 0x04; private const val FEN_BB_GLB_RSTN = 0x02; private const val FEN_BBRSTB = 0x01
    private const val FEN_ELDR = 0x1000
    private const val REG_RF_CTRL = 0x1F; private const val REG_RF_B_CTRL = 0x76
    private const val REG_SYS_CLKR = 0x08; private const val LOADER_CLK_EN = 0x20; private const val ANA8M = 0x02
    private const val REG_SYS_CFG = 0xF0
    private const val REG_EFUSE_CTRL = 0x30; private const val REG_EFUSE_ACCESS = 0xCF

    private const val rA_LSSIWrite = 0xC90; private const val rB_LSSIWrite = 0xE90
    private const val rHSSIRead = 0x8B0; private const val rA_PIRead = 0xD04; private const val rB_PIRead = 0xD44

    /** What was last written to each radio register, per path: reading one back through the baseband is slow. */
    private val rfShadow = arrayOf(HashMap<Int, Int>(), HashMap<Int, Int>())

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear(); rfShadow.forEach { it.clear() }
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("RTL8812AU: couldn't reopen adapter"); running = false; return }
        var epRx: UsbEndpoint? = null
        try {
            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) { epRx = ep; break }
            }
            val rx = epRx ?: run { UsbWifi.log("RTL8812AU: no bulk-IN endpoint"); onStatus("RTL8812AU: no RX endpoint"); return }
            UsbWifi.log("=== RTL8812AU bring-up (rtl8812au) ===")
            UsbWifi.log("RTL8812AU: RX EP=0x%02x".format(rx.address))

            onStatus("RTL8812AU · powering on…")
            if (!powerOn(conn)) UsbWifi.log("RTL8812AU: power-on poll timed out (continuing)")
            w16(conn, Rtl8812auTables.REG_CR, 0x0000)
            w16(conn, Rtl8812auTables.REG_CR, Rtl8812auTables.CR_INIT16)
            w16(conn, Rtl8812auTables.REG_RXFF_BNDY, Rtl8812auTables.RX_DMA_BOUNDARY)
            w16(conn, Rtl8812auTables.REG_CR, r16(conn, Rtl8812auTables.REG_CR) or Rtl8812auTables.CR_MACEN8)
            val cr = r16(conn, Rtl8812auTables.REG_CR)
            cut = (r32(conn, REG_SYS_CFG) ushr 12) and 0xF
            UsbWifi.log("RTL8812AU: MAC on, REG_CR=0x%04x, chip revision %d".format(cr, cut))
            if (cr == 0xEAEA) { onStatus("RTL8812AU: MAC didn't power on (replug)"); return }
            rfe = runCatching { readRfeType(conn) }.getOrDefault(0)
            UsbWifi.log("RTL8812AU: board type (RFE) $rfe")

            onStatus("RTL8812AU · loading MAC/BB/RF…")
            applyTable(conn, Rtl8812auTables.MAC_REG, Kind.MAC)
            // The baseband and both radio paths on (PHY_BBConfig8812).
            run { val v = r8(conn, REG_SYS_FUNC_EN); w8(conn, REG_SYS_FUNC_EN, v or FEN_USBA); w8(conn, REG_SYS_FUNC_EN, v or FEN_USBA or FEN_BB_GLB_RSTN or FEN_BBRSTB) }
            w8(conn, REG_RF_CTRL, 0x07)
            w8(conn, REG_RF_B_CTRL, 0x07)
            sleep(1)
            applyTable(conn, Rtl8812auTables.PHY_REG, Kind.BB)
            applyTable(conn, Rtl8812auTables.AGC_TAB, Kind.BB)
            applyTable(conn, Rtl8812auTables.RADIOA, Kind.RF_A)
            applyTable(conn, Rtl8812auTables.RADIOB, Kind.RF_B)

            w8(conn, REG_RX_DRVINFO_SZ, 0x04)
            w32(conn, Rtl8812auTables.REG_RCR, Rtl8812auTables.RCR_MONITOR or RCR_APP_PHYST_RXFF)
            w16(conn, Rtl8812auTables.REG_RXFLTMAP0, Rtl8812auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8812auTables.REG_RXFLTMAP1, Rtl8812auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8812auTables.REG_RXFLTMAP2, Rtl8812auTables.RXFLTMAP_ALL)
            // The first channel: the band is switched by the tune itself.
            channelTune(conn, HOP[0])
            UsbWifi.log("RTL8812AU: RCR=0x%08x BB(0x800)=0x%08x — monitor live ch ${HOP[0]}".format(
                r32(conn, Rtl8812auTables.REG_RCR), r32(conn, 0x800)))
            onStatus("RTL8812AU · live · 2.4 + 5 GHz monitor")

            rxLoop(conn, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("RTL8812AU: bring-up error — ${e.message}")
            onStatus("RTL8812AU: error · ${e.message}")
        } finally {
            runCatching { conn.close() }
            running = false
        }
    }

    private enum class Kind { MAC, BB, RF_A, RF_B }

    /** A table of (address, value) pairs; a [Rtl8812auTables.DELAY] address pauses for the value in milliseconds. */
    private fun applyTable(conn: UsbDeviceConnection, tab: IntArray, kind: Kind) {
        var i = 0
        while (i + 1 < tab.size) {
            val a = tab[i]; val v = tab[i + 1]; i += 2
            if (a == Rtl8812auTables.DELAY) { sleep(v); continue }
            when (kind) {
                Kind.MAC -> w8(conn, a, v)
                Kind.BB -> w32(conn, a, v)
                Kind.RF_A -> rfWrite(conn, 0, a, v)
                Kind.RF_B -> rfWrite(conn, 1, a, v)
            }
        }
    }

    private fun powerOn(conn: UsbDeviceConnection): Boolean {
        var ok = true
        for (st in Rtl8812auTables.PWR_ON) {
            val cmd = st[0]; val off = st[1]; val msk = st[2]; val v = st[3]
            when (cmd) {
                Rtl8812auTables.E -> return ok
                Rtl8812auTables.D -> sleep(v)
                Rtl8812auTables.W -> { val cur = r8(conn, off); w8(conn, off, (cur and msk.inv()) or (v and msk)) }
                Rtl8812auTables.P -> {
                    var hit = false; val end = System.currentTimeMillis() + 500
                    while (System.currentTimeMillis() < end) { if ((r8(conn, off) and msk) == (v and msk)) { hit = true; break }; sleep(1) }
                    if (!hit) ok = false
                }
            }
        }
        return ok
    }

    private fun channelTune(conn: UsbDeviceConnection, ch: Int) {
        for (op in Rtl8812auTables.channelTune(ch, rfe, cut)) {
            val kind = op[0]; val addr = op[1]; val mask = op[2]; val v = op[3]
            when (kind) {
                Rtl8812auTables.RF -> rfMask(conn, 0, addr, mask, v)
                Rtl8812auTables.RFB -> rfMask(conn, 1, addr, mask, v)
                Rtl8812auTables.MAC8 -> macMask(conn, addr, mask, v, 1)
                Rtl8812auTables.MAC16 -> macMask(conn, addr, mask, v, 2)
                else -> bbMask(conn, addr, mask, v)
            }
        }
    }

    /**
     * The board's RFE type from the adapter's efuse (logical byte 0xCA, as Hal_ReadRFEType_8812A): which antenna switch
     * and LNA lines the baseband drives. An empty efuse is type 0, and a type with bit 7 set (the PA / LNA flags are
     * elsewhere in the efuse) is taken as 4.
     */
    private fun readRfeType(conn: UsbDeviceConnection): Int {
        // Efuse power: access key, loader clock and the efuse loader reset on.
        w8(conn, REG_EFUSE_ACCESS, 0x69)
        w16(conn, REG_SYS_FUNC_EN, r16(conn, REG_SYS_FUNC_EN) or FEN_ELDR)
        w16(conn, REG_SYS_CLKR, r16(conn, REG_SYS_CLKR) or LOADER_CLK_EN or ANA8M)
        try {
            val map = EfuseMap.logical(512) { addr -> efuseByte(conn, addr) }
            val v = map[0xCA]
            return when {
                v == 0xFF -> 0
                v and 0x80 != 0 -> 4
                else -> v and 0x3F
            }
        } finally {
            w8(conn, REG_EFUSE_ACCESS, 0x00)
        }
    }

    private fun efuseByte(conn: UsbDeviceConnection, addr: Int): Int {
        w8(conn, REG_EFUSE_CTRL + 1, addr and 0xFF)
        w8(conn, REG_EFUSE_CTRL + 2, ((addr shr 8) and 0x03) or (r8(conn, REG_EFUSE_CTRL + 2) and 0xFC))
        w8(conn, REG_EFUSE_CTRL + 3, r8(conn, REG_EFUSE_CTRL + 3) and 0x7F)
        var tries = 0
        while ((r8(conn, REG_EFUSE_CTRL + 3) and 0x80) == 0 && tries < 100) { sleep(1); tries++ }
        return if (tries < 100) r8(conn, REG_EFUSE_CTRL) else 0xFF
    }

    /** The efuse's logical map out of its raw bytes (Hal_EfuseReadEFuse8812A): headers say which words follow. */
    internal object EfuseMap {
        fun logical(rawLen: Int, raw: (Int) -> Int): IntArray {
            val words = IntArray(64 * 4) { 0xFFFF }
            var addr = 0
            var h = raw(addr)
            if (h == 0xFF) return IntArray(512) { 0xFF }
            addr++
            while (h != 0xFF && addr < rawLen) {
                val offset: Int; var wren: Int
                if ((h and 0x1F) == 0x0F) {
                    val low = (h and 0xE0) shr 5
                    h = raw(addr)
                    if ((h and 0x0F) == 0x0F) {
                        addr++
                        h = raw(addr)
                        if (h != 0xFF && addr < rawLen) addr++
                        continue
                    }
                    offset = ((h and 0xF0) shr 1) or low
                    wren = h and 0x0F
                    addr++
                } else {
                    offset = (h shr 4) and 0x0F
                    wren = h and 0x0F
                }
                for (i in 0 until 4) {
                    if ((wren and 1) == 0) {
                        val lo = raw(addr); addr++
                        if (addr >= rawLen) break
                        val hi = raw(addr); addr++
                        if (offset < 64) words[offset * 4 + i] = lo or (hi shl 8)
                        if (addr >= rawLen) break
                    }
                    wren = wren shr 1
                }
                h = raw(addr)
                if (h != 0xFF && addr < rawLen) addr++
            }
            return IntArray(512) { i -> val w = words[i / 2]; if (i % 2 == 0) w and 0xFF else (w shr 8) and 0xFF }
        }
    }

    private class RxState {
        var total = 0L; var bufs = 0L; var lastLog = 0L; var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0
        var frames = 0L
        fun start() { val t = System.currentTimeMillis(); lastLog = t; hopAt = t; lastEmit = t }
    }

    private fun rxTick(conn: UsbDeviceConnection, now: Long, st: RxState,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        if (now - st.lastLog > 3000) {
            val (aps, clients) = frames.live(now, REPORT_MS)
            UsbWifi.log("RTL8812AU: RX ${st.bufs} bufs / ${st.total} B ch ${HOP[st.hopIdx]} | ${aps + clients} live ($aps AP · $clients client, ${frames.tracked} tracked)")
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { emitSightings(onFrame); st.lastEmit = now }
        if (now - st.hopAt > DWELL_MS) {
            val ni = (st.hopIdx + 1) % HOP.size
            if (ni != st.hopIdx) { st.hopIdx = ni; runCatching { channelTune(conn, HOP[st.hopIdx]) } }
            val (aps, clients) = frames.live(now, REPORT_MS)
            onStatus("RTL8812AU · live · $aps access points · $clients devices · ch ${HOP[st.hopIdx]}")
            st.hopAt = now
        }
    }

    private fun rxLoop(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val nbuf = 24; val bufSz = 16384
        val buffers = Array(nbuf) { ByteBuffer.allocateDirect(bufSz) }
        val reqs = ArrayList<UsbRequest>(nbuf)
        var initOk = true
        for (i in 0 until nbuf) {
            val r = UsbRequest()
            if (!r.initialize(conn, epRx)) { initOk = false; break }
            r.clientData = i; buffers[i].clear()
            if (!r.queue(buffers[i])) { initOk = false; break }
            reqs.add(r)
        }
        if (!initOk) {
            UsbWifi.log("RTL8812AU: async RX unavailable (${reqs.size}/$nbuf) — synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            rxLoopSync(conn, epRx, onFrame, onStatus); return
        }
        UsbWifi.log("RTL8812AU: async RX live — $nbuf buffers in flight")
        val scratch = ByteArray(bufSz)
        val st = RxState().apply { start() }
        try {
            while (!stop) {
                val req = try { conn.requestWait(100L) } catch (e: TimeoutException) { null }
                val now = System.currentTimeMillis()
                if (req != null) {
                    val idx = req.clientData as? Int ?: -1
                    if (idx in 0 until nbuf) {
                        val b = buffers[idx]
                        val n = b.position()
                        val parseLen = if (n in 1..bufSz) {
                            st.total += n; st.bufs++
                            b.rewind(); b.get(scratch, 0, n); n
                        } else 0

                        b.clear()
                        if (!runCatching { req.queue(b) }.getOrDefault(false)) {
                            runCatching { req.close() }
                            val nr = UsbRequest()
                            if (runCatching { nr.initialize(conn, epRx) }.getOrDefault(false)) {
                                nr.clientData = idx; b.clear()
                                if (runCatching { nr.queue(b) }.getOrDefault(false)) reqs[idx] = nr
                            }
                        }
                        if (parseLen > 0) runCatching { st.frames += parseRxBuf(scratch, parseLen, HOP[st.hopIdx]) }
                    }
                }
                rxTick(conn, now, st, onFrame, onStatus)
            }
        } finally {
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
        }
        emitSightings(onFrame)
        UsbWifi.log("RTL8812AU: RX loop stopped (${st.total} B)")
    }

    private fun rxLoopSync(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                           onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val buf = ByteArray(16384)
        val st = RxState().apply { start() }
        while (!stop) {
            val n = conn.bulkTransfer(epRx, buf, buf.size, 500)
            val now = System.currentTimeMillis()
            if (n > 0) { st.total += n; st.bufs++; runCatching { st.frames += parseRxBuf(buf, n, HOP[st.hopIdx]) } }
            rxTick(conn, now, st, onFrame, onStatus)
        }
        emitSightings(onFrame)
        UsbWifi.log("RTL8812AU: RX loop stopped (${st.total} B)")
    }

    /** Parses one USB buffer; returns how many good frames it held. */
    private fun parseRxBuf(b: ByteArray, n: Int, ch: Int): Int {
        val size = Rtl8812auTables.RXDESC_SIZE
        var off = 0
        var good = 0
        while (off + size <= n) {
            val d0 = le32(b, off)
            val pkt = d0 and 0x3fff
            if (pkt == 0 || pkt > 4096) break
            val crc = (d0 ushr 14) and 1
            val drv = ((d0 ushr 16) and 0xf) * 8
            val shift = (d0 ushr 24) and 3
            val fs = off + size + drv + shift
            val fe = minOf(n, fs + pkt)
            if (crc == 0 && fe - fs >= 24) {
                val rate = b[off + 12].toInt() and 0x7f
                val rssi = if (drv >= 8 && off + size + drv <= n) rtlRssi(b, off + size, rate) else NOMINAL_RSSI
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

    private fun emitSightings(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    private fun r(conn: UsbDeviceConnection, addr: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        return if (conn.controlTransfer(IN_VENDOR, VREQ, addr, 0, b, len, 500) == len) b else ByteArray(len)
    }
    private fun w(conn: UsbDeviceConnection, addr: Int, data: ByteArray) {
        conn.controlTransfer(OUT_VENDOR, VREQ, addr, 0, data, data.size, 500)
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

    /** [path] 0 = radio A, 1 = radio B. */
    private fun rfWrite(conn: UsbDeviceConnection, path: Int, addr: Int, v: Int) {
        val d = v and 0xfffff
        w32(conn, if (path == 0) rA_LSSIWrite else rB_LSSIWrite, (((addr and 0xff) shl 20) or d) and 0x0fffffff)
        rfShadow[path][addr] = d
    }
    private fun rfRead(conn: UsbDeviceConnection, path: Int, addr: Int): Int {
        rfShadow[path][addr]?.let { return it }
        bbMask(conn, rHSSIRead, 0xff, addr and 0xff); sleep(1)
        return r32(conn, if (path == 0) rA_PIRead else rB_PIRead) and 0xfffff
    }
    private fun rfMask(conn: UsbDeviceConnection, path: Int, addr: Int, mask: Int, v: Int) {
        val cur = rfRead(conn, path, addr); rfWrite(conn, path, addr, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }

    private fun sh(mask: Int) = Integer.numberOfTrailingZeros(mask)
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
