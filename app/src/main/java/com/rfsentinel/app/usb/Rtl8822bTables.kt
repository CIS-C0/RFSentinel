package com.rfsentinel.app.usb

/*
 * RTL8822B (RTL8812BU / RTL8822BU) firmware, power sequences and phydm register
 * tables for receive-only monitor mode over USB.
 *
 * The firmware image and the BB / AGC / RF tables ship as binary assets
 * (assets/usbwifi/rtl8822b_*.bin, little-endian), extracted from devourer
 * (OpenIPC, https://github.com/OpenIPC/devourer, GPL-2.0), which takes them from
 * Realtek's rtl88x2bu Linux driver. The power sequences and the table walker are
 * ported from devourer's src/jaguar2/HalJaguar2.cpp and src/PhyTableLoader.cpp.
 */

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Rtl8822bTables {

    class Data(val firmware: ByteArray, val phyReg: IntArray, val agcTab: IntArray, val radioA: IntArray, val radioB: IntArray)

    fun load(ctx: Context): Data {
        fun bytes(name: String) = ctx.assets.open("usbwifi/rtl8822b_$name.bin").use { it.readBytes() }
        fun words(name: String): IntArray {
            val b = bytes(name)
            if (b.size % 8 != 0) throw IOException("rtl8822b_$name.bin is damaged")
            return IntArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
        }
        val fw = bytes("fw")
        checkFirmware(fw)
        return Data(fw, words("phy_reg"), words("agc_tab"), words("radioa"), words("radiob"))
    }

    // --- Firmware header (HalMAC WLAN_FW_HDR_*) ---
    const val FW_HDR_SIZE = 64
    const val FW_CHKSUM_SIZE = 8

    fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    fun dmemSize(fw: ByteArray) = le32(fw, 36) + FW_CHKSUM_SIZE
    fun imemSize(fw: ByteArray) = le32(fw, 48) + FW_CHKSUM_SIZE
    fun ememSize(fw: ByteArray) = if (fw[24].toInt() and 0x10 != 0) le32(fw, 52) + FW_CHKSUM_SIZE else 0
    fun dmemAddr(fw: ByteArray) = le32(fw, 32) and 0x7fffffff
    fun imemAddr(fw: ByteArray) = le32(fw, 60) and 0x7fffffff
    fun ememAddr(fw: ByteArray) = le32(fw, 56) and 0x7fffffff
    fun version(fw: ByteArray) = "${(fw[4].toInt() and 0xff) or ((fw[5].toInt() and 0xff) shl 8)}.${fw[6].toInt() and 0xff}"

    /** The image's declared section sizes must add up to its length (HalMAC chk_fw_size). */
    fun checkFirmware(fw: ByteArray) {
        if (fw.size < FW_HDR_SIZE || FW_HDR_SIZE + dmemSize(fw) + imemSize(fw) + ememSize(fw) != fw.size)
            throw IOException("RTL8822B firmware image is damaged")
    }

    // --- Power sequences: {offset, command, mask, value} (halmac_pwr_seq_8822b.c, USB + all-interface rows) ---
    const val PW = 0; const val PP = 1; const val PD = 2

    /** card_en_flow_8822b: card-disable -> card-emulation -> active. */
    val PWR_ON = arrayOf(
        intArrayOf(0x004A, PW, 0x01, 0), intArrayOf(0x0005, PW, 0x98, 0),
        intArrayOf(0xFF0A, PW, 0xFF, 0), intArrayOf(0xFF0B, PW, 0xFF, 0),
        intArrayOf(0x0012, PW, 0x02, 0), intArrayOf(0x0012, PW, 0x01, 0x01),
        intArrayOf(0x0020, PW, 0x01, 0x01), intArrayOf(0x0001, PD, 0, 1),
        intArrayOf(0x0000, PW, 0x20, 0), intArrayOf(0x0005, PW, 0x1C, 0),
        intArrayOf(0x0006, PP, 0x02, 0x02), intArrayOf(0xFF1A, PW, 0xFF, 0),
        intArrayOf(0x0006, PW, 0x01, 0x01), intArrayOf(0x0005, PW, 0x80, 0),
        intArrayOf(0x0005, PW, 0x18, 0), intArrayOf(0x10C3, PW, 0x01, 0x01),
        intArrayOf(0x0005, PW, 0x01, 0x01), intArrayOf(0x0005, PP, 0x01, 0),
        intArrayOf(0x0020, PW, 0x08, 0x08),
        intArrayOf(0x10A8, PW, 0xFF, 0), intArrayOf(0x10A9, PW, 0xFF, 0xEF), intArrayOf(0x10AA, PW, 0xFF, 0x0C),
        intArrayOf(0x0029, PW, 0xFF, 0xF9), intArrayOf(0x0024, PW, 0x04, 0),
        intArrayOf(0x00AF, PW, 0x20, 0x20)
    )

    /** card_dis_flow_8822b: active -> card-emulation -> card-disable. */
    val PWR_OFF = arrayOf(
        intArrayOf(0x0093, PW, 0xFF, 0xC4), intArrayOf(0x001F, PW, 0xFF, 0),
        intArrayOf(0x00EF, PW, 0xFF, 0), intArrayOf(0xFF1A, PW, 0xFF, 0x30),
        intArrayOf(0x0049, PW, 0x02, 0), intArrayOf(0x0006, PW, 0x01, 0x01),
        intArrayOf(0x0002, PW, 0x02, 0), intArrayOf(0x10C3, PW, 0x01, 0),
        intArrayOf(0x0005, PW, 0x02, 0x02), intArrayOf(0x0005, PP, 0x02, 0),
        intArrayOf(0x0020, PW, 0x08, 0), intArrayOf(0x0000, PW, 0x20, 0x20),
        intArrayOf(0x0007, PW, 0xFF, 0x20), intArrayOf(0x0067, PW, 0x20, 0),
        intArrayOf(0x004A, PW, 0x01, 0), intArrayOf(0x0081, PW, 0xC0, 0),
        intArrayOf(0x0005, PW, 0x18, 0x08), intArrayOf(0x0090, PW, 0x02, 0)
    )

    // --- phydm table walker (check_positive + IF / ELSE_IF / ELSE / ENDIF blocks) ---

    private const val ITRF_USB = 0x02
    private const val PLATFORM_CE = 0x04

    /** Whether a conditional block's selector [c1] matches this chip's cut and RFE type. */
    fun checkPositive(c1: Int, cut: Int, rfe: Int): Boolean {
        val cutForPara = if (cut == 0) 15 else cut
        val pkgForPara = 15 // package type unknown
        val driver1 = (cutForPara shl 24) or ((ITRF_USB and 0xF0) shl 16) or (PLATFORM_CE shl 16) or
            (pkgForPara shl 12) or ((ITRF_USB and 0x0F) shl 8) or (rfe and 0xff)
        if (c1 and 0x0F000000 != 0 && c1 and 0x0F000000 != driver1 and 0x0F000000) return false
        if (c1 and 0x0000F000 != 0 && c1 and 0x0000F000 != driver1 and 0x0000F000) return false
        if (c1 and 0x00000F00 != 0 && c1 and 0x00000F00 != driver1 and 0x00000F00) return false
        return (c1 and 0xFF) == (driver1 and 0xFF)
    }

    /** Calls [write] for every (address, value) pair of [table] that applies to this chip. */
    inline fun walk(table: IntArray, cut: Int, rfe: Int, write: (Int, Int) -> Unit) {
        var matched = true; var skipped = false; var preV1 = 0
        var i = 0
        while (i + 1 < table.size) {
            val v1 = table[i]; val v2 = table[i + 1]
            if (v1 and 0xC0000000.toInt() != 0) {
                if (v1 < 0) { // positive half: IF / ELSE_IF / ELSE / ENDIF
                    when ((v1 ushr 28) and 3) {
                        3 -> { matched = true; skipped = false }
                        2 -> matched = !skipped
                        else -> preV1 = v1
                    }
                } else { // negative half completes an IF / ELSE_IF
                    if (!skipped) {
                        if (checkPositive(preV1, cut, rfe)) { matched = true; skipped = true } else { matched = false }
                    } else matched = false
                }
            } else if (matched) write(v1, v2)
            i += 2
        }
    }
}
