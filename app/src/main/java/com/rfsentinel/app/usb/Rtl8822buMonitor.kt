package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Realtek RTL8812BU / RTL8822BU (88x2bu, "Jaguar2")
 * USB WiFi adapters, driven from userspace over Android's USB host API - no root,
 * no kernel driver. Hops the 2.4 and 5 GHz channels and reports the access points
 * and client devices it hears. It never transmits 802.11 frames: the only bulk-OUT
 * traffic is the chip's own firmware download and two configuration messages to
 * that firmware (rfe type, cut, antenna paths).
 *
 * Bring-up, channel tuning, gain control and RX descriptor parsing are ported from
 * devourer (OpenIPC, https://github.com/OpenIPC/devourer, GPL-2.0), src/jaguar2/:
 * RtlJaguar2Device, HalJaguar2, HalmacJaguar2MacInit, HalmacJaguar2Fw and
 * FrameParserJaguar2. devourer's TX, beamforming, calibration-for-TX, IQK and
 * narrowband code is not included.
 */

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

object Rtl8822buMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val pathSense = PathSense()

    /**
     * Finds which receive path has the antenna. Single-antenna dongles wire only one of
     * the chip's two paths (the Wise Tiger 8812BU uses path B), but the chip receives
     * 2.4 GHz CCK frames - most 2.4 GHz beacons - on path A only, so they arrive ~30 dB
     * weak. OFDM frames report both paths' power; when one path is consistently far
     * stronger, CCK is moved to it. Two-antenna adapters never reach a verdict.
     */
    internal class PathSense {
        var aVotes = 0; private set
        var bVotes = 0; private set
        var decided = false

        fun add(a: Int, b: Int) {
            if (decided || maxOf(a, b) < -95) return
            if (b - a >= GAP_DB) bVotes++ else if (a - b >= GAP_DB) aVotes++
        }

        /** 0 = path A, 1 = path B, null while undecided or when both paths have antennas. */
        fun verdict(): Int? {
            val n = aVotes + bVotes
            if (n < MIN_VOTES) return null
            return when {
                bVotes >= n * 0.85 -> 1
                aVotes >= n * 0.85 -> 0
                else -> null
            }
        }

        fun reset() { aVotes = 0; bVotes = 0; decided = false }

        companion object {
            const val GAP_DB = 10
            const val MIN_VOTES = 40
        }
    }

    /** 2.4 GHz channels, every cycle. */
    internal val HOP_24 = IntArray(13) { it + 1 }
    /** 5 GHz channels without radar rules, every cycle (where vehicle and mobile routers usually sit). */
    internal val HOP_5 = intArrayOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    /** 5 GHz radar (DFS) channels, two per cycle. */
    internal val HOP_DFS = intArrayOf(52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144)

    /** Channels of hop cycle [k]: all of 2.4 GHz, the common 5 GHz ones and two radar channels in turn. */
    internal fun hopCycle(k: Int): IntArray {
        val n = HOP_DFS.size
        val i = Math.floorMod(2 * k, n)
        return HOP_24 + HOP_5 + intArrayOf(HOP_DFS[i], HOP_DFS[(i + 1) % n])
    }

    private const val DWELL_MS = 200L
    private val CYCLE_MS = hopCycle(0).size * DWELL_MS
    private val FRESH_MS = CYCLE_MS * 9 / 5
    private const val REPORT_MS = 10_000L
    private const val DIG_MS = 200L

    fun abort() { stop = true }

    /**
     * Debug builds: commands sent with adb (see [UsbWifi]), run on the RX thread -
     * `park <ch>`, `hop`, `igi <hex>`, `dig`, `stats`, `rd <addr>`, `w8|w32 <addr> <val>`,
     * `bb <addr> <mask> <val>`, `rfrd <path> <addr>`, `rf <path> <addr> <mask> <val>` (hex).
     */
    internal val commands = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun run(ctx: Context, device: UsbDevice, label: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        // A previous session that was just told to stop gets a moment to power the chip down.
        val wait = System.currentTimeMillis() + 3000
        while (running && System.currentTimeMillis() < wait) sleep(50)
        if (running) { onStatus("$label: previous session still stopping, replug the adapter"); return }
        running = true; stop = false; frames.clear(); pathSense.reset()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("$label: couldn't open the adapter"); running = false; return }
        var chip: Chip? = null
        try {
            // The WiFi function is the vendor-specific interface (combo chips add Bluetooth ones).
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC } ?: device.getInterface(0)
            if (!conn.claimInterface(intf, true)) throw IOException("couldn't claim the USB interface")
            var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) { if (epIn == null) epIn = ep } else if (epOut == null) epOut = ep
            }
            val rx = epIn ?: throw IOException("no bulk-IN endpoint")
            val tx = epOut ?: throw IOException("no bulk-OUT endpoint")
            UsbWifi.log("=== $label bring-up (88x2bu) === RX EP 0x%02x, firmware EP 0x%02x".format(rx.address, tx.address))

            val tables = Rtl8822bTables.load(ctx)
            val c = Chip(conn, tx, tables, onStatus, label)
            chip = c
            c.bringUp(hopCycle(0)[0])
            // A stalled bulk-IN endpoint never delivers frames (CLEAR_FEATURE ENDPOINT_HALT).
            conn.controlTransfer(0x02, 0x01, 0, rx.address, null, 0, 500)
            onStatus("$label · live · 2.4 + 5 GHz monitor")
            rxLoop(conn, rx, c, label, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("$label: stopped — ${e.message}")
            onStatus("$label: error · ${e.message}")
        } finally {
            chip?.let { if (it.up) runCatching { it.powerOff() } }
            runCatching { conn.close() }
            running = false
        }
    }

    private class RxState(val label: String) {
        var total = 0L; var bufs = 0L; var lastLog = 0L; var hopAt = 0L; var lastEmit = 0L; var digAt = 0L
        var cycle = 0; var hopIdx = 0; var plan = hopCycle(0)
        /** Register accesses that failed in a row; the adapter is gone or wedged past a limit. */
        var ioFailures = 0
        /** A channel held by a debug command instead of hopping (0 = hopping). */
        var parked = 0
        var dig = true
        val channel get() = if (parked > 0) parked else plan[hopIdx]
        fun start() { val t = System.currentTimeMillis(); lastLog = t; hopAt = t; lastEmit = t; digAt = t }
    }

    private fun rxTick(chip: Chip, now: Long, st: RxState,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        while (true) {
            val c = commands.poll() ?: break
            try { command(chip, st, c) } catch (e: Exception) { UsbWifi.log("${st.label}: command '$c' failed — ${e.message}") }
        }
        if (now - st.lastLog > 3000) {
            if (st.parked > 0) frames.window().forEach { UsbWifi.log("${st.label}:   $it") }
            val (aps, clients) = frames.live(now, REPORT_MS)
            UsbWifi.log("${st.label}: RX ${st.bufs} bufs / ${st.total} B ch ${st.channel} IGI 0x%02x FA ${chip.lastFa} | ${aps + clients} live ($aps AP · $clients client, ${frames.tracked} tracked)".format(chip.igi))
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { emit(onFrame); st.lastEmit = now }
        if (!pathSense.decided && chip.rf2t2r) pathSense.verdict()?.let { path ->
            pathSense.decided = true
            val name = if (path == 1) "B" else "A"
            UsbWifi.log("${st.label}: antenna on path $name (${pathSense.bVotes} frames stronger on B, ${pathSense.aVotes} on A)" +
                if (path == 1) " — 2.4 GHz CCK reception moved to path B" else "")
            if (path == 1) chipIo(st) { chip.setCckPath(1) }
        }
        if (st.dig && now - st.digAt > DIG_MS) {
            // A failed read under RX load skips one gain step; the next one re-reads everything.
            chipIo(st) { chip.digStep() }
            st.digAt = now
        }
        if (st.parked == 0 && now - st.hopAt > DWELL_MS) {
            st.hopIdx++
            if (st.hopIdx >= st.plan.size) { st.cycle++; st.plan = hopCycle(st.cycle); st.hopIdx = 0 }
            chipIo(st) { chip.tune(st.channel) }
            val (aps, clients) = frames.live(now, REPORT_MS)
            val band = if (st.channel > 14) "5 GHz" else "2.4 GHz"
            onStatus("${st.label} · live · $aps access points · $clients devices · ch ${st.channel} ($band)")
            st.hopAt = now
        }
    }

    private fun command(chip: Chip, st: RxState, line: String) {
        val a = line.trim().split(Regex("\\s+"))
        fun h(i: Int) = java.lang.Long.decode(if (a[i].startsWith("0x")) a[i] else "0x" + a[i]).toInt()
        val l = st.label
        when (a[0]) {
            "park" -> { st.parked = a[1].toInt(); chip.tune(st.parked); frames.window(); UsbWifi.log("$l: parked on ch ${st.parked}") }
            "hop" -> { st.parked = 0; UsbWifi.log("$l: hopping") }
            "igi" -> { st.dig = false; chip.setIgi(h(1)); UsbWifi.log("$l: IGI fixed 0x%02x".format(h(1))) }
            "dig" -> { st.dig = true; UsbWifi.log("$l: DIG on") }
            "stats" -> frames.window().forEach { UsbWifi.log("$l:   $it") }
            "rd" -> UsbWifi.log("$l: [0x%04x] = 0x%08x".format(h(1), chip.r32(h(1))))
            "w8" -> chip.w8(h(1), h(2))
            "w32" -> chip.w32(h(1), h(2))
            "bb" -> { chip.bb(h(1), h(2), h(3)); UsbWifi.log("$l: [0x%04x] now 0x%08x".format(h(1), chip.r32(h(1)))) }
            "rfrd" -> UsbWifi.log("$l: RF%s 0x%02x = 0x%05x".format(if (h(1) == 0) "A" else "B", h(2), chip.rfRead(h(1), h(2))))
            "rf" -> { chip.rfSet(h(1), h(2), h(3), h(4)); UsbWifi.log("$l: RF%s 0x%02x now 0x%05x".format(if (h(1) == 0) "A" else "B", h(2), chip.rfRead(h(1), h(2)))) }
            else -> UsbWifi.log("$l: unknown command '$line'")
        }
    }

    private inline fun chipIo(st: RxState, op: () -> Unit) {
        try { op(); st.ioFailures = 0 } catch (e: IOException) {
            if (++st.ioFailures % 10 == 1) UsbWifi.log("${st.label}: register access failed (${st.ioFailures}) — ${e.message}")
            if (st.ioFailures >= MAX_IO_FAILURES) throw IOException("adapter stopped responding (replug it)")
        }
    }

    private const val MAX_IO_FAILURES = 30

    private fun rxLoop(conn: UsbDeviceConnection, epRx: UsbEndpoint, chip: Chip, label: String,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        // 16 KB buffers: the chip's RX aggregation is capped below that, so an aggregate
        // never spans two transfers (and some Android USB hosts never complete larger reads).
        val nbuf = 16; val bufSz = 16384
        val buffers = Array(nbuf) { ByteBuffer.allocateDirect(bufSz) }
        val reqs = ArrayList<UsbRequest>(nbuf)
        for (i in 0 until nbuf) {
            val r = UsbRequest()
            if (!r.initialize(conn, epRx)) { runCatching { r.close() }; break }
            r.clientData = i; buffers[i].clear()
            if (!r.queue(buffers[i])) { runCatching { r.close() }; break }
            reqs.add(r)
        }
        val st = RxState(label).apply { start() }
        if (reqs.size < nbuf) {
            UsbWifi.log("$label: async RX unavailable (${reqs.size}/$nbuf) — synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            val buf = ByteArray(bufSz)
            while (!stop) {
                val n = conn.bulkTransfer(epRx, buf, buf.size, 100)
                if (n > 0) { st.total += n; st.bufs++; runCatching { parseRxBuf(buf, n, st.channel, chip.rf2t2r) } }
                rxTick(chip, System.currentTimeMillis(), st, onFrame, onStatus)
            }
        } else {
            UsbWifi.log("$label: async RX live — $nbuf buffers in flight")
            val scratch = ByteArray(bufSz)
            try {
                while (!stop) {
                    val req = try { conn.requestWait(100L) } catch (e: TimeoutException) { null }
                    if (req != null) {
                        val idx = req.clientData as? Int ?: -1
                        if (idx in 0 until nbuf) {
                            val b = buffers[idx]
                            val n = b.position()
                            if (n in 1..bufSz) { st.total += n; st.bufs++; b.rewind(); b.get(scratch, 0, n) }
                            b.clear()
                            if (!runCatching { req.queue(b) }.getOrDefault(false)) {
                                runCatching { req.close() }
                                val nr = UsbRequest()
                                if (runCatching { nr.initialize(conn, epRx) }.getOrDefault(false)) {
                                    nr.clientData = idx
                                    if (runCatching { nr.queue(b) }.getOrDefault(false)) reqs[idx] = nr
                                }
                            }
                            if (n in 1..bufSz) runCatching { parseRxBuf(scratch, n, st.channel, chip.rf2t2r) }
                        }
                    }
                    rxTick(chip, System.currentTimeMillis(), st, onFrame, onStatus)
                }
            } finally {
                reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            }
        }
        emit(onFrame)
        UsbWifi.log("$label: RX loop stopped (${st.total} B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    /** One RX descriptor of an aggregated bulk-IN buffer (88xx layout, 24 bytes). */
    internal class RxDesc(val pktLen: Int, val crcErr: Boolean, val drvInfo: Int, val shift: Int,
                          val physt: Boolean, val rate: Int, val c2h: Boolean) {
        val frameOffset get() = RXDESC_SIZE + drvInfo + shift
        /** Offset of the next descriptor, 8-byte aligned. */
        val next get() = (frameOffset + pktLen + 7) and 7.inv()
    }

    internal const val RXDESC_SIZE = 24
    private const val FCS_LEN = 4

    internal fun rxDesc(b: ByteArray, off: Int): RxDesc {
        val d0 = Rtl8822bTables.le32(b, off)
        return RxDesc(
            pktLen = d0 and 0x3fff,
            crcErr = (d0 ushr 14) and 1 != 0,
            drvInfo = ((d0 ushr 16) and 0xf) * 8,
            shift = (d0 ushr 24) and 3,
            physt = (d0 ushr 26) and 1 != 0,
            rate = b[off + 12].toInt() and 0x7f,
            c2h = b[off + 11].toInt() and 0x10 != 0
        )
    }

    /**
     * Per-path receive power from a jaguar2 PHY status report, in dBm: page 0 (CCK)
     * has one value, page 1 (OFDM / HT / VHT) one per path. The page number in the
     * report, not the frame's rate, says which (as rtw88 reads it). Readings at or
     * below the noise floor or above 0 dBm (a corrupt report) come back null.
     */
    internal fun phyPaths(b: ByteArray, at: Int, paths: Int): IntArray? {
        fun dbm(i: Int) = (b[i].toInt() and 0xff).takeIf { it in 1..110 }?.let { it - 110 } ?: NO_SIGNAL
        return when (b[at].toInt() and 0x0f) {
            0 -> intArrayOf(dbm(at + 1))
            1 -> IntArray(paths) { dbm(at + 1 + it) }
            else -> null
        }
    }

    internal const val NO_SIGNAL = Int.MIN_VALUE

    internal fun parseRxBuf(b: ByteArray, n: Int, ch: Int, twoPaths: Boolean, sink: MonitorFrames = frames,
                            sense: PathSense? = pathSense) {
        var off = 0
        while (off + RXDESC_SIZE <= n) {
            val d = rxDesc(b, off)
            if (d.pktLen == 0 || off + d.frameOffset + d.pktLen > n) break
            if (!d.c2h && !d.crcErr) {
                val fs = off + d.frameOffset
                val fe = fs + d.pktLen - FCS_LEN // RCR appends the FCS
                if (fe - fs >= 24) {
                    val p = if (d.physt && d.drvInfo >= 28) phyPaths(b, off + RXDESC_SIZE + d.shift, if (twoPaths) 2 else 1) else null
                    val best = p?.max()?.takeIf { it != NO_SIGNAL }
                    // Both paths of an OFDM report: which one has the antenna, and diagnostics.
                    val pa = p?.takeIf { it.size == 2 }?.get(0)?.takeIf { it != NO_SIGNAL }
                    val pb = p?.takeIf { it.size == 2 }?.get(1)?.takeIf { it != NO_SIGNAL }
                    if (pa != null && pb != null) sense?.add(pa, pb)
                    sink.frame(b, fs, fe, ch, best, System.currentTimeMillis(), pa, pb)
                }
            }
            off += d.next
        }
    }

    /**
     * One RTL8822B behind a USB connection: register access, firmware download,
     * MAC / BB / RF bring-up, channel tuning and gain control. Used only from the
     * driver's RX thread.
     */
    private class Chip(private val conn: UsbDeviceConnection, private val epOut: UsbEndpoint,
                       private val t: Rtl8822bTables.Data, private val onStatus: (String) -> Unit,
                       private val label: String) {
        var up = false; private set
        var cut = 0; private set
        var rf2t2r = false; private set
        private var rfe = 0
        private var rsvdBoundary = 0
        private var h2cSeq = 0
        private var lastPktOffset = 0
        var igi = 0; private set
        var lastFa = 0; private set

        // --- register access (Realtek vendor request 0x05, wValue = address) ---
        private val io = ByteArray(4)

        private fun read(addr: Int, len: Int): Int {
            repeat(3) {
                if (conn.controlTransfer(0xC0, 0x05, addr and 0xffff, 0, io, len, 500) == len) return when (len) {
                    1 -> io[0].toInt() and 0xff
                    2 -> (io[0].toInt() and 0xff) or ((io[1].toInt() and 0xff) shl 8)
                    else -> Rtl8822bTables.le32(io, 0)
                }
            }
            throw IOException("register read 0x%04x failed (unplugged?)".format(addr))
        }

        private fun write(addr: Int, v: Int, len: Int) {
            val b = ByteArray(len) { (v ushr (8 * it)).toByte() }
            repeat(3) { if (conn.controlTransfer(0x40, 0x05, addr and 0xffff, 0, b, len, 500) == len) return }
            throw IOException("register write 0x%04x failed (unplugged?)".format(addr))
        }

        fun r8(a: Int) = read(a, 1)
        fun r16(a: Int) = read(a, 2)
        fun r32(a: Int) = read(a, 4)
        fun w8(a: Int, v: Int) = write(a, v, 1)
        fun w16(a: Int, v: Int) = write(a, v, 2)
        fun w32(a: Int, v: Int) = write(a, v, 4)

        /** Masked BB / MAC register write (phy_set_bb_reg). */
        fun bb(a: Int, mask: Int, v: Int) {
            if (mask == -1) { w32(a, v); return }
            val s = Integer.numberOfTrailingZeros(mask)
            w32(a, (r32(a) and mask.inv()) or ((v shl s) and mask))
        }

        // --- RF registers: 3-wire LSSI write, direct read window ---
        fun rfWrite(path: Int, addr: Int, v: Int) {
            if (addr == 0xfe || addr == 0xffe) { sleep(50); return }
            w32(if (path == 0) 0x0C90 else 0x0E90, (((addr and 0xff) shl 20) or (v and 0xfffff)) and 0x0fffffff)
        }

        fun rfRead(path: Int, addr: Int): Int = r32((if (path == 0) 0x2800 else 0x2c00) + ((addr and 0xff) shl 2)) and 0xfffff

        fun rfSet(path: Int, addr: Int, mask: Int, v: Int) {
            val data = if (mask == 0xfffff) v else {
                val s = Integer.numberOfTrailingZeros(mask)
                (rfRead(path, addr) and mask.inv()) or ((v shl s) and mask)
            }
            rfWrite(path, addr, data)
        }

        // --- bring-up (RtlJaguar2Device::bring_up, RX subset) ---
        fun bringUp(ch: Int) {
            val id = r8(0x00FC)
            UsbWifi.log("$label: chip id 0x%02x".format(id))
            if (id != 0x0a && id != 0x50) throw IOException("not an RTL8822B chip (id 0x%02x)".format(id))
            onStatus("$label · powering on…")
            var fwOk = false
            for (attempt in 0 until 4) {
                checkStop()
                if (attempt > 0) UsbWifi.log("$label: firmware didn't start — full power cycle, try ${attempt + 1}/4")
                preInitSystemCfg()
                powerOn()
                readChipVersion()
                initSystemCfg()
                onStatus("$label · loading firmware…")
                fwOk = downloadFirmware(t.firmware) || downloadFirmware(t.firmware)
                if (fwOk) break
            }
            if (!fwOk) throw IOException("firmware didn't start (replug the adapter)")
            UsbWifi.log("$label: firmware ${Rtl8822bTables.version(t.firmware)} running")
            checkStop()
            initMacCfg()
            initUsbCfg()
            enableBbRf()
            rfe = readEfuseRfe()
            sendFwGeneralInfo()
            onStatus("$label · loading radio tables…")
            applyTables()
            checkStop()
            configTrxMode()
            setChannel(ch)
            doLck()
            configTrxMode()
            rfeInit()
            coexWlanOnly(ch > 14)
            enableRx()
            up = true
            UsbWifi.log("$label: monitor live ch $ch — CR 0x%04x RCR 0x%08x RF18 0x%05x".format(r16(0x100), r32(0x608), rfRead(0, 0x18)))
        }

        private fun checkStop() { if (stop) throw IOException("stopped") }

        private fun runPwrSeq(seq: Array<IntArray>, timeoutMs: Long, fatal: Boolean) {
            for (s in seq) {
                val off = s[0]; val msk = s[2]; val v = s[3]
                when (s[1]) {
                    Rtl8822bTables.PW -> w8(off, (r8(off) and msk.inv()) or (v and msk))
                    Rtl8822bTables.PD -> sleep(v)
                    Rtl8822bTables.PP -> {
                        val end = System.currentTimeMillis() + timeoutMs
                        while (r8(off) and msk != v and msk) {
                            if (System.currentTimeMillis() > end) {
                                if (fatal) throw IOException("power-on didn't complete (chip not responding)")
                                break
                            }
                        }
                    }
                }
            }
        }

        fun powerOff() = runPwrSeq(Rtl8822bTables.PWR_OFF, 200, fatal = false)

        private fun powerOn() {
            powerOff() // from whatever state the adapter was left in
            runPwrSeq(Rtl8822bTables.PWR_ON, 500, fatal = true)
        }

        private fun readChipVersion() {
            val v = r32(0x00F0)
            cut = (v ushr 12) and 0xf
            rf2t2r = v and (1 shl 27) != 0
            UsbWifi.log("$label: 8822B cut $cut, ${if (rf2t2r) "2T2R" else "1T1R"} (SYS_CFG1 0x%08x)".format(v))
        }

        private fun enableBbRf() {
            w8(0x0002, r8(0x0002) or 0x03)
            w8(0x001F, r8(0x001F) or 0x07)
            w32(0x00EC, r32(0x00EC) or (0x7 shl 24))
        }

        private fun preInitSystemCfg() {
            w8(0x001C, 0)
            if (r8(0x00FF) == 0x20) w8(0xFE5B, r8(0xFE5B) or 0x10) // USB 3 workaround
            w32(0x0064, r32(0x0064) or (3 shl 28)) // pinmux
            w32(0x004C, r32(0x004C) and ((1 shl 25) or (1 shl 26)).inv())
            w32(0x0040, r32(0x0040) or (1 shl 2))
            w8(0x0002, r8(0x0002) and 0x03.inv())
            w8(0x001F, r8(0x001F) and 0x07.inv())
            w32(0x00EC, r32(0x00EC) and (0x7 shl 24).inv())
        }

        private fun initSystemCfg() {
            w32(0x1080, r32(0x1080) or (1 shl 16)) // WL platform reset only
            w8(0x0003, r8(0x0003) or 0xD8)
            val mcu = r32(0x0080)
            if (mcu and (1 shl 20) != 0) { // boot from flash: off
                w32(0x0080, mcu and (1 shl 20).inv())
                w32(0x0040, r32(0x0040) and (1 shl 19).inv())
            }
            if (cut == 1) w8(0x1018, r8(0x1018) and 0x07.inv())
        }

        // --- firmware download (HalmacJaguar2Fw) ---

        private fun downloadFirmware(fw: ByteArray): Boolean {
            val lteBackup = ltecoexRead(0x38) ?: 0
            wlanCpuEn(false)
            val pqMap1 = r8(0x010D); w8(0x010D, 3 shl 6)
            val cr = r8(0x0100)
            w8(0x0100, 0x01 or 0x04) // HCI TXDMA + TXDMA
            w32(0x1330, 1 shl 31)
            val page1 = r16(0x0230)
            val rqpn = r32(0x022C) or (1 shl 31)
            w16(0x0230, 0x200); w32(0x022C, rqpn)
            val bcn = r8(0x0550)
            w8(0x0550, (bcn and 0x08.inv()) or 0x10)
            pltfmReset()

            var ok = startDlfw(fw)

            w8(0x010D, pqMap1); w8(0x0100, cr); w32(0x1330, 1 shl 31)
            w16(0x0230, page1); w32(0x022C, rqpn); w8(0x0550, bcn)
            if (ok) ok = dlfwEndFlow()
            if (!ok) {
                w8(0x0080, r8(0x0080) and 0x01.inv())
                w8(0x0003, r8(0x0003) or 0x04)
            }
            ltecoexWrite(0x38, lteBackup)
            return ok
        }

        private fun wlanCpuEn(on: Boolean) {
            if (on) { w8(0x001D, r8(0x001D) or 0x01); w8(0x0003, r8(0x0003) or 0x04) }
            else { w8(0x0003, r8(0x0003) and 0x04.inv()); w8(0x001D, r8(0x001D) and 0x01.inv()) }
        }

        private fun pltfmReset() {
            w8(0x1082, r8(0x1082) and 0x01.inv())
            w8(0x0009, r8(0x0009) and 0x40.inv())
            w8(0x1082, r8(0x1082) or 0x01)
            w8(0x0009, r8(0x0009) or 0x40)
        }

        private fun ltecoexReady(): Boolean {
            val end = System.currentTimeMillis() + 500
            while (r8(0x1703) and 0x20 == 0) if (System.currentTimeMillis() > end) return false
            return true
        }

        private fun ltecoexRead(offset: Int): Int? {
            if (!ltecoexReady()) { UsbWifi.log("$label: LTE coex not ready (read)"); return null }
            w32(0x1700, 0x800F0000.toInt() or offset)
            return r32(0x1708)
        }

        private fun ltecoexWrite(offset: Int, v: Int) {
            if (!ltecoexReady()) { UsbWifi.log("$label: LTE coex not ready (write)"); return }
            w32(0x1704, v)
            w32(0x1700, 0xC00F0000.toInt() or offset)
        }

        private fun startDlfw(fw: ByteArray): Boolean {
            w16(0x0080, (r16(0x0080) and 0x3800) or 0x1)
            val dmem = Rtl8822bTables.dmemSize(fw); val imem = Rtl8822bTables.imemSize(fw); val emem = Rtl8822bTables.ememSize(fw)
            var at = Rtl8822bTables.FW_HDR_SIZE
            if (!dlfwToMem(fw, at, Rtl8822bTables.dmemAddr(fw), dmem)) return false
            at += dmem
            if (!dlfwToMem(fw, at, Rtl8822bTables.imemAddr(fw), imem)) return false
            at += imem
            if (emem != 0 && !dlfwToMem(fw, at, Rtl8822bTables.ememAddr(fw), emem)) return false
            return true
        }

        private fun dlfwToMem(fw: ByteArray, from: Int, dest: Int, size: Int): Boolean {
            w32(0x1208, r32(0x1208) or (1 shl 25)) // reset the DDMA checksum
            var done = 0; var first = true
            while (done < size) {
                val pkt = minOf(DLFW_CHUNK, size - done)
                if (!sendFwPage(0, fw, from + done, pkt)) return false
                if (!iddmaDlfw(OCPBASE_TXBUF + TXDESC_SIZE + lastPktOffset, dest + done, pkt, first)) return false
                first = false
                done += pkt
            }
            return checkFwChksum(dest)
        }

        private fun ddmaIdle(): Boolean {
            repeat(1000) { if (r32(0x1208) and (1 shl 31) == 0) return true }
            return false
        }

        private fun iddmaDlfw(src: Int, dest: Int, len: Int, first: Boolean): Boolean {
            if (!ddmaIdle()) { UsbWifi.log("$label: firmware DMA busy"); return false }
            var ctrl = (1 shl 29) or (1 shl 31) or (len and 0x3ffff) // checksum on, owned by DMA
            if (!first) ctrl = ctrl or (1 shl 24) // continue the checksum
            w32(0x1200, src); w32(0x1204, dest); w32(0x1208, ctrl)
            return ddmaIdle().also { if (!it) UsbWifi.log("$label: firmware DMA stuck") }
        }

        private fun checkFwChksum(memAddr: Int): Boolean {
            var fwCtrl = r8(0x0080)
            val imem = memAddr < OCPBASE_DMEM
            if (r32(0x1208) and (1 shl 27) != 0) {
                fwCtrl = if (imem) (fwCtrl or 0x08) and 0x10.inv() else (fwCtrl or 0x20) and 0x40.inv()
                w8(0x0080, fwCtrl)
                UsbWifi.log("$label: firmware checksum failed (${if (imem) "IMEM" else "DMEM"})")
                return false
            }
            w8(0x0080, fwCtrl or if (imem) 0x18 else 0x60)
            return true
        }

        /** Sends one chunk into the reserved TX page, the way HalMAC's dl_rsvd_page does. */
        private fun sendFwPage(page: Int, src: ByteArray, from: Int, size: Int): Boolean {
            w16(0x0204, (page and 0xfff) or 0x8000)
            val cr1 = r8(0x0101); w8(0x0101, cr1 or 0x01)
            val txq2 = r8(0x0422); w8(0x0422, txq2 and 0x40.inv())
            // A transfer that's an exact multiple of the bulk packet size gets 8 bytes of
            // padding the descriptor declares, so it still ends with a short packet.
            var len = TXDESC_SIZE + size
            val pad = if (len % 512 == 0) 8 else 0
            len += pad
            lastPktOffset = pad
            val frame = ByteArray(len)
            setDesc(frame, 0x00, 0, 16, size)                   // TXPKTSIZE
            setDesc(frame, 0x00, 16, 8, TXDESC_SIZE + pad)      // OFFSET
            if (pad != 0) setDesc(frame, 0x04, 24, 5, 1)        // PKT_OFFSET
            setDesc(frame, 0x04, 8, 5, QSEL_BEACON)             // reserved-page queue
            txDescChecksum(frame)
            System.arraycopy(src, from, frame, TXDESC_SIZE + pad, size)
            conn.bulkTransfer(epOut, frame, frame.size, 1000)
            // The beacon-valid latch, not the bulk completion, says the page arrived.
            var ok = false
            val end = System.currentTimeMillis() + 200
            while (System.currentTimeMillis() < end) { if (r8(0x0205) and 0x80 != 0) { ok = true; break } }
            if (!ok) UsbWifi.log("$label: firmware page not acknowledged (offset $from, $size B)")
            w16(0x0204, rsvdBoundary or 0x8000)
            w8(0x0422, txq2)
            w8(0x0101, cr1)
            return ok
        }

        private fun dlfwEndFlow(): Boolean {
            w32(0x0210, 1 shl 2)
            val fwCtrl = r16(0x0080)
            if (fwCtrl and 0x50 != 0x50) { UsbWifi.log("$label: firmware IMEM/DMEM checksum not ready (0x%04x)".format(fwCtrl)); return false }
            w16(0x0080, (fwCtrl or (1 shl 14)) and 0x01.inv())
            wlanCpuEn(true)
            val end = System.currentTimeMillis() + 1000
            while (r16(0x0080) != 0xC078) {
                if (System.currentTimeMillis() > end) {
                    UsbWifi.log("$label: firmware didn't report ready (0x80 = 0x%04x)".format(r16(0x0080)))
                    return false
                }
                sleep(1)
            }
            return true
        }

        // --- MAC init after the firmware runs (HalmacJaguar2MacInit) ---

        private fun initMacCfg() {
            // TX DMA queue mapping (USB, 3 bulk-OUT) and page allocation.
            w16(0x010C, (3 shl 14) or (3 shl 12) or (1 shl 10) or (1 shl 8) or (2 shl 6) or (2 shl 4))
            val fwff = r8(0x0601) and 0x80
            if (fwff != 0) w8(0x0601, r8(0x0601) and 0x80.inv())
            w8(0x0100, 0)
            w16(0x029C, r16(0x02A0))
            w8(0x0100, 0xFF) // the whole MAC on before the LLT init
            if (fwff != 0) w8(0x0601, r8(0x0601) or 0x80)
            w32(0x1330, 1 shl 31)

            val txPages = TX_FIFO_SIZE shr 7                    // 2048 pages of 128 B
            val rsvdPages = 16 + 24 + 8 + 8 + 0 + 4 + 50        // drv, h2c extra/static, h2cq, cpu, fw txbuf, csi
            rsvdBoundary = txPages - rsvdPages                  // 1938
            val csiAddr = txPages - 50
            val pub = rsvdBoundary - 64 - 64 - 64 - 0 - 1
            w16(0x0230, 64); w16(0x0234, 64); w16(0x0238, 64); w16(0x023C, 0); w16(0x0240, pub)
            w32(0x022C, r32(0x022C) or (1 shl 31))
            w16(0x169C, csiAddr)
            w8(0x0422, r8(0x0422) or 0x10)
            w16(0x0204, rsvdBoundary); w16(0x0424, rsvdBoundary); w16(0x0206, rsvdBoundary); w16(0x0456, rsvdBoundary)
            w32(0x011C, RX_FIFO_SIZE - 256 - 1)
            w8(0x0208, (r8(0x0208) and 0xF0.inv()) or (3 shl 4))
            w8(0x020B, 3)
            w8(0x020D, r8(0x020D) or 0x02)
            w8(0x0208, r8(0x0208) or 0x01) // auto LLT init
            var n = 1000
            while (r8(0x0208) and 0x01 != 0) if (--n == 0) throw IOException("LLT init timed out")
            w8(0x0103, 0)

            // H2C queue
            val h2cq = (txPages - 50 - 4 - 0 - 8) shl 7
            val h2cqSize = 8 shl 7
            w32(0x0244, (r32(0x0244) and 0xFFFC0000.toInt()) or h2cq)
            w32(0x024C, (r32(0x024C) and 0xFFFC0000.toInt()) or h2cq)
            w32(0x0248, (r32(0x0248) and 0xFFFC0000.toInt()) or (h2cq + h2cqSize))
            w8(0x0254, (r8(0x0254) and 0xFC) or 0x01)
            w8(0x0254, (r8(0x0254) and 0xFB) or 0x04)
            w8(0x020D, (r8(0x020D) and 0x7f) or 0x80)

            // MAC clock 80 MHz and the matching microsecond ticks.
            w32(0x0024, r32(0x0024) and ((1 shl 20) or (1 shl 21)).inv())
            w8(0x055C, 80); w8(0x0638, 80)

            // Protocol
            w8(0x04BC, r8(0x04BC) and 0x40.inv())
            w8(0x0455, 0x70)
            w8(0x045E, r8(0x045E) or 0x04)
            w32(0x04C8, 0xFF or (0x08 shl 8) or (0x20 shl 16) or (0x20 shl 24))
            w16(0x04CE, 0x01 or (0x08 shl 8))
            w8(0x1448, 6); w8(0x144A, 6); w8(0x144C, 6); w8(0x144E, 6)
            w8(0x0480, r8(0x0480) or 0x20)

            // EDCA
            w8(0x05B4, r8(0x05B4) and 0x70.inv())
            w16(0x0522, 0)
            w8(0x051B, 0x09); w8(0x0512, 0x19)
            w32(0x0514, 0x0A or (0x0E shl 8) or (0x10 shl 16) or (0x10 shl 24))
            w16(0x0502, 0x186); w16(0x0506, 0x3BC)
            w32(0x0544, 0x05 or (0x1B shl 16))
            w16(0x055E, 0x30 or (0x30 shl 8))
            w8(0x0550, r8(0x0550) or 0x08)
            w32(0x0540, 0x04 or (0x064 shl 8))
            w8(0x0558, 0x04); w8(0x0559, 0x02)
            w8(0x0521, r8(0x0521) and 0x10.inv())

            // WMAC: group-address filter and RX filter maps wide open.
            w32(0x0620, -1); w32(0x0624, -1)
            w32(0x06A0, 0x0FFFFFFF); w16(0x06A4, 0xFFFF)
            w32(0x0608, 0xE400220E.toInt())
            w8(0x060C, 12288 shr 9)
            w8(0x0606, 0x30); w8(0x0605, 0x30)
            w8(0x066C, r8(0x066C) or 0x02)
            w8(0x0718, r8(0x0718) or 0x40)
            w32(0x07D8, 0x30810041); w8(0x07D4, 0x98)
        }

        /** USB RX DMA and aggregation (kept under 16 KB per transfer). */
        private fun initUsbCfg() {
            val usb3 = r8(0x00FF) == 0x20
            var v = 0x02 or (0x3 shl 2)
            v = v or when { usb3 -> 0; r8(0xFE11) and 0x3 == 0x1 -> 0x10; else -> 0x20 }
            w8(0x0290, v)
            w16(0x020C, r16(0x020C) or (1 shl 9))
            val agg = r8(0x0283) and 0x80.inv()
            val pq = r8(0x010C) or 0x04
            w32(0x0280, r32(0x0280) and (1 shl 29).inv())
            w8(0x010C, pq)
            w8(0x0283, agg)
            w16(0x0280, 0x03 or ((if (usb3) 0x0A else 0x20) shl 8))
            UsbWifi.log("$label: USB ${if (usb3) "3" else "2"}, RX aggregation on")
        }

        /** Physical efuse -> logical map walk; the RFE type (antenna front-end) is at 0xCA. */
        private fun readEfuseRfe(): Int {
            fun efuse(addr: Int): Int {
                w8(0x0031, addr and 0xff)
                w8(0x0032, ((addr shr 8) and 0x03) or (r8(0x0032) and 0xFC))
                w8(0x0033, r8(0x0033) and 0x7f)
                val end = System.currentTimeMillis() + 100
                while (r8(0x0033) and 0x80 == 0) { if (System.currentTimeMillis() > end) return 0xFF }
                return r8(0x0030)
            }
            var rfe = 0xFF
            var phys = 0
            while (phys < 1024) {
                val hdr = efuse(phys++)
                if (hdr == 0xFF) break
                val offset: Int; val wordEn: Int
                if (hdr and 0x1F == 0x0F) {
                    val ext = efuse(phys++)
                    if (ext and 0x0F == 0x0F) continue
                    offset = ((ext and 0xF0) shr 1) or ((hdr and 0xE0) shr 5)
                    wordEn = ext and 0x0F
                } else {
                    offset = (hdr shr 4) and 0x0F
                    wordEn = hdr and 0x0F
                }
                val base = offset shl 3
                for (i in 0 until 4) {
                    if (wordEn and (1 shl i) != 0) continue
                    for (k in 0 until 2) {
                        val d = efuse(phys++)
                        if (base + i * 2 + k == 0xCA) rfe = d
                    }
                }
            }
            UsbWifi.log("$label: efuse RFE type ${if (rfe == 0xFF) "blank (using 0)" else "0x%02x".format(rfe)}")
            return if (rfe == 0xFF) 0 else rfe
        }

        /** HalMAC GENERAL_INFO + PHYDM_INFO to the firmware, as the Linux driver sends after boot. */
        private fun sendFwGeneralInfo() {
            val gen = ByteArray(32)
            gen[0] = 0x01; gen[1] = 0xff.toByte(); gen[2] = 0x0d; gen[4] = 12; gen[6] = (h2cSeq++).toByte()
            gen[10] = (TX_FIFO_SIZE / 128 - 50 - 4 - rsvdBoundary).toByte() // fw TX buffer page above the boundary
            val phy = ByteArray(32)
            phy[0] = 0x01; phy[1] = 0xff.toByte(); phy[2] = 0x11; phy[4] = 16; phy[6] = (h2cSeq++).toByte()
            phy[8] = rfe.toByte(); phy[9] = (if (rf2t2r) 0x02 else 0x04).toByte(); phy[10] = cut.toByte()
            phy[11] = (if (rf2t2r) 0x33 else 0x11).toByte(); phy[13] = 7
            for (p in listOf(gen, phy)) {
                val frame = ByteArray(TXDESC_SIZE + 32)
                setDesc(frame, 0x00, 0, 16, 32)
                setDesc(frame, 0x04, 8, 5, 0x13) // H2C queue
                txDescChecksum(frame)
                System.arraycopy(p, 0, frame, TXDESC_SIZE, 32)
                if (conn.bulkTransfer(epOut, frame, frame.size, 1000) != frame.size)
                    UsbWifi.log("$label: firmware info message not sent (continuing)")
            }
        }

        // --- BB / RF (HalJaguar2) ---

        private fun applyTables() {
            bb(0x0808, (1 shl 28) or (1 shl 29), 0) // OFDM / CCK off while loading
            for (tab in listOf(t.phyReg, t.agcTab)) Rtl8822bTables.walk(tab, cut, rfe) { a, v ->
                when (a) {
                    0xfe -> sleep(50)
                    0xfd -> sleep(5)
                    0xfc -> sleep(1)
                    0xfb, 0xfa, 0xf9 -> {} // microsecond delays: a USB round trip is longer
                    else -> w32(a, v)
                }
            }
            Rtl8822bTables.walk(t.radioA, cut, rfe) { a, v -> rfWrite(0, a, v) }
            Rtl8822bTables.walk(t.radioB, cut, rfe) { a, v -> rfWrite(1, a, v) }
            bb(0x0808, (1 shl 28) or (1 shl 29), 0x3)
            UsbWifi.log("$label: BB / AGC / RF tables applied (rfe $rfe)")
        }

        /** RF mode table and TX / RX antenna paths (config_phydm_trx_mode_8822b). */
        private fun configTrxMode() {
            val path = if (rf2t2r) 0x3 else 0x1
            bb(0x0c08, 0xffff, 0x3231)
            if (rf2t2r) bb(0x0e08, 0xffff, 0x3231)
            bb(0x093c, (1 shl 19) or (1 shl 18), 0x3)
            bb(0x080c, (1 shl 29) or (1 shl 28), 0x1)
            bb(0x080c, 1 shl 30, 0x1)
            bb(0x080c, 0xff, (path shl 4) or path)
            bb(0x0a04, 0xf0000000.toInt(), 0x8)
            bb(0x093c, 0xfff00000.toInt(), 0x001)
            if (rf2t2r) bb(0x0940, 0xfff0, 0x043) else { bb(0x0940, 0xf0, 0x1); bb(0x0940, 0xff00, 0x0) }
            w32(0x19a8, 0xd90a0000.toInt()) // SoML on
            bb(0x0a2c, 1 shl 22, 0); bb(0x0a2c, 1 shl 18, 0)
            bb(0x0a04, 0x0f000000, 0)
            bb(0x0808, 0xff, (path shl 4) or path)
            val two = if (rf2t2r) 1 else 0
            bb(0x1904, 1 shl 16, two); bb(0x0800, 1 shl 28, two); bb(0x0850, 1 shl 23, two)
            for (i in 0 until 100) {
                rfWrite(0, 0xef, 0x80000); rfWrite(0, 0x33, 0x00001)
                if (rfRead(0, 0x33) == 0x00001) break
            }
            rfWrite(0, 0xef, 0x80000); rfWrite(0, 0x33, 0x00001)
            rfWrite(0, 0x3e, 0x00034); rfWrite(0, 0x3f, 0x4080c); rfWrite(0, 0xef, 0x00000)
        }

        /** LC calibration: locks the RF local oscillator. */
        private fun doLck() {
            val aac = (rfRead(0, 0xc9) and 0xf8) shr 3
            if (aac < 4 || aac > 7) { rfSet(0, 0xca, 1 shl 19, 0); rfSet(0, 0xb2, 0x7c000, 0x6) }
            val c00 = r32(0x0c00); val e00 = r32(0x0e00)
            w32(0x0c00, 0x4); w32(0x0e00, 0x4)
            rfWrite(0, 0x0, 0x10000); if (rf2t2r) rfWrite(1, 0x0, 0x10000)
            val lc = rfRead(0, 0x18)
            rfWrite(0, 0xc4, 0x01402)
            rfWrite(0, 0x18, lc or 0x08000)
            sleep(100)
            var locked = false
            for (i in 0 until 5) { if (rfRead(0, 0x18) and 0x8000 == 0) { locked = true; break }; sleep(10) }
            rfWrite(0, 0x18, lc and 0x08000.inv()); rfWrite(0, 0xc4, 0x81402)
            w32(0x0c00, c00); w32(0x0e00, e00)
            rfWrite(0, 0x0, 0x3ffff); if (rf2t2r) rfWrite(1, 0x0, 0x3ffff)
            UsbWifi.log("$label: LC calibration ${if (locked) "locked" else "timed out (continuing)"}")
        }

        /** RFE mux: chip-top pins and the RFE source select (phydm_rfe_8822b_init). */
        private fun rfeInit() {
            bb(0x0064, (1 shl 29) or (1 shl 28), 0x3)
            bb(0x004c, (1 shl 26) or (1 shl 25), 0x0)
            bb(0x0040, 1 shl 2, 0x1)
            bb(0x1990, 0x3f, 0x30)
            bb(0x1990, (1 shl 11) or (1 shl 10), 0x3)
            bb(0x0974, 0x3f, 0x3f)
            bb(0x0974, (1 shl 11) or (1 shl 10), 0x3)
        }

        /** Gives the antennas to WiFi (without it a combo chip leaves them with Bluetooth). */
        private fun coexWlanOnly(is5g: Boolean) {
            bb(0x004c, 0x01800000, 0x2)
            bb(0x0cb4, 0xff, 0x77)
            bb(0x0974, 0x300, 0x3)
            bb(0x1990, 0x300, 0x0)
            bb(0x0cbc, 0x80000, 0x0)
            bb(0x0070, 0xff000000.toInt(), 0x0e)
            w32(0x1704, 0x7700)
            w32(0x1700, 0xc00f0038.toInt())
            switchAntenna(is5g)
        }

        private fun switchAntenna(is5g: Boolean) = bb(0x0cbc, 0x300, if (is5g) 0x1 else 0x2)

        /** MAC RX on, promiscuous monitor RCR (with PHY status), starting gain. */
        private fun enableRx() {
            w16(0x0100, 0x06FF)
            w32(0x0608, 0xF000002F.toInt())
            igi = 0x40
            bb(0x0c50, 0x7f, igi); bb(0x0e50, 0x7f, igi)
        }

        // --- channels ---

        private var tunedCh = 0
        private var primed = false
        private var rf18Cache = 0; private var agcCache = 0; private var fcCache = 0; private var rfBeCache = 0
        private var lastAgcBucket = -1; private var lastFc = -1; private var lastRfBe = -1; private var lastDf18 = -1; private var lastCckKey = -1

        /** Moves to channel [ch]: a full channel set on a band change, the fast path within a band. */
        fun tune(ch: Int) {
            if (ch == tunedCh) return
            if (tunedCh == 0 || (tunedCh <= 14) != (ch <= 14)) {
                setChannel(ch)
                switchAntenna(ch > 14)
            } else fastRetune(ch)
        }

        private fun rfeIfem(ch: Int) {
            val g2 = ch <= 14
            bb(0x0cb0, 0xffffff, if (g2) 0x745774 else 0x477547)
            bb(0x0eb0, 0xffffff, if (g2) 0x745774 else 0x477547)
            bb(0x0cb4, 0x0000ff00, if (g2) 0x57 else 0x75)
            bb(0x0eb4, 0x0000ff00, if (g2) 0x57 else 0x75)
            bb(0x0cbc, 0x3f, 0x0); bb(0x0cbc, (1 shl 11) or (1 shl 10), 0x0)
            bb(0x0ebc, 0x3f, 0x0); bb(0x0ebc, (1 shl 11) or (1 shl 10), 0x0)
            bb(0x0ca0, 0x0000ffff, if (g2) 0xa501 else 0xa5a5)
            bb(0x0ea0, 0x0000ffff, if (g2) 0xa501 else 0xa5a5)
        }

        /** CCK block, CCK checks and CCA mask per band (config_phydm_switch_band_8822b). */
        private fun bandBlock(g2: Boolean) {
            if (g2) {
                bb(0x0808, 1 shl 28, 0x1)
                bb(0x0454, 1 shl 7, 0x0)
                bb(0x0a80, 1 shl 18, 0x0)
                bb(0x0814, 0x0000FC00, 15)
            } else {
                bb(0x0a80, 1 shl 18, 0x1)
                bb(0x0454, 1 shl 7, 0x1)
                bb(0x0808, 1 shl 28, 0x0)
                bb(0x0814, 0x0000FC00, 34)
            }
        }

        private fun igiToggle() {
            val v = r32(0x0c50) and 0x7f
            bb(0x0c50, 0x7f, v - 2); bb(0x0c50, 0x7f, v)
            bb(0x0e50, 0x7f, v - 2); bb(0x0e50, 0x7f, v)
        }

        /** Full 20 MHz channel set (config_phydm_switch_channel / _bandwidth_8822b). */
        private fun setChannel(ch: Int) {
            primed = false; lastAgcBucket = -1; lastFc = -1; lastRfBe = -1; lastDf18 = -1; lastCckKey = -1
            val g2 = ch <= 14
            rfeIfem(ch)
            bandBlock(g2)
            var rf18 = rf18For(rfRead(0, 0x18), ch)
            if (g2) {
                bb(0x0958, 0x1f, 0x0)
                bb(0x0860, 0x1ffe0000, 0x96a)
                if (ch == 14) { w32(0x0a24, 0x00006577); bb(0x0a28, 0xffff, 0x0000) }
                else { w32(0x0a24, 0x384f6577); bb(0x0a28, 0xffff, 0x1525) }
            } else {
                agcBucket(ch).takeIf { it >= 0 }?.let { bb(0x0958, 0x1f, it) }
                fcFor(ch).takeIf { it >= 0 }?.let { bb(0x0860, 0x1ffe0000, it) }
            }
            bb(0x0c04, (1 shl 21) or (1 shl 18), 0x0)
            bb(0x0e04, (1 shl 21) or (1 shl 18), 0x0)
            if (!g2 || rfe !in intArrayOf(3, 5, 8, 17)) { w32(0x08cc, 0x08108000); bb(0x08d8, 1 shl 27, 0) }
            else { w32(0x08cc, 0x08108492); bb(0x08d8, 1 shl 27, 1) }
            val be = rfBeFor(ch)
            if (be != 0xff) rfSet(0, 0xbe, 0x38000, be)
            rfSet(0, 0xdf, 1 shl 18, if (ch == 144) 1 else 0) // before RF18 on channel 144
            // 20 MHz
            w32(0x08ac, r32(0x08ac) and 0xFFCFFC00.toInt())
            bb(0x08c4, 1 shl 30, 0x1)
            rfWrite(0, 0x18, rf18)
            if (rf2t2r) rfWrite(1, 0x18, rf18)
            rfSet(0, 0xb8, 1 shl 19, 0); rfSet(0, 0xb8, 1 shl 19, 1)
            // RX digital filter for 20 MHz
            bb(0x0948, (1 shl 29) or (1 shl 28), 0x2)
            bb(0x094c, (1 shl 29) or (1 shl 28), 0x2)
            bb(0x0c20, 1 shl 31, 0x1)
            bb(0x0e20, 1 shl 31, 0x1)
            // CCA thresholds
            val col = if (g2) (if (rf2t2r) 1 else 0) else (if (rf2t2r) 3 else 2)
            val cca = if (rfe in intArrayOf(3, 5, 12, 15, 16, 17, 19)) CCA_IFEM_RFE else CCA_IFEM
            w32(0x082c, cca[0][col]); w32(0x0830, cca[1][col]); w32(0x0838, cca[2][col])
            if (!g2 && (ch in 52..64 || ch in 100..144)) bb(0x0838, 0xf0, 0x5)
            // Leave the RX dead zone: toggle the RX paths, then the gain.
            val rxAnt = if (rf2t2r) 0x3 else 0x1
            bb(0x0808, 0xff, 0x0)
            bb(0x0808, 0xff, rxAnt or (rxAnt shl 4))
            igiToggle()
            // Spur notch / CSI mask reset (the PSD spur search itself isn't ported).
            bb(0x087c, 1 shl 13, 0x0); bb(0x0c20, 1 shl 28, 0x0); bb(0x0e20, 1 shl 28, 0x0)
            for (a in 0x880..0x89c step 4) w32(a, 0)
            bb(0x0874, 0x1, 0x0)
            tunedCh = ch
        }

        /** Within-band hop: RF18 plus the channel-keyed constants that changed (devourer fast_retune). */
        private fun fastRetune(ch: Int) {
            if (!primed) {
                rf18Cache = rfRead(0, 0x18); agcCache = r32(0x0958); fcCache = r32(0x0860); rfBeCache = rfRead(0, 0xbe)
                primed = true
            }
            val g2 = ch <= 14
            var rf18 = rf18For(rf18Cache, ch)
            val agc = agcBucket(ch)
            if (agc >= 0 && agc != lastAgcBucket) { agcCache = (agcCache and 0x1f.inv()) or agc; w32(0x0958, agcCache); lastAgcBucket = agc }
            val fc = fcFor(ch)
            if (fc >= 0 && fc != lastFc) { fcCache = (fcCache and 0x1ffe0000.inv()) or (fc shl 17); w32(0x0860, fcCache); lastFc = fc }
            val be = rfBeFor(ch)
            if (be != 0xff && be != lastRfBe) { rfBeCache = (rfBeCache and 0x38000.inv()) or (be shl 15); rfWrite(0, 0xbe, rfBeCache); lastRfBe = be }
            val df18 = if (ch == 144) 1 else 0
            if (df18 != lastDf18) { rfSet(0, 0xdf, 1 shl 18, df18); lastDf18 = df18 }
            if (g2) {
                val key = if (ch == 14) 1 else 0
                if (key != lastCckKey) {
                    if (ch == 14) { w32(0x0a24, 0x00006577); bb(0x0a28, 0xffff, 0x0000) }
                    else { w32(0x0a24, 0x384f6577); bb(0x0a28, 0xffff, 0x1525) }
                    lastCckKey = key
                }
            }
            rfWrite(0, 0x18, rf18)
            if (rf2t2r) rfWrite(1, 0x18, rf18)
            rf18Cache = rf18
            tunedCh = ch
        }

        /**
         * Dynamic initial gain: raises the receiver's gain floor when false alarms flood
         * in and lowers it when the channel is quiet, so weak signals still decode.
         * Thresholds are devourer's per-100 ms values scaled to [DIG_MS].
         */
        /** Path that receives CCK (0xa04[27:24]: 0 = path A, 5 = path B; phydm_config_rx_path_8822b). */
        fun setCckPath(path: Int) = bb(0x0a04, 0x0f000000, if (path == 1) 0x5 else 0x0)

        fun setIgi(v: Int) { igi = v and 0x7f; bb(0x0c50, 0x7f, igi); bb(0x0e50, 0x7f, igi) }

        fun digStep() {
            val ofdm = r32(0x0f48) and 0xffff
            val cck = r32(0x0a5c) and 0xffff
            val fa = ofdm + cck
            lastFa = fa
            bb(0x09a4, 1 shl 17, 1); bb(0x09a4, 1 shl 17, 0) // reset the OFDM / CCK counters
            bb(0x0a2c, 1 shl 15, 0); bb(0x0a2c, 1 shl 15, 1)
            val cur = r8(0x0c50) and 0x7f
            val scale = (DIG_MS / 100).toInt()
            var next = when {
                fa > 750 * scale -> cur + 2
                fa > 500 * scale -> cur + 1
                fa < 250 * scale -> if (cur >= 2) cur - 2 else cur
                else -> cur
            }
            next = next.coerceIn(0x1c, 0x3e)
            if (next != cur) { bb(0x0c50, 0x7f, next); bb(0x0e50, 0x7f, next) }
            igi = next
        }
    }

    // --- channel constants (config_phydm_switch_channel_8822b) ---

    /**
     * RF 0x18 for a 20 MHz channel, from its current value: band, channel, sub-band and
     * bandwidth fields set explicitly (rtw8822b_set_channel_rf), other bits kept except
     * the LC-calibration trigger (bit 15), which the read-back can still show set.
     */
    internal fun rf18For(cur: Int, ch: Int): Int {
        var v = cur and (RF18_BAND or 0xff or RF18_RFSI or RF18_BW or RF18_LCK).inv()
        if (ch > 14) v = v or (1 shl 16) or (1 shl 8)
        v = v or (ch and 0xff)
        if (ch > 144) v = v or (1 shl 18) else if (ch >= 80) v = v or (1 shl 17)
        return v or RF18_BW // 20 MHz
    }

    private const val RF18_BAND = (1 shl 16) or (1 shl 9) or (1 shl 8)
    private const val RF18_RFSI = (1 shl 18) or (1 shl 17)
    private const val RF18_BW = (1 shl 11) or (1 shl 10)
    private const val RF18_LCK = 1 shl 15

    internal fun agcBucket(ch: Int) = when {
        ch <= 14 -> 0
        ch <= 64 -> 1
        ch in 100..144 -> 2
        ch >= 149 -> 3
        else -> -1
    }

    internal fun fcFor(ch: Int) = when {
        ch <= 14 -> 0x96a
        ch <= 48 -> 0x494
        ch in 52..64 -> 0x453
        ch in 100..116 -> 0x452
        ch >= 118 -> 0x412
        else -> -1
    }

    /** RF 0xBE[17:15] VCO band for a 5 GHz channel; 0 on 2.4 GHz, 0xff for no change. */
    internal fun rfBeFor(ch: Int): Int {
        if (ch <= 14) return 0
        return when {
            ch < 36 -> RF_BE_LOW[0]
            ch <= 64 -> RF_BE_LOW[(ch - 36) shr 1]
            ch in 100..144 -> RF_BE_MID[(ch - 100) shr 1]
            ch in 149..177 -> RF_BE_HIGH[(ch - 149) shr 1]
            ch > 177 -> RF_BE_HIGH[(177 - 149) shr 1]
            else -> 0xff
        }
    }

    private val RF_BE_LOW = intArrayOf(0x7, 0x6, 0x6, 0x5, 0x0, 0x0, 0x7, 0xff, 0x6, 0x5, 0x0, 0x0, 0x7, 0x6, 0x6)
    private val RF_BE_MID = intArrayOf(0x6, 0x5, 0x0, 0x0, 0x7, 0x6, 0x6, 0xff, 0x0, 0x0, 0x7, 0x6,
        0x6, 0x5, 0x0, 0xff, 0x7, 0x6, 0x6, 0x5, 0x0, 0x0, 0x7)
    private val RF_BE_HIGH = intArrayOf(0x5, 0x5, 0x0, 0x7, 0x7, 0x6, 0x5, 0xff, 0x0, 0x7, 0x7, 0x6, 0x5, 0x5, 0x0)

    /** CCA thresholds 0x82c / 0x830 / 0x838; columns 2.4 GHz 1R, 2.4 GHz 2R, 5 GHz 1R, 5 GHz 2R. */
    private val CCA_IFEM = arrayOf(
        intArrayOf(0x75C97010, 0x75C97010, 0x75C97010, 0x75C97010),
        intArrayOf(0x79a0eaaa, 0x79A0EAAC, 0x79a0eaaa, 0x79a0eaaa),
        intArrayOf(0x87765541.toInt(), 0x87746341.toInt(), 0x87765541.toInt(), 0x87746341.toInt())
    )
    private val CCA_IFEM_RFE = arrayOf(
        intArrayOf(0x75da8010, 0x75da8010, 0x75da8010, 0x75da8010),
        intArrayOf(0x79a0eaaa, 0x97A0EAAC.toInt(), 0x79a0eaaa, 0x79a0eaaa),
        intArrayOf(0x87765541.toInt(), 0x86666341.toInt(), 0x87765561.toInt(), 0x86666361.toInt())
    )

    // --- HalMAC constants ---
    private const val TXDESC_SIZE = 48
    private const val QSEL_BEACON = 0x10
    private const val DLFW_CHUNK = 4096
    private const val OCPBASE_TXBUF = 0x18780000
    private const val OCPBASE_DMEM = 0x00200000
    private const val TX_FIFO_SIZE = 262144
    private const val RX_FIFO_SIZE = 24576

    /** Sets [bits] bits at [shift] of the little-endian dword at [at] in a TX descriptor. */
    internal fun setDesc(d: ByteArray, at: Int, shift: Int, bits: Int, v: Int) {
        val mask = if (bits == 32) -1 else ((1 shl bits) - 1) shl shift
        val cur = Rtl8822bTables.le32(d, at)
        val nv = (cur and mask.inv()) or ((v shl shift) and mask)
        for (k in 0 until 4) d[at + k] = (nv ushr (8 * k)).toByte()
    }

    /** The 8822B checksums only the first 32 bytes of a TX descriptor: XOR of 16 LE words. */
    internal fun txDescChecksum(d: ByteArray) {
        setDesc(d, 0x1C, 0, 16, 0)
        var sum = 0
        for (w in 0 until 16) sum = sum xor ((d[2 * w].toInt() and 0xff) or ((d[2 * w + 1].toInt() and 0xff) shl 8))
        setDesc(d, 0x1C, 0, 16, sum)
    }

    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
