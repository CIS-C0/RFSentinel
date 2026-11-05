package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for MediaTek MT7612U / MT7632U USB WiFi adapters (e.g.
 * ALFA AWUS036ACM, ASUS USB-AC55, Netgear A6210), 2.4 + 5 GHz, driven from userspace
 * over Android's USB host API - no root, no kernel driver. Loads MediaTek's ROM patch
 * and firmware (assets/usbwifi/mt7662*.bin, from linux-firmware; licence in
 * LICENCE.ralink_a_mediatek_company_firmware), hops both bands and reports the access
 * points and client devices it hears. It never transmits.
 *
 * Ported from Wardrive Go by RocketGod (https://github.com/RocketGod-git/wardrive-go,
 * GPL-3.0), which follows the Linux mt76x2u driver (mt76 project). Changes here: frames
 * with a bad checksum are dropped (a corrupted address would show up as a phantom
 * device), the auto-responder (ACK / CTS) is left off, the ROM patch is skipped when an
 * MT7632 already has it (as the kernel does), and the handshake / PMKID capture was removed.
 *
 * MT7610U adapters (one chain, 2.4 + 5 GHz) run here too: same USB transport, firmware upload,
 * MCU protocol and RX format, with the bring-up and tuning of the Linux mt76x0u driver (run with
 * [x0], tables in [Mt7610uTables]). There the host programs the radio through the MCU. Left out
 * of that path: everything for transmitting (TSSI, TX power, beacons) and the auto-responder.
 */

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

