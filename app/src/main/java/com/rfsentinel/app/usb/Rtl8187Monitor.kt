package com.rfsentinel.app.usb

/*
 * Receive-only monitor mode for Realtek RTL8187L / RTL8187B USB WiFi adapters
 * (e.g. ALFA AWUS036H), 2.4 GHz 802.11b/g, driven from userspace over Android's USB
 * host API - no root, no kernel driver, no firmware. Hops the 2.4 GHz channels and
 * reports the access points and client devices it hears. It never transmits.
 *
 * Bring-up, radio (RTL8225 / RTL8225Z2) tuning and RX descriptor handling are ported
 * from the Linux kernel rtl8187 driver (drivers/net/wireless/realtek/rtl818x/rtl8187;
 * Michael Wu, Andrea Merello, Herton Ronaldo Krzesinski, Hin-Tak Leung, Larry Finger;
 * register values from Realtek's r8187 driver), with Kismet's Android userspace port
 * (Android PCAP Capture, Mike Kershaw / Dragorn) as the reference for driving it over USB.
 * Both GPL-2.0. Every transmit path (TX power is left at the lowest setting) was left out.
 */

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

object Rtl8187Monitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    private val frames = MonitorFrames()
    private val HOP = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    private const val DWELL_MS = 350L
    private val FRESH_MS get() = (HOP.size * DWELL_MS * 9 / 5).coerceAtLeast(3000L)
    private const val REPORT_MS = 10_000L

    /** USB IDs the kernel driver runs as RTL8187B (the rest are RTL8187L unless the chip says otherwise). */
    private val B_IDS = setOf(0x050d to 0x705e, 0x0bda to 0x8189, 0x0bda to 0x8197, 0x0bda to 0x8198,
        0x0846 to 0x4260, 0x0df6 to 0x0028, 0x0df6 to 0x0029, 0x1737 to 0x0073)

    private const val REQT_READ = 0xC0; private const val REQT_WRITE = 0x40; private const val REQ_REG = 0x05
    /** One frame per bulk transfer, at most this long (RTL8187_MAX_RX). */
    private const val MAX_RX = 0x9C4

    // struct rtl818x_csr, mapped at 0xFF00
    private const val MAR0 = 0xFF08; private const val MAR1 = 0xFF0C; private const val BRSR = 0xFF2C
    private const val RESP_RATE = 0xFF34; private const val CMD = 0xFF37; private const val INT_MASK = 0xFF3C
    private const val TX_CONF = 0xFF40; private const val RX_CONF = 0xFF44; private const val INT_TIMEOUT = 0xFF48
    private const val EEPROM_CMD = 0xFF50; private const val CONFIG1 = 0xFF52; private const val ANAPARAM = 0xFF54
    private const val MSR = 0xFF58; private const val CONFIG3 = 0xFF59; private const val TESTR = 0xFF5B
    private const val PGSELECT = 0xFF5E; private const val ANAPARAM2 = 0xFF60; private const val PHY0 = 0xFF7C
    private const val RF_OUT = 0xFF80; private const val RF_EN = 0xFF82; private const val RF_SEL = 0xFF84
    private const val RF_IN = 0xFF86; private const val RF_PARA = 0xFF88; private const val RF_TIMING = 0xFF8C
    private const val GP_ENABLE = 0xFF90; private const val GPIO0 = 0xFF91; private const val HSSI_PARA = 0xFF94
    private const val TX_AGC_CTL = 0xFF9C; private const val TX_GAIN_CCK = 0xFF9D; private const val TX_GAIN_OFDM = 0xFF9E
    private const val TX_ANTENNA = 0xFF9F; private const val WPA_CONF = 0xFFB0; private const val CW_CONF = 0xFFBC
    private const val RATE_FALLBACK = 0xFFBE; private const val ACM_CONTROL = 0xFFBF; private const val INT_MIG = 0xFFE2
    private const val TID_AC_MAP = 0xFFE8; private const val ANAPARAM3A = 0xFFEE; private const val TALLY_SEL = 0xFFFC

    private const val EEPROM_CMD_NORMAL = 0x00; private const val EEPROM_CMD_LOAD = 0x40; private const val EEPROM_CMD_CONFIG = 0xC0
    private const val CONFIG3_ANAPARAM_WRITE = 0x40
    private const val CMD_TX_ENABLE = 0x04; private const val CMD_RX_ENABLE = 0x08; private const val CMD_RESET = 0x10
    private const val TX_CONF_LOOPBACK_MAC = 1 shl 17; private const val TX_CONF_NO_ICV = 1 shl 19
    private const val TX_CONF_HWVER_MASK = 7 shl 25; private const val TX_CONF_R8187vD = 5 shl 25; private const val TX_CONF_R8187vD_B = 6 shl 25
    private const val TX_CONF_DISREQQSIZE = 1 shl 28; private const val TX_CONF_HW_SEQNUM = 1 shl 30; private const val TX_CONF_CW_MIN = 1 shl 31
    private const val RX_CONF_MONITOR = 1 shl 0; private const val RX_CONF_NICMAC = 1 shl 1; private const val RX_CONF_BROADCAST = 1 shl 3
    private const val RX_CONF_DATA = 1 shl 18; private const val RX_CONF_MGMT = 1 shl 20; private const val RX_CONF_BSSID = 1 shl 23
    private const val RX_CONF_AUTORESETPHY = 1 shl 28; private const val RX_CONF_ONLYERLPKT = 1 shl 31
    private const val RX_FIFO_NONE = 7 shl 13; private const val RX_MAX_DMA = 7 shl 10
    /** RX descriptor: the frame's checksum failed. */
    private const val RX_DESC_CRC32_ERR = 1 shl 13

    private const val ANAPARAM_ON_L = 0xa0000a59.toInt(); private const val ANAPARAM2_ON_L = 0x860c7312.toInt()
    private const val ANAPARAM_ON_B = 0x45090658; private const val ANAPARAM2_ON_B = 0x727f3f52

    private enum class Rf { RTL8225, RTL8225Z2, RTL8225Z2B }
    private const val BvB = 0; private const val BvD = 1; private const val BvE = 2

    private lateinit var conn: UsbDeviceConnection
    private var is8187b = false
    private var hwRev = BvB
    /** 0: B-cut ASIC, radio registers by bit-banging the 3-wire bus; otherwise by a USB request. */
    private var asicRev = 0
    private var rf = Rf.RTL8225

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
            UsbWifi.log("=== RTL8187 bring-up (rtl8187) ===")
            if (!claimed) UsbWifi.log("RTL8187: couldn't claim the USB interface (continuing)")
            is8187b = (device.vendorId to device.productId) in B_IDS
            val t0 = System.currentTimeMillis()

            w8(EEPROM_CMD, EEPROM_CMD_CONFIG)
            val pg = r8(PGSELECT) and 1.inv()
            w8(PGSELECT, pg or 1)
            asicRev = r8(0xFFFE) and 0x3
            w8(PGSELECT, pg)
            w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
            val chip = if (!is8187b) {
                when (r32(TX_CONF) and TX_CONF_HWVER_MASK) {
                    TX_CONF_R8187vD_B -> { is8187b = true; hwRev = BvB; "RTL8187BvB (early)" } // some 8187B report the 8187 ID
                    TX_CONF_R8187vD -> "RTL8187vD"
                    else -> "RTL8187vB"
                }
            } else when (r8(0xFFE1)) {
                1 -> { hwRev = BvD; "RTL8187BvD" }
                2 -> { hwRev = BvE; "RTL8187BvE" }
                else -> { hwRev = BvB; "RTL8187BvB" }
            }
            var detect = ""
            rf = if (is8187b) Rf.RTL8225Z2B else {
                rfWrite(0, 0x1B7)
                val reg8 = rfRead(8); val reg9 = rfRead(9)
                rfWrite(0, 0x0B7)
                detect = " (RF reg8 0x%03x, reg9 0x%03x)".format(reg8, reg9)
                if (reg8 != 0x588 || reg9 != 0x700) Rf.RTL8225 else Rf.RTL8225Z2
            }
            val rx = BulkRx.inEndpoint(intf, if (is8187b) 0x83 else 0x81)
                ?: run { UsbWifi.log("RTL8187: no bulk-IN endpoint"); onStatus("$chipName: no RX endpoint"); return }
            UsbWifi.log("RTL8187: $chip, radio $rf$detect, ASIC rev $asicRev (${if (asicRev != 0) "USB" else "bit-banged"} radio writes), " +
                "TX_CONF 0x%08x, RX EP 0x%02x".format(r32(TX_CONF), rx.address))

            onStatus("$chipName · starting radio…")
            if (!(if (is8187b) initHwB() else initHwL())) { onStatus("$chipName: reset timed out (replug)"); return }
            start()
            tune(HOP[0], txPower = true)
            UsbWifi.log("RTL8187: started in ${System.currentTimeMillis() - t0} ms - RX_CONF 0x%08x TX_CONF 0x%08x CMD 0x%02x CONFIG3 0x%02x ANAPARAM 0x%08x ANAPARAM2 0x%08x, USB errors %d - monitor live ch %d".format(
                r32(RX_CONF), r32(TX_CONF), r8(CMD), r8(CONFIG3), r32(ANAPARAM), r32(ANAPARAM2), ctlErrors, HOP[0]))
            onStatus("$chipName · live · 2.4 GHz monitor")
            rxLoop(chipName, rx, onFrame, onStatus)
        } catch (e: Exception) {
            UsbWifi.log("RTL8187: bring-up error - ${UsbWifi.describe(e)}")
            onStatus("$chipName: error · ${e.message}")
        } finally {
            runCatching { c.close() }
            running = false
        }
    }

    // ---- bring-up (rtl8187_init_hw / rtl8187b_init_hw / rtl8187_start) ----

    private fun setAnaparam() {
        w8(EEPROM_CMD, EEPROM_CMD_CONFIG)
        val reg = r8(CONFIG3) or CONFIG3_ANAPARAM_WRITE
        w8(CONFIG3, reg)
        w32(ANAPARAM, if (is8187b) ANAPARAM_ON_B else ANAPARAM_ON_L)
        w32(ANAPARAM2, if (is8187b) ANAPARAM2_ON_B else ANAPARAM2_ON_L)
        if (is8187b) w8(ANAPARAM3A, 0x00)
        w8(CONFIG3, reg and CONFIG3_ANAPARAM_WRITE.inv())
        w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
    }

    private fun cmdReset(): Boolean {
        w8(CMD, (r8(CMD) and 0x02) or CMD_RESET)
        var ok = false
        for (i in 0 until 10) { sleep(2); if (r8(CMD) and CMD_RESET == 0) { ok = true; break } }
        if (!ok) { UsbWifi.log("RTL8187: reset timeout"); return false }
        w8(EEPROM_CMD, EEPROM_CMD_LOAD) // reload registers from the EEPROM
        for (i in 0 until 10) { sleep(4); if (r8(EEPROM_CMD) and EEPROM_CMD_CONFIG == 0) return true }
        UsbWifi.log("RTL8187: EEPROM reload timeout")
        return false
    }

    private fun initHwL(): Boolean {
        setAnaparam()
        w16(INT_MASK, 0)
        sleep(200)
        w8(0xFE18, 0x10); w8(0xFE18, 0x11); w8(0xFE18, 0x00)
        sleep(200)
        if (!cmdReset()) return false
        setAnaparam()

        w16(RF_SEL, 0); w8(GPIO0, 0)
        w16(RF_SEL, 4 shl 8); w8(GPIO0, 1); w8(GP_ENABLE, 0)
        w8(EEPROM_CMD, EEPROM_CMD_CONFIG)
        w16(0xFFF4, 0xFFFF)
        w8(CONFIG1, (r8(CONFIG1) and 0x3F) or 0x80)
        w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
        w32(INT_TIMEOUT, 0); w8(WPA_CONF, 0); w8(RATE_FALLBACK, 0)
        w8(RESP_RATE, 8 shl 4); w16(BRSR, 0x01F3)

        // host_usb_init
        w16(RF_SEL, 0); w8(GPIO0, 0)
        w8(0xFE53, r8(0xFE53) or 0x80)
        w16(RF_SEL, 4 shl 8); w8(GPIO0, 0x20); w8(GP_ENABLE, 0)
        w16(RF_OUT, 0x80); w16(RF_SEL, 0x80); w16(RF_EN, 0x80)
        sleep(100)
        w32(RF_TIMING, 0x000a8008); w16(BRSR, 0xFFFF); w32(RF_PARA, 0x00100044)
        w8(EEPROM_CMD, EEPROM_CMD_CONFIG); w8(CONFIG3, 0x44); w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
        w16(RF_EN, 0x1FF7)
        sleep(100)

        rfInit()

        w16(BRSR, 0x01F3)
        val pg = r8(PGSELECT) and 1.inv()
        w8(PGSELECT, pg or 1); w16(0xFFFE, 0x10); w8(TALLY_SEL, 0x80); w8(0xFFFF, 0x60); w8(PGSELECT, pg)
        return true
    }

    private fun initHwB(): Boolean {
        setAnaparam()
        // Reset the PLL (Realtek: saves about 30 mA)
        w8(0xFF61, 0x10)
        val pll = r8(0xFF62)
        w8(0xFF62, pll and 0x20.inv()); w8(0xFF62, pll or 0x20)
        if (!cmdReset()) return false
        setAnaparam()

        w16(0xFF34, 0x0FFF) // all 12 rates
        w8(CW_CONF, r8(CW_CONF) or 0x02)
        w16(0xFFE0, 0x0FFF, 1); w8(0xFFE2, 0x00, 1) // auto rate fallback 1M-54M
        w16(0xFFD4, 0xFFFF, 1)
        w8(EEPROM_CMD, EEPROM_CMD_CONFIG)
        w8(CONFIG1, (r8(CONFIG1) and 0x3F) or 0x80)
        w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
        w8(WPA_CONF, 0)
        for (e in REG_TABLE_B) w8(e[0] or 0xFF00, e[1], e[2])
        w16(TID_AC_MAP, 0xFA50); w16(INT_MIG, 0)
        w32(0xFFF0, 0, 1); w32(0xFFF4, 0, 1); w8(0xFFF8, 0, 1)
        w32(RF_TIMING, 0x00004001)
        w16(0xFF72, 0x569A, 2) // RFSW_CTRL
        w16(RF_OUT, 0x0480); w16(RF_SEL, 0x2488); w16(RF_EN, 0x1FFF)
        sleep(100)

        rfInit()

        w8(CMD, CMD_TX_ENABLE or CMD_RX_ENABLE)
        w16(INT_MASK, 0xFFFF)
        w8(0xFE41, 0xF4); w8(0xFE40, 0x00); w8(0xFE42, 0x00); w8(0xFE42, 0x01)
        w8(0xFE40, 0x0F); w8(0xFE42, 0x00); w8(0xFE42, 0x01)
        w8(0xFFDB, r8(0xFFDB) or 0x04)
        w16(0xFF72, 0x59FA, 3); w16(0xFF74, 0x59D2, 3); w16(0xFF76, 0x59D2, 3)
        w16(0xFF78, 0x19FA, 3); w16(0xFF7A, 0x19FA, 3); w16(0xFF7C, 0x00D0, 3)
        w8(0xFF61, 0)
        w8(0xFF80, 0x0F, 1); w8(0xFF83, 0x03, 1); w8(0xFFDA, 0x10); w8(0xFF4D, 0x08, 2)
        w32(HSSI_PARA, 0x0600321B)
        w16(0xFFEC, 0x0800, 1)
        w8(ACM_CONTROL, 0)
        w8(MSR, 0x10) // ENEDCA
        return true
    }

    private fun start() {
        if (is8187b) {
            w32(RX_CONF, RX_CONF_MGMT or RX_CONF_DATA or RX_CONF_BROADCAST or RX_CONF_NICMAC or RX_CONF_BSSID or
                RX_FIFO_NONE or RX_MAX_DMA or RX_CONF_AUTORESETPHY or RX_CONF_ONLYERLPKT or RX_CONF_MONITOR)
            w8(TX_AGC_CTL, r8(TX_AGC_CTL) and 0x07.inv())
            w32(TX_CONF, TX_CONF_HW_SEQNUM or TX_CONF_DISREQQSIZE or (7 shl 8) or 7 or (7 shl 21))
            return
        }
        w16(INT_MASK, 0xFFFF)
        w32(MAR0, -1); w32(MAR1, -1)
        w32(RX_CONF, RX_CONF_ONLYERLPKT or RX_CONF_AUTORESETPHY or RX_CONF_BSSID or RX_CONF_MGMT or RX_CONF_DATA or
            RX_FIFO_NONE or RX_MAX_DMA or RX_CONF_BROADCAST or RX_CONF_NICMAC or RX_CONF_MONITOR)
        w8(CW_CONF, (r8(CW_CONF) and 0x01.inv()) or 0x02)
        w8(TX_AGC_CTL, r8(TX_AGC_CTL) and 0x07.inv())
        w32(TX_CONF, TX_CONF_CW_MIN or (7 shl 21) or TX_CONF_NO_ICV)
        w8(CMD, r8(CMD) or CMD_TX_ENABLE or CMD_RX_ENABLE)
    }

    /** rtl8187_config: tune with the MAC in TX loopback, so nothing can go out during the change. */
    private fun tune(ch: Int, txPower: Boolean = false) {
        val reg = r32(TX_CONF)
        w32(TX_CONF, reg or TX_CONF_LOOPBACK_MAC)
        if (txPower) setTxPower(ch)
        rfWrite(0x7, CHAN[ch - 1])
        sleep(10)
        sleep(10)
        w32(TX_CONF, reg)
    }

    // ---- RTL8225 radio (rtl8225.c) ----

    private fun rfInit() = when (rf) {
        Rf.RTL8225 -> rf8225Init()
        Rf.RTL8225Z2 -> rf8225z2Init()
        Rf.RTL8225Z2B -> rf8225z2bInit()
    }

    private fun rfCalibrate(name: String) {
        var r6 = rfRead(6)
        if (r6 and 0x80 == 0) {
            rfWrite(0x02, 0x0C4D); sleep(200)
            rfWrite(0x02, 0x044D); sleep(100)
            r6 = rfRead(6)
        }
        UsbWifi.log("RTL8187: $name RF calibration %s (reg6 0x%03x)".format(if (r6 and 0x80 != 0) "ok" else "FAILED", r6))
    }

    private fun rf8225Init() {
        val init = intArrayOf(0x067, 0xFE0, 0x44D, 0x441, 0x486, 0xBC0, 0xAE6, 0x82A,
            0x01F, 0x334, 0xFD4, 0x391, 0x050, 0x6DB, 0x029, 0x914)
        for (i in init.indices) rfWrite(i, init[i])
        sleep(100)
        rfWrite(0x2, 0xC4D); sleep(200)
        rfWrite(0x2, 0x44D); sleep(200)
        rfCalibrate("RTL8225")
        rfWrite(0x0, 0x127)
        for (i in RXGAIN_8225.indices) { rfWrite(0x1, i + 1); rfWrite(0x2, RXGAIN_8225[i]) }
        rfWrite(0x0, 0x027); rfWrite(0x0, 0x22F)
        for (i in AGC_8225.indices) { phyOfdm(0xB, AGC_8225[i]); phyOfdm(0xA, 0x80 + i) }
        sleep(1)
        for (e in OFDM_8225) phyOfdm(e[0], e[1])
        sensitivity8225()
        for (e in CCK_8225) phyCck(e[0], e[1])
        w8(TESTR, 0x0D)
        setTxPower(1)
        antennaA()
        // set sensitivity
        rfWrite(0x0c, 0x50)
        sensitivity8225()
        phyCck(0x41, 0x8d) // rtl8225_threshold[2]
    }

    private fun sensitivity8225() {
        phyOfdm(0x0d, GAIN_8225[8]); phyOfdm(0x1b, GAIN_8225[10]); phyOfdm(0x1d, GAIN_8225[11]); phyOfdm(0x23, GAIN_8225[9])
    }

    private fun rf8225z2Init() {
        val init = intArrayOf(0x2BF, 0xEE0, 0x44D, 0x441, 0x8C3, 0xC72, 0x0E6, 0x82A,
            0x03F, 0x335, 0x9D4, 0x7BB, 0x850, 0xCDF, 0x02B, 0x114)
        for (i in init.indices) rfWrite(i, init[i])
        sleep(100)
        rfWrite(0x0, 0x1B7)
        for (i in RXGAIN_Z2.indices) { rfWrite(0x1, i + 1); rfWrite(0x2, RXGAIN_Z2[i]) }
        rfWrite(0x3, 0x080); rfWrite(0x5, 0x004); rfWrite(0x0, 0x0B7)
        rfWrite(0x2, 0xC4D); sleep(200)
        rfWrite(0x2, 0x44D); sleep(100)
        rfCalibrate("RTL8225Z2")
        sleep(200)
        rfWrite(0x0, 0x2BF)
        for (i in AGC_8225.indices) { phyOfdm(0xB, AGC_8225[i]); phyOfdm(0xA, 0x80 + i) }
        sleep(1)
        for (e in OFDM_Z2) phyOfdm(e[0], e[1])
        phyOfdm(0x0b, 0x23); phyOfdm(0x1b, 0x15); phyOfdm(0x1d, 0xc5) // rtl8225z2_gain_bg[12..14]
        phyOfdm(0x21, 0x37)
        for (e in CCK_Z2) phyCck(e[0], e[1])
        w8(TESTR, 0x0D); sleep(1)
        setTxPower(1)
        antennaA()
    }

    private fun rf8225z2bInit() {
        val init = intArrayOf(0x0B7, 0xEE0, 0x44D, 0x441, 0x8C3, 0xC72, 0x0E6, 0x82A,
            0x03F, 0x335, 0x9D4, 0x7BB, 0x850, 0xCDF, 0x02B, 0x114)
        for (i in init.indices) rfWrite(i, init[i])
        rfWrite(0x0, 0x1B7)
        for (i in RXGAIN_Z2.indices) { rfWrite(0x1, i + 1); rfWrite(0x2, RXGAIN_Z2[i]) }
        rfWrite(0x3, 0x080); rfWrite(0x5, 0x004); rfWrite(0x0, 0x0B7)
        rfWrite(0x2, 0xC4D); rfWrite(0x2, 0x44D); rfWrite(0x0, 0x2BF)
        w8(TX_GAIN_CCK, 0x03); w8(TX_GAIN_OFDM, 0x07); w8(TX_ANTENNA, 0x03)
        phyOfdm(0x80, 0x12)
        for (i in AGC_Z2.indices) { phyOfdm(0xF, AGC_Z2[i]); phyOfdm(0xE, 0x80 + i); phyOfdm(0xE, 0) }
        phyOfdm(0x80, 0x10)
        for (i in OFDM_Z2B.indices) phyOfdm(i, OFDM_Z2B[i])
        phyOfdm(0x97, 0x46); phyOfdm(0xa4, 0xb6); phyOfdm(0x85, 0xfc)
        phyCck(0xc1, 0x88)
    }

    /** RX on antenna A (the AWUS036H has one connector). */
    private fun antennaA() {
        phyCck(0x10, 0x9b)
        phyOfdm(0x26, 0x90)
        w8(TX_ANTENNA, 0x03)
        sleep(1)
        w32(HSSI_PARA, 0x3dc00002)
    }

    /**
     * The radio's transmit power registers, at the lowest setting: this driver never
     * transmits, but the kernel driver writes them on every tune and they share the
     * analog / OFDM setup with the receiver.
     */
    private fun setTxPower(ch: Int) {
        when (rf) {
            Rf.RTL8225 -> {
                val cck = 0; val ofdm = 10
                w8(TX_GAIN_CCK, TX_GAIN_8225[cck / 6] shr 1)
                val tab = if (ch == 14) TX_CCK_8225_CH14 else TX_CCK_8225
                for (i in 0 until 8) phyCck(0x44 + i, tab[(cck % 6) * 8 + i])
                sleep(1)
                anaparam2On()
                phyOfdm(2, 0x42); phyOfdm(6, 0x00); phyOfdm(8, 0x00)
                w8(TX_GAIN_OFDM, TX_GAIN_8225[ofdm / 6] shr 1)
                phyOfdm(5, TX_OFDM_8225[ofdm % 6]); phyOfdm(7, TX_OFDM_8225[ofdm % 6])
                sleep(1)
            }
            Rf.RTL8225Z2 -> {
                val cck = 0; val ofdm = 10
                val tab = if (ch == 14) TX_CCK_Z2_CH14 else TX_CCK_Z2
                for (i in 0 until 8) phyCck(0x44 + i, tab[i])
                w8(TX_GAIN_CCK, cck); sleep(1) // rtl8225z2_tx_gain_cck_ofdm[n] == n
                anaparam2On()
                phyOfdm(2, 0x42); phyOfdm(5, 0x00); phyOfdm(6, 0x40); phyOfdm(7, 0x00); phyOfdm(8, 0x40)
                w8(TX_GAIN_OFDM, ofdm)
                sleep(1)
            }
            Rf.RTL8225Z2B -> {
                val bvb = hwRev == BvB
                val cck = if (bvb) 0 else 7
                val ofdm = if (bvb) 2 else 10
                val tab = if (ch == 14) TX_CCK_Z2_CH14 else TX_CCK_Z2
                val row = if (bvb) 0 else 8 // cck 0 (BvB) or 7 (others)
                for (i in 0 until 8) phyCck(0x44 + i, tab[row + i])
                w8(TX_GAIN_CCK, cck shl 1); sleep(1)
                w8(TX_GAIN_OFDM, ofdm shl 1)
                val v = if (bvb) 0x60 else 0x5c // ofdm <= 11
                phyOfdm(0x87, v); phyOfdm(0x89, v)
                sleep(1)
            }
        }
    }

    private fun anaparam2On() {
        w8(EEPROM_CMD, EEPROM_CMD_CONFIG)
        val reg = r8(CONFIG3)
        w8(CONFIG3, reg or CONFIG3_ANAPARAM_WRITE)
        w32(ANAPARAM2, ANAPARAM2_ON_L)
        w8(CONFIG3, reg and CONFIG3_ANAPARAM_WRITE.inv())
        w8(EEPROM_CMD, EEPROM_CMD_NORMAL)
    }

    private fun rfWrite(addr: Int, data: Int) = if (asicRev != 0) rfWrite8051(addr, data) else rfWriteBitbang(addr, data)

    private fun rfWrite8051(addr: Int, data: Int) {
        val reg80 = r16(RF_OUT) and 0x000C.inv(); val reg82 = r16(RF_EN); val reg84 = r16(RF_SEL) and 0x000F.inv()
        w16(RF_EN, reg82 or 0x7); w16(RF_SEL, reg84 or 0x7)
        w16(RF_OUT, reg80 or 0x4); w16(RF_OUT, reg80)
        if (conn.controlTransfer(REQT_WRITE, REQ_REG, addr, 0x8225, byteArrayOf(data.toByte(), (data ushr 8).toByte()), 2, 500) < 0) ctlErrors++
        w16(RF_OUT, reg80 or 0x4); w16(RF_OUT, reg80 or 0x4)
        w16(RF_SEL, reg84)
    }

    private fun rfWriteBitbang(addr: Int, data: Int) {
        val bang = (data shl 4) or (addr and 0xf)
        val reg80 = r16(RF_OUT) and 0xfff3; val reg82 = r16(RF_EN)
        w16(RF_EN, reg82 or 0x7)
        val reg84 = r16(RF_SEL)
        w16(RF_SEL, reg84 or 0x7)
        w16(RF_OUT, reg80 or 0x4); w16(RF_OUT, reg80)
        for (i in 15 downTo 0) {
            val reg = reg80 or ((bang ushr i) and 1)
            if (i and 1 != 0) w16(RF_OUT, reg)
            w16(RF_OUT, reg or 0x2); w16(RF_OUT, reg or 0x2)
            if (i and 1 == 0) w16(RF_OUT, reg)
        }
        w16(RF_OUT, reg80 or 0x4); w16(RF_OUT, reg80 or 0x4)
        w16(RF_SEL, reg84)
    }

    private fun rfRead(addr: Int): Int {
        val reg80 = r16(RF_OUT) and 0xF.inv() and 0xffff; val reg82 = r16(RF_EN); val reg84 = r16(RF_SEL)
        w16(RF_EN, reg82 or 0xF); w16(RF_SEL, reg84 or 0xF)
        w16(RF_OUT, reg80 or 0x4); w16(RF_OUT, reg80)
        for (i in 4 downTo 0) {
            val reg = reg80 or ((addr shr i) and 1)
            if (i and 1 == 0) w16(RF_OUT, reg)
            w16(RF_OUT, reg or 0x2); w16(RF_OUT, reg or 0x2)
            if (i and 1 != 0) w16(RF_OUT, reg)
        }
        w16(RF_OUT, reg80 or 0x8 or 0x2); w16(RF_OUT, reg80 or 0x8); w16(RF_OUT, reg80 or 0x8)
        var out = 0
        for (i in 11 downTo 0) {
            w16(RF_OUT, reg80 or 0x8)
            w16(RF_OUT, reg80 or 0x8 or 0x2); w16(RF_OUT, reg80 or 0x8 or 0x2); w16(RF_OUT, reg80 or 0x8 or 0x2)
            if (r16(RF_IN) and 0x2 != 0) out = out or (1 shl i)
            w16(RF_OUT, reg80 or 0x8)
        }
        w16(RF_OUT, reg80 or 0x8 or 0x4)
        w16(RF_EN, reg82); w16(RF_SEL, reg84); w16(RF_OUT, 0x03A0)
        return out
    }

    private fun writePhy(addr: Int, data: Int) {
        val d = (data shl 8) or addr or 0x80
        w8(PHY0 + 3, (d ushr 24) and 0xff); w8(PHY0 + 2, (d ushr 16) and 0xff)
        w8(PHY0 + 1, (d ushr 8) and 0xff); w8(PHY0, d and 0xff)
    }
    private fun phyOfdm(addr: Int, data: Int) = writePhy(addr, data)
    private fun phyCck(addr: Int, data: Int) = writePhy(addr, data or 0x10000)

    // ---- receive ----

    private class RxState { var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0 }

    private fun rxLoop(chipName: String, ep: android.hardware.usb.UsbEndpoint,
                       onFrame: (List<MonitorSighting>) -> Unit, onStatus: (String) -> Unit) {
        val st = RxState()
        val t0 = System.currentTimeMillis(); st.hopAt = t0; st.lastEmit = t0
        val stats = BulkRx.Stats("RTL8187")
        val hdr = if (is8187b) 20 else 16
        val total = BulkRx.run("RTL8187", conn, ep, 16, MAX_RX, { stop }, onData@{ b, n ->
            stats.transfer(n)
            if (n >= hdr) stats.sample("$n bytes, frame control %02x, descriptor".format(b[0].toInt() and 0xff), b, n - hdr, hdr)
            val f = parseRx(b, n, is8187b)
            if (f == null) {
                if (n >= hdr && le32(b, n - hdr) and RX_DESC_CRC32_ERR != 0) stats.badChecksum++ else stats.malformed++
                return@onData
            }
            stats.frame(f.rssi)
            frames.frame(b, f.start, f.end, HOP[st.hopIdx], f.rssi)
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
        UsbWifi.log("RTL8187: RX loop stopped ($total B)")
    }

    private fun emit(onFrame: (List<MonitorSighting>) -> Unit) {
        val batch = frames.drain(System.currentTimeMillis(), FRESH_MS)
        if (batch.isNotEmpty()) onFrame(batch)
    }

    /** Where the 802.11 frame sits in a received transfer (FCS removed) and its signal in dBm. */
    internal data class Rx(val start: Int, val end: Int, val rssi: Int)

    /**
     * One bulk transfer = one frame followed by the RX descriptor (16 bytes on the
     * RTL8187L, 20 on the RTL8187B). Null for a failed checksum or a malformed transfer.
     */
    internal fun parseRx(b: ByteArray, n: Int, is8187b: Boolean): Rx? {
        val hdr = if (is8187b) 20 else 16
        if (n < hdr + 24 || n > b.size) return null
        val h = n - hdr
        val flags = le32(b, h)
        if (flags and RX_DESC_CRC32_ERR != 0) return null
        val len = flags and 0x0FFF // frame + FCS
        if (len < 24 + 4 || len > h) return null
        // Signal from the AGC value, scaled as the kernel driver does.
        val rssi = if (is8187b) 14 - (b[h + 14].toInt() and 0xff) / 2
                   else -4 - ((27 * (b[h + 6].toInt() and 0xff)) shr 6)
        return Rx(0, len - 4, rssi)
    }

    // ---- register access ----

    /** Failed register reads / writes (an unplugged or stalled adapter), for the log. */
    @Volatile private var ctlErrors = 0

    private fun read(addr: Int, len: Int, idx: Int = 0): Int {
        val b = ByteArray(len)
        if (conn.controlTransfer(REQT_READ, REQ_REG, addr, idx and 3, b, len, 500) != len) { ctlErrors++; return 0 }
        var v = 0
        for (i in len - 1 downTo 0) v = (v shl 8) or (b[i].toInt() and 0xff)
        return v
    }
    private fun write(addr: Int, v: Int, len: Int, idx: Int = 0) {
        val b = ByteArray(len) { (v ushr (8 * it)).toByte() }
        if (conn.controlTransfer(REQT_WRITE, REQ_REG, addr, idx and 3, b, len, 500) < 0) ctlErrors++
    }
    private fun r8(a: Int) = read(a, 1)
    private fun r16(a: Int) = read(a, 2)
    private fun r32(a: Int) = read(a, 4)
    private fun w8(a: Int, v: Int, idx: Int = 0) = write(a, v, 1, idx)
    private fun w16(a: Int, v: Int, idx: Int = 0) = write(a, v, 2, idx)
    private fun w32(a: Int, v: Int, idx: Int = 0) = write(a, v, 4, idx)

    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}

    // ---- tables (rtl8225.c / dev.c) ----

    private val CHAN = intArrayOf(0x085c, 0x08dc, 0x095c, 0x09dc, 0x0a5c, 0x0adc, 0x0b5c,
        0x0bdc, 0x0c5c, 0x0cdc, 0x0d5c, 0x0ddc, 0x0e5c, 0x0f72)

    private val RXGAIN_8225 = intArrayOf(
        0x0400, 0x0401, 0x0402, 0x0403, 0x0404, 0x0405, 0x0408, 0x0409,
        0x040a, 0x040b, 0x0502, 0x0503, 0x0504, 0x0505, 0x0540, 0x0541,
        0x0542, 0x0543, 0x0544, 0x0545, 0x0580, 0x0581, 0x0582, 0x0583,
        0x0584, 0x0585, 0x0588, 0x0589, 0x058a, 0x058b, 0x0643, 0x0644,
        0x0645, 0x0680, 0x0681, 0x0682, 0x0683, 0x0684, 0x0685, 0x0688,
        0x0689, 0x068a, 0x068b, 0x068c, 0x0742, 0x0743, 0x0744, 0x0745,
        0x0780, 0x0781, 0x0782, 0x0783, 0x0784, 0x0785, 0x0788, 0x0789,
        0x078a, 0x078b, 0x078c, 0x078d, 0x0790, 0x0791, 0x0792, 0x0793,
        0x0794, 0x0795, 0x0798, 0x0799, 0x079a, 0x079b, 0x079c, 0x079d,
        0x07a0, 0x07a1, 0x07a2, 0x07a3, 0x07a4, 0x07a5, 0x07a8, 0x07a9,
        0x07aa, 0x07ab, 0x07ac, 0x07ad, 0x07b0, 0x07b1, 0x07b2, 0x07b3,
        0x07b4, 0x07b5, 0x07b8, 0x07b9, 0x07ba, 0x07bb, 0x07bb)

    private val RXGAIN_Z2 = intArrayOf(
        0x0400, 0x0401, 0x0402, 0x0403, 0x0404, 0x0405, 0x0408, 0x0409,
        0x040a, 0x040b, 0x0502, 0x0503, 0x0504, 0x0505, 0x0540, 0x0541,
        0x0542, 0x0543, 0x0544, 0x0545, 0x0580, 0x0581, 0x0582, 0x0583,
        0x0584, 0x0585, 0x0588, 0x0589, 0x058a, 0x058b, 0x0643, 0x0644,
        0x0645, 0x0680, 0x0681, 0x0682, 0x0683, 0x0684, 0x0685, 0x0688,
        0x0689, 0x068a, 0x068b, 0x068c, 0x0742, 0x0743, 0x0744, 0x0745,
        0x0780, 0x0781, 0x0782, 0x0783, 0x0784, 0x0785, 0x0788, 0x0789,
        0x078a, 0x078b, 0x078c, 0x078d, 0x0790, 0x0791, 0x0792, 0x0793,
        0x0794, 0x0795, 0x0798, 0x0799, 0x079a, 0x079b, 0x079c, 0x079d,
        0x07a0, 0x07a1, 0x07a2, 0x07a3, 0x07a4, 0x07a5, 0x07a8, 0x07a9,
        0x03aa, 0x03ab, 0x03ac, 0x03ad, 0x03b0, 0x03b1, 0x03b2, 0x03b3,
        0x03b4, 0x03b5, 0x03b8, 0x03b9, 0x03ba, 0x03bb, 0x03bb)

    private val AGC_8225 = intArrayOf(
        0x9e, 0x9e, 0x9e, 0x9e, 0x9e, 0x9e, 0x9e, 0x9e, 0x9d, 0x9c, 0x9b, 0x9a, 0x99, 0x98, 0x97, 0x96,
        0x95, 0x94, 0x93, 0x92, 0x91, 0x90, 0x8f, 0x8e, 0x8d, 0x8c, 0x8b, 0x8a, 0x89, 0x88, 0x87, 0x86,
        0x85, 0x84, 0x83, 0x82, 0x81, 0x80, 0x3f, 0x3e, 0x3d, 0x3c, 0x3b, 0x3a, 0x39, 0x38, 0x37, 0x36,
        0x35, 0x34, 0x33, 0x32, 0x31, 0x30, 0x2f, 0x2e, 0x2d, 0x2c, 0x2b, 0x2a, 0x29, 0x28, 0x27, 0x26,
        0x25, 0x24, 0x23, 0x22, 0x21, 0x20, 0x1f, 0x1e, 0x1d, 0x1c, 0x1b, 0x1a, 0x19, 0x18, 0x17, 0x16,
        0x15, 0x14, 0x13, 0x12, 0x11, 0x10, 0x0f, 0x0e, 0x0d, 0x0c, 0x0b, 0x0a, 0x09, 0x08, 0x07, 0x06,
        0x05, 0x04, 0x03, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01)

    private val AGC_Z2 = intArrayOf(
        0x5e, 0x5e, 0x5e, 0x5e, 0x5d, 0x5b, 0x59, 0x57, 0x55, 0x53, 0x51, 0x4f,
        0x4d, 0x4b, 0x49, 0x47, 0x45, 0x43, 0x41, 0x3f, 0x3d, 0x3b, 0x39, 0x37,
        0x35, 0x33, 0x31, 0x2f, 0x2d, 0x2b, 0x29, 0x27, 0x25, 0x23, 0x21, 0x1f,
        0x1d, 0x1b, 0x19, 0x17, 0x15, 0x13, 0x11, 0x0f, 0x0d, 0x0b, 0x09, 0x07,
        0x05, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x01, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19,
        0x19, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x26, 0x27, 0x27, 0x28,
        0x28, 0x29, 0x2a, 0x2a, 0x2a, 0x2b, 0x2b, 0x2b, 0x2c, 0x2c, 0x2c, 0x2d,
        0x2d, 0x2d, 0x2d, 0x2e, 0x2e, 0x2e, 0x2e, 0x2f, 0x2f, 0x2f, 0x30, 0x30,
        0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31,
        0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31)

    private val OFDM_Z2B = intArrayOf(
        0x10, 0x0d, 0x01, 0x00, 0x14, 0xfb, 0xfb, 0x60, 0x00, 0x60, 0x00, 0x00, 0x00, 0x5c, 0x00, 0x00,
        0x40, 0x00, 0x40, 0x00, 0x00, 0x00, 0xa8, 0x26, 0x32, 0x33, 0x07, 0xa5, 0x6f, 0x55, 0xc8, 0xb3,
        0x0a, 0xe1, 0x2C, 0x8a, 0x86, 0x83, 0x34, 0x0f, 0x4f, 0x24, 0x6f, 0xc2, 0x6b, 0x40, 0x80, 0x00,
        0xc0, 0xc1, 0x58, 0xf1, 0x00, 0xe4, 0x90, 0x3e, 0x6d, 0x3c, 0xfb, 0x07)

    /** rtl8225_gain rows 0..6, 4 bytes each; row 2 (-82 dBm) is the one used. */
    private val GAIN_8225 = intArrayOf(
        0x23, 0x88, 0x7c, 0xa5, 0x23, 0x88, 0x7c, 0xb5, 0x23, 0x88, 0x7c, 0xc5, 0x33, 0x80, 0x79, 0xc5,
        0x43, 0x78, 0x76, 0xc5, 0x53, 0x60, 0x73, 0xc5, 0x63, 0x58, 0x70, 0xc5)

    private val OFDM_8225 = arrayOf(
        intArrayOf(0x00, 0x01), intArrayOf(0x01, 0x02), intArrayOf(0x02, 0x42), intArrayOf(0x03, 0x00),
        intArrayOf(0x04, 0x00), intArrayOf(0x05, 0x00), intArrayOf(0x06, 0x40), intArrayOf(0x07, 0x00),
        intArrayOf(0x08, 0x40), intArrayOf(0x09, 0xfe), intArrayOf(0x0a, 0x09), intArrayOf(0x0b, 0x80),
        intArrayOf(0x0c, 0x01), intArrayOf(0x0e, 0xd3), intArrayOf(0x0f, 0x38), intArrayOf(0x10, 0x84),
        intArrayOf(0x11, 0x06), intArrayOf(0x12, 0x20), intArrayOf(0x13, 0x20), intArrayOf(0x14, 0x00),
        intArrayOf(0x15, 0x40), intArrayOf(0x16, 0x00), intArrayOf(0x17, 0x40), intArrayOf(0x18, 0xef),
        intArrayOf(0x19, 0x19), intArrayOf(0x1a, 0x20), intArrayOf(0x1b, 0x76), intArrayOf(0x1c, 0x04),
        intArrayOf(0x1e, 0x95), intArrayOf(0x1f, 0x75), intArrayOf(0x20, 0x1f), intArrayOf(0x21, 0x27),
        intArrayOf(0x22, 0x16), intArrayOf(0x24, 0x46), intArrayOf(0x25, 0x20), intArrayOf(0x26, 0x90),
        intArrayOf(0x27, 0x88))

    private val OFDM_Z2 = arrayOf(
        intArrayOf(0x00, 0x01), intArrayOf(0x01, 0x02), intArrayOf(0x02, 0x42), intArrayOf(0x03, 0x00),
        intArrayOf(0x04, 0x00), intArrayOf(0x05, 0x00), intArrayOf(0x06, 0x40), intArrayOf(0x07, 0x00),
        intArrayOf(0x08, 0x40), intArrayOf(0x09, 0xfe), intArrayOf(0x0a, 0x08), intArrayOf(0x0b, 0x80),
        intArrayOf(0x0c, 0x01), intArrayOf(0x0d, 0x43), intArrayOf(0x0e, 0xd3), intArrayOf(0x0f, 0x38),
        intArrayOf(0x10, 0x84), intArrayOf(0x11, 0x07), intArrayOf(0x12, 0x20), intArrayOf(0x13, 0x20),
        intArrayOf(0x14, 0x00), intArrayOf(0x15, 0x40), intArrayOf(0x16, 0x00), intArrayOf(0x17, 0x40),
        intArrayOf(0x18, 0xef), intArrayOf(0x19, 0x19), intArrayOf(0x1a, 0x20), intArrayOf(0x1b, 0x15),
        intArrayOf(0x1c, 0x04), intArrayOf(0x1d, 0xc5), intArrayOf(0x1e, 0x95), intArrayOf(0x1f, 0x75),
        intArrayOf(0x20, 0x1f), intArrayOf(0x21, 0x17), intArrayOf(0x22, 0x16), intArrayOf(0x23, 0x80),
        intArrayOf(0x24, 0x46), intArrayOf(0x25, 0x00), intArrayOf(0x26, 0x90), intArrayOf(0x27, 0x88))

    private val CCK_8225 = arrayOf(
        intArrayOf(0x00, 0x98), intArrayOf(0x03, 0x20), intArrayOf(0x04, 0x7e), intArrayOf(0x05, 0x12),
        intArrayOf(0x06, 0xfc), intArrayOf(0x07, 0x78), intArrayOf(0x08, 0x2e), intArrayOf(0x10, 0x9b),
        intArrayOf(0x11, 0x88), intArrayOf(0x12, 0x47), intArrayOf(0x13, 0xd0), intArrayOf(0x19, 0x00),
        intArrayOf(0x1a, 0xa0), intArrayOf(0x1b, 0x08), intArrayOf(0x40, 0x86), intArrayOf(0x41, 0x8d),
        intArrayOf(0x42, 0x15), intArrayOf(0x43, 0x18), intArrayOf(0x44, 0x1f), intArrayOf(0x45, 0x1e),
        intArrayOf(0x46, 0x1a), intArrayOf(0x47, 0x15), intArrayOf(0x48, 0x10), intArrayOf(0x49, 0x0a),
        intArrayOf(0x4a, 0x05), intArrayOf(0x4b, 0x02), intArrayOf(0x4c, 0x05))

    private val CCK_Z2 = arrayOf(
        intArrayOf(0x00, 0x98), intArrayOf(0x03, 0x20), intArrayOf(0x04, 0x7e), intArrayOf(0x05, 0x12),
        intArrayOf(0x06, 0xfc), intArrayOf(0x07, 0x78), intArrayOf(0x08, 0x2e), intArrayOf(0x10, 0x9b),
        intArrayOf(0x11, 0x88), intArrayOf(0x12, 0x47), intArrayOf(0x13, 0xd0), intArrayOf(0x19, 0x00),
        intArrayOf(0x1a, 0xa0), intArrayOf(0x1b, 0x08), intArrayOf(0x40, 0x86), intArrayOf(0x41, 0x8d),
        intArrayOf(0x42, 0x15), intArrayOf(0x43, 0x18), intArrayOf(0x44, 0x36), intArrayOf(0x45, 0x35),
        intArrayOf(0x46, 0x2e), intArrayOf(0x47, 0x25), intArrayOf(0x48, 0x1c), intArrayOf(0x49, 0x12),
        intArrayOf(0x4a, 0x09), intArrayOf(0x4b, 0x04), intArrayOf(0x4c, 0x05))

    private val TX_GAIN_8225 = intArrayOf(0x02, 0x06, 0x0e, 0x1e, 0x3e, 0x7e)
    private val TX_OFDM_8225 = intArrayOf(0x80, 0x90, 0xa2, 0xb5, 0xcb, 0xe4)
    private val TX_CCK_8225 = intArrayOf(
        0x18, 0x17, 0x15, 0x11, 0x0c, 0x08, 0x04, 0x02, 0x1b, 0x1a, 0x17, 0x13, 0x0e, 0x09, 0x04, 0x02,
        0x1f, 0x1e, 0x1a, 0x15, 0x10, 0x0a, 0x05, 0x02, 0x22, 0x21, 0x1d, 0x18, 0x11, 0x0b, 0x06, 0x02,
        0x26, 0x25, 0x21, 0x1b, 0x14, 0x0d, 0x06, 0x03, 0x2b, 0x2a, 0x25, 0x1e, 0x16, 0x0e, 0x07, 0x03)
    private val TX_CCK_8225_CH14 = intArrayOf(
        0x18, 0x17, 0x15, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x1b, 0x1a, 0x17, 0x0e, 0x00, 0x00, 0x00, 0x00,
        0x1f, 0x1e, 0x1a, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x22, 0x21, 0x1d, 0x11, 0x00, 0x00, 0x00, 0x00,
        0x26, 0x25, 0x21, 0x13, 0x00, 0x00, 0x00, 0x00, 0x2b, 0x2a, 0x25, 0x15, 0x00, 0x00, 0x00, 0x00)
    private val TX_CCK_Z2 = intArrayOf(
        0x36, 0x35, 0x2e, 0x25, 0x1c, 0x12, 0x09, 0x04, 0x30, 0x2f, 0x29, 0x21, 0x19, 0x10, 0x08, 0x03,
        0x2b, 0x2a, 0x25, 0x1e, 0x16, 0x0e, 0x07, 0x03, 0x26, 0x25, 0x21, 0x1b, 0x14, 0x0d, 0x06, 0x03)
    private val TX_CCK_Z2_CH14 = intArrayOf(
        0x36, 0x35, 0x2e, 0x1b, 0x00, 0x00, 0x00, 0x00, 0x30, 0x2f, 0x29, 0x15, 0x00, 0x00, 0x00, 0x00,
        0x30, 0x2f, 0x29, 0x15, 0x00, 0x00, 0x00, 0x00, 0x30, 0x2f, 0x29, 0x15, 0x00, 0x00, 0x00, 0x00)

    /** rtl8187b_reg_table: {register (low byte), value, page}. */
    private val REG_TABLE_B = arrayOf(
        intArrayOf(0xF0, 0x32, 0), intArrayOf(0xF1, 0x32, 0), intArrayOf(0xF2, 0x00, 0), intArrayOf(0xF3, 0x00, 0),
        intArrayOf(0xF4, 0x32, 0), intArrayOf(0xF5, 0x43, 0), intArrayOf(0xF6, 0x00, 0), intArrayOf(0xF7, 0x00, 0),
        intArrayOf(0xF8, 0x46, 0), intArrayOf(0xF9, 0xA4, 0), intArrayOf(0xFA, 0x00, 0), intArrayOf(0xFB, 0x00, 0),
        intArrayOf(0xFC, 0x96, 0), intArrayOf(0xFD, 0xA4, 0), intArrayOf(0xFE, 0x00, 0), intArrayOf(0xFF, 0x00, 0),
        intArrayOf(0x58, 0x4B, 1), intArrayOf(0x59, 0x00, 1), intArrayOf(0x5A, 0x4B, 1), intArrayOf(0x5B, 0x00, 1),
        intArrayOf(0x60, 0x4B, 1), intArrayOf(0x61, 0x09, 1), intArrayOf(0x62, 0x4B, 1), intArrayOf(0x63, 0x09, 1),
        intArrayOf(0xCE, 0x0F, 1), intArrayOf(0xCF, 0x00, 1), intArrayOf(0xF0, 0x4E, 1), intArrayOf(0xF1, 0x01, 1),
        intArrayOf(0xF2, 0x02, 1), intArrayOf(0xF3, 0x03, 1), intArrayOf(0xF4, 0x04, 1), intArrayOf(0xF5, 0x05, 1),
        intArrayOf(0xF6, 0x06, 1), intArrayOf(0xF7, 0x07, 1), intArrayOf(0xF8, 0x08, 1),
        intArrayOf(0x4E, 0x00, 2), intArrayOf(0x0C, 0x04, 2), intArrayOf(0x21, 0x61, 2), intArrayOf(0x22, 0x68, 2),
        intArrayOf(0x23, 0x6F, 2), intArrayOf(0x24, 0x76, 2), intArrayOf(0x25, 0x7D, 2), intArrayOf(0x26, 0x84, 2),
        intArrayOf(0x27, 0x8D, 2), intArrayOf(0x4D, 0x08, 2), intArrayOf(0x50, 0x05, 2), intArrayOf(0x51, 0xF5, 2),
        intArrayOf(0x52, 0x04, 2), intArrayOf(0x53, 0xA0, 2), intArrayOf(0x54, 0x1F, 2), intArrayOf(0x55, 0x23, 2),
        intArrayOf(0x56, 0x45, 2), intArrayOf(0x57, 0x67, 2), intArrayOf(0x58, 0x08, 2), intArrayOf(0x59, 0x08, 2),
        intArrayOf(0x5A, 0x08, 2), intArrayOf(0x5B, 0x08, 2), intArrayOf(0x60, 0x08, 2), intArrayOf(0x61, 0x08, 2),
        intArrayOf(0x62, 0x08, 2), intArrayOf(0x63, 0x08, 2), intArrayOf(0x64, 0xCF, 2),
        intArrayOf(0x5B, 0x40, 0), intArrayOf(0x84, 0x88, 0), intArrayOf(0x85, 0x24, 0), intArrayOf(0x88, 0x54, 0),
        intArrayOf(0x8B, 0xB8, 0), intArrayOf(0x8C, 0x07, 0), intArrayOf(0x8D, 0x00, 0), intArrayOf(0x94, 0x1B, 0),
        intArrayOf(0x95, 0x12, 0), intArrayOf(0x96, 0x00, 0), intArrayOf(0x97, 0x06, 0), intArrayOf(0x9D, 0x1A, 0),
        intArrayOf(0x9F, 0x10, 0), intArrayOf(0xB4, 0x22, 0), intArrayOf(0xBE, 0x80, 0), intArrayOf(0xDB, 0x00, 0),
        intArrayOf(0xEE, 0x00, 0), intArrayOf(0x4C, 0x00, 2),
        intArrayOf(0x9F, 0x00, 3), intArrayOf(0x8C, 0x01, 0), intArrayOf(0x8D, 0x10, 0), intArrayOf(0x8E, 0x08, 0),
        intArrayOf(0x8F, 0x00, 0))
}
