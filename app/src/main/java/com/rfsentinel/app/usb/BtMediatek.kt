package com.rfsentinel.app.usb

/*
 * Bluetooth bring-up of MediaTek MT7921AU (MT7961) combo adapters: the btmtk_usb_setup path of
 * the Linux driver (drivers/bluetooth/btmtk.c, GPL-2.0; Copyright (c) 2021 MediaTek Inc.), step
 * by step in the same order: read the chip ID, firmware version and flavour, download the
 * BT_RAM_CODE patch sections with WMT PATCH_DWNLD commands (each answered on a polled control
 * IN request, btmtk_usb_hci_wmt_sync), set the endpoint reset option, then turn the Bluetooth
 * function on with WMT FUNC_CTRL. If the download fails, the Bluetooth subsystem is reset the
 * way btmtk_usb_subsys_reset does and the setup runs once more (Linux also resets the USB port,
 * which Android apps can't). The ISO data interface (LE audio) isn't claimed: nothing here uses it.
 */

import android.content.Context
import java.io.IOException

internal object BtMediatek {

    private const val WMT_PATCH_DWNLD = 0x1
    private const val WMT_FUNC_CTRL = 0x6
    private const val PKT_START = 1; private const val PKT_CONTINUE = 2; private const val PKT_END = 3
    private enum class Patch { UNDONE, PROGRESS, DONE }

    private const val EP_RST_OPT = 0x74011890; private const val EP_RST_IN_OUT_OPT = 0x00010001
    private const val BT_MISC = 0x70002510; private const val BT_RST_DONE = 0x100
    private const val BT_SUBSYS_RST = 0x70002610; private const val BT_WDT_STATUS = 0x740003A0
    private const val UDMA_INT_STA_BT = 0x74000024; private const val UDMA_INT_STA_BT1 = 0x74000308

    private const val HEADER = 32; private const val GLOBAL_DESC = 64; private const val SEC_MAP = 64
    private const val SEC_MAP_COMMON = 12; private const val SEC_MAP_NEED_SEND = 52

    /** What [identify] read: the chip and its firmware file. */
    class Chip(val devId: Int, val fwVersion: Int, val flavor: Int) {
        val name get() = if (devId == 0x7961) "MT7921AU" else "MT%04X".format(devId and 0xffff)
        /** btmtk_fw_get_filename for the chips with a USB form; null for one this app doesn't drive. */
        val firmware: String? get() = when (devId) {
            0x7961 -> if (flavor != 0) null else "BT_RAM_CODE_MT7961_1_%x_hdr.bin".format((fwVersion and 0xff) + 1)
            else -> null
        }
    }

    // ---- registers (btmtk_usb_reg_read, btmtk_usb_uhw_reg_read / _write) ----

    private fun regRead(h: HciUsb, reg: Int): Int {
        val b = h.ctlIn(0xC0, 0x63, reg ushr 16, reg and 0xffff, 4) ?: throw IOException("register %08x not readable".format(reg))
        if (b.size < 4) throw IOException("register %08x: short read".format(reg))
        return HciUsb.le32(b, 0)
    }

    private fun uhwWrite(h: HciUsb, reg: Int, v: Int) {
        val d = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        if (!h.ctlOut(0x5E, 0x02, reg ushr 16, reg and 0xffff, d)) throw IOException("uhw register %08x not written".format(reg))
    }

    private fun uhwRead(h: HciUsb, reg: Int): Int {
        val b = h.ctlIn(0xDE, 0x01, reg ushr 16, reg and 0xffff, 4) ?: throw IOException("uhw register %08x not readable".format(reg))
        if (b.size < 4) throw IOException("uhw register %08x: short read".format(reg))
        return HciUsb.le32(b, 0)
    }

    /** The first part of btmtk_usb_setup: chip ID, firmware version and flavour. */
    fun identify(h: HciUsb): Chip {
        var devId = regRead(h, 0x80000008L.toInt())
        var fwVersion = 0; var flavor = 0
        if (devId == 0 || devId != 0x7663) {
            devId = regRead(h, 0x70010200)
            fwVersion = regRead(h, 0x80021004L.toInt())
            flavor = (regRead(h, 0x70010020) and 0x80) shr 7
        }
        return Chip(devId, fwVersion, flavor)
    }