object Mt7612uMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val ALL_HOP = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        36, 40, 44, 48, 52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165
    )
    private var HOP = ALL_HOP
    private const val DWELL_MS = 125L
    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L
    private const val CAL_TTL_MS = 30_000L

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0; private const val OUT_CLASS = 0x20
    private const val DEV_MODE = 0x01; private const val MULTI_WRITE = 0x06; private const val MULTI_READ = 0x07
    private const val READ_EEPROM = 0x09; private const val WRITE_FCE = 0x42; private const val WRITE_CFG = 0x46; private const val READ_CFG = 0x47

    // firmware load (mt76x2u_mcu_load_rom_patch / _load_firmware)
    private const val ASIC_VERSION = 0x0000; private const val MCU_CLOCK_CTL = 0x0708; private const val MCU_COM_REG0 = 0x0730
    private const val MCU_SEMAPHORE_03 = 0x07BC
    private const val FCE_PSE_CTRL = 0x0800; private const val FCE_DMA_ADDR = 0x0230; private const val FCE_DMA_LEN = 0x0234
    private const val TX_CPU_FROM_FCE_BASE_PTR = 0x09a0; private const val TX_CPU_FROM_FCE_MAX_COUNT = 0x09a4
    private const val TX_CPU_FROM_FCE_CPU_DESC_IDX = 0x09a8; private const val FCE_PDMA_GLOBAL_CONF = 0x09c4; private const val FCE_SKIP_FS = 0x0a6c
    private const val USB_U3DMA_CFG = 0x9018
    private const val ILM_OFFSET = 0x80000; private const val DLM_OFFSET = 0x110000; private const val ROM_PATCH_OFFSET = 0x90000
    private const val FW_URB_MAX_PAYLOAD = 0x3900; private const val ROM_PATCH_MAX_PAYLOAD = 2048
    private const val FW_HDR_LEN = 32; private const val PATCH_HDR_LEN = 30; private const val REV_E3 = 0x22
    private const val DMA_INFO_BASE = (2 shl 27) or (1 shl 30)

    // MAC / BBP
    private const val WLAN_FUN_CTRL = 0x0080; private const val MAC_CSR0 = 0x1000; private const val MAC_SYS_CTRL = 0x1004
    private const val MAC_STATUS = 0x1200; private const val CH_IDLE = 0x1130; private const val CH_BUSY = 0x1134; private const val RX_STAT_0 = 0x1700
    private const val WPDMA_GLO_CFG = 0x0208; private const val RX_FILTR_CFG = 0x1400; private const val US_CYC_CFG = 0x02a4
    private const val TXOP_CTRL_CFG = 0x1340; private const val COEXCFG0 = 0x0040; private const val EXT_CCA_CFG = 0x141c
    private const val TX_ALC_CFG_4 = 0x13c0; private const val PBF_TX_MAX_PCNT = 0x0408; private const val PBF_RX_MAX_PCNT = 0x040c
    private const val TX_LINK_CFG = 0x1350; private const val AUTO_RSP_CFG = 0x1404; private const val MAX_LEN_CFG = 0x1018
    private const val WMM_AIFSN = 0x0214; private const val WMM_CWMIN = 0x0218; private const val WMM_CWMAX = 0x021c
    private const val CH_TIME_CFG = 0x110c; private const val TX_PIN_CFG = 0x1328; private const val TXOP_HLDR_ET = 0x1608
    private const val XIFS_TIME_CFG = 0x1100; private const val BKOFF_SLOT_CFG = 0x1104; private const val FCE_L2_STUFF = 0x080c
    private const val XO_CTRL5 = 0x0114; private const val XO_CTRL6 = 0x0118; private const val XO_CTRL7 = 0x011c
    private const val ED_CCA_TIMER = 0x1140; private const val ADDR_504 = 0x0504; private const val ADDR_50C = 0x050c
    private const val TX_BAND_CFG = 0x132c; private const val BBP_CORE1 = 0x2004
    private const val BBP_AGC0 = 0x2300; private const val BBP_AGC2 = 0x2308; private const val BBP_AGC7 = 0x231c
    private const val BBP_AGC11 = 0x232c; private const val BBP_AGC61 = 0x23f4; private const val BBP_TXO4 = 0x2610; private const val BBP_RXO13 = 0x2934

    private const val WLAN_EN = 1 shl 0; private const val WLAN_CLK_EN = 1 shl 1
    private const val WLAN_RESET_RF = 1 shl 2; private const val FRC_WL_ANT_SEL = 1 shl 5
    private const val SYS_CTRL_RESET_CSR = 1 shl 0; private const val SYS_CTRL_RESET_BBP = 1 shl 1
    private const val SYS_CTRL_ENABLE_TX = 1 shl 2; private const val SYS_CTRL_ENABLE_RX = 1 shl 3
    private const val WPDMA_TX_DMA_BUSY = 1 shl 1; private const val WPDMA_RX_DMA_BUSY = 1 shl 3
    private const val DMA_RX_DROP_OR_PAD = 1 shl 18; private const val DMA_RX_BULK_AGG_EN = 1 shl 21
    private const val DMA_RX_BULK_EN = 1 shl 22; private const val DMA_TX_BULK_EN = 1 shl 23
    private const val COEX_EN = 1 shl 0
    /** Drop bad checksums, PHY / version errors and control frames; keep everything else (not-to-me, other BSS). */
    private const val RX_FILTER_MONITOR = 0x0001FF13
    /** AUTO_RSP_CFG without the auto-responder (bit 0): the adapter never answers with an ACK or CTS. */
    private const val AUTO_RSP_NO_RESPONDER = 0x12

    // MT7610U (mt76x0u)
    private const val USB_DMA_CFG = 0x0238; private const val DMA_TX_WL_DROP = 1 shl 16
    private const val EFUSE_CTRL = 0x0024; private const val EFUSE_DATA = 0x0028
    private const val CMB_CTRL = 0x0020; private const val COEXCFG3 = 0x004c; private const val CSR_EE_CFG1 = 0x0104
    private const val WMM_CTRL = 0x0230; private const val RF_MISC = 0x0518
    private const val TX0_RF_GAIN_CORR = 0x13a0; private const val TX0_RF_GAIN_ATTEN = 0x13a8
    private const val TX_ALC_CFG_0 = 0x13b0; private const val TX_ALC_CFG_1 = 0x13b4; private const val TX_ALC_VGA3 = 0x13c8
    private const val BBP_CORE0 = 0x2000; private const val BBP_IBI9 = 0x2124; private const val BBP_AGC8 = 0x2320
    private const val BBP_TXBE0 = 0x2700; private const val BBP_TXBE5 = 0x2714
    private const val IVB_SIZE = 0x40; private const val DLM_X0 = 0x80000; private const val FW_URB_X0 = 0x38f8
    private const val CMD_RANDOM_READ = 10; private const val CMD_RANDOM_WRITE = 12; private const val BW_SETTING = 2
    private const val RF_MEMMAP = 0x80000000.toInt(); private const val RF_PAIRS_PER_CMD = 24 // MT_INBAND_PACKET_MAX_LEN / 8
    private const val X0_CAL_R = 1; private const val X0_CAL_RXDCOC = 2; private const val X0_CAL_LC = 3
    private const val X0_CAL_VCO = 12; private const val X0_CAL_FULL = 0xff
    private const val RF_G_BAND = 0x100; private const val RF_A_BAND = 0x200

    private const val MIN_FRAME = 10; private const val MAX_BAD_LOGS = 12; private const val DEAF_MS = 15_000L
    private const val UNCALIBRATED = " · not calibrated: unplug the adapter and plug it back in"
    private const val FW_ATTEMPTS = 3; private const val CLAIM_WAITS = 10
    private const val DMA_HDR = 4; private const val RXWI_LEN = 32; private const val FCE_INFO = 4
    /** Room for the largest 802.11ac MPDU (11454 B) plus descriptors: a smaller buffer splits big frames over two transfers. */
    private const val RX_BUF = 16384
    private const val RXINFO_L2PAD = 1 shl 14; private const val RXINFO_CRCERR = 1 shl 8

    private const val EE_SIZE = 512; private const val EE_MAC_ADDR = 0x004; private const val EE_NIC_CONF_0 = 0x034
    private const val EE_NIC_CONF_1 = 0x036; private const val EE_NIC_CONF_2 = 0x042; private const val EE_LNA_GAIN = 0x044
    private const val EE_RSSI_OFFSET_2G_0 = 0x046; private const val EE_RSSI_OFFSET_2G_1 = 0x048; private const val EE_RSSI_OFFSET_5G_0 = 0x04a
    private const val EE_XTAL_TRIM_1 = 0x03a; private const val EE_XTAL_TRIM_2 = 0x09e
    private const val EE_ANTENNA = 0x022; private const val EE_CFG1_INIT = 0x024; private const val EE_RSSI_OFFSET_5G_1 = 0x04c; private const val EE_TSSI_BOUND4 = 0x0da

    private const val CMD_FUN_SET_OP = 1; private const val CMD_LOAD_CR = 2; private const val CMD_INIT_GAIN_OP = 3
    private const val CMD_POWER_SAVING_OP = 20; private const val CMD_SWITCH_CHANNEL_OP = 30; private const val CMD_CALIBRATION_OP = 31
    private const val Q_SELECT = 1; private const val RADIO_ON = 0x31; private const val MT_RF_BBP_CR = 2
    private const val MCU_CAL_TEMP = 2; private const val MCU_CAL_RXDCOC = 3; private const val MCU_CAL_RC = 4
    private const val MCU_CAL_LC = 6; private const val MCU_CAL_TX_LOFT = 7; private const val MCU_CAL_TXIQ = 8
    private const val MCU_CAL_RXIQC_FI = 12; private const val MCU_CAL_TX_SHAPING = 15

    private lateinit var conn: UsbDeviceConnection
    private var epResp: UsbEndpoint? = null
    private var seq = 0
    private var mcuLogged = 0
    /**
     * MCU commands that wait for an answer, and how many got one. None answered at bring-up: the
     * adapter kept power through a USB self-reset and its firmware lost track of the endpoints, so
     * calibration never applied. Frames may still arrive, but only a replug fully resets it.
     */
    private var mcuAsked = 0
    private var mcuAnswered = 0
    @Volatile private var uncalibrated = false
    private var mcuGain = 0
    private var rssiOff2g = 0; private var rssiOff5g = 0; private var lna2g = 0; private var lna5g = 0
    /** Running an MT7610U ([run]'s x0): its 5 GHz LNA gain per sub-band, EEPROM words, and crystal trim. */
    private var x0 = false
    private var tag = "MT7612U"
    private val lnaX0 = IntArray(3)
    private var conf0X0 = 0
    private var freqOffsetX0 = 0
    private var antCfgX0 = 0 to 0
    private var antConf2X0 = 0
    /** Chain 1's RSSI offsets (the EEPROM words' high bytes, as mt76x2_read_rx_gain reads them). */
    private var rssiOff2g1 = 0; private var rssiOff5g1 = 0
    private val lastCalMs = HashMap<Int, Long>()
    @Volatile private var ctlErrors = 0

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, chipName: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit, x0: Boolean = false) {
        if (running) return
        this.x0 = x0; tag = if (x0) "MT7610U" else "MT7612U"
        HOP = UsbWifi.hops(ALL_HOP)
        running = true; stop = false; frames.clear(); lastCalMs.clear(); mcuLogged = 0; ctlErrors = 0; mcuAsked = 0; mcuAnswered = 0; uncalibrated = false
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(device) ?: run { onStatus("$chipName: couldn't reopen adapter"); running = false; return }
        conn = c
        try {
            val intf = device.getInterface(0)
            // Right after plug-in the system can still hold the adapter for a moment: without the
            // interface every firmware transfer fails, so keep asking for a few seconds.
            var claimed = c.claimInterface(intf, true)
            var waits = 0
            while (!claimed && waits < CLAIM_WAITS && !stop) { sleep(300); waits++; claimed = c.claimInterface(intf, true) }
            if (!claimed) UsbWifi.log("$tag: couldn't claim the USB interface after ${waits * 300} ms (continuing)")
            else if (waits > 0) UsbWifi.log("$tag: USB interface claimed after ${waits * 300} ms")
            val bulkIn = ArrayList<UsbEndpoint>(); val bulkOut = ArrayList<UsbEndpoint>()
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn += ep else bulkOut += ep }
            }
            val rx = bulkIn.getOrNull(0); val cmdOut = bulkOut.getOrNull(0)
            epResp = bulkIn.getOrNull(1)
            UsbWifi.log("=== $tag bring-up (${if (x0) "mt76x0u" else "mt76x2u"}) ===")
            UsbWifi.log("$tag: bulk IN [${bulkIn.joinToString { "0x%02x".format(it.address) }}] OUT [${bulkOut.joinToString { "0x%02x".format(it.address) }}]")
            if (rx == null || cmdOut == null) { onStatus("$chipName: no RX / command endpoint"); return }
            val t0 = System.currentTimeMillis()
            if (x0) { runX0(ctx, device, chipName, cmdOut, rx, bulkOut + bulkIn, t0, onFrame, onStatus); return }

            // ---- firmware ----
            val ver = regRd(ASIC_VERSION)
            val chip = ver ushr 16; val e3 = (ver and 0xffff) >= REV_E3
            UsbWifi.log("MT7612U: ASIC_VERSION 0x%08x (chip %04x, %s)".format(ver, chip, if (e3) "E3+" else "pre-E3"))
            // Power the chip up before the upload, in mt76x2u_init_hardware's order (reset WLAN,
            // power on, wait for the MAC, then firmware). Uploading to a cold chip first failed on
            // the first plug-in and only worked on a quick replug.
            onStatus("$chipName · powering on…")
            resetWlan()
            powerOn()
            if (!poll(1000, 5) { regRd(MAC_CSR0).let { it != 0 && it != -1 } }) UsbWifi.log("MT7612U: MAC not ready before the firmware (continuing)")
            onStatus("$chipName · loading firmware…")
            val patch = Firmware.read(ctx, "mt7662_rom_patch.bin")
            val fw = Firmware.read(ctx, "mt7662.bin")
            // A first upload sometimes stalls right after plug-in; the second try (after a firmware reset) goes through.
            var loaded = false
            for (attempt in 1..FW_ATTEMPTS) {
                if (attempt > 1) {
                    UsbWifi.log("MT7612U: retrying the firmware load (attempt $attempt of $FW_ATTEMPTS)")
                    if (!claimed) claimed = c.claimInterface(intf, true)
                    sleep(1000)
                    resetWlan(); powerOn()
                }
                // A bulk endpoint left stalled (an earlier session, a half-finished upload) refuses every
                // transfer until it is cleared; the kernel resets the whole device, which apps can't.
                for (ep in bulkOut + bulkIn) clearHalt(ep)
                if (!loadRomPatch(cmdOut, patch, e3, romProtect = chip != 0x7612)) continue
                if (loadFirmware(cmdOut, fw, e3)) { loaded = true; break }
            }
            if (!loaded) { onStatus("$chipName: firmware didn't load - unplug the adapter and plug it back in"); return }

            // ---- radio ----
            onStatus("$chipName · starting the radio…")
            // after the firmware: DMA idle, then the MAC ready again (as the kernel waits)
            if (!poll(100, 5) { regRd(WPDMA_GLO_CFG) and (WPDMA_TX_DMA_BUSY or WPDMA_RX_DMA_BUSY) == 0 }) UsbWifi.log("MT7612U: DMA still busy after the firmware (continuing)")
            if (!poll(1000, 5) { regRd(MAC_CSR0).let { it != 0 && it != -1 } }) { onStatus("$chipName: MAC not ready - unplug the adapter and plug it back in"); return }
            initDma()
            mcuCmd(cmdOut, CMD_FUN_SET_OP, leWords(Q_SELECT, 1), false)
            mcuCmd(cmdOut, CMD_POWER_SAVING_OP, leWords(RADIO_ON, 0), false)
            macReset()
            val ee = readEeprom()
            mcuGain = computeMcuGain(ee)
            computeRssiCal(ee)
            UsbWifi.log("MT7612U: EEPROM MAC %s:xx:xx:xx, NIC_CONF 0x%04x/0x%04x, mcu_gain 0x%08x, rssi_off 2g %d 5g %d, lna 2g %d 5g %d".format(
                (0 until 3).joinToString(":") { "%02x".format(ee[EE_MAC_ADDR + it].toInt() and 0xff) },
                le16(ee, EE_NIC_CONF_0), le16(ee, EE_NIC_CONF_1), mcuGain, rssiOff2g, rssiOff5g, lna2g, lna5g))
            fixupXtal(ee)
            regRmw(US_CYC_CFG, 0xff, 0x1e)
            regWr(TXOP_CTRL_CFG, 0x583f)

            onStatus("$chipName · loading RF / BBP registers…")
            loadCr(cmdOut, ee, HOP[0])
            onStatus("$chipName · tuning and calibrating…")
            regWr(BBP_AGC0, (regRd(BBP_AGC0) and (1 shl 4).inv()) or (1 shl 3)) // RX path
            tune(cmdOut, HOP[0], scan = false)
            macStart()
            UsbWifi.log("MT7612U: started in %d ms - SYS_CTRL 0x%08x STATUS 0x%08x U3DMA 0x%08x RX_FILTR 0x%08x AUTO_RSP 0x%08x AGC0 0x%08x, USB errors %d - monitor live ch %d".format(
                System.currentTimeMillis() - t0, regRd(MAC_SYS_CTRL), regRd(MAC_STATUS), cfgRd(USB_U3DMA_CFG), regRd(RX_FILTR_CFG),
                regRd(AUTO_RSP_CFG), regRd(BBP_AGC0), ctlErrors, HOP[0]))
            uncalibrated = mcuAsked >= 3 && mcuAnswered == 0
            if (uncalibrated) UsbWifi.log("MT7612U: none of $mcuAsked MCU commands answered - the adapter kept power through a USB reset, " +
                "so calibration didn't apply. Unplug the adapter and plug it back in.")
            onStatus("$chipName · live · 2.4 + 5 GHz monitor" + UNCALIBRATED.takeIf { uncalibrated }.orEmpty())
            rxLoop(chipName, cmdOut, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("$tag: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    // ---- firmware ----

    private fun loadRomPatch(out: UsbEndpoint, patch: ByteArray, e3: Boolean, romProtect: Boolean): Boolean {
        if (patch.size <= PATCH_HDR_LEN) return false
        val patchReg = if (e3) MCU_CLOCK_CTL else MCU_COM_REG0
        val patchMask = if (e3) 0x1 else 0x2
        if (romProtect) {
            if (!poll(600, 10) { regRd(MCU_SEMAPHORE_03) and 1 == 1 }) { UsbWifi.log("MT7612U: no hardware semaphore for the ROM patch"); return false }
            if (regRd(patchReg) and patchMask != 0) { UsbWifi.log("MT7612U: ROM patch already applied"); regWr(MCU_SEMAPHORE_03, 1); return true }
        }
        UsbWifi.log("MT7612U: ROM patch build '${asciiz(patch, 0, 16)}', ${patch.size - PATCH_HDR_LEN} B")
        cfgWr(USB_U3DMA_CFG, DMA_RX_BULK_EN or DMA_TX_BULK_EN or 0x20)
        vendor(DEV_MODE, OUT_VENDOR, 0x1, null) // firmware reset
        sleep(8)
        configureFce()
        val sent = sendFw(out, patch, PATCH_HDR_LEN, patch.size - PATCH_HDR_LEN, ROM_PATCH_MAX_PAYLOAD, ROM_PATCH_OFFSET)
        if (sent) {
            vendor(DEV_MODE, OUT_CLASS, 0x12, byteArrayOf(0x6f, 0xfc.toByte(), 0x08, 0x01, 0x20, 0x04, 0x00, 0x00, 0x00, 0x09, 0x00)) // enable patch
            vendor(DEV_MODE, OUT_CLASS, 0x12, byteArrayOf(0x6f, 0xfc.toByte(), 0x05, 0x01, 0x07, 0x01, 0x00, 0x04)) // reset WMT
            sleep(20)
            val applied = poll(100, 10) { regRd(patchReg) and patchMask == patchMask }
            UsbWifi.log("MT7612U: ROM patch ${if (applied) "applied" else "not acknowledged (continuing)"}")
        }
        if (romProtect) regWr(MCU_SEMAPHORE_03, 1)
        return sent
    }

    private fun loadFirmware(out: UsbEndpoint, fw: ByteArray, e3: Boolean): Boolean {
        if (fw.size <= FW_HDR_LEN) return false
        val ilmLen = le32(fw, 0); val dlmLen = le32(fw, 4); val fwVer = le16(fw, 10)
        if (FW_HDR_LEN + ilmLen + dlmLen != fw.size) { UsbWifi.log("MT7612U: firmware file size mismatch"); return false }
        UsbWifi.log("MT7612U: firmware %d.%d.%02d build '%s', ILM %d B, DLM %d B".format(
            (fwVer shr 12) and 0xf, (fwVer shr 8) and 0xf, fwVer and 0xf, asciiz(fw, 16, 16), ilmLen, dlmLen))
        vendor(DEV_MODE, OUT_VENDOR, 0x1, null) // firmware reset
        sleep(8)
        cfgWr(USB_U3DMA_CFG, DMA_RX_BULK_EN or DMA_TX_BULK_EN or 0x20)
        configureFce()
        if (!sendFw(out, fw, FW_HDR_LEN, ilmLen, FW_URB_MAX_PAYLOAD, ILM_OFFSET)) return false
        if (!sendFw(out, fw, FW_HDR_LEN + ilmLen, dlmLen, FW_URB_MAX_PAYLOAD, if (e3) DLM_OFFSET + 0x800 else DLM_OFFSET)) return false
        vendor(DEV_MODE, OUT_VENDOR, 0x12, null) // load IVB
        if (!poll(100, 10) { regRd(MCU_COM_REG0) and 0x1 == 0x1 }) { UsbWifi.log("MT7612U: firmware failed to start"); return false }
        regWr(MCU_COM_REG0, regRd(MCU_COM_REG0) or 0x2)
        regWr(FCE_PSE_CTRL, 0x1)
        UsbWifi.log("MT7612U: firmware running")
        return true
    }

    private fun sendFw(out: UsbEndpoint, src: ByteArray, srcOff: Int, dataLen: Int, maxPayload: Int, dstAddr: Int): Boolean {
        val maxLen = maxPayload - 8
        var pos = 0
        while (pos < dataLen) {
            val len = minOf(dataLen - pos, maxLen)
            val rounded = (len + 3) and 3.inv()
            val buf = ByteArray(4 + rounded + 4)
            putLe32(buf, 0, DMA_INFO_BASE or (len and 0xffff))
            System.arraycopy(src, srcOff + pos, buf, 4, len)
            var tries = 0
            while (true) {
                singleWr(WRITE_FCE, FCE_DMA_ADDR, dstAddr + pos)
                singleWr(WRITE_FCE, FCE_DMA_LEN, rounded shl 16)
                if (conn.bulkTransfer(out, buf, buf.size, 1000) >= 0) break
                if (++tries >= 3) { UsbWifi.log("$tag: firmware upload failed at $pos of $dataLen B"); return false }
                UsbWifi.log("$tag: firmware chunk at $pos of $dataLen B not taken - resending")
                sleep(20)
            }
            regWr(TX_CPU_FROM_FCE_CPU_DESC_IDX, regRd(TX_CPU_FROM_FCE_CPU_DESC_IDX) + 1)
            pos += len
            sleep(5)
        }
        return true
    }

    private fun configureFce() {
        regWr(FCE_PSE_CTRL, 0x1); regWr(TX_CPU_FROM_FCE_BASE_PTR, 0x400230); regWr(TX_CPU_FROM_FCE_MAX_COUNT, 0x1)
        regWr(FCE_PDMA_GLOBAL_CONF, 0x44); regWr(FCE_SKIP_FS, 0x3)
    }

    // ---- radio bring-up ----

    private fun resetWlan() {
        var v = regRd(WLAN_FUN_CTRL) and FRC_WL_ANT_SEL.inv()
        if (v and WLAN_EN != 0) { regWr(WLAN_FUN_CTRL, v or WLAN_RESET_RF); sleep(1); v = v and WLAN_RESET_RF.inv() }
        regWr(WLAN_FUN_CTRL, v); sleep(1)
        v = v or WLAN_EN; regWr(WLAN_FUN_CTRL, v); sleep(1)
        v = v or WLAN_CLK_EN; regWr(WLAN_FUN_CTRL, v); sleep(1)
    }

    private fun powerOn() {
        cfgSet(0x148, 1 shl 0)
        val up = (1 shl 28) or (1 shl 12) or (1 shl 13)
        if (!poll(1000, 2) { cfgRd(0x148) and up == up }) UsbWifi.log("MT7612U: power-on poll timed out (continuing)")
        cfgClear(0x148, 0x7f shl 16); sleep(1)
        cfgClear(0x148, 0xf shl 24); sleep(1)
        cfgSet(0x148, 0xf shl 24); cfgClear(0x148, 0xfff)
        cfgClear(0x1204, 1 shl 3)
        cfgSet(0x80, 1 shl 0)
        cfgClear(0x64, 1 shl 18)
        powerOnRf(0); powerOnRf(1)
    }

    private fun powerOnRf(unit: Int) {
        val s = if (unit != 0) 8 else 0
        cfgSet(0x130, 1 shl s); sleep(1)
        cfgSet(0x130, ((1 shl 1) or (1 shl 3) or (1 shl 4) or (1 shl 5)) shl s); sleep(1)
        cfgClear(0x130, (1 shl 2) shl s); sleep(1)
        cfgSet(0x130, (1 shl 0) or (1 shl 16)); sleep(1)
        cfgClear(0x1c, 0xff); cfgSet(0x1c, 0x30)
        cfgWr(0x14, 0x484f); sleep(1)
        cfgSet(0x130, 1 shl 17); sleep(1)
        cfgClear(0x130, 1 shl 16); sleep(1)
        cfgSet(0x14c, (1 shl 19) or (1 shl 20))
        regWr(0x530, 0xf)
    }

    private fun initDma() {
        var v = cfgRd(USB_U3DMA_CFG)
        v = v or DMA_RX_DROP_OR_PAD or DMA_RX_BULK_EN or DMA_TX_BULK_EN
        v = v and DMA_RX_BULK_AGG_EN.inv() // one frame per transfer
        cfgWr(USB_U3DMA_CFG, v)
    }

    private fun macReset() {
        regWr(WPDMA_GLO_CFG, (1 shl 4) or (1 shl 5))
        regWr(PBF_TX_MAX_PCNT, 0xefef3f1f.toInt())
        regWr(PBF_RX_MAX_PCNT, 0xfebf)
        for (p in MAC_INITVALS) regWr(p[0], p[1])
        regWr(TX_LINK_CFG, 0x1020)
        regWr(AUTO_RSP_CFG, AUTO_RSP_NO_RESPONDER)
        regWr(MAX_LEN_CFG, 0x2f00)
        regWr(WMM_AIFSN, 0x2273); regWr(WMM_CWMIN, 0x2344); regWr(WMM_CWMAX, 0x34aa)
        regClear(MAC_SYS_CTRL, SYS_CTRL_RESET_CSR or SYS_CTRL_RESET_BBP)
        regClear(COEXCFG0, COEX_EN)
        regSet(EXT_CCA_CFG, 0xf000)
        regClear(TX_ALC_CFG_4, 1 shl 31)
    }

    private fun macStart() {
        regWr(MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX)
        waitWpdma()
        regWr(RX_FILTR_CFG, RX_FILTER_MONITOR)
        regWr(MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX or SYS_CTRL_ENABLE_RX)
        waitWpdma()
        regWr(CH_TIME_CFG, 0x15f)
        regRd(CH_BUSY); regRd(CH_IDLE)
    }

    /** Hops use the firmware's quick channel switch; the full calibration runs on the first tune and every 30 s per channel. */
    private fun tune(ep: UsbEndpoint, ch: Int, scan: Boolean) {
        if (x0) { tuneX0(ep, ch, scan); return }
        val is5 = if (ch <= 14) 0 else 1
        if (is5 == 0) { regSet(TX_BAND_CFG, 1 shl 2); regClear(TX_BAND_CFG, 1 shl 1) }
        else { regClear(TX_BAND_CFG, 1 shl 2); regSet(TX_BAND_CFG, 1 shl 1) }
        regRmw(BBP_CORE1, 0x18, 0)
        regRmw(BBP_AGC0, 0x7000, 1 shl 12)
        val wait = !scan
        val m = byteArrayOf(ch.toByte(), if (scan) 1 else 0, 0, 0, 0x03, 0x00, 0x00, 0x00)
        mcuCmd(ep, CMD_SWITCH_CHANNEL_OP, m, wait); sleep(6)
        m[6] = 0xe0.toByte()
        mcuCmd(ep, CMD_SWITCH_CHANNEL_OP, m, wait)

        val now = System.currentTimeMillis()
        if (!scan || now - (lastCalMs[ch] ?: 0L) > CAL_TTL_MS) {
            mcuCmd(ep, CMD_INIT_GAIN_OP, leWords(ch or (1 shl 31), mcuGain), wait)
            regSet(BBP_RXO13, 1 shl 10)
            mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RXDCOC, ch), wait)
            mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RC, 0), wait)
            regWr(BBP_AGC61, 0xff64a4e2.toInt()); regWr(BBP_AGC7, 0x08081010)
            regWr(BBP_AGC11, 0x00000404); regWr(BBP_AGC2, 0x00007070)
            regWr(TXOP_CTRL_CFG, 0x04101b3f)
            regSet(BBP_TXO4, 1 shl 25); regSet(BBP_RXO13, 1 shl 8)
            lastCalMs[ch] = now
        }
        if (scan) return
        if (is5 == 1) mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_LC, 0), true)
        mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TX_LOFT, is5), true)
        mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TXIQ, is5), true)
        mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RXIQC_FI, is5), true)
        mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TEMP, 0), true)
        mcuCmd(ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TX_SHAPING, 0), true)
        // EDCCA setup (the auto-responder stays off)
        regSet(TX_LINK_CFG, 1 shl 12)
        regClear(TXOP_CTRL_CFG, 1 shl 20)
        regWr(BBP_AGC2, 0x00007070)
        regSet(TXOP_HLDR_ET, 1 shl 1)
        regSet(MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX)
        regWr(TX_PIN_CFG, regRd(TX_PIN_CFG) or 0xf or (0xf shl 8) or (1 shl 16) or (1 shl 18))
        regRd(ED_CCA_TIMER)
    }

    private fun fixupXtal(ee: ByteArray) {
        var ev = le16(ee, EE_XTAL_TRIM_2)
        var offset = ev and 0x7f
        if ((ev and 0xff) == 0xff) offset = 0 else if (ev and 0x80 != 0) offset = -offset
        ev = ev shr 8
        if (ev == 0x00 || ev == 0xff) {
            ev = le16(ee, EE_XTAL_TRIM_1) and 0xff
            if (ev == 0x00 || ev == 0xff) ev = 0x14
        }
        ev = ev and 0x7f
        val c2 = (ev + offset) and 0x7f
        cfgWr(XO_CTRL5, (cfgRd(XO_CTRL5) and (0x7f shl 8).inv()) or (c2 shl 8))
        cfgSet(XO_CTRL6, 0x7f shl 8)
        regWr(ADDR_504, 0x06000000); regWr(ADDR_50C, 0x08800000); sleep(5); regWr(ADDR_504, 0)
        regRmw(XIFS_TIME_CFG, 0xff shl 8, 0xd shl 8)
        regRmw(BKOFF_SLOT_CFG, 0xf shl 8, 1 shl 8)
        regClear(FCE_L2_STUFF, 1 shl 4)
        when ((le16(ee, EE_NIC_CONF_2) shr 9) and 0x3) {
            0 -> regWr(XO_CTRL7, 0x5c1fee80)
            1 -> regWr(XO_CTRL7, 0x5c1feed0)
        }
    }

    private fun loadCr(ep: UsbEndpoint, ee: ByteArray, ch: Int) {
        val nc0 = le16(ee, EE_NIC_CONF_0); val nc1 = le16(ee, EE_NIC_CONF_1)
        val cfg = (1 shl 31) or ((nc0 shr 8) and 0xff) or ((nc1 shl 8) and 0xff00)
        val m = ByteArray(8); m[0] = MT_RF_BBP_CR.toByte(); m[2] = ch.toByte(); putLe32(m, 4, cfg)
        mcuCmd(ep, CMD_LOAD_CR, m, true)
    }

    private fun mcuCmd(ep: UsbEndpoint, cmd: Int, payload: ByteArray, waitResp: Boolean) {
        seq = (seq + 1) and 0xf; if (seq == 0) seq = 1
        val rounded = (payload.size + 3) and 3.inv()
        val txinfo = (rounded and 0xffff) or (2 shl 27) or (seq shl 16) or (cmd shl 20) or (1 shl 30)
        val buf = ByteArray(4 + rounded + 4)
        putLe32(buf, 0, txinfo); System.arraycopy(payload, 0, buf, 4, payload.size)
        if (conn.bulkTransfer(ep, buf, buf.size, 1000) < 0) ctlErrors++
        if (!waitResp) return
        val rep = epResp ?: run { sleep(10); return }
        val rb = ByteArray(512)
        val n = conn.bulkTransfer(rep, rb, rb.size, 300)
        mcuAsked++; if (n > 0) mcuAnswered++
        if (mcuLogged < 6) {
            mcuLogged++
            UsbWifi.log(if (n > 0) "MT7612U: MCU cmd $cmd answered ($n B, 0x%08x)".format(if (n >= 4) le32(rb, 0) else 0)
                        else "MT7612U: MCU cmd $cmd - no answer ($n)")
        }
    }

    private fun drainCmdResp() {
        val ep = epResp ?: return
        val tmp = ByteArray(512)
        repeat(6) { if (conn.bulkTransfer(ep, tmp, tmp.size, 1) <= 0) return }
    }

    private fun reKickRx() {
        regClear(MAC_SYS_CTRL, SYS_CTRL_ENABLE_RX)
        if (x0) {
            val v = regRd(USB_DMA_CFG)
            regWr(USB_DMA_CFG, v and DMA_RX_BULK_EN.inv()); regWr(USB_DMA_CFG, v or DMA_RX_BULK_EN)
        } else {
            val v = cfgRd(USB_U3DMA_CFG)
            cfgWr(USB_U3DMA_CFG, v and DMA_RX_BULK_EN.inv()); cfgWr(USB_U3DMA_CFG, v or DMA_RX_BULK_EN)
        }
        regSet(MAC_SYS_CTRL, SYS_CTRL_ENABLE_RX)
    }

    // ---- receive ----

    private class RxState {
        var hopAt = 0L; var lastEmit = 0L; var lastRxAt = 0L; var lastCheck = 0L; var hopIdx = 0
        var badLogged = 0; var revivals = 0
    }

    private fun rxLoop(chipName: String, epCmd: UsbEndpoint, ep: UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0; st.lastRxAt = t0; st.lastCheck = t0
        val stats = BulkRx.Stats(tag)
        val total = BulkRx.run(tag, conn, ep, 24, RX_BUF, { stop }, onData@{ b, n ->
            stats.transfer(n)
            st.lastRxAt = System.currentTimeMillis()
            stats.sample("$n bytes, DMA header + RXWI", b, 0, DMA_HDR + 16)
            val ch = HOP[st.hopIdx]
            val f = parseRx(b, n, onBad = { crc -> if (crc) stats.badChecksum++ else stats.malformed++ },
                why = { if (st.badLogged < MAX_BAD_LOGS) { st.badLogged++; UsbWifi.log("$tag: transfer dropped - $it") } }) ?: return@onData
            val rssi = f.rawRssi?.let { if (ch <= 14) it + rssiOff2g - lna2g else it + rssiOff5g - lna5g }
            val rssi1 = f.rawRssi1?.takeUnless { x0 }?.let { if (ch <= 14) it + rssiOff2g1 - lna2g else it + rssiOff5g1 - lna5g } // MT7610U: one chain
            stats.frame(rssi)
            frames.frame(b, f.start, f.end, ch, rssi, pathA = rssi, pathB = rssi1)
        }) { now ->
            stats.maybeLog(now) {
                val (aps, clients) = frames.live(now, REPORT_MS)
                val busy = regRd(CH_BUSY); val rs0 = regRd(RX_STAT_0) // clear on read
                "ch ${HOP[st.hopIdx]}, $aps access points, $clients devices, channel busy $busy, " +
                    "radio drops: ${rs0 and 0xffff} checksum / ${(rs0 ushr 16) and 0xffff} PHY, USB errors $ctlErrors"
            }
            if (now - st.lastCheck > 3000) {
                st.lastCheck = now
                // Energy on the air but nothing arriving for a while: restart the USB receive path.
                if (now - st.lastRxAt > 6000 && regRd(CH_BUSY) > 50000) {
                    UsbWifi.log("$tag: receive stalled (${(now - st.lastRxAt) / 1000} s) - restarting it")
                    runCatching { reKickRx() }
                    st.lastRxAt = now
                } else if (now - st.lastRxAt > DEAF_MS) {
                    // Nothing at all, not even energy on the channel: the receiver itself is off or
                    // lost its calibration (seen after a quick restart without a replug). Switch it
                    // back on and fully retune.
                    val sys = regRd(MAC_SYS_CTRL)
                    if (st.revivals < 5) UsbWifi.log("$tag: nothing received for ${(now - st.lastRxAt) / 1000} s (SYS_CTRL 0x%08x) - re-enabling the receiver".format(sys))
                    st.revivals++
                    runCatching {
                        if (sys and SYS_CTRL_ENABLE_RX == 0) macStart() else reKickRx()
                        tune(epCmd, HOP[st.hopIdx], scan = false)
                    }
                    drainCmdResp()
                    st.lastRxAt = now
                }
            }
            if (now - st.lastEmit > 2000) { frames.emit(FRESH_MS, onFrame); st.lastEmit = now }
            val lock = AntennaCheck.lockIndex(HOP) // antenna check: hold the reference's channel
            if (lock >= 0 && lock == st.hopIdx) AntennaCheck.holding = HOP[lock]
            if (if (lock >= 0) lock != st.hopIdx else now - st.hopAt > DWELL_MS) {
                st.hopIdx = if (lock >= 0) lock else (st.hopIdx + 1) % HOP.size
                runCatching { tune(epCmd, HOP[st.hopIdx], scan = true) }
                drainCmdResp()
                val (aps, clients) = frames.live(now, REPORT_MS)
                onStatus("$chipName · live · $aps access points · $clients devices · ch ${HOP[st.hopIdx]}" +
                    UNCALIBRATED.takeIf { uncalibrated }.orEmpty())
                st.hopAt = System.currentTimeMillis() // the dwell starts once the retune is done
            }
        }
        frames.emit(FRESH_MS, onFrame)
        UsbWifi.log("$tag: RX loop stopped ($total B)")
    }

    /** Where the 802.11 frame sits in a transfer and the radio's raw RSSI per chain (0 and 1, before calibration; null when absent). */
    internal data class Rx(val start: Int, val end: Int, val rawRssi: Int?, val rawRssi1: Int? = null)

    /**
     * One transfer: | DMA header (4: length) | RXWI (32) | 802.11 header | L2 pad (2, when flagged) | body | FCE info |.
     * The L2 pad is removed in place (the header moves up 2 bytes), as mt76 does, so the frame
     * is contiguous. Null for a bad checksum or a malformed transfer ([onBad] says which).
     * With RX aggregation off a transfer holds exactly one frame, so its length must match the
     * DMA header: anything else is a piece of a split transfer, whose "descriptor" is frame
     * payload (random lengths and signal values, phantom addresses) and is dropped.
     */
    internal fun parseRx(b: ByteArray, n: Int, onBad: (Boolean) -> Unit = {}, why: (String) -> Unit = {}): Rx? {
        // Only lengths and flags go into [why], never frame bytes (addresses, network names).
        if (n < DMA_HDR + RXWI_LEN + MIN_FRAME || n > b.size) { onBad(false); why("$n B transfer, too short"); return null }
        val dmaLen = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
        if (dmaLen < RXWI_LEN + MIN_FRAME || dmaLen and 3 != 0 || DMA_HDR + dmaLen > n || n > DMA_HDR + dmaLen + FCE_INFO) {
            onBad(false); why("$n B transfer, DMA length $dmaLen"); return null
        }
        val rxwi = DMA_HDR
        val rxinfo = le32(b, rxwi)
        if (rxinfo and RXINFO_CRCERR != 0) { onBad(true); return null }
        val mpdu = (le32(b, rxwi + 4) ushr 16) and 0x3fff
        var d = rxwi + RXWI_LEN
        val pad = if (rxinfo and RXINFO_L2PAD != 0) 2 else 0
        if (mpdu < MIN_FRAME || d + mpdu + pad > DMA_HDR + dmaLen || DMA_HDR + dmaLen - (d + mpdu + pad) >= 4) {
            onBad(false); why("$n B transfer, DMA length $dmaLen, frame $mpdu B, pad $pad, RX info 0x%08x".format(rxinfo)); return null
        }
        // ACK / CTS / RTS / PS-Poll: shorter than a full header and carry no sender worth showing.
        if (mpdu < 24) { if ((b[d].toInt() shr 2) and 3 != 1) { onBad(false); why("$mpdu B frame that isn't a control frame") }; return null }
        if (pad != 0) {
            val hdr = headerLength(b, d).coerceAtMost(mpdu)
            System.arraycopy(b, d, b, d + pad, hdr)
            d += pad
        }
        val raw = b[rxwi + 12].toInt() // RSSI chain 0, signed dBm-ish
        val raw1 = b[rxwi + 13].toInt() // chain 1
        return Rx(d, d + mpdu, if (raw != 0) raw else null, if (raw1 != 0) raw1 else null)
    }

    /** 802.11 header length from its frame control (address 4, QoS, HT control). */
    private fun headerLength(b: ByteArray, d: Int): Int {
        val fc = b[d].toInt() and 0xff; val fc1 = b[d + 1].toInt() and 0xff
        val type = (fc shr 2) and 3; val sub = (fc shr 4) and 0xf
        var len = 24
        if (type == 2) {
            if (fc1 and 0x03 == 0x03) len += 6
            if (sub and 0x8 != 0) { len += 2; if (fc1 and 0x80 != 0) len += 4 }
        } else if (type == 0 && fc1 and 0x80 != 0) len += 4
        return len
    }

    private fun computeRssiCal(ee: ByteArray) {
        fun signExt(v: Int, bits: Int): Int { val s = 1 shl (bits - 1); return if (v and s != 0) v - (1 shl bits) else v }
        fun rssiOff(v: Int): Int { val b0 = v and 0xff; return if (b0 == 0 || b0 == 0xff || b0 and 0x80 == 0) 0 else signExt(b0, 7) }
        fun lnaOf(v: Int): Int { val b0 = v and 0xff; return if (b0 == 0xff) 0 else signExt(b0, 8) }
        rssiOff2g = rssiOff(le16(ee, EE_RSSI_OFFSET_2G_0))
        rssiOff5g = rssiOff(le16(ee, EE_RSSI_OFFSET_5G_0))
        rssiOff2g1 = rssiOff(le16(ee, EE_RSSI_OFFSET_2G_0) ushr 8)
        rssiOff5g1 = rssiOff(le16(ee, EE_RSSI_OFFSET_5G_0) ushr 8)
        lna2g = lnaOf(le16(ee, EE_LNA_GAIN))
        lna5g = lnaOf(le16(ee, EE_LNA_GAIN) ushr 8)
    }

    private fun computeMcuGain(ee: ByteArray): Int {
        val v044 = le16(ee, EE_LNA_GAIN)
        val lna2 = v044 and 0xff
        val lna5g0 = (v044 ushr 8) and 0xff
        var lna5g1 = (le16(ee, EE_RSSI_OFFSET_2G_1) ushr 8) and 0xff
        if (lna5g1 == 0 || lna5g1 == 0xff) lna5g1 = lna5g0
        return lna2 or (lna5g0 shl 8) or (lna5g1 shl 16) or (lna5g0 shl 24)
    }

    private fun readEeprom(): ByteArray {
        val ee = ByteArray(EE_SIZE)
        var failed = 0
        var i = 0
        while (i + 4 <= EE_SIZE) {
            val b = ByteArray(4)
            if (conn.controlTransfer(IN_VENDOR, READ_EEPROM, 0, i, b, 4, 1000) == 4) System.arraycopy(b, 0, ee, i, 4) else failed++
            i += 4
        }
        if (failed > 0) UsbWifi.log("MT7612U: $failed of ${EE_SIZE / 4} EEPROM reads failed")
        return ee
    }

    // ---- MT7610U (mt76x0u): bring-up, in the order of mt76x0u_probe / _init_hardware / _start ----

    private fun runX0(ctx: Context, device: UsbDevice, chipName: String, cmdOut: UsbEndpoint, rx: UsbEndpoint, endpoints: List<UsbEndpoint>,
                      t0: Long, onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        onStatus("$chipName · powering on…")
        chipOnOffX0(enable = false, reset = false) // otherwise the MCU fails to start on a warm adapter
        if (!macReadyX0()) { onStatus("$chipName: MAC not ready - unplug the adapter and plug it back in"); return }
        val ver = regRd(ASIC_VERSION)
        UsbWifi.log("MT7610U: ASIC_VERSION 0x%08x".format(ver))
        if (ver ushr 16 != 0x7610) { onStatus("$chipName: this MediaTek chip (%04x) isn't supported yet".format(ver ushr 16)); return }
        chipOnOffX0(enable = true, reset = true)
        if (!macReadyX0()) { onStatus("$chipName: MAC not ready - unplug the adapter and plug it back in"); return }

        onStatus("$chipName · loading firmware…")
        val fw = Firmware.read(ctx, "mt7610u.bin")
        var loaded = false
        for (attempt in 1..FW_ATTEMPTS) {
            if (attempt > 1) {
                UsbWifi.log("MT7610U: retrying the firmware load (attempt $attempt of $FW_ATTEMPTS)")
                sleep(1000)
                chipOnOffX0(enable = true, reset = true)
            }
            for (ep in endpoints) clearHalt(ep)
            if (loadFirmwareX0(cmdOut, fw)) { loaded = true; break }
        }
        if (!loaded) { onStatus("$chipName: firmware didn't load - unplug the adapter and plug it back in"); return }

        onStatus("$chipName · starting the radio…")
        initDmaX0()
        if (!initHardwareX0(cmdOut)) { onStatus("$chipName: radio didn't start - unplug the adapter and plug it back in"); return }
        val board = (conf0X0 ushr 12) and 3 // BOARD_TYPE: 1 = 2.4 GHz only, 2 = 5 GHz only
        val has2 = board != 2 && !(device.vendorId == 0x2357 && device.productId == 0x0105) // Archer T1U: 5 GHz only
        val has5 = board != 1
        antSelectX0(has2, has5)
        rfInitX0(cmdOut)
        regClear(BBP_AGC0, (1 shl 4) or (1 shl 3)) // RX path: the one chain
        regClear(BBP_TXBE5, 0x3)
        regRmw(US_CYC_CFG, 0xff, 0x1e)
        regWr(TXOP_CTRL_CFG, 0x583f)
        HOP = HOP.filter { if (it <= 14) has2 else has5 }.toIntArray()
        if (HOP.isEmpty()) { onStatus("$chipName: this adapter's band is turned off in Settings"); return }

        onStatus("$chipName · tuning and calibrating…")
        macStart()
        calibrateX0(cmdOut, HOP[0], powerOn = true)
        tune(cmdOut, HOP[0], scan = false)
        UsbWifi.log("MT7610U: started in %d ms - SYS_CTRL 0x%08x STATUS 0x%08x USB_DMA 0x%08x RX_FILTR 0x%08x AUTO_RSP 0x%08x, USB errors %d - monitor live ch %d".format(
            System.currentTimeMillis() - t0, regRd(MAC_SYS_CTRL), regRd(MAC_STATUS), regRd(USB_DMA_CFG), regRd(RX_FILTR_CFG),
            regRd(AUTO_RSP_CFG), ctlErrors, HOP[0]))
        uncalibrated = mcuAsked >= 3 && mcuAnswered == 0
        onStatus("$chipName · live · monitor" + UNCALIBRATED.takeIf { uncalibrated }.orEmpty())
        rxLoop(chipName, cmdOut, rx, onFrame, onStatus)
    }

    private fun macReadyX0() = poll(2500, 5) { regRd(MAC_CSR0).let { it != 0 && it != -1 } }

    /** mt76x0_chip_onoff. */
    private fun chipOnOffX0(enable: Boolean, reset: Boolean) {
        var v = regRd(WLAN_FUN_CTRL)
        if (reset) {
            v = (v or (0xff shl 24)) and FRC_WL_ANT_SEL.inv() // GPIO_OUT_EN
            if (v and WLAN_EN != 0) {
                val resets = (1 shl 3) or WLAN_RESET_RF
                regWr(WLAN_FUN_CTRL, v or resets); sleep(1)
                v = v and resets.inv()
            }
        }
        regWr(WLAN_FUN_CTRL, v); sleep(1)
        v = if (enable) v or WLAN_EN or WLAN_CLK_EN else v and WLAN_EN.inv()
        regWr(WLAN_FUN_CTRL, v); sleep(1)
        val locked = (1 shl 22) or (1 shl 23) // XTAL_RDY, PLL_LD
        if (enable && !poll(2000, 2) { regRd(CMB_CTRL) and locked == locked }) UsbWifi.log("MT7610U: PLL and crystal check failed (continuing)")
    }

    /** mt76x0u_load_firmware. */
    private fun loadFirmwareX0(out: UsbEndpoint, fw: ByteArray): Boolean {
        regWr(USB_DMA_CFG, DMA_RX_BULK_EN or DMA_TX_BULK_EN)
        if (regRd(MCU_COM_REG0) == 1) { UsbWifi.log("MT7610U: firmware already running"); return true }
        if (fw.size <= FW_HDR_LEN) return false
        val ilmLen = le32(fw, 0); val dlmLen = le32(fw, 4)
        if (ilmLen <= IVB_SIZE || FW_HDR_LEN + ilmLen + dlmLen != fw.size) { UsbWifi.log("MT7610U: firmware file size mismatch"); return false }
        UsbWifi.log("MT7610U: firmware build '${asciiz(fw, 16, 16)}', ILM $ilmLen B, DLM $dlmLen B")
        regWr(MAC_SYS_CTRL, 0x2c)
        regSet(USB_DMA_CFG, DMA_RX_BULK_EN or DMA_TX_BULK_EN or 0x20)
        vendor(DEV_MODE, OUT_VENDOR, 0x1, null) // firmware reset
        sleep(6)
        configureFce()
        regSet(USB_DMA_CFG, DMA_TX_WL_DROP); regClear(USB_DMA_CFG, DMA_TX_WL_DROP)
        if (!sendFw(out, fw, FW_HDR_LEN + IVB_SIZE, ilmLen - IVB_SIZE, FW_URB_X0, IVB_SIZE)) return false
        if (!sendFw(out, fw, FW_HDR_LEN + ilmLen, dlmLen, FW_URB_X0, DLM_X0)) return false
        vendor(DEV_MODE, OUT_VENDOR, 0x12, fw.copyOfRange(FW_HDR_LEN, FW_HDR_LEN + IVB_SIZE)) // start from the IVB
        if (!poll(1000, 10) { regRd(MCU_COM_REG0) and 0x1 == 0x1 }) { UsbWifi.log("MT7610U: firmware failed to start"); return false }
        regWr(FCE_PSE_CTRL, 0x1)
        UsbWifi.log("MT7610U: firmware running")
        return true
    }

    /** mt76x0_init_usb_dma. */
    private fun initDmaX0() {
        val v = (regRd(USB_DMA_CFG) or DMA_RX_BULK_EN or DMA_TX_BULK_EN) and DMA_RX_BULK_AGG_EN.inv() // one frame per transfer
        regWr(USB_DMA_CFG, v)
        regWr(USB_DMA_CFG, v or DMA_RX_DROP_OR_PAD)
        regWr(USB_DMA_CFG, v and DMA_RX_DROP_OR_PAD.inv())
    }

    /** mt76x0_init_hardware up to the EEPROM; its shared key and WCID clearing only matters to a client. */
    private fun initHardwareX0(ep: UsbEndpoint): Boolean {
        if (!waitWpdma()) UsbWifi.log("MT7610U: DMA still busy after the firmware (continuing)")
        if (!macReadyX0()) return false
        regWr(MAC_SYS_CTRL, SYS_CTRL_RESET_CSR or SYS_CTRL_RESET_BBP); sleep(200)
        regClear(MAC_SYS_CTRL, SYS_CTRL_RESET_CSR or SYS_CTRL_RESET_BBP)
        mcuX0(ep, CMD_FUN_SET_OP, leWords(Q_SELECT, 1), false)
        writeAll(Mt7610uTables.MAC)
        regClear(MAC_SYS_CTRL, SYS_CTRL_RESET_CSR or SYS_CTRL_RESET_BBP)
        regSet(EXT_CCA_CFG, 0xf000)
        regClear(FCE_L2_STUFF, 1 shl 4)
        regRmw(WMM_CTRL, 0x3ff, 0x201)
        if (!poll(100, 2) { regRd(MAC_STATUS) and 3 == 0 }) { UsbWifi.log("MT7610U: MAC not idle"); return false }
        if ((1..20).none { regRd(BBP_CORE0).let { v -> v != 0 && v != -1 } }) { UsbWifi.log("MT7610U: BBP not ready"); return false }
        writeAll(Mt7610uTables.BBP_INIT); writeAll(Mt7610uTables.BBP_2G); writeAll(Mt7610uTables.BBP_DCOC)
        val ee = readEfuse()
        conf0X0 = le16(ee, EE_NIC_CONF_0)
        calFromEfuse(ee)
        UsbWifi.log("MT7610U: EFUSE MAC %s:xx:xx:xx, NIC_CONF 0x%04x, rssi_off 2g %d 5g %d, lna 2g %d 5g %s, freq offset %d".format(
            (0 until 3).joinToString(":") { "%02x".format(ee[EE_MAC_ADDR + it].toInt() and 0xff) },
            conf0X0, rssiOff2g, rssiOff5g, lna2g, lnaX0.joinToString("/"), freqOffsetX0))
        antCfgX0 = le16(ee, EE_ANTENNA) to le16(ee, EE_CFG1_INIT)
        antConf2X0 = le16(ee, EE_NIC_CONF_2)
        return true
    }

    private fun writeAll(tab: IntArray) { for (i in tab.indices step 2) regWr(tab[i], tab[i + 1]) }

    /** mt76x02_efuse_read over the 512 B map, 16 B per read: unwritten blocks read as 0xff. */
    private fun readEfuse(): ByteArray {
        val ee = ByteArray(EE_SIZE)
        for (addr in 0 until EE_SIZE step 16) {
            regWr(EFUSE_CTRL, (regRd(EFUSE_CTRL) and (0x03ff0000 or 0xc0).inv()) or (addr shl 16) or (1 shl 30))
            if (!poll(1000, 1) { regRd(EFUSE_CTRL) and (1 shl 30) == 0 }) UsbWifi.log("MT7610U: EFUSE read timed out at $addr")
            if (regRd(EFUSE_CTRL) and 0x3f == 0x3f) ee.fill(0xff.toByte(), addr, addr + 16)
            else for (i in 0 until 4) putLe32(ee, addr + i * 4, regRd(EFUSE_DATA + i * 4))
        }
        return ee
    }

    /** mt76x0_set_freq_offset and mt76x0_read_rx_gain. */
    internal fun x0FreqOffset(trim: Int, compensation: Int): Int {
        val valid = { v: Int -> v != 0 && v != 0xff }
        val base = if (valid(trim)) trim else 0
        val c = if (valid(compensation)) compensation else 0
        val delta = if (c and 0x80 != 0) c and 0x7f else -(c and 0x7f) // mt76x02_sign_extend: the top bit set means positive
        return (base - delta) and 0xff
    }

    private fun calFromEfuse(ee: ByteArray) {
        fun u8(i: Int) = ee[i].toInt() and 0xff
        fun offset(i: Int) = ee[i].toInt().takeIf { it in -10..10 } ?: 0
        fun lna(v: Int) = if (v == 0xff) 0 else v.toByte().toInt()
        freqOffsetX0 = x0FreqOffset(u8(EE_XTAL_TRIM_1), u8(EE_TSSI_BOUND4 + 1))
        rssiOff2g = offset(EE_RSSI_OFFSET_2G_0); rssiOff5g = offset(EE_RSSI_OFFSET_5G_0)
        lna2g = lna(u8(EE_LNA_GAIN))
        val l0 = u8(EE_LNA_GAIN + 1)
        fun orFirst(v: Int) = if (v != 0 && v != 0xff) v else l0
        lnaX0[0] = lna(l0); lnaX0[1] = lna(orFirst(u8(EE_RSSI_OFFSET_2G_1 + 1))); lnaX0[2] = lna(orFirst(u8(EE_RSSI_OFFSET_5G_1 + 1)))
        lna5g = lnaX0[0]
    }

    /** mt76x0_phy_ant_select. */
    private fun antSelectX0(has2: Boolean, has5: Boolean) {
        var ant = antCfgX0.first and ((1 shl 14) or (1 shl 12)).inv()
        var wlan = regRd(WLAN_FUN_CTRL) and ((1 shl 6) or (1 shl 5)).inv()
        var coex3 = regRd(COEXCFG3) and (0xf shl 2).inv()
        if (ant and (1 shl 15) != 0) { // dual antenna
            val diversity = antConf2X0 and (1 shl 3) == 0 && antConf2X0 and (1 shl 4) != 0
            if (diversity) ant = ant or (1 shl 12) else coex3 = coex3 or (1 shl 4)
            coex3 = coex3 or (1 shl 3)
            if (has2) wlan = wlan or (1 shl 6)
        } else if (has5) coex3 = coex3 or (1 shl 3) or (1 shl 4)
        else { wlan = wlan or (1 shl 6); coex3 = coex3 or (1 shl 1) }
        regWr(WLAN_FUN_CTRL, wlan)
        regRmw(CMB_CTRL, 0xffff, ant)
        regRmw(CSR_EE_CFG1, 0xffff, antCfgX0.second)
        regClear(COEXCFG0, 1 shl 2)
        regWr(COEXCFG3, coex3)
    }

    /** mt76x0_phy_rf_init. */
    private fun rfInitX0(ep: UsbEndpoint) {
        rfWrAll(ep, Mt7610uTables.RF_CENTRAL); rfWrAll(ep, Mt7610uTables.RF_2G)
        rfWrAll(ep, Mt7610uTables.RF_5G); rfWrAll(ep, Mt7610uTables.RF_VGA)
        rfWrAll(ep, Mt7610uTables.RF_BW_INIT)
        rfWrAll(ep, rfRows(Mt7610uTables.RF_BAND, RF_G_BAND))
        rfWr(ep, rf(0, 22), minOf(freqOffsetX0, 0xbf))
        rfRd(ep, rf(0, 22))
        rfRmw(ep, rf(0, 73), 0, 0x80); rfRmw(ep, rf(0, 73), 0x80, 0); rfRmw(ep, rf(0, 73), 0, 0x80) // DAC reset
        rfRmw(ep, rf(0, 4), 0, 0x80) // VCO calibration
    }

    /** mt76x0_phy_set_channel at 20 MHz, without the transmit power. */
    private fun tuneX0(ep: UsbEndpoint, ch: Int, scan: Boolean) {
        val f = Mt7610uTables.CHANNELS.firstOrNull { it[0] == ch } ?: return
        val is5 = ch > 14
        mcuX0(ep, CMD_FUN_SET_OP, leWords(BW_SETTING, 0), true)
        regRmw(BBP_CORE1, 0x18, 0)
        regRmw(BBP_AGC0, 0x7000 or 0x300, 1 shl 12)
        regRmw(BBP_TXBE0, 0x3, 0)
        regRmw(TX_BAND_CFG, 0x7, if (is5) 1 shl 1 else 1 shl 2)
        regRmw(EXT_CCA_CFG, 0xfff, (1 shl 2) or (2 shl 4) or (3 shl 6) or (1 shl 8))
        // mt76x0_phy_set_band
        rfWrAll(ep, if (is5) Mt7610uTables.RF_5G else Mt7610uTables.RF_2G)
        rfWr(ep, rf(5, 0), if (is5) 0x44 else 0x45)
        rfWr(ep, rf(6, 0), if (is5) 0x45 else 0x44)
        regWr(TX_ALC_VGA3, if (is5) 0x00000005 else 0x00050007)
        regWr(TX0_RF_GAIN_CORR, if (is5) 0x01010102 else 0x003E0002)
        setChanRfX0(ep, f)
        if (ch == 14) regSet(BBP_CORE1, 0x20) else regClear(BBP_CORE1, 0x20)
        // mt76x0_read_rx_gain, then mt76x0_phy_set_chan_bbp_params
        if (is5) lna5g = lnaX0[if (ch <= 64) 0 else if (ch <= 128) 1 else 2]
        val tab = if (is5) Mt7610uTables.BBP_5G else Mt7610uTables.BBP_2G
        for (i in tab.indices step 2) regWr(tab[i], if (tab[i] == BBP_AGC8) x0AgcGain(tab[i + 1], if (is5) lna5g else lna2g) else tab[i + 1])
        rfRmw(ep, rf(0, 4), 0, 0x80) // VCO on
        // Linux skips calibration only while scanning; a monitor is always hopping, so as on the MT7612U each channel gets it every 30 s.
        val now = System.currentTimeMillis()
        if (!scan || now - (lastCalMs[ch] ?: 0L) > CAL_TTL_MS) { calibrateX0(ep, ch, powerOn = false); lastCalMs[ch] = now }
    }

    /** The AGC value with its gain field (bits 14:8) lowered by twice the LNA gain. */
    internal fun x0AgcGain(v: Int, lna: Int) = (v and (0x7f shl 8).inv()) or (((((v ushr 8) and 0x7f) - lna * 2) and 0x7f) shl 8)

    /** mt76x0_phy_set_chan_rf_params; [f] is a row of [Mt7610uTables.CHANNELS]. */
    private fun setChanRfX0(ep: UsbEndpoint, f: IntArray) {
        val band = f[1]; val sdm = f[20] == 1
        rfWr(ep, rf(0, 37), f[2]); rfWr(ep, rf(0, 36), f[3]); rfWr(ep, rf(0, 35), f[4])
        rfWr(ep, rf(0, 34), f[5]); rfWr(ep, rf(0, 33), f[6])
        rfRmw(ep, rf(0, 32), 0xe0, f[7]); rfRmw(ep, rf(0, 32), 0x1f, f[8])
        rfRmw(ep, rf(0, 31), 0xe0, f[9]); rfRmw(ep, rf(0, 31), 0x1f, f[10])
        if (sdm) { rfRmw(ep, rf(0, 30), 0x80, 0); rfRmw(ep, rf(0, 30), 0, 0x80) } else rfRmw(ep, rf(0, 30), 0x80, f[11])
        rfRmw(ep, rf(0, 30), 0x7c, f[12])
        rfRmw(ep, rf(0, 30), 0x02, f[13] shl 1)
        rfWr(ep, rf(0, 29), f[14] and 0xff)
        rfRmw(ep, rf(0, 30), 0x01, (f[14] ushr 8) and 0x1)
        rfRmw(ep, rf(0, 28), 0xc0, f[15]); rfRmw(ep, rf(0, 28), 0x30, f[16]); rfRmw(ep, rf(0, 28), 0x0c, f[17])
        rfWr(ep, rf(0, 26), f[18] and 0xff); rfWr(ep, rf(0, 27), (f[18] ushr 8) and 0xff)
        rfRmw(ep, rf(0, 28), 0x03, (f[18] ushr 16) and 0x3)
        rfRmw(ep, rf(0, 24), 0x03, f[19])
        rfWrAll(ep, rfRows(Mt7610uTables.RF_BW, band, anyBand = true))
        rfWrAll(ep, rfRows(Mt7610uTables.RF_BAND, band))
        regClear(RF_MISC, 0xc)
        val is2 = band and RF_G_BAND != 0
        if (conf0X0 and (if (is2) 1 shl 8 else 1 shl 9) == 0) { // external PA
            regSet(RF_MISC, if (is2) 1 shl 3 else 1 shl 2)
            rfWrAll(ep, rfRows(Mt7610uTables.RF_EXT_PA, band))
        }
        regWr(TX0_RF_GAIN_ATTEN, if (is2) 0x63707400 else 0x686A7800)
        regWr(TX_ALC_CFG_1, regRd(TX_ALC_CFG_1) and (if (is2) 0x896400FF else 0x890400FF).toInt())
    }

    /** mt76x0_phy_calibrate, without the TSSI step: that one drives the transmit path. */
    private fun calibrateX0(ep: UsbEndpoint, ch: Int, powerOn: Boolean) {
        fun cal(id: Int, v: Int) { mcuX0(ep, CMD_CALIBRATION_OP, leWords(id, v), true) }
        if (powerOn) { cal(X0_CAL_R, 0); cal(X0_CAL_VCO, ch) }
        val txAlc = regRd(TX_ALC_CFG_0)
        regWr(TX_ALC_CFG_0, 0); sleep(1)
        val ibi9 = regRd(BBP_IBI9)
        regWr(BBP_IBI9, 0xffffff7e.toInt())
        val is5 = ch > 14
        cal(X0_CAL_FULL, if (!is5) 0x600 else if (ch < 100) 0x701 else if (ch < 140) 0x801 else 0x901)
        cal(X0_CAL_LC, if (is5) 1 else 0)
        sleep(16)
        regWr(BBP_IBI9, ibi9)
        regWr(TX_ALC_CFG_0, txAlc)
        cal(X0_CAL_RXDCOC, 1)
    }

    private fun rf(bank: Int, reg: Int) = (bank shl 16) or reg

    /** The register, value pairs of a register, band mask, value table that apply to [band]. */
    private fun rfRows(tab: IntArray, band: Int, anyBand: Boolean = false): IntArray {
        val out = ArrayList<Int>()
        for (i in tab.indices step 3) if (tab[i + 1] and band != 0 || anyBand && tab[i + 1] == 0) { out += tab[i]; out += tab[i + 2] }
        return out.toIntArray()
    }

    /** mt76x02u_mcu_wr_rp: USB reaches the radio's registers only through the MCU; only the last command waits. */
    private fun rfWrAll(ep: UsbEndpoint, pairs: IntArray) {
        var i = 0
        while (i < pairs.size) {
            val end = minOf(i + RF_PAIRS_PER_CMD * 2, pairs.size)
            val m = IntArray(end - i) { k -> if (k % 2 == 0) RF_MEMMAP + pairs[i + k] else pairs[i + k] }
            mcuX0(ep, CMD_RANDOM_WRITE, leWords(*m), end == pairs.size)
            i = end
        }
    }
    private fun rfWr(ep: UsbEndpoint, reg: Int, v: Int) = rfWrAll(ep, intArrayOf(reg, v))

    /** The register's value, or -1 when the MCU didn't answer. */
    private fun rfRd(ep: UsbEndpoint, reg: Int): Int {
        val r = mcuX0(ep, CMD_RANDOM_READ, leWords(RF_MEMMAP + reg, 0), true) ?: return -1
        return le32(r, 8) and 0xff // | FCE info | register | value |
    }
    private fun rfRmw(ep: UsbEndpoint, reg: Int, mask: Int, v: Int) {
        val cur = rfRd(ep, reg)
        if (cur >= 0) rfWr(ep, reg, v or (cur and mask.inv()))
    }

    /** __mt76x02u_mcu_send_msg: sequence 0 asks for no answer. The answer, or null. */
    private fun mcuX0(ep: UsbEndpoint, cmd: Int, payload: ByteArray, wait: Boolean): ByteArray? {
        var s = 0
        if (wait) { seq = (seq + 1) and 0xf; if (seq == 0) seq = 1; s = seq }
        val rounded = (payload.size + 3) and 3.inv()
        val buf = ByteArray(4 + rounded + 4)
        putLe32(buf, 0, (rounded and 0xffff) or (2 shl 27) or (s shl 16) or (cmd shl 20) or (1 shl 30))
        System.arraycopy(payload, 0, buf, 4, payload.size)
        if (conn.bulkTransfer(ep, buf, buf.size, 500) < 0) ctlErrors++
        if (!wait) return null
        mcuAsked++
        val rep = epResp ?: run { sleep(10); return null }
        val rb = ByteArray(1024)
        repeat(5) {
            val n = conn.bulkTransfer(rep, rb, rb.size, 300)
            if (n >= 4 && (le32(rb, 0) ushr 16) and 0xf == s) { mcuAnswered++; return rb }
        }
        if (mcuLogged < 6) { mcuLogged++; UsbWifi.log("MT7610U: MCU cmd $cmd - no answer") }
        return null
    }

    // ---- register access ----

    /** Standard CLEAR_FEATURE(ENDPOINT_HALT) on [ep]. */
    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
    }
    private fun vendor(req: Int, reqType: Int, value: Int, data: ByteArray?) {
        if (conn.controlTransfer(reqType, req, value, 0, data, data?.size ?: 0, 1000) < 0) ctlErrors++
    }
    private fun regWr(addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        if (conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) < 0) ctlErrors++
    }
    private fun regRd(addr: Int): Int {
        val b = ByteArray(4)
        if (conn.controlTransfer(IN_VENDOR, MULTI_READ, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) != 4) { ctlErrors++; return 0 }
        return le32(b, 0)
    }
    private fun regSet(addr: Int, bits: Int) = regWr(addr, regRd(addr) or bits)
    private fun regClear(addr: Int, bits: Int) = regWr(addr, regRd(addr) and bits.inv())
    private fun regRmw(addr: Int, mask: Int, v: Int) = regWr(addr, (regRd(addr) and mask.inv()) or v)
    private fun cfgWr(addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        if (conn.controlTransfer(OUT_VENDOR, WRITE_CFG, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) < 0) ctlErrors++
    }
    private fun cfgRd(addr: Int): Int {
        val b = ByteArray(4)
        if (conn.controlTransfer(IN_VENDOR, READ_CFG, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) != 4) { ctlErrors++; return 0 }
        return le32(b, 0)
    }
    private fun cfgSet(addr: Int, bits: Int) = cfgWr(addr, cfgRd(addr) or bits)
    private fun cfgClear(addr: Int, bits: Int) = cfgWr(addr, cfgRd(addr) and bits.inv())
    private fun singleWr(req: Int, offset: Int, v: Int) {
        conn.controlTransfer(OUT_VENDOR, req, v and 0xffff, offset, null, 0, 1000)
        conn.controlTransfer(OUT_VENDOR, req, (v ushr 16) and 0xffff, offset + 2, null, 0, 1000)
    }
    private fun waitWpdma() = poll(1000, 2) { regRd(WPDMA_GLO_CFG) and (WPDMA_TX_DMA_BUSY or WPDMA_RX_DMA_BUSY) == 0 }

    private inline fun poll(ms: Int, stepMs: Int, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        do { if (ok()) return true; sleep(stepMs) } while (System.currentTimeMillis() < end)
        return false
    }

    private fun leWords(vararg w: Int): ByteArray { val b = ByteArray(w.size * 4); for (i in w.indices) putLe32(b, i * 4, w[i]); return b }
    private fun putLe32(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte() }
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun asciiz(b: ByteArray, off: Int, max: Int) = String(b, off, minOf(max, b.size - off), Charsets.US_ASCII).trim { it <= ' ' }
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}

    private val MAC_INITVALS = arrayOf(
        intArrayOf(0x0400, 0x00080c00), intArrayOf(0x0404, 0x1efebcff), intArrayOf(0x0800, 0x00000001),
        intArrayOf(0x1004, 0x00000000), intArrayOf(0x1018, 0x003e3f00), intArrayOf(0x1030, 0xaaa99887.toInt()),
        intArrayOf(0x1034, 0x000000aa), intArrayOf(0x1100, 0x33a40d0a), intArrayOf(0x1104, 0x00000209),
        intArrayOf(0x1118, 0x00422010), intArrayOf(0x1204, 0x00000000), intArrayOf(0x1238, 0x001700c8),
        intArrayOf(0x1330, 0x00101001), intArrayOf(0x1334, 0x00010000), intArrayOf(0x1338, 0x00000000),
        intArrayOf(0x1340, 0x0400583f), intArrayOf(0x1344, 0x00ffff20), intArrayOf(0x1348, 0x000a2290),
        intArrayOf(0x134c, 0x47f01f0f), intArrayOf(0x1380, 0x002c00dc), intArrayOf(0x13e0, 0xe3f42004.toInt()),
        intArrayOf(0x13e4, 0xe3f42084.toInt()), intArrayOf(0x13e8, 0xe3f42104.toInt()), intArrayOf(0x13ec, 0x00060fff),
        intArrayOf(0x1400, RX_FILTER_MONITOR), intArrayOf(0x1408, 0x0000017f), intArrayOf(0x140c, 0x00004003),
        intArrayOf(0x150c, 0x00000003), intArrayOf(0x1608, 0x00000002), intArrayOf(0x0a44, 0x00000000),
        intArrayOf(0x0260, 0x00000000), intArrayOf(0x0250, 0x00000000), intArrayOf(0x120c, 0x00000000),
        intArrayOf(0x1264, 0x00000000), intArrayOf(0x13c0, 0x00000000), intArrayOf(0x13c8, 0x00000000),
        intArrayOf(0x1314, 0x3a3a3a3a), intArrayOf(0x1318, 0x3a3a3a3a), intArrayOf(0x131c, 0x3a3a3a3a),
        intArrayOf(0x1320, 0x3a3a3a3a), intArrayOf(0x1324, 0x3a3a3a3a), intArrayOf(0x13d4, 0x3a3a3a3a),
        intArrayOf(0x13d8, 0x0000003a), intArrayOf(0x13dc, 0x0000003a), intArrayOf(0x0024, 0x0000d000),
        intArrayOf(0x0a38, 0x0000000a), intArrayOf(0x0824, 0x60401c18), intArrayOf(0x0210, 0x94ff0000.toInt()),
        intArrayOf(0x1478, 0x00000004), intArrayOf(0x1384, 0x00001818), intArrayOf(0x1358, 0xedcba980.toInt()),
        intArrayOf(0x1648, 0x00830083), intArrayOf(0x1410, 0x000001ff), intArrayOf(0x1350, 0x00001020)
    )
}
