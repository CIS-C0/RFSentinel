package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Ralink RT3070 USB WiFi adapters (e.g. ALFA AWUS036NH /
 * AWUS036NEH), 2.4 GHz 802.11b/g/n, driven from userspace over Android's USB host API -
 * no root, no kernel driver. Loads Ralink's firmware (assets/usbwifi/rt2870.bin, from
 * linux-firmware; licence in LICENCE.ralink-firmware.txt), hops the 2.4 GHz channels and
 * reports the access points and client devices it hears. It never transmits.
 *
 * Ported from Wardrive Go by RocketGod (https://github.com/RocketGod-git/wardrive-go,
 * GPL-3.0), which follows the Linux rt2800usb / rt2800lib driver (rt2x00 project,
 * GPL-2.0-or-later). Checked against the kernel and completed here: eFuse addressing,
 * EEPROM reads in 64-byte steps, the DMA / frame-length setup, the receive gain
 * (BBP 62-66), external-LNA boards, the EEPROM's BBP overrides and the MCU current
 * request. Its handshake / PMKID capture was left out.
 */

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

object Rt3070Monitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val HOP = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    private const val DWELL_MS = 350L
    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L
    private const val RX_SIZE = 4096

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
    private const val DEVICE_MODE = 0x01; private const val MULTI_WRITE = 0x06; private const val MULTI_READ = 0x07
    private const val EEPROM_READ = 0x09
    private const val USB_MODE_RESET = 1; private const val USB_MODE_FIRMWARE = 8; private const val USB_MODE_AUTORUN = 17
    private const val FIRMWARE_BASE = 0x3000; private const val FW_LEN = 4096; private const val CSR_CACHE = 64
    private const val MCU_CURRENT = 0x36; private const val MCU_BOOT_SIGNAL = 0x72
    private const val REV_RT3070F = 0x0201

    private const val OPT_14_CSR = 0x0114; private const val WPDMA_GLO_CFG = 0x0208; private const val USB_DMA_CFG = 0x02a0
    private const val US_CYC_CNT = 0x02a4; private const val PBF_SYS_CTRL = 0x0400; private const val HOST_CMD_CSR = 0x0404
    private const val PBF_CFG = 0x0408; private const val PBF_MAX_PCNT = 0x040c; private const val RF_CSR_CFG = 0x0500
    private const val EFUSE_CTRL = 0x0580; private const val LDO_CFG0 = 0x05d4
    private const val MAC_CSR0 = 0x1000; private const val MAC_SYS_CTRL = 0x1004; private const val MAX_LEN_CFG = 0x1018
    private const val BBP_CSR_CFG = 0x101c; private const val LED_CFG = 0x102c
    private const val XIFS_TIME_CFG = 0x1100; private const val BKOFF_SLOT_CFG = 0x1104; private const val CH_TIME_CFG = 0x110c
    private const val BCN_TIME_CFG = 0x1114; private const val CH_IDLE_STA = 0x1130; private const val CH_BUSY_STA = 0x1134
    private const val CH_BUSY_STA_SEC = 0x1138; private const val MAC_STATUS_CFG = 0x1200; private const val PWR_PIN_CFG = 0x1204
    private const val AUTOWAKEUP_CFG = 0x1208; private const val TX_PIN_CFG = 0x1328; private const val TX_BAND_CFG = 0x132c
    private const val TX_SW_CFG0 = 0x1330; private const val TX_SW_CFG1 = 0x1334; private const val TX_SW_CFG2 = 0x1338
    private const val TXOP_CTRL_CFG = 0x1340; private const val TX_RTS_CFG = 0x1344; private const val TX_TIMEOUT_CFG = 0x1348
    private const val TX_RTY_CFG = 0x134c; private const val TX_LINK_CFG = 0x1350
    private const val HT_FBK_CFG0 = 0x1354; private const val HT_FBK_CFG1 = 0x1358; private const val LG_FBK_CFG0 = 0x135c
    private const val LG_FBK_CFG1 = 0x1360; private const val CCK_PROT_CFG = 0x1364; private const val OFDM_PROT_CFG = 0x1368
    private const val MM20_PROT_CFG = 0x136c; private const val MM40_PROT_CFG = 0x1370; private const val GF20_PROT_CFG = 0x1374
    private const val GF40_PROT_CFG = 0x1378; private const val EXP_ACK_TIME = 0x1380
    private const val RX_FILTR_CFG = 0x1400; private const val AUTO_RSP_CFG = 0x1404
    private const val LEGACY_BASIC_RATE = 0x1408; private const val HT_BASIC_RATE = 0x140c; private const val TXOP_HLDR_ET = 0x1608
    private const val H2M_MAILBOX_CSR = 0x7010; private const val H2M_MAILBOX_CID = 0x7014; private const val H2M_MAILBOX_STATUS = 0x701c
    private const val H2M_INT_SRC = 0x7024; private const val H2M_BBP_AGENT = 0x7028

    /**
     * Keep everything except frames with a bad checksum, PHY or version errors and
     * control frames (ACK, RTS, CTS, block ack...), which say nothing about who is there.
     */
    private const val RX_FILTER_MONITOR = 0x0001FF13

    // EEPROM words
    private const val EE_MAC = 0x02; private const val EE_NIC_CONF0 = 0x1a; private const val EE_NIC_CONF1 = 0x1b
    private const val EE_FREQ = 0x1d; private const val EE_LNA = 0x22; private const val EE_RSSI_BG = 0x23; private const val EE_BBP_START = 0x78

    /** rf_vals_3x: synthesizer N and K per 2.4 GHz channel (R is 2 on all of them). */
    private val RF_N = intArrayOf(241, 241, 242, 242, 243, 243, 244, 244, 245, 245, 246, 246, 247, 248)
    private val RF_K = intArrayOf(2, 7, 2, 7, 2, 7, 2, 7, 2, 7, 2, 7, 2, 4)

    private lateinit var conn: UsbDeviceConnection
    private var revLtF = false
    private val ee = ByteArray(512)
    private var freqOffset = 0
    private var lnaGain = 0
    private var rssiOffset = 0
    private var externalLna = false
    private var calBw20 = 0x16

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, chipName: String, onStatus: (String) -> Unit,
            onFrame: (List<MonitorSighting>) -> Unit) {
        if (running) return
        running = true; stop = false; frames.clear()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(device) ?: run { onStatus("$chipName: couldn't reopen adapter"); running = false; return }
        conn = c; ctlErrors = 0
        try {
            val intf = device.getInterface(0)
            val claimed = c.claimInterface(intf, true)
            val rx = BulkRx.inEndpoint(intf, 0x81)
                ?: run { UsbWifi.log("RT3070: no bulk-IN endpoint"); onStatus("$chipName: no RX endpoint"); return }
            UsbWifi.log("=== RT3070 bring-up (rt2800usb) ===")
            if (!claimed) UsbWifi.log("RT3070: couldn't claim the USB interface (continuing)")
            val t0 = System.currentTimeMillis()
            if (!waitCsrReady()) { UsbWifi.log("RT3070: MAC_CSR0 never ready"); onStatus("$chipName: not responding (replug)"); return }
            val csr0 = regRd(MAC_CSR0)
            val rt = csr0 ushr 16; val rev = csr0 and 0xffff
            UsbWifi.log("RT3070: chip RT%04x rev 0x%04x, RX EP=0x%02x".format(rt, rev, rx.address))
            if (rt != 0x3070) { onStatus("$chipName: this Ralink chip (RT%04x) isn't supported yet".format(rt)); return }
            revLtF = rev < REV_RT3070F

            onStatus("$chipName · reading calibration…")
            readEeprom()

            onStatus("$chipName · loading firmware…")
            if (!loadFirmware(ctx)) { onStatus("$chipName: firmware didn't start (replug)"); return }

            onStatus("$chipName · starting radio…")
            if (!enableRadio()) { onStatus("$chipName: radio didn't start (replug)"); return }
            regWr(RX_FILTR_CFG, RX_FILTER_MONITOR)
            tune(HOP[0])
            UsbWifi.log(("RT3070: started in %d ms - SYS_CTRL 0x%08x WPDMA 0x%08x USB_DMA 0x%08x RX_FILTR 0x%08x MAC_STATUS 0x%08x PBF 0x%08x, " +
                "BBP 0:%02x 3:%02x 4:%02x 62:%02x 66:%02x 75:%02x 82:%02x, " +
                "RF 1:%02x 2:%02x 3:%02x 6:%02x 17:%02x 23:%02x 24:%02x 27:%02x 31:%02x, USB errors %d - monitor live ch %d").format(
                System.currentTimeMillis() - t0, regRd(MAC_SYS_CTRL), regRd(WPDMA_GLO_CFG), regRd(USB_DMA_CFG), regRd(RX_FILTR_CFG),
                regRd(MAC_STATUS_CFG), regRd(PBF_SYS_CTRL),
                bbpRd(0), bbpRd(3), bbpRd(4), bbpRd(62), bbpRd(66), bbpRd(75), bbpRd(82),
                rfRd(1), rfRd(2), rfRd(3), rfRd(6), rfRd(17), rfRd(23), rfRd(24), rfRd(27), rfRd(31), ctlErrors, HOP[0]))
            onStatus("$chipName · live · 2.4 GHz monitor")
            rxLoop(chipName, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("RT3070: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    // ---- EEPROM / eFuse (rt2800usb_read_eeprom, rt2800_validate_eeprom) ----

    private fun readEeprom() {
        ee.fill(0xff.toByte())
        val efuse = regRd(EFUSE_CTRL) and 0x80000000.toInt() != 0
        if (efuse) {
            var i = 0
            while (i < ee.size / 2) { // word address, 8 words (16 bytes) per read
                var reg = regRd(EFUSE_CTRL)
                reg = setf(reg, 0x03fe0000, i) // ADDRESS_IN
                reg = setf(reg, 0x000000c0, 0) // MODE: read
                reg = reg or 0x40000000        // KICK
                regWr(EFUSE_CTRL, reg)
                val end = System.currentTimeMillis() + 50
                while (regRd(EFUSE_CTRL) and 0x40000000 != 0 && System.currentTimeMillis() < end) sleep(1)
                // The data comes out from the end to the start.
                putLe32(ee, i * 2, regRd(0x059c)); putLe32(ee, i * 2 + 4, regRd(0x0598))
                putLe32(ee, i * 2 + 8, regRd(0x0594)); putLe32(ee, i * 2 + 12, regRd(0x0590))
                i += 8
            }
        } else {
            var off = 0; var failed = 0
            while (off < ee.size) {
                val chunk = ByteArray(CSR_CACHE)
                if (conn.controlTransfer(IN_VENDOR, EEPROM_READ, 0, off, chunk, CSR_CACHE, 1000) == CSR_CACHE) chunk.copyInto(ee, off)
                else failed++
                off += CSR_CACHE
            }
            if (failed > 0) UsbWifi.log("RT3070: $failed of ${ee.size / CSR_CACHE} EEPROM reads failed - using defaults for those")
        }
        val freq = ew(EE_FREQ) and 0xff
        freqOffset = if (freq == 0xff) 0 else freq
        val lna = ew(EE_LNA) and 0xff
        lnaGain = if (lna == 0xff) 0 else lna
        val ro = ew(EE_RSSI_BG) and 0xff
        rssiOffset = if (ro > 10) 0 else ro
        val nic1 = ew(EE_NIC_CONF1)
        externalLna = nic1 != 0xffff && nic1 and 0x0004 != 0
        val nic0 = ew(EE_NIC_CONF0)
        // Only the maker part of the adapter's own address: enough to see the EEPROM read right.
        val maker = (0 until 3).joinToString(":") { "%02x".format(ee[EE_MAC * 2 + it].toInt() and 0xff) }
        UsbWifi.log("RT3070: EEPROM (%s) chip id 0x%04x version 0x%04x, MAC %s:xx:xx:xx, NIC_CONF0 0x%04x (RF 0x%x, RX path %d), NIC_CONF1 0x%04x, freq_off %d lna_gain %d rssi_off %d ext_lna %b".format(
            if (efuse) "eFuse" else "93C46", ew(0), ew(1), maker, nic0, (nic0 ushr 8) and 0xf, nic0 and 0xf, nic1,
            freqOffset, lnaGain, rssiOffset, externalLna))
    }

    private fun ew(word: Int) = (ee[word * 2].toInt() and 0xff) or ((ee[word * 2 + 1].toInt() and 0xff) shl 8)

    // ---- firmware (rt2800_load_firmware, rt2800usb_write_firmware) ----

    private fun loadFirmware(ctx: Context): Boolean {
        regWr(AUTOWAKEUP_CFG, 0)
        if (!waitCsrReady()) return false
        disableWpdma()
        val autorun = run {
            val b = ByteArray(4)
            conn.controlTransfer(IN_VENDOR, DEVICE_MODE, USB_MODE_AUTORUN, 0, b, 4, 1000) == 4 && (le32(b, 0) and 0x3) == 2
        }
        if (autorun) UsbWifi.log("RT3070: firmware already running (autorun)")
        else {
            val fw = ctx.assets.open("usbwifi/rt2870.bin").use { it.readBytes() }
            if (fw.size < FW_LEN) { UsbWifi.log("RT3070: rt2870.bin too short (${fw.size} B)"); return false }
            var off = 0 // the RT3070 runs the first 4 KB image
            while (off < FW_LEN) {
                val len = minOf(CSR_CACHE, FW_LEN - off)
                if (conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, 0, FIRMWARE_BASE + off, fw.copyOfRange(off, off + len), len, 1000) < 0) {
                    UsbWifi.log("RT3070: firmware write failed at $off"); return false
                }
                off += len
            }
            UsbWifi.log("RT3070: firmware written ($off B)")
        }
        regWr(H2M_MAILBOX_CID, -1); regWr(H2M_MAILBOX_STATUS, -1)
        conn.controlTransfer(OUT_VENDOR, DEVICE_MODE, USB_MODE_FIRMWARE, 0, null, 0, 1000)
        sleep(10)
        regWr(H2M_MAILBOX_CSR, 0)
        if (!poll(1000) { regRd(PBF_SYS_CTRL) and 0x80 != 0 }) { UsbWifi.log("RT3070: PBF not ready - firmware didn't start"); return false }
        disableWpdma()
        regWr(H2M_BBP_AGENT, 0); regWr(H2M_MAILBOX_CSR, 0); regWr(H2M_INT_SRC, 0)
        mcuRequest(MCU_BOOT_SIGNAL)
        sleep(1)
        UsbWifi.log("RT3070: firmware running")
        return true
    }

    private fun mcuRequest(command: Int) {
        poll(100) { regRd(H2M_MAILBOX_CSR) and 0xff000000.toInt() == 0 } // mailbox free
        regWr(H2M_MAILBOX_CSR, 0x01000000) // owner = host, token 0, no arguments
        regWr(HOST_CMD_CSR, command and 0xff)
    }

    // ---- radio (rt2800usb_enable_radio, rt2800_enable_radio) ----

    private fun enableRadio(): Boolean {
        if (!waitWpdma()) UsbWifi.log("RT3070: WPDMA busy (continuing)")
        var dma = regRd(USB_DMA_CFG)
        dma = setf(dma, 0x00010000, 0)   // PHY_CLEAR
        dma = setf(dma, 0x00200000, 0)   // RX_BULK_AGG_EN: one frame per transfer
        dma = setf(dma, 0x000000ff, 128) // RX_BULK_AGG_TIMEOUT
        dma = setf(dma, 0x0000ff00, 0)   // RX_BULK_AGG_LIMIT
        dma = dma or 0x00400000 or 0x00800000 // RX / TX bulk enable
        regWr(USB_DMA_CFG, dma)

        waitWpdma()
        initRegisters()
        if (!poll(1000) { regRd(MAC_STATUS_CFG) and 0x3 == 0 }) UsbWifi.log("RT3070: BBP/RF busy (continuing)")
        regWr(H2M_BBP_AGENT, 0); regWr(H2M_MAILBOX_CSR, 0); regWr(H2M_INT_SRC, 0)
        mcuRequest(MCU_BOOT_SIGNAL)
        sleep(1)
        // rt2800_wait_bbp_ready
        regWr(H2M_BBP_AGENT, 0); regWr(H2M_MAILBOX_CSR, 0)
        sleep(1)
        if (!poll(500) { bbpRd(0).let { it != 0 && it != 0xff } }) { UsbWifi.log("RT3070: BBP not responding"); return false }

        initBbp()
        initRfcsr()
        sleep(1)
        mcuRequest(MCU_CURRENT)

        regWr(MAC_SYS_CTRL, (regRd(MAC_SYS_CTRL) or 0x04) and 0x08.inv())
        sleep(1)
        var wp = regRd(WPDMA_GLO_CFG)
        wp = wp or 0x01 or 0x04 or 0x40 // TX / RX DMA, TX write-back done
        wp = setf(wp, 0x30, 2)          // DMA burst size
        regWr(WPDMA_GLO_CFG, wp)
        regWr(MAC_SYS_CTRL, regRd(MAC_SYS_CTRL) or 0x04 or 0x08)
        return true
    }

    private fun initRegisters() {
        disableWpdma()
        // rt2800usb_init_registers: reset the MAC and BBP
        waitCsrReady()
        regWr(PBF_SYS_CTRL, regRd(PBF_SYS_CTRL) and 0x00002000.inv())
        regWr(MAC_SYS_CTRL, 0x3)
        conn.controlTransfer(OUT_VENDOR, DEVICE_MODE, USB_MODE_RESET, 0, null, 0, 1000)
        regWr(MAC_SYS_CTRL, 0)

        regWr(LEGACY_BASIC_RATE, 0x0000013f); regWr(HT_BASIC_RATE, 0x00008003)
        regWr(MAC_SYS_CTRL, 0)
        regWr(BCN_TIME_CFG, (regRd(BCN_TIME_CFG) and 0xf01fffff.toInt().inv()) or 1600) // beacon interval only, no TSF / beacons
        regWr(RX_FILTR_CFG, RX_FILTER_MONITOR)
        run { var r = regRd(BKOFF_SLOT_CFG); r = setf(r, 0xff, 9); r = setf(r, 0xff00, 2); regWr(BKOFF_SLOT_CFG, r) }
        regWr(TX_SW_CFG0, 0x00000400)
        if (revLtF) { regWr(TX_SW_CFG1, 0x00000000); regWr(TX_SW_CFG2, 0x0000002c) }
        else { regWr(TX_SW_CFG1, 0x00080606); regWr(TX_SW_CFG2, 0x00000000) }
        regWr(TX_LINK_CFG, 0x00001020)
        regWr(TX_TIMEOUT_CFG, 0x000a2090)
        run {
            var r = regRd(MAX_LEN_CFG)
            r = setf(r, 0x00000fff, 3840) // max MPDU
            r = setf(r, 0x00003000, 3)    // max PSDU (USB)
            r = setf(r, 0x0000c000, 10)   // min PSDU
            r = setf(r, 0x000f0000, 10)   // min MPDU
            regWr(MAX_LEN_CFG, r)
        }
        regWr(LED_CFG, 0x7f031e46)
        regWr(PBF_MAX_PCNT, 0x1f3fbf9f)
        regWr(TX_RTY_CFG, 0x47d01f0f)
        regWr(AUTO_RSP_CFG, 0x00000012) // auto-responder off: never answers with an ACK or CTS
        regWr(CCK_PROT_CFG, 0x05740003); regWr(OFDM_PROT_CFG, 0x05740003)
        regWr(MM20_PROT_CFG, 0x03f44084); regWr(MM40_PROT_CFG, 0x03f54084)
        regWr(GF20_PROT_CFG, 0x03f44084); regWr(GF40_PROT_CFG, 0x03f54084)
        regWr(PBF_CFG, 0x00f40006)
        regWr(WPDMA_GLO_CFG, 0x30) // DMA off, burst size 3, little endian, no header scatter
        regWr(TXOP_CTRL_CFG, 0x0000583f)
        regWr(TXOP_HLDR_ET, 0x00000002)
        regWr(TX_RTS_CFG, 0x00092b20)
        regWr(EXP_ACK_TIME, 0x002400ca)
        regWr(XIFS_TIME_CFG, 0x33a41010)
        regWr(PWR_PIN_CFG, 0x00000003)
        regWr(US_CYC_CNT, setf(regRd(US_CYC_CNT), 0xff, 30))
        regWr(HT_FBK_CFG0, 0x65432100); regWr(HT_FBK_CFG1, 0xedcba980.toInt())
        regWr(LG_FBK_CFG0, 0xedcba988.toInt()); regWr(LG_FBK_CFG1, 0x00002100)
        regWr(CH_TIME_CFG, 0x0000001f) // channel statistics timer
    }

    private fun initBbp() {
        // rt2800_init_bbp_30xx
        val tab = intArrayOf(65, 0x2c, 66, 0x38, 69, 0x12, 73, 0x10, 70, 0x0a, 79, 0x13, 80, 0x05, 81, 0x33,
            82, 0x62, 83, 0x6a, 84, 0x99, 86, 0x00, 91, 0x04, 92, 0x00, 103, if (revLtF) 0x00 else 0xc0, 105, 0x05, 106, 0x35)
        for (i in tab.indices step 2) bbpWr(tab[i], tab[i + 1])
        // Board-specific overrides stored in the EEPROM.
        for (i in 0 until 16) {
            val w = ew(EE_BBP_START + i)
            if (w != 0xffff && w != 0x0000) bbpWr(w ushr 8, w and 0xff)
        }
    }

    private fun initRfcsr() {
        // rt2800_init_rfcsr_30xx
        rfToggle(30)
        val tab = intArrayOf(4, 0x40, 5, 0x03, 6, 0x02, 7, 0x60, 9, 0x0f, 10, 0x41, 11, 0x21, 12, 0x7b,
            14, 0x90, 15, 0x58, 16, 0xb3, 17, 0x92, 18, 0x2c, 19, 0x02, 20, 0xba, 21, 0xdb,
            24, 0x16, 25, 0x03, 29, 0x1f)
        for (i in tab.indices step 2) rfWr(tab[i], tab[i + 1])
        if (revLtF) {
            var r = regRd(LDO_CFG0); r = setf(r, 0x03000000, 1); r = setf(r, 0x1c000000, 3); regWr(LDO_CFG0, r)
        }
        calBw20 = rxFilterCalibration(0x16)
        if (revLtF) rfWr(27, 0x03)
        regWr(OPT_14_CSR, regRd(OPT_14_CSR) or 0x1) // LED open drain
        // rt2800_normal_mode_setup_3xxx
        var rf = rfRd(17)
        rf = setf(rf, 0x08, 0) // TX_LO1_EN
        if (!externalLna) rf = setf(rf, 0x20, 1)
        rfWr(17, rf)
        rf = rfRd(27)
        rf = setf(rf, 0x03, if (revLtF) 3 else 0); rf = setf(rf, 0x04, 0); rf = setf(rf, 0x30, 0); rf = setf(rf, 0x40, 0)
        rfWr(27, rf)
        UsbWifi.log("RT3070: RX filter calibration 0x%02x".format(calBw20))
    }

    /** rt2800_init_rx_filter (20 MHz): tune the baseband filter with test tones; returns RFCSR24. */
    private fun rxFilterCalibration(target: Int): Int {
        var rf24 = 0x07
        rfWr(24, rf24)
        bbpWr(4, setf(bbpRd(4), 0x18, 0))
        rfWr(31, setf(rfRd(31), 0x20, 0))
        rfWr(22, setf(rfRd(22), 0x01, 1)) // baseband loopback
        bbpWr(24, 0)
        var passband = 0
        for (i in 0 until 100) { bbpWr(25, 0x90); sleep(1); passband = bbpRd(55); if (passband != 0) break }
        bbpWr(24, 0x06)
        var overtuned = 0
        for (i in 0 until 100) {
            bbpWr(25, 0x90); sleep(1)
            val stopband = bbpRd(55)
            if (passband - stopband <= target) { rf24++; if (passband - stopband == target) overtuned++ } else break
            rfWr(24, rf24)
        }
        if (overtuned != 0) rf24--
        rfWr(24, rf24)
        bbpWr(24, 0)
        rfWr(22, setf(rfRd(22), 0x01, 0))
        bbpWr(4, setf(bbpRd(4), 0x18, 0))
        return rf24
    }

    private fun rfToggle(reg: Int) {
        val v = rfRd(reg); rfWr(reg, v or 0x80); sleep(1); rfWr(reg, v and 0x80.inv())
    }

    /** rt2800_config_channel_rf3xxx + the channel's BBP / pin setup, 20 MHz, one antenna. */
    private fun tune(ch: Int) {
        val i = ch - 1
        rfWr(2, RF_N[i])
        rfWr(3, setf(rfRd(3), 0x0f, RF_K[i]))
        rfWr(6, setf(rfRd(6), 0x03, 2))
        rfWr(12, setf(rfRd(12), 0x1f, 0)) // TX power: lowest (never transmits)
        rfWr(13, setf(rfRd(13), 0x1f, 0))
        var rf = rfRd(1)
        rf = setf(rf, 0x04, 0); rf = setf(rf, 0x10, 1); rf = setf(rf, 0x40, 1) // RX0 on, RX1 / RX2 off
        rf = setf(rf, 0x08, 0); rf = setf(rf, 0x20, 1); rf = setf(rf, 0x80, 1)
        rfWr(1, rf)
        rfWr(23, setf(rfRd(23), 0x7f, freqOffset))
        rfWr(24, setf(rfRd(24), 0x7f, calBw20))
        rfWr(31, setf(rfRd(31), 0x7f, calBw20))
        rfWr(7, setf(rfRd(7), 0x01, 1)) // RF tuning
        rfToggle(30)                     // RF calibration

        val agc = 0x37 - lnaGain
        bbpWr(62, agc); bbpWr(63, agc); bbpWr(64, agc); bbpWr(86, 0)
        if (externalLna) { bbpWr(82, 0x62); bbpWr(75, 0x46) } else { bbpWr(82, 0x84); bbpWr(75, 0x50) }
        regWr(TX_BAND_CFG, setf(setf(setf(regRd(TX_BAND_CFG), 0x01, 0), 0x02, 0), 0x04, 1)) // 2.4 GHz
        regWr(TX_PIN_CFG, 0x02 or 0x100 or 0x200 or 0x10000 or 0x40000) // G-band PA, LNAs, RF / TR switch
        bbpWr(4, setf(bbpRd(4), 0x18, 0)) // 20 MHz
        bbpWr(3, setf(bbpRd(3), 0x20, 0))
        sleep(1)
        regRd(CH_IDLE_STA); regRd(CH_BUSY_STA); regRd(CH_BUSY_STA_SEC) // clear on read
        bbpWr(66, 0x1c + 2 * lnaGain) // default receive gain (rt2800_get_default_vgc)
    }

    // ---- receive ----

    private class RxState { var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0 }

    private fun rxLoop(chipName: String, ep: android.hardware.usb.UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0
        val stats = BulkRx.Stats("RT3070")
        val total = BulkRx.run("RT3070", conn, ep, 24, RX_SIZE, { stop }, { b, n ->
            stats.transfer(n)
            // RXINFO + RXWI, and the RXD that follows the frame
            val rxd = if (n >= 4) 4 + (le32(b, 0) and 0xffff) else -1
            stats.sample("$n bytes, RXD %s, RXINFO+RXWI".format(
                if (rxd in 0..n - 4) "%08x".format(le32(b, rxd)) else "past the end"), b, 0, 20)
            forEachFrame(b, n, rssiOffset, lnaGain, onBad = { crc -> if (crc) stats.badChecksum++ else stats.malformed++ }) { s, e, rssi ->
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
        UsbWifi.log("RT3070: RX loop stopped ($total B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    /**
     * Frames in one bulk transfer: | RXINFO (4) | RXWI (16) | 802.11 frame, padded | RXD (4) |,
     * RXINFO giving the length up to RXD. Frames with a bad checksum are skipped; [onFrame]
     * gets the 802.11 frame's [start, end) and its signal (null when the radio gave none).
     * [onBad] hears about the skipped ones: true for a bad checksum, false for a malformed one.
     */
    internal fun forEachFrame(b: ByteArray, n: Int, rssiOffset: Int, lnaGain: Int, onBad: (Boolean) -> Unit = {},
                              onFrame: (Int, Int, Int?) -> Unit) {
        var off = 0
        while (off + 4 + 16 + 4 <= n) {
            val pktLen = le32(b, off) and 0xffff
            if (pktLen < 16 + 10 || off + 4 + pktLen + 4 > n) { if (off == 0) onBad(false); return } // trailing USB padding is fine
            val rxwi = off + 4
            val mpdu = (le32(b, rxwi) ushr 16) and 0xfff
            val rxd = le32(b, off + 4 + pktLen)
            val start = rxwi + 16
            when {
                rxd and 0x100 != 0 -> onBad(true)
                mpdu < 24 || start + mpdu > off + 4 + pktLen -> onBad(false)
                else -> {
                    val raw = b[rxwi + 8].toInt() // RSSI0; 0 = no reading
                    onFrame(start, start + mpdu, if (raw > 0) -12 - rssiOffset - lnaGain - raw else null)
                }
            }
            off += 4 + pktLen + 4
        }
        if (off == 0) onBad(false)
    }

    // ---- register access ----

    /** Failed register reads / writes (an unplugged or stalled adapter), for the log. */
    @Volatile private var ctlErrors = 0

    private fun regWr(addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        if (conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, 0, addr, b, 4, 1000) < 0) ctlErrors++
    }
    private fun regRd(addr: Int): Int {
        val b = ByteArray(4)
        if (conn.controlTransfer(IN_VENDOR, MULTI_READ, 0, addr, b, 4, 1000) != 4) { ctlErrors++; return 0 }
        return le32(b, 0)
    }

    private fun bbpIdle() = poll(50) { regRd(BBP_CSR_CFG) and 0x20000 == 0 }
    private fun bbpWr(reg: Int, v: Int) {
        bbpIdle()
        regWr(BBP_CSR_CFG, (v and 0xff) or ((reg and 0xff) shl 8) or 0x20000 or 0x80000)
    }
    private fun bbpRd(reg: Int): Int {
        bbpIdle()
        regWr(BBP_CSR_CFG, ((reg and 0xff) shl 8) or 0x10000 or 0x20000 or 0x80000)
        bbpIdle()
        return regRd(BBP_CSR_CFG) and 0xff
    }

    private fun rfIdle() = poll(50) { regRd(RF_CSR_CFG) and 0x20000 == 0 }
    private fun rfWr(reg: Int, v: Int) {
        rfIdle()
        regWr(RF_CSR_CFG, (v and 0xff) or ((reg and 0x3f) shl 8) or 0x10000 or 0x20000)
    }
    private fun rfRd(reg: Int): Int {
        rfIdle()
        regWr(RF_CSR_CFG, ((reg and 0x3f) shl 8) or 0x20000)
        rfIdle()
        return regRd(RF_CSR_CFG) and 0xff
    }

    private fun disableWpdma() {
        // TX / RX DMA off, busy bits cleared, TX write-back done set
        regWr(WPDMA_GLO_CFG, (regRd(WPDMA_GLO_CFG) and 0x0f.inv()) or 0x40)
    }
    private fun waitCsrReady() = poll(1000) { regRd(MAC_CSR0).let { it != 0 && it != -1 } }
    private fun waitWpdma() = poll(1000) { regRd(WPDMA_GLO_CFG) and 0x0a == 0 }

    private inline fun poll(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        do { if (ok()) return true; sleep(1) } while (System.currentTimeMillis() < end)
        return false
    }

    /** Sets the field [mask] of [reg] to [v] (truncated to the field, like rt2x00_set_field32). */
    private fun setf(reg: Int, mask: Int, v: Int) = (reg and mask.inv()) or ((v shl Integer.numberOfTrailingZeros(mask)) and mask)
    private fun putLe32(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte() }
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