    /**
     * btmtk_usb_hci_wmt_sync: the WMT command goes out as HCI vendor command 0xFC6F, its event
     * comes back on control IN request 1 (wValue 48), asked again every 500 us while it returns nothing.
     * Returns the event's WMT flag byte and, for FUNC_CTRL, its status word.
     */
    private fun wmt(h: HciUsb, op: Int, flag: Int, data: ByteArray): Pair<Int, Int?> {
        val dlen = data.size
        if (5 + dlen > 255) throw IOException("WMT command too long")
        h.send(0xFC6F, byteArrayOf(1, op.toByte(), (dlen + 1).toByte(), ((dlen + 1) shr 8).toByte(), flag.toByte()) + data)
        val deadline = System.currentTimeMillis() + h.cmdTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val e = h.ctlIn(0xC0, 0x01, 48, 0, 64)
            if (e == null) { if (h.unplugged) throw IOException("adapter unplugged"); continue }
            if (e.isEmpty()) { Thread.sleep(0, 500_000); continue }
            // hci_event_hdr (2) + wmt_hdr: dir, op, dlen (2), flag
            if (e.size < 7) throw IOException("WMT event too short (${e.size} bytes)")
            if (e[3].toInt() and 0xff != op) throw IOException("WMT answer for op ${e[3].toInt() and 0xff}, expected $op")
            val status = if (op == WMT_FUNC_CTRL && e.size >= 9) ((e[7].toInt() and 0xff) shl 8) or (e[8].toInt() and 0xff) else null
            return (e[6].toInt() and 0xff) to status
        }
        throw IOException("WMT command $op timed out")
    }

    private fun patchStatus(flag: Int) = when (flag) { 2 -> Patch.DONE; 1 -> Patch.PROGRESS; else -> Patch.UNDONE }

    /** btmtk_setup_firmware_79xx. */
    private fun download(h: HciUsb, fw: ByteArray, tag: String) {
        if (fw.size < HEADER + GLOBAL_DESC) throw IOException("firmware too small")
        val sections = HciUsb.le32(fw, HEADER + 12)
        if (sections <= 0 || fw.size < HEADER + GLOBAL_DESC + SEC_MAP * sections) throw IOException("firmware section table damaged")
        UsbWifi.log("$tag: firmware built ${String(fw, 0, 14, Charsets.US_ASCII).trimEnd('\u0000')}, $sections sections")
        for (i in 0 until sections) {
            val map = HEADER + GLOBAL_DESC + SEC_MAP * i
            val offset = HciUsb.le32(fw, map + 4)
            var size = HciUsb.le32(fw, map + 16) // bin_info_spec.dlsize
            if (size <= 0) continue
            if (offset < 0 || offset.toLong() + size > fw.size) throw IOException("firmware section $i out of bounds")
            var retry = 20
            var go = false
            while (retry > 0) {
                val cmd = byteArrayOf(0) + fw.copyOfRange(map + SEC_MAP_COMMON, map + SEC_MAP_COMMON + SEC_MAP_NEED_SEND)
                when (patchStatus(wmt(h, WMT_PATCH_DWNLD, 0, cmd).first)) {
                    Patch.UNDONE -> { go = true; break }
                    Patch.PROGRESS -> { Thread.sleep(100); retry-- }
                    Patch.DONE -> break
                }
            }
            if (retry == 0) throw IOException("firmware section $i: chip stayed busy")
            if (!go) { UsbWifi.log("$tag: section $i already loaded"); continue }
            var at = offset
            var first = true
            while (size > 0) {
                val len = minOf(250, size)
                val flag = when { first -> PKT_START; size - len <= 0 -> PKT_END; else -> PKT_CONTINUE }
                first = false
                if (patchStatus(wmt(h, WMT_PATCH_DWNLD, flag, fw.copyOfRange(at, at + len)).first) == Patch.PROGRESS)
                    throw IOException("firmware section $i: unexpected busy answer")
                size -= len; at += len
            }
            UsbWifi.log("$tag: section $i loaded (${HciUsb.le32(fw, map + 16) / 1024} KB)")
        }
        Thread.sleep(110) // firmware activation
    }

    /** btmtk_usb_subsys_reset, MT7921 branch. */
    private fun subsysReset(h: HciUsb) {
        uhwWrite(h, EP_RST_OPT, EP_RST_IN_OUT_OPT)
        uhwRead(h, BT_WDT_STATUS)
        uhwWrite(h, BT_SUBSYS_RST, 1)
        uhwWrite(h, UDMA_INT_STA_BT, 0xFF); uhwRead(h, UDMA_INT_STA_BT)
        uhwWrite(h, UDMA_INT_STA_BT1, 0xFF); uhwRead(h, UDMA_INT_STA_BT1)
        Thread.sleep(20) // MT7921 needs 20 ms between the reset toggles
        uhwWrite(h, BT_SUBSYS_RST, 0); uhwRead(h, BT_SUBSYS_RST)
        val until = System.currentTimeMillis() + 1_000
        while (uhwRead(h, BT_MISC) and BT_RST_DONE == 0) {
            if (System.currentTimeMillis() > until) { UsbWifi.log("BT MT7921AU: subsystem reset timed out"); break }
            Thread.sleep(20)
        }
        if (regRead(h, 0x70010200) == 0) throw IOException("chip ID unreadable after reset")
    }

    /**
     * The rest of btmtk_usb_setup for the 79xx chips: firmware, endpoint reset option, Bluetooth on.
     * Null when it runs, else what failed.
     */
    fun setup(ctx: Context, h: HciUsb, chip: Chip, tag: String): String? {
        val name = chip.firmware ?: return "${chip.name} Bluetooth isn't supported yet"
        val fw = Firmware.read(ctx, name)
        UsbWifi.log("$tag: chip %04x, firmware version %02x, flavour %d: %s".format(chip.devId and 0xffff, chip.fwVersion and 0xff, chip.flavor, name))
        try {
            download(h, fw, tag)
        } catch (e: IOException) {
            if (h.unplugged) throw e
            // Linux: retry once after a reset (btmtk_reset_sync).
            UsbWifi.log("$tag: firmware download failed (${e.message}) - resetting the Bluetooth subsystem and trying again")
            subsysReset(h)
            download(h, fw, tag)
        }
        uhwWrite(h, EP_RST_OPT, EP_RST_IN_OUT_OPT)
        val (flag, status) = wmt(h, WMT_FUNC_CTRL, 0, byteArrayOf(1))
        UsbWifi.log("$tag: Bluetooth function on (flag %d, status %s)".format(flag, status?.let { "0x%03x".format(it) } ?: "none"))
        return null
    }

    /** btmtk_usb_shutdown: Bluetooth function off. */
    fun shutdown(h: HciUsb) {
        runCatching { wmt(h, WMT_FUNC_CTRL, 0, byteArrayOf(0)) }
    }
}
