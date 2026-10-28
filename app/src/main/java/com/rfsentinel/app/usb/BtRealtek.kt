package com.rfsentinel.app.usb

/*
 * Bluetooth bring-up of Realtek USB controllers (RTL8761B dongles, RTL8821AU / RTL8822BU combo
 * adapters and their relatives): btrtl_setup_realtek of the Linux driver (drivers/bluetooth/btrtl.c,
 * GPL-2.0; Copyright (C) 2015 Endless Mobile, Inc.), in the same order: read the chip's subversion
 * (vendor register read 0xFC61), its local version, pick the chip from the same table, read the
 * ROM version (0xFC6D) and the security key ID, pick the patch for that ROM out of the "Realtech"
 * epatch file, append the config file and send it in 252-byte fragments (0xFC20). Only the v1
 * epatch format is handled: chips whose firmware is the newer "RTBTCore" format (RTL8822CU,
 * RTL8852 / 8851 family) are named and left alone.
 */

import android.content.Context
import java.io.IOException

internal object BtRealtek {

    private const val LMP_8723A = 0x1200; private const val LMP_8723B = 0x8723; private const val LMP_8821A = 0x8821
    private const val LMP_8761A = 0x8761; private const val LMP_8822B = 0x8822; private const val LMP_8852A = 0x8852
    private const val LMP_8851B = 0x8851; private const val LMP_8922A = 0x8922
    private const val FRAG = 252

    /** ic_id_table, USB rows: subversion, HCI revision, HCI version (0 = any), firmware, config, config required. */
    class Ic(val lmpSubver: Int, val hciRev: Int, val hciVer: Int, val name: String, val fw: String?, val cfg: String?, val cfgNeeded: Boolean)

    // fw / cfg: this app's file names (assets/usbwifi, or Firmware's download), null when not handled here.
    private val TABLE = listOf(
        Ic(LMP_8723A, 0xb, 0x6, "RTL8723AU", null, null, false),
        Ic(LMP_8723B, 0xb, 0x6, "RTL8723BU", "bt_rtl8723b_fw.bin", null, false),
        Ic(LMP_8723B, 0xd, 0x8, "RTL8723DU", null, null, true),
        Ic(LMP_8821A, 0xa, 0x6, "RTL8821AU", "bt_rtl8821a_fw.bin", null, false),
        Ic(LMP_8821A, 0xc, 0x8, "RTL8821CU", "bt_rtl8821c_fw.bin", "bt_rtl8821c_config.bin", false),
        Ic(LMP_8761A, 0xa, 0x6, "RTL8761AU", "bt_rtl8761a_fw.bin", null, false),
        Ic(LMP_8761A, 0xb, 0xa, "RTL8761BU", "bt_rtl8761bu_fw.bin", "bt_rtl8761bu_config.bin", false),
        Ic(LMP_8761A, 0x0e, 0, "RTL8761CU", "bt_rtl8761cu_fw.bin", "bt_rtl8761cu_config.bin", false),
        Ic(LMP_8822B, 0xc, 0xa, "RTL8822CU", null, null, false),
        Ic(LMP_8822B, 0xb, 0x7, "RTL8822BU", "bt_rtl8822b_fw.bin", "bt_rtl8822b_config.bin", true),
        Ic(LMP_8852A, 0xa, 0xb, "RTL8852AU", null, null, false),
        Ic(LMP_8852A, 0xb, 0xb, "RTL8852BU", null, null, false),
        Ic(LMP_8852A, 0xc, 0xc, "RTL8852CU", null, null, false),
        Ic(LMP_8851B, 0xb, 0xc, "RTL8851BU", null, null, false),
        Ic(LMP_8922A, 0xa, 0xc, "RTL8922AU", null, null, false),
        Ic(LMP_8852A, 0x87, 0xc, "RTL8852BTU", null, null, false)
    )

    /** btrtl_match_ic for the USB bus. */
    internal fun match(lmpSubver: Int, hciRev: Int, hciVer: Int): Ic? =
        TABLE.firstOrNull { it.lmpSubver == lmpSubver && it.hciRev == hciRev && (it.hciVer == hciVer || it.hciVer == 0) }

