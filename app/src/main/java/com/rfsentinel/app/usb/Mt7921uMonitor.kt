package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for MediaTek MT7921AU USB WiFi 6E adapters (e.g. ALFA
 * AWUS036AXML, Comfast CF-952AX, Netgear A8000 / A7500, TP-Link TXE50UH): 2.4, 5 and
 * 6 GHz, driven from userspace over Android's USB host API - no root, no kernel driver.
 * Loads MediaTek's ROM patch and RAM firmware (assets/usbwifi/WIFI_*MT7961*.bin, from
 * linux-firmware; licence in LICENCE.mediatek), puts the radio in sniffer mode, hops the
 * three bands (6 GHz on its 15 preferred scanning channels, where access points beacon)
 * and reports the access points and client devices it hears. It never transmits.
 *
 * Ported from mt76-usb-macos by Network Weather (https://github.com/Network-Weather/mt76-usb-macos,
 * BSD-3-Clause-Clear), a transcription of the MT7921 path of the Linux mt76 driver
 * (openwrt/mt76: Copyright (C) 2020-2023 MediaTek Inc., (C) 2018 Lorenzo Bianconi,
 * (C) 2016 Felix Fietkau; BSD-3-Clause-Clear). Changes here: Android USB host API instead
 * of IOKit, no USB port reset (Android apps can't; the WFSYS reset covers a re-run),
 * commands sent while receiving don't wait for their answer on the shared receive
 * endpoint (it's counted as it arrives), frames with a bad checksum are dropped (a
 * corrupted address would show up as a phantom device), and every transmit path
 * (frame injection, TX descriptors) was left out.
 */

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

object Mt7921uMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()

    /** Band (0 = 2.4, 1 = 5, 2 = 6 GHz) and channel of each hop. 6 GHz: the preferred scanning channels. */
    internal val HOP: List<Pair<Int, Int>> =
        (1..13).map { 0 to it } +
        listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
            149, 153, 157, 161, 165).map { 1 to it } +
        listOf(5, 21, 37, 53, 69, 85, 101, 117, 133, 149, 165, 181, 197, 213, 229).map { 2 to it }
    private const val DWELL_MS = 150L
    /** Each 6 GHz channel is visited every this many rounds, unless something was heard on it lately. */
    private const val SIX_GHZ_EVERY = 3
    /** A 6 GHz channel that carried a frame this recently is visited every round. */
    private const val SIX_GHZ_ACTIVE_MS = 90_000L

    /**
     * The channels of hop round [round]: all of 2.4 and 5 GHz, and a rotating third of the 6 GHz
     * channels plus any 6 GHz channel in [active6]. 6 GHz switches are the slowest (often the full
     * timeout) and most places have little there yet, so a full 6 GHz pass every round would
     * stretch the loop by about half for nothing; every channel is still visited every 3 rounds.
     */
    internal fun roundPlan(round: Int, active6: Set<Int>): List<Pair<Int, Int>> {
        val six = HOP.filter { it.first == 2 }
        return HOP.filter { it.first != 2 } +
            six.filterIndexed { i, (_, ch) -> i % SIX_GHZ_EVERY == round % SIX_GHZ_EVERY || ch in active6 }
    }
    private const val RX_ANTENNAS = 0x3
    private const val SWITCH_TIMEOUT_MS = 500L
    /** A full round, allowing ~150 ms per channel switch on top of the dwell. */
    private val FRESH_MS get() = (HOP.size * (DWELL_MS + 150) * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L

    // ---- USB vendor requests (mt76 usb.c) ----
    private const val TYPE_VENDOR = 0x40 or 0x1F       // MT_USB_TYPE_VENDOR
    private const val TYPE_UHW_VENDOR = 0x40 or 0x1E   // MT_USB_TYPE_UHW_VENDOR
    private const val DIR_IN = 0x80
    private const val VEND_DEV_MODE = 0x01; private const val VEND_WRITE = 0x02; private const val VEND_POWER_ON = 0x04
    private const val VEND_READ_EXT = 0x63; private const val VEND_WRITE_EXT = 0x66

    // ---- registers (mt792x_regs.h) ----
    private const val HW_CHIPID = 0x70010200; private const val HW_REV = 0x70010204
    private const val CHIP_ID_7961 = 0x7961
    private const val CONN_ON_MISC = 0x7C0600F0
    private const val TOP_MISC_FW_STATE = 0x7; private const val FW_PWR_ON = 1 shl 0; private const val FW_N9_RDY = 0x3
    private const val UDMA_TX_QSEL = 0x74000008; private const val FW_DL_EN = 1 shl 3
    private const val UDMA_WLCFG_0 = 0x74000018; private const val UDMA_WLCFG_1 = 0x7400000C
    private const val WL_RX_EN = 1 shl 22; private const val WL_TX_EN = 1 shl 23; private const val WL_RX_FLUSH = 1 shl 19
    private const val WL_RX_AGG_PKT_LMT = 0xFF; private const val WL_TX_TMOUT_LMT = 0x0FFFFF00
    private const val WL_RX_AGG_TO = 0xFF; private const val WL_RX_AGG_LMT = 0xFF00
    private const val WL_TX_TMOUT_FUNC_EN = 1 shl 16; private const val WL_RX_MPSZ_PAD0 = 1 shl 18; private const val TICK_1US_EN = 1 shl 20
    private const val USB_TX_TIMEOUT_LIMIT = 50000
    private const val WL_RX_BUSY = 1 shl 30; private const val WL_TX_BUSY = 1 shl 31
    private const val UDMA_CONN_INFRA_STATUS = 0x74000A20; private const val UDMA_CONN_WFSYS_INIT_DONE = 1 shl 22
    private const val UDMA_CONN_INFRA_STATUS_SEL = 0x74000A24
    private const val SSUSB_EPCTL_CSR_EP_RST_OPT = 0x74011800 + 0x090
    private const val UWFDMA0_GLO_CFG = 0x7C024000 + 0x208
    private const val GLO_TX_DMA_EN = 1 shl 0; private const val GLO_RX_DMA_EN = 1 shl 2; private const val GLO_RX_DMA_BUSY = 1 shl 3
    private const val GLO_FW_DWLD_BYPASS_DMASHDL = 1 shl 9; private const val GLO_OMIT_RX_INFO_PFET2 = 1 shl 21
    private const val GLO_OMIT_RX_INFO = 1 shl 27; private const val GLO_OMIT_TX_INFO = 1 shl 28
    private const val WFDMA_DUMMY_CR = 0x54000000 + 0x120; private const val WFDMA_NEED_REINIT = 1 shl 1
    private const val WFDMA_HOST_CONFIG = 0x7C027030; private const val HOST_CONFIG_USB_RXEVT_EP4_EN = 1 shl 6
    private const val DMA_SHDL = 0x7C026000
    private const val CBTOP_RGU_WF_SUBSYS_RST = 0x70002000 + 0x600; private const val WF_WHOLE_PATH = 1 shl 0
    private const val SWDEF_MODE = 0x41F200 + 0x3C

    // ---- MCU (mt76_connac_mcu.h, mt7921/mcu.c) ----
    internal const val CMD_TARGET_ADDRESS_LEN_REQ = 0x01; internal const val CMD_FW_START_REQ = 0x02
    internal const val CMD_NIC_POWER_CTRL = 0x04; internal const val CMD_PATCH_START_REQ = 0x05
    internal const val CMD_PATCH_FINISH_REQ = 0x07; internal const val CMD_PATCH_SEM_CONTROL = 0x10
    private const val CMD_FIELD_QUERY = 1 shl 16; private const val CMD_FIELD_CE = 1 shl 18; private const val CMD_FIELD_WA = 1 shl 19
    private const val EXT_CID = 0xED
    internal fun extCmd(id: Int) = EXT_CID or ((id shl 8) and 0xFF00)
    internal fun ceCmd(id: Int) = CMD_FIELD_CE or (id and 0xFF)
    internal const val EXT_CHANNEL_SWITCH = 0x08; internal const val EXT_EFUSE_BUFFER_MODE = 0x21
    internal const val CE_GET_NIC_CAPAB = 0x8A; internal const val CE_SET_RX_FILTER = 0x0A
    internal const val UNI_SNIFFER = 0x24
    private const val MCU_TXD_LEN = 64; private const val UNI_TXD_LEN = 48
    private const val MCU_PKT_ID = 0xA0; private const val Q_QUERY = 0; private const val Q_SET = 1; private const val Q_NA = 3
    private const val S2D_H2N = 0; private const val S2D_H2C = 2
    private const val UNI_EXT_ACK = 0x07
    private const val MCU_RXD_LEN = 36; private const val RXD_SEQ_OFFSET = 29; private const val RXD_STATUS_OFFSET = 32
    private const val PATCH_IS_DL = 1; private const val PATCH_NOT_DL_SEM_SUCCESS = 2
    private const val FW_SCATTER_MAX = 4096
    private const val PATCH_ADDR = 0x900000

    // Monitor filters (mt7921/main.c configure_filter, mt76_connac2 RFCR bits).
    /** Enable | pass FCS failures | control | other BSS - frames with a bad checksum are still dropped in [parseRx]. */
    internal const val MONITOR_FILTER = (1 shl 31) or (1 shl 2) or (1 shl 5) or (1 shl 6)
    internal const val FIF_BIT_CLR = 1 shl 1
    /** Every RFCR "drop" bit a monitor clears (not FCS failures). */
    internal const val MONITOR_DROP_CLEAR = (1 shl 0) or (1 shl 3) or (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 7) or
        (1 shl 8) or (1 shl 9) or (1 shl 10) or (1 shl 11) or (1 shl 13) or (1 shl 16) or (1 shl 17) or (1 shl 18) or
        (1 shl 19) or (1 shl 21)

    // ---- RX descriptor (mt76_connac2_mac.h) ----
    private const val PKT_TYPE_NORMAL = 2; private const val PKT_TYPE_RX_EVENT = 7; private const val PKT_FLAG_NORMAL_MCU = 1
    private const val RXD1_FCS_ERR = 1 shl 27
    private const val RXD2_HDR_TRANS = 1 shl 13; private const val RXD2_AMSDU_ERR = 1 shl 23; private const val RXD2_MAX_LEN_ERROR = 1 shl 24

    private lateinit var conn: UsbDeviceConnection
    private lateinit var epRx: UsbEndpoint
    private lateinit var epCmd: UsbEndpoint
    private lateinit var epData: UsbEndpoint
    private var seq = 0
    @Volatile private var ctlErrors = 0
    private var cmdsSent = 0; private var cmdsAnswered = 0

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, chipName: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear(); ctlErrors = 0; seq = 0; cmdsSent = 0; cmdsAnswered = 0
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(device) ?: run { onStatus("$chipName: couldn't reopen adapter"); running = false; return }
        conn = c
        try {
            UsbWifi.log("=== MT7921AU bring-up (mt76 mt7921u) ===")
            // The WiFi function is the vendor-class interface with 2 bulk IN and 6 bulk OUT endpoints
            // (the others on these adapters are Bluetooth); roles by position, as mt76u_set_endpoints.
            val intf = wifiInterface(device) ?: run {
                UsbWifi.log("MT7921AU: no interface with class ff/ff/ff and 2 bulk IN + 6 bulk OUT endpoints")
                onStatus("$chipName: WiFi interface not found"); return
            }
            BulkRx.claim("MT7921AU", c, intf) { stop }
            val (ins, outs) = bulkEndpoints(intf)
            epRx = ins[0]; epCmd = outs[0]; epData = outs[1]
            UsbWifi.log("MT7921AU: interface ${intf.id}, bulk IN [${ins.joinToString { "0x%02x".format(it.address) }}] " +
                "OUT [${outs.joinToString { "0x%02x".format(it.address) }}]")
            val t0 = System.currentTimeMillis()

            val chip = rr(HW_CHIPID) and 0xffff
            UsbWifi.log("MT7921AU: chip 0x%04x rev 0x%08x".format(chip, rr(HW_REV)))
            if (chip != CHIP_ID_7961) { onStatus("$chipName: not an MT7921 (chip %04x)".format(chip)); return }

            onStatus("$chipName · loading firmware…")
            val patch = Firmware.read(ctx, "WIFI_MT7961_patch_mcu_1_2_hdr.bin")
            val ram = Firmware.read(ctx, "WIFI_RAM_CODE_MT7961_1.bin")
            val err = bringUp(patch, ram)
            if (err != null) { onStatus("$chipName: $err - unplug the adapter and plug it back in"); return }

            onStatus("$chipName · starting monitor mode…")
            // Monitor: receive everything (other networks, control frames), sniffer on.
            mcuCmd(ceCmd(CE_SET_RX_FILTER), rxFilterPayload(MONITOR_FILTER, 0, 0), wait = false)
            mcuCmd(ceCmd(CE_SET_RX_FILTER), rxFilterPayload(0, FIF_BIT_CLR, MONITOR_DROP_CLEAR), wait = false)
            if (!mcuUni(UNI_SNIFFER, snifferEnablePayload(true), wait = true)) UsbWifi.log("MT7921AU: sniffer mode not acknowledged (continuing)")
            val (band0, ch0) = HOP[0]
            tune(band0, ch0, wait = true)
            UsbWifi.log("MT7921AU: started in %d ms - CONN_ON_MISC 0x%08x WLCFG_0 0x%08x, USB errors %d - monitor live ch %d".format(
                System.currentTimeMillis() - t0, rr(CONN_ON_MISC), rr(UDMA_WLCFG_0), ctlErrors, ch0))
            onStatus("$chipName · live · 2.4 + 5 + 6 GHz monitor")
            rxLoop(chipName, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("MT7921AU: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    private fun wifiInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { i ->
        i.interfaceClass == 0xff && i.interfaceSubclass == 0xff && i.interfaceProtocol == 0xff &&
            bulkEndpoints(i).let { (ins, outs) -> ins.size >= 2 && outs.size >= 6 }
    }

    private fun bulkEndpoints(i: UsbInterface): Pair<List<UsbEndpoint>, List<UsbEndpoint>> {
        val ins = ArrayList<UsbEndpoint>(); val outs = ArrayList<UsbEndpoint>()
        for (k in 0 until i.endpointCount) {
            val ep = i.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) ins += ep else outs += ep
        }
        return ins to outs
    }

    // ---- bring-up (mt7921u_probe .. __mt7921_init_hardware) ----

    /** Null when the firmware runs, else what failed. */
    private fun bringUp(patch: ByteArray, ram: ByteArray): String? {
        val misc = rr(CONN_ON_MISC)
        UsbWifi.log("MT7921AU: CONN_ON_MISC 0x%08x".format(misc))
        // Still running from an earlier session (no replug in between): reset the WiFi subsystem first.
        if (misc and FW_N9_RDY != 0) {
            val ok = wfsysReset()
            UsbWifi.log("MT7921AU: WFSYS reset ${if (ok) "done" else "not confirmed"}, CONN_ON_MISC 0x%08x".format(rr(CONN_ON_MISC)))
        }
        ctl(TYPE_VENDOR, VEND_POWER_ON, 0, 1, null, 0)
        if (!poll(CONN_ON_MISC, FW_PWR_ON, FW_PWR_ON, 500)) return "MCU didn't power on"
        if (!dmaInit()) return "DMA didn't start"
        wr(SWDEF_MODE, 0) // normal operating mode, set before the firmware download
        setBits(UDMA_TX_QSEL, FW_DL_EN)
        mcuCmd(CMD_NIC_POWER_CTRL, byteArrayOf(1, 0, 0, 0), wait = false)
        if (!poll(CONN_ON_MISC, TOP_MISC_FW_STATE, FW_PWR_ON, 1000)) UsbWifi.log("MT7921AU: MCU not reporting ready for download (continuing)")
        loadPatch(patch)?.let { return it }
        loadRam(ram)?.let { return it }
        if (!poll(CONN_ON_MISC, FW_N9_RDY, FW_N9_RDY, 1500)) return "firmware didn't start"
        clearBits(UDMA_TX_QSEL, FW_DL_EN)
        UsbWifi.log("MT7921AU: firmware running")
        // 5 and 6 GHz stay silent until the firmware has the chip's calibration data.
        if (mcuCmd(ceCmd(CE_GET_NIC_CAPAB), ByteArray(0), wait = true) == null) return "no answer to the capability query"
        if (mcuCmd(extCmd(EXT_EFUSE_BUFFER_MODE), byteArrayOf(0, 1, 0, 0), wait = true) == null) return "calibration not accepted"
        UsbWifi.log("MT7921AU: calibration (efuse) pushed")
        return null
    }

    private fun dmaInit(): Boolean {
        // WFDMA prefetch rings and DMA scheduler (mt792xu_dma_init / mt792x_wfdma_init).
        intArrayOf(0, 1, 2, 3, 4, 16, 17).zip(intArrayOf(0x080, 0x0C0, 0x100, 0x140, 0x180, 0x280, 0x2C0)).forEach { (idx, base) ->
            rmw(0x7C024000 + 0x600 + (idx shl 2), 0xFF or 0xFFFF0000.toInt(), 4 or ((base shl 16) and 0xFFFF0000.toInt()))
        }
        clearBits(UWFDMA0_GLO_CFG, GLO_OMIT_RX_INFO)
        setBits(UWFDMA0_GLO_CFG, GLO_OMIT_TX_INFO or GLO_OMIT_RX_INFO_PFET2 or GLO_FW_DWLD_BYPASS_DMASHDL or GLO_TX_DMA_EN or GLO_RX_DMA_EN)
        rmw(DMA_SHDL + 0x010, 0xFFFF0000.toInt(), 0xFFE00000.toInt())
        clearBits(DMA_SHDL + 0x00C, 1 shl 16)
        rmw(DMA_SHDL + 0x01C, 0x00000FFF or 0x0FFF0000, 1)
        for (i in 0 until 5) wr(DMA_SHDL + 0x020 + (i shl 2), 0x3 or (0xFFF shl 16))
        for (i in 5 until 16) wr(DMA_SHDL + 0x020 + (i shl 2), 0)
        wr(DMA_SHDL + 0x060, 0x32013201); wr(DMA_SHDL + 0x064, 0x32013201)
        wr(DMA_SHDL + 0x068, 0x55555444); wr(DMA_SHDL + 0x06C, 0x55555444)
        wr(DMA_SHDL + 0x070, 0x76540132); wr(DMA_SHDL + 0x074, 0xFEDCBA98.toInt())
        setBits(WFDMA_DUMMY_CR, WFDMA_NEED_REINIT)
        // USB DMA: one frame per transfer (no RX aggregation).
        clearBits(UDMA_WLCFG_0, WL_RX_FLUSH)
        setBits(UDMA_WLCFG_0, WL_RX_EN or WL_TX_EN or WL_RX_MPSZ_PAD0 or TICK_1US_EN)
        rmw(UDMA_WLCFG_1, WL_TX_TMOUT_LMT, (USB_TX_TIMEOUT_LIMIT shl 8) and WL_TX_TMOUT_LMT)
        setBits(UDMA_WLCFG_0, WL_TX_TMOUT_FUNC_EN)
        clearBits(UDMA_WLCFG_0, WL_RX_AGG_TO or WL_RX_AGG_LMT)
        clearBits(UDMA_WLCFG_1, WL_RX_AGG_PKT_LMT)
        // Firmware answers arrive on the frame endpoint (EP4) from here on.
        if (!poll(UWFDMA0_GLO_CFG, GLO_RX_DMA_BUSY, 0, 1000)) return false
        clearBits(UWFDMA0_GLO_CFG, GLO_RX_DMA_EN)
        setBits(WFDMA_HOST_CONFIG, HOST_CONFIG_USB_RXEVT_EP4_EN)
        setBits(UWFDMA0_GLO_CFG, GLO_RX_DMA_EN)
        epctlRstOpt(false)
        return true
    }

    private fun epctlRstOpt(reset: Boolean) {
        val mask = 0x3F0 or 0x700000
        uhwRmw(SSUSB_EPCTL_CSR_EP_RST_OPT, mask, if (reset) mask else 0)
    }

    /** mt792xu_wfsys_reset with the MT7921 descriptor. */
    private fun wfsysReset(): Boolean {
        setBits(UDMA_WLCFG_0, WL_RX_FLUSH)
        poll(UDMA_WLCFG_0, WL_RX_BUSY or WL_TX_BUSY, 0, 1000)
        epctlRstOpt(false)
        uhwRmw(CBTOP_RGU_WF_SUBSYS_RST, 0, WF_WHOLE_PATH)
        sleep(1)
        uhwRmw(CBTOP_RGU_WF_SUBSYS_RST, WF_WHOLE_PATH, 0)
        uhwWr(UDMA_CONN_INFRA_STATUS_SEL, 0)
        repeat(2) {
            uhwRr(UDMA_CONN_INFRA_STATUS)?.let { if (it and UDMA_CONN_WFSYS_INIT_DONE != 0) return true }
            sleep(100)
        }
        return false
    }

    // ---- firmware ----

    internal data class PatchSection(val type: Int, val offs: Int, val addr: Int, val len: Int, val secInfo: Int)
    internal data class RamRegion(val addr: Int, val len: Int, val featureSet: Int)

    /** ROM patch: a 96-byte header, then one 64-byte descriptor per section (big-endian). Null when malformed. */
    internal fun parsePatch(b: ByteArray): List<PatchSection>? {
        if (b.size < 96) return null
        val n = be32(b, 44)
        if (n !in 1..16 || 96 + n * 64 > b.size) return null
        return (0 until n).map { i ->
            val s = 96 + i * 64
            PatchSection(be32(b, s), be32(b, s + 4), be32(b, s + 12), be32(b, s + 16), be32(b, s + 20))
        }.takeIf { secs -> secs.all { it.offs >= 0 && it.len >= 0 && it.offs.toLong() + it.len <= b.size } }
    }

    /** RAM firmware: regions described in a trailer (36 bytes, preceded by 40 per region, little-endian). Null when malformed. */
    internal fun parseRam(b: ByteArray): List<RamRegion>? {
        if (b.size < 36) return null
        val t = b.size - 36
        val n = b[t + 2].toInt() and 0xff
        if (n !in 1..32 || 36 + n * 40 > b.size) return null
        val regions = (0 until n).map { i ->
            val r = t - (n - i) * 40
            RamRegion(le32(b, r + 16), le32(b, r + 20), b[r + 24].toInt() and 0xff)
        }
        val payload = b.size.toLong() - 36 - n * 40
        return regions.takeIf { rs -> rs.all { it.len >= 0 } && rs.sumOf { it.len.toLong() } <= payload }
    }

    private fun loadPatch(blob: ByteArray): String? {
        val sem = mcuCmd(CMD_PATCH_SEM_CONTROL, le32Bytes(1), wait = true)?.let { it[RXD_STATUS_OFFSET].toInt() and 0xff } ?: -1
        if (sem == PATCH_IS_DL) { UsbWifi.log("MT7921AU: ROM patch already applied"); return null }
        if (sem != PATCH_NOT_DL_SEM_SUCCESS) return "no ROM patch semaphore ($sem)"
        try {
            val secs = parsePatch(blob) ?: return "ROM patch file malformed"
            UsbWifi.log("MT7921AU: ROM patch '${asciiz(blob, 0, 16)}', ${secs.size} section(s)")
            for (s in secs) {
                if (s.type and 0xFFFF != 0x2) return "ROM patch section type 0x%x".format(s.type)
                if (!initDownload(s.addr, s.len, patchDataMode(s.secInfo))) return "ROM patch download refused"
                if (!sendScatter(blob, s.offs, s.len)) return "ROM patch upload failed"
            }
            val st = mcuCmd(CMD_PATCH_FINISH_REQ, ByteArray(4), wait = true)?.let { it[RXD_STATUS_OFFSET].toInt() and 0xff } ?: -1
            if (st != 0) return "ROM patch not started ($st)"
            UsbWifi.log("MT7921AU: ROM patch applied")
            return null
        } finally {
            mcuCmd(CMD_PATCH_SEM_CONTROL, le32Bytes(0), wait = true)
        }
    }

    private fun loadRam(blob: ByteArray): String? {
        val regions = parseRam(blob) ?: return "firmware file malformed"
        val t = blob.size - 36
        UsbWifi.log("MT7921AU: firmware '${asciiz(blob, t + 7, 10)}' built '${asciiz(blob, t + 17, 15)}', ${regions.size} region(s)")
        var override = 0
        var offset = 0
        for (r in regions) {
            if (r.featureSet and (1 shl 5) != 0) override = r.addr // FW_FEATURE_OVERRIDE_ADDR
            if (r.featureSet and (1 shl 6) != 0) { offset += r.len; continue } // FW_FEATURE_NON_DL
            if (!initDownload(r.addr, r.len, ramDataMode(r.featureSet))) return "firmware download refused"
            if (!sendScatter(blob, offset, r.len)) return "firmware upload failed"
            offset += r.len
        }
        val option = if (override != 0) 1 else 0 // FW_START_OVERRIDE
        return if (mcuCmd(CMD_FW_START_REQ, le32Bytes(option) + le32Bytes(override), wait = true) == null) "firmware start not acknowledged" else null
    }

    internal fun patchDataMode(secInfo: Int): Int {
        var mode = 1 shl 31 // DL_MODE_NEED_RSP
        if (secInfo == -1) return mode // PATCH_SEC_NOT_SUPPORT
        when ((secInfo ushr 24) and 0xff) {
            1 -> mode = mode or 1 or (((secInfo and 0x0F) shl 1) and 0x6) or (1 shl 3) // AES: encrypt, key index, reset IV
            2 -> mode = mode or 1 or (1 shl 6) or (1 shl 3) // scramble
        }
        return mode
    }

    internal fun ramDataMode(featureSet: Int): Int {
        var mode = 1 shl 31
        if (featureSet and 1 != 0) mode = mode or 1 or (1 shl 3)
        if (featureSet and (1 shl 4) != 0) mode = mode or (1 shl 6)
        return mode or (((featureSet and 0x6) shr 1) shl 1)
    }

    private fun initDownload(addr: Int, len: Int, mode: Int): Boolean =
        mcuCmd(if (addr == PATCH_ADDR) CMD_PATCH_START_REQ else CMD_TARGET_ADDRESS_LEN_REQ,
            le32Bytes(addr) + le32Bytes(len) + le32Bytes(mode), wait = true) != null

    private fun sendScatter(blob: ByteArray, from: Int, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = minOf(FW_SCATTER_MAX, len - off)
            val f = scatterFrame(blob, from + off, n)
            if (conn.bulkTransfer(epData, f, f.size, 3000) < 0) { UsbWifi.log("MT7921AU: upload failed at $off of $len B"); return false }
            off += n
        }
        return true
    }

    // ---- MCU commands ----

    private fun nextSeq(): Int { seq = (seq + 1) and 0xF; if (seq == 0) seq = 1; return seq }

    /** Sends [cmd] (a plain command id, or an EXT / CE command word); with [wait], its answer or null. */
    private fun mcuCmd(cmd: Int, payload: ByteArray, wait: Boolean): ByteArray? {
        val s = nextSeq()
        val f = cmdFrame(cmd, payload, s)
        cmdsSent++
        if (conn.bulkTransfer(epCmd, f, f.size, 3000) < 0) { ctlErrors++; return null }
        return if (wait) mcuWait(s) else EMPTY
    }

    private fun mcuUni(cid: Int, payload: ByteArray, wait: Boolean): Boolean {
        val s = nextSeq()
        val f = uniFrame(cid, payload, s)
        cmdsSent++
        if (conn.bulkTransfer(epCmd, f, f.size, 3000) < 0) { ctlErrors++; return false }
        return !wait || mcuWait(s) != null
    }

    private val EMPTY = ByteArray(0)

    /** The firmware's answer to [s], read off the receive endpoint (frames arriving meanwhile are skipped). */
    private fun mcuWait(s: Int, timeoutMs: Long = 3000): ByteArray? {
        val buf = ByteArray(16384)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = conn.bulkTransfer(epRx, buf, buf.size, 500)
            if (n < MCU_RXD_LEN) continue
            if (eventSeq(buf, n) == s) { cmdsAnswered++; return buf.copyOf(n) }
        }
        UsbWifi.log("MT7921AU: no answer to command seq $s")
        return null
    }

    /** Frame on the wire: | le32 length | 64-byte MCU TXD | payload | padding to 4 + 4 zero bytes |. */
    internal fun cmdFrame(cmd: Int, payload: ByteArray, s: Int): ByteArray {
        val cid = cmd and 0xFF
        val ext = (cmd ushr 8) and 0xFF
        val setQuery = if (ext != 0 || cmd and CMD_FIELD_CE != 0) (if (cmd and CMD_FIELD_QUERY != 0) Q_QUERY else Q_SET) else Q_NA
        val s2d = if (cmd and CMD_FIELD_WA != 0) S2D_H2C else S2D_H2N
        val total = MCU_TXD_LEN + payload.size
        val f = ByteArray(padded(4 + total))
        putLe32(f, 0, total and 0xffff)
        txdHead(f, 4, total)
        putLe16(f, 4 + 34, (1 shl 15) or (0x20 shl 10)) // port MCU, queue RX_Q0
        f[4 + 36] = cid.toByte(); f[4 + 37] = MCU_PKT_ID.toByte(); f[4 + 38] = setQuery.toByte(); f[4 + 39] = s.toByte()
        f[4 + 41] = ext.toByte(); f[4 + 42] = s2d.toByte(); f[4 + 43] = (if (ext != 0) 1 else 0).toByte()
        System.arraycopy(payload, 0, f, 4 + MCU_TXD_LEN, payload.size)
        return f
    }

    /** A unified command: | le32 length | 48-byte UNI TXD | payload | padding |. */
    internal fun uniFrame(cid: Int, payload: ByteArray, s: Int): ByteArray {
        val total = UNI_TXD_LEN + payload.size
        val f = ByteArray(padded(4 + total))
        putLe32(f, 0, total and 0xffff)
        txdHead(f, 4, total)
        putLe16(f, 4 + 34, cid)
        f[4 + 37] = MCU_PKT_ID.toByte(); f[4 + 39] = s.toByte(); f[4 + 42] = S2D_H2N.toByte(); f[4 + 43] = UNI_EXT_ACK.toByte()
        System.arraycopy(payload, 0, f, 4 + UNI_TXD_LEN, payload.size)
        return f
    }

    /** A firmware chunk for the data endpoint: | le32 length | data | padding |. */
    internal fun scatterFrame(src: ByteArray, from: Int, len: Int): ByteArray {
        val f = ByteArray(padded(4 + len))
        putLe32(f, 0, len and 0xffff)
        System.arraycopy(src, from, f, 4, len)
        return f
    }

    /** TXD words 0-1 (length, command type, MCU port; long format, command header) and the inner length. */
    private fun txdHead(f: ByteArray, at: Int, total: Int) {
        putLe32(f, at, (total and 0xffff) or (2 shl 23) or (0x20 shl 25))
        putLe32(f, at + 4, (1 shl 31) or (1 shl 16))
        putLe16(f, at + 32, total - 32)
    }

    private fun padded(len: Int) = ((len + 3) and 3.inv()) + 4

    internal fun rxFilterPayload(fif: Int, bitOp: Int, bitmap: Int): ByteArray {
        val p = ByteArray(68)
        p[4] = (if (fif != 0) 1 else 2).toByte()
        putLe32(p, 8, fif); putLe32(p, 12, bitmap); p[16] = bitOp.toByte()
        return p
    }

    internal fun snifferEnablePayload(on: Boolean): ByteArray {
        val p = ByteArray(12)
        putLe16(p, 4, 0); putLe16(p, 6, 8); p[8] = (if (on) 1 else 0).toByte()
        return p
    }

    /**
     * CHANNEL_SWITCH: 20 MHz on [ch] in [band] (0 = 2.4, 1 = 5, 2 = 6 GHz), two streams. The RX path
     * is an antenna mask, as mt7921_mcu_set_chan_info sends it (antenna_mask): 0x3 listens on both
     * antennas; the 2 in mt76-usb-macos's frames is antenna 2 alone.
     */
    internal fun chanInfoPayload(band: Int, ch: Int): ByteArray {
        val p = ByteArray(76)
        p[0] = ch.toByte(); p[1] = ch.toByte(); p[2] = 0; p[3] = 2; p[4] = RX_ANTENNAS.toByte(); p[10] = band.toByte()
        return p
    }

    /** Sniffer config TLV: same channel, 20 MHz, firmware drops frames with errors. */
    internal fun snifferConfigPayload(band: Int, ch: Int): ByteArray {
        val p = ByteArray(20)
        putLe16(p, 4, 1); putLe16(p, 6, 16)
        p[10] = (band + 1).toByte(); p[11] = 0; p[12] = ch.toByte(); p[13] = 0; p[14] = ch.toByte(); p[16] = 1
        return p
    }

    /** Hops: the answers are counted as they arrive on the receive endpoint ([wait] only before receiving starts). */
    private fun tune(band: Int, ch: Int, wait: Boolean) {
        mcuCmd(extCmd(EXT_CHANNEL_SWITCH), chanInfoPayload(band, ch), wait)
        mcuUni(UNI_SNIFFER, snifferConfigPayload(band, ch), wait)
    }

    // ---- receive ----

    /**
     * Where the 802.11 frame sits in a transfer, its signal in dBm (strongest antenna; null when the
     * descriptor has none) and the band (0 / 1 / 2) and channel it was received on.
     */
    internal data class Rx(val start: Int, val end: Int, val rssi: Int?, val band: Int, val channel: Int,
                           val chain0: Int? = null, val chain1: Int? = null)

    /** The sequence number of a firmware answer (event) in this transfer, or null for anything else. */
    internal fun eventSeq(b: ByteArray, n: Int): Int? {
        if (n < MCU_RXD_LEN) return null
        val rxd0 = le32(b, 0)
        val type = (rxd0 ushr 27) and 0x1F
        val flag = (rxd0 ushr 16) and 0xF
        if (type != PKT_TYPE_RX_EVENT || flag == PKT_FLAG_NORMAL_MCU) return null
        return b[RXD_SEQ_OFFSET].toInt() and 0xff
    }

    /**
     * One transfer: | RXD (24) | optional groups 4, 1, 2, 3, 5 | header offset (2 x n) | 802.11 frame |,
     * ending at the RXD's own length. Null for anything but a received frame, a bad checksum or
     * a malformed descriptor ([onBad] says whether it was the checksum).
     */
    internal fun parseRx(b: ByteArray, n: Int, onBad: (Boolean) -> Unit = {}): Rx? {
        if (n < 24 || n > b.size) { onBad(false); return null }
        val rxd0 = le32(b, 0); val rxd1 = le32(b, 4); val rxd2 = le32(b, 8); val rxd3 = le32(b, 12)
        val type = (rxd0 ushr 27) and 0x1F
        val flag = (rxd0 ushr 16) and 0xF
        if (type != PKT_TYPE_NORMAL && !(type == PKT_TYPE_RX_EVENT && flag == PKT_FLAG_NORMAL_MCU)) return null
        if (rxd1 and RXD1_FCS_ERR != 0) { onBad(true); return null }
        if (rxd2 and (RXD2_AMSDU_ERR or RXD2_MAX_LEN_ERROR or RXD2_HDR_TRANS) != 0) { onBad(false); return null }
        val dmaLen = rxd0 and 0xffff
        val end = if (dmaLen in 1 until n) dmaLen else n
        // Groups in the order mt7921_mac_fill_rx reads them; group 5 needs group 3.
        val mask = (rxd1 ushr 11) and 31
        if (mask and 16 != 0 && mask and 4 == 0) { onBad(false); return null }
        var off = 24
        var g3 = -1; var g5 = -1
        for ((bit, size) in GROUPS) {
            if (mask and bit == 0) continue
            if (off + size > end) { onBad(false); return null }
            if (bit == 4) g3 = off
            if (bit == 16) g5 = off
            off += size
        }
        // Signal: RCPI per antenna (group 5 word 6, else group 3 word 1), to dBm as mt76's to_rssi;
        // an antenna that heard nothing reports 0 (or 255) and is left out.
        val rcpiWord = when { g5 >= 0 -> le32(b, g5 + 24); g3 >= 0 -> le32(b, g3 + 4); else -> null }
        fun chain(w: Int, c: Int): Int? = ((w ushr (8 * c)) and 0xff).takeIf { it in 1..254 }?.let { it / 2 - 110 }?.takeIf { it < 0 }
        val rssi = rcpiWord?.let { w -> (0 until 4).mapNotNull { chain(w, it) }.maxOrNull() }
        val start = off + 2 * ((rxd2 ushr 14) and 0x3)
        if (end - start < 10) { onBad(false); return null }
        val chfreq = (rxd3 ushr 8) and 0xff
        val (band, ch) = when {
            chfreq > 180 -> 2 to (chfreq - 181) * 4 + 1
            chfreq > 14 -> 1 to chfreq
            else -> 0 to chfreq
        }
        return Rx(start, end, rssi, band, ch, rcpiWord?.let { chain(it, 0) }, rcpiWord?.let { chain(it, 1) })
    }

    /** Optional RXD groups: bit in the group mask and size, in reading order (4, 1, 2, 3, 5). */
    private val GROUPS = listOf(8 to 16, 1 to 16, 2 to 8, 4 to 8, 16 to 72)

    /** The channel as [MonitorFrames] takes it: the number on 2.4 / 5 GHz, MHz on 6 GHz (its numbers overlap theirs). */
    internal fun monitorChannel(band: Int, ch: Int) = if (band == 2) 5950 + 5 * ch else ch

    private class RxState {
        var hopAt = 0L; var lastEmit = 0L; var lastRxAt = 0L; var lastCheck = 0L; var hopIdx = 0
        var round = 0; var plan: List<Pair<Int, Int>> = HOP
        /** 6 GHz channel number -> when a frame was last heard on it. */
        val heard6 = HashMap<Int, Long>()
        /** The channel held for the antenna check instead of hopping. */
        var locked: Pair<Int, Int>? = null
        val current get() = locked ?: plan[hopIdx]
        /** Sequence number of the hop's last command until the firmware confirms it (-1: confirmed). */
        var pendingSeq = -1; var sentAt = 0L
        var switches = 0; var switchMsTotal = 0L; var switchMsMax = 0L; var unconfirmed = 0
        fun switchSummary(): String {
            val s = if (switches == 0) "channel switch: none confirmed" else
                "channel switch ${switchMsTotal / switches} ms avg, $switchMsMax ms max"
            return "$s, $unconfirmed unconfirmed".also { switches = 0; switchMsTotal = 0; switchMsMax = 0; unconfirmed = 0 }
        }
    }

    private fun rxLoop(chipName: String, onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0; st.lastRxAt = t0; st.lastCheck = t0
        val stats = BulkRx.Stats("MT7921AU")
        val total = BulkRx.run("MT7921AU", conn, epRx, 16, 16384, { stop }, onData@{ b, n ->
            stats.transfer(n)
            st.lastRxAt = System.currentTimeMillis()
            stats.sample("$n bytes, RXD", b, 0, 24)
            val ev = eventSeq(b, n)
            if (ev != null) {
                cmdsAnswered++
                if (ev == st.pendingSeq) {
                    // the switch is done: the dwell on this channel starts now
                    val now = System.currentTimeMillis(); val ms = now - st.sentAt
                    st.switches++; st.switchMsTotal += ms; if (ms > st.switchMsMax) st.switchMsMax = ms
                    st.pendingSeq = -1; st.hopAt = now
                }
                return@onData
            }
            val f = parseRx(b, n, onBad = { crc -> if (crc) stats.badChecksum++ else stats.malformed++ }) ?: return@onData
            val (hopBand, hopCh) = st.current
            if (f.band == 2 && f.channel > 0) st.heard6[f.channel] = System.currentTimeMillis()
            // The descriptor's channel, else the one tuned to.
            val ch = if (f.channel > 0) monitorChannel(f.band, f.channel) else monitorChannel(hopBand, hopCh)
            stats.frame(f.rssi)
            frames.frame(b, f.start, f.end, ch, f.rssi, pathA = f.chain0, pathB = f.chain1)
        }) { now ->
            stats.maybeLog(now) {
                val (aps, clients) = frames.live(now, REPORT_MS)
                val (band, ch) = st.current
                "${listOf("2.4", "5", "6")[band]} GHz ch $ch, $aps access points, $clients devices, " +
                    "firmware answered $cmdsAnswered of $cmdsSent commands, ${st.switchSummary()}, USB errors $ctlErrors"
            }
            if (now - st.lastCheck > 3000) {
                st.lastCheck = now
                // Nothing arriving for a while (not even command answers): put the sniffer back on.
                if (now - st.lastRxAt > 10_000) {
                    UsbWifi.log("MT7921AU: receive stalled (${(now - st.lastRxAt) / 1000} s) - restarting the sniffer")
                    runCatching { mcuUni(UNI_SNIFFER, snifferEnablePayload(true), wait = false) }
                    st.lastRxAt = now
                }
            }
            if (now - st.lastEmit > 2000) { emit(onFrame); st.lastEmit = now }
            // As the kernel does, a channel switch is only done once the firmware confirms it: the
            // dwell is counted from that answer, so slow switches don't eat the listening time.
            if (st.pendingSeq >= 0 && now - st.sentAt > SWITCH_TIMEOUT_MS) { st.unconfirmed++; st.pendingSeq = -1; st.hopAt = now }
            // Antenna check: hold the reference's channel (once the firmware confirms the switch).
            val lock = AntennaCheck.lockChannel.let { l -> if (l == 0) null else HOP.firstOrNull { monitorChannel(it.first, it.second) == l } }
            if (lock != null && lock == st.locked && st.pendingSeq < 0) AntennaCheck.holding = AntennaCheck.lockChannel
            if (lock != st.locked || (lock == null && st.pendingSeq < 0 && now - st.hopAt > DWELL_MS)) {
                if (lock != null || st.locked != null) st.locked = lock
                else {
                    st.hopIdx++
                    if (st.hopIdx >= st.plan.size) {
                        st.round++
                        st.plan = roundPlan(st.round, st.heard6.filterValues { now - it < SIX_GHZ_ACTIVE_MS }.keys)
                        st.hopIdx = 0
                    }
                }
                val (band, ch) = st.current
                runCatching { tune(band, ch, wait = false) }
                st.pendingSeq = seq; st.sentAt = now
                val (aps, clients) = frames.live(now, REPORT_MS)
                onStatus("$chipName · live · $aps access points · $clients devices · ${listOf("2.4", "5", "6")[band]} GHz ch $ch")
                st.hopAt = now
            }
        }
        emit(onFrame)
        UsbWifi.log("MT7921AU: RX loop stopped ($total B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    // ---- register access (mt76 usb.c: 32-bit address split over wValue / wIndex) ----

    private fun ctl(type: Int, req: Int, value: Int, index: Int, data: ByteArray?, len: Int): Int {
        repeat(3) {
            val r = conn.controlTransfer(type, req, value, index, data, len, 1000)
            if (r >= 0) return r
            sleep(5)
        }
        ctlErrors++
        return -1
    }
    private fun rr(addr: Int): Int = rrChecked(addr) ?: -1
    private fun rrChecked(addr: Int): Int? {
        val b = ByteArray(4)
        return if (ctl(DIR_IN or TYPE_VENDOR, VEND_READ_EXT, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4) == 4) le32(b, 0) else null
    }
    private fun wr(addr: Int, v: Int) {
        ctl(TYPE_VENDOR, VEND_WRITE_EXT, (addr ushr 16) and 0xffff, addr and 0xffff, le32Bytes(v), 4)
    }
    private fun uhwRr(addr: Int): Int? {
        val b = ByteArray(4)
        return if (ctl(DIR_IN or TYPE_UHW_VENDOR, VEND_DEV_MODE, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4) == 4) le32(b, 0) else null
    }
    private fun uhwWr(addr: Int, v: Int) {
        ctl(TYPE_UHW_VENDOR, VEND_WRITE, (addr ushr 16) and 0xffff, addr and 0xffff, le32Bytes(v), 4)
    }
    /** Read-modify-write; skipped when the read fails (writing back garbage would be worse). */
    private fun rmw(addr: Int, mask: Int, v: Int) { rrChecked(addr)?.let { wr(addr, (it and mask.inv()) or v) } }
    private fun setBits(addr: Int, bits: Int) = rmw(addr, 0, bits)
    private fun clearBits(addr: Int, bits: Int) = rmw(addr, bits, 0)
    private fun uhwRmw(addr: Int, mask: Int, v: Int) { uhwRr(addr)?.let { uhwWr(addr, (it and mask.inv()) or v) } }

    private fun poll(addr: Int, mask: Int, expect: Int, ms: Int): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            // A failed read never counts as the expected value.
            rrChecked(addr)?.let { if (it and mask == expect) return true }
            if (System.currentTimeMillis() > end) return false
            sleep(10)
        }
    }

    private fun le32Bytes(v: Int) = ByteArray(4).also { putLe32(it, 0, v) }
    private fun putLe32(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte() }
    private fun putLe16(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte() }
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun be32(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
        ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
    private fun asciiz(b: ByteArray, off: Int, max: Int): String {
        val n = minOf(max, b.size - off).coerceAtLeast(0)
        return String(b, off, n, Charsets.US_ASCII).substringBefore('\u0000').trim()
    }
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