    /** What [identify] found: the chip (null: not in the table) and its ROM version. */
    class Found(val ic: Ic?, val romVersion: Int, val keyId: Int, val lmpSubver: Int, val hciRev: Int, val hciVer: Int) {
        val name get() = ic?.name ?: "Realtek %04x".format(lmpSubver)
        /** The files to load; empty when no upload is needed or this chip isn't handled. */
        val files get() = listOfNotNull(ic?.fw, ic?.cfg?.takeIf { keyId == 0 })
    }

    /** btrtl_vendor_read_reg16: 0xFC61 answers status + 2 bytes. */
    private fun reg16(h: HciUsb, cmd: ByteArray): Int {
        val r = h.cmd(0xFC61, cmd)
        if (r.size != 3 || r[0].toInt() != 0) throw IOException("vendor register read failed")
        return HciUsb.le16(r, 1)
    }

    private val CHIP_SUBVER = byteArrayOf(0x10, 0x38, 0x04, 0x28, 0x80.toByte())
    private val CHIP_REV = byteArrayOf(0x10, 0x3A, 0x04, 0x28, 0x80.toByte())
    private val SEC_PROJ = byteArrayOf(0x10, 0xA4.toByte(), 0xAD.toByte(), 0x00, 0xB0.toByte())

    /** btrtl_initialize, up to the firmware file names. */
    fun identify(h: HciUsb, tag: String): Found {
        var dropped = false
        while (true) {
            var lmpSubver = reg16(h, CHIP_SUBVER)
            var hciRev: Int; var hciVer: Int
            var ic: Ic? = null
            var e8822 = false
            if (lmpSubver == LMP_8822B) {
                hciRev = reg16(h, CHIP_REV)
                if (hciRev == 0x000e) { hciVer = 0x0c; e8822 = true } // RTL8822E: not handled here
                else hciVer = 0
            } else { hciRev = 0; hciVer = 0 }
            if (!e8822) {
                val v = h.cmd(0x1001) // HCI_OP_READ_LOCAL_VERSION
                if (v.size != 9) throw IOException("local version answer has the wrong length")
                hciVer = v[1].toInt() and 0xff
                hciRev = HciUsb.le16(v, 2)
                lmpSubver = HciUsb.le16(v, 7)
                ic = match(lmpSubver, hciRev, hciVer)
            }
            UsbWifi.log("$tag: examining hci_ver=%02x hci_rev=%04x lmp_subver=%04x".format(hciVer, hciRev, lmpSubver))
            // Not in the table: the controller may be running a stock firmware; ask it to drop it (0xFC66) and look again, once.
            if (ic == null && !dropped) {
                dropped = true
                h.send(0xFC66)
                Thread.sleep(200)
                continue
            }
            if (ic == null) return Found(null, 0, 0, lmpSubver, hciRev, hciVer)
            var rom = 0
            if (ic.lmpSubver != LMP_8723A) { // has_rom_version on every USB chip but the 8723A
                val r = h.cmd(0xFC6D)
                if (r.size != 2) throw IOException("ROM version answer has the wrong length")
                rom = r[1].toInt() and 0xff
                UsbWifi.log("$tag: rom_version status=%x version=%x".format(r[0].toInt() and 0xff, rom))
            }
            val keyId = reg16(h, SEC_PROJ) and 0xff
            UsbWifi.log("$tag: key id $keyId")
            return Found(ic, rom, keyId, lmpSubver, hciRev, hciVer)
        }
    }

    /** project_id_to_lmp_subver. */
    private val PROJECTS = mapOf(0 to LMP_8723A, 1 to LMP_8723B, 2 to LMP_8821A, 3 to LMP_8761A, 7 to 0x8703, 8 to LMP_8822B,
        9 to LMP_8723B, 10 to LMP_8821A, 13 to LMP_8822B, 14 to LMP_8761A, 18 to LMP_8852A, 20 to LMP_8852A, 25 to LMP_8852A,
        36 to LMP_8851B, 44 to LMP_8922A, 47 to LMP_8852A, 51 to LMP_8761A)

    /** rtlbt_parse_firmware for the v1 "Realtech" epatch: the patch for [romVersion], with the file's version at its end. */
    internal fun parse(fw: ByteArray, lmpSubver: Int, romVersion: Int): ByteArray {
        val ext = byteArrayOf(0x51, 0x04, 0xfd.toByte(), 0x77)
        val sig = String(fw, 0, minOf(8, fw.size), Charsets.US_ASCII)
        if (sig == "RTBTCore") throw IOException("this chip's firmware is the newer RTBTCore format, not supported yet")
        if (sig != "Realtech") throw IOException("bad epatch signature")
        val headerLen = 14 // signature 8, fw_version 4, num_patches 2
        if (fw.size < headerLen + ext.size + 3) throw IOException("firmware too small")
        var p = fw.size - ext.size
        if (!fw.copyOfRange(p, fw.size).contentEquals(ext)) throw IOException("extension signature mismatch")
        // Walk the instructions backwards from the end to the one naming the project.
        var project = -1
        while (p >= headerLen + 3) {
            val opcode = fw[--p].toInt() and 0xff
            val length = fw[--p].toInt() and 0xff
            val data = fw[--p].toInt() and 0xff
            if (opcode == 0xff) break
            if (length == 0) throw IOException("firmware instruction with length 0")
            if (opcode == 0 && length == 1) { project = data; break }
            p -= length
        }
        if (project < 0) throw IOException("firmware has no version instruction")
        val forLmp = PROJECTS[project] ?: throw IOException("unknown firmware project $project")
        if (forLmp != lmpSubver) throw IOException("firmware is for %x but this is a %x".format(forLmp, lmpSubver))
        val patches = HciUsb.le16(fw, 12)
        if (fw.size < headerLen + ext.size + 3 + 8 * patches) throw IOException("firmware patch table truncated")
        val ids = headerLen
        val lengths = ids + 2 * patches
        val offsets = lengths + 2 * patches
        var length = 0; var offset = 0
        for (i in 0 until patches) {
            if (HciUsb.le16(fw, ids + 2 * i) == romVersion + 1) {
                length = HciUsb.le16(fw, lengths + 2 * i)
                offset = HciUsb.le32(fw, offsets + 4 * i)
                break
            }
        }
        if (offset == 0) throw IOException("no patch for ROM version $romVersion")
        if (length < 4 || offset < 0 || offset > fw.size || length > fw.size - offset) throw IOException("patch out of bounds")
        val out = ByteArray(length)
        fw.copyInto(out, 0, offset, offset + length - 4)
        fw.copyInto(out, length - 4, 8, 12) // the file's fw_version
        return out
    }

    /** rtl_download_firmware: 252-byte fragments, index 0..0x7f then from 1 again, the last one with bit 7 set. */
    private fun send(h: HciUsb, data: ByteArray, tag: String) {
        val frags = data.size / FRAG + 1
        var j = 0
        var at = 0
        for (i in 0 until frags) {
            var index = j++
            if (index == 0x7f) j = 1
            var len = FRAG
            if (i == frags - 1) { index = index or 0x80; len = data.size % FRAG }
            val cmd = ByteArray(1 + len)
            cmd[0] = index.toByte()
            data.copyInto(cmd, 1, at, at + len)
            val r = h.cmd(0xFC20, cmd)
            if (r.size != 2) throw IOException("download answer has the wrong length")
            at += FRAG
        }
        val v = h.cmd(0x1001)
        if (v.size == 9) UsbWifi.log("$tag: fw version 0x%04x%04x".format(HciUsb.le16(v, 2), HciUsb.le16(v, 7)))
    }

    /** btrtl_download_firmware (8723B-style chips). Null when it runs, else what failed. */
    fun setup(ctx: Context, h: HciUsb, f: Found, tag: String): String? {
        val ic = f.ic ?: run { UsbWifi.log("$tag: unknown chip, assuming no firmware upload needed"); return null }
        val fwName = ic.fw ?: return "${ic.name} Bluetooth isn't supported yet"
        val patch = parse(Firmware.read(ctx, fwName), ic.lmpSubver, f.romVersion)
        val cfg = ic.cfg?.takeIf { f.keyId == 0 }?.let { runCatching { Firmware.read(ctx, it) }.getOrNull() }
        if (ic.cfgNeeded && cfg == null) return "${ic.name}: its config file is missing"
        val all = if (cfg != null) patch + cfg else patch
        UsbWifi.log("$tag: cfg_sz ${cfg?.size ?: 0}, total sz ${all.size}")
        send(h, all, tag)
        return null
    }
}
