package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * MT7921AU (WiFi 6E) driver, ported from mt76-usb-macos. The command frames are checked byte for
 * byte against that project's golden frames (tests/golden_mt7921_frames.json), which were recorded
 * from its implementation validated on an ALFA AWUS036AXML.
 */
class Mt7921uDriverTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun le32(b: ByteArray, at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v ushr (8 * k)).toByte() }
    private fun word(v: Int) = ByteArray(4).also { le32(it, 0, v) }
    private val m = Mt7921uMonitor

    @Test
    fun adaptersRouteToTheDriver() {
        assertEquals(UsbWifi.Driver.MT7921U, UsbWifi.chipOf(0x0e8d, 0x7961)?.driver) // ALFA AWUS036AXML
        assertEquals(UsbWifi.Driver.MT7921U, UsbWifi.chipOf(0x35bc, 0x0107)?.driver) // TP-Link TXE50UH
        assertNull("MT7902 needs other firmware", UsbWifi.chipOf(0x0e8d, 0x7902))
    }

    /** mt76-usb-macos tests/golden_mt7921_frames.json (endpoint 8: commands). */
    private val golden = mapOf(
        "patch_sem_get" to "4400000044000041000001800000000000000000000000000000000000000000000000002400008010a003010000000000000000000000000000000000000000000000000100000000000000",
        "init_download_patch" to "4c0000004c000041000001800000000000000000000000000000000000000000000000002c00008005a0030100000000000000000000000000000000000000000000000000009000001000000100008000000000",
        "init_download_ram" to "4c0000004c000041000001800000000000000000000000000000000000000000000000002c00008001a0030100000000000000000000000000000000000000000000000000002000002000000000008000000000",
        "start_patch" to "4400000044000041000001800000000000000000000000000000000000000000000000002400008007a003010000000000000000000000000000000000000000000000000000000000000000",
        "start_firmware" to "4800000048000041000001800000000000000000000000000000000000000000000000002800008002a00301000000000000000000000000000000000000000000000000010000000000000000000000",
        "nic_power_ctrl" to "4400000044000041000001800000000000000000000000000000000000000000000000002400008004a003010000000000000000000000000000000000000000000000000100000000000000",
        "get_nic_capability" to "400000004000004100000180000000000000000000000000000000000000000000000000200000808aa0010100000000000000000000000000000000000000000000000000000000",
        "set_eeprom" to "44000000440000410000018000000000000000000000000000000000000000000000000024000080eda001010021000100000000000000000000000000000000000000000001000000000000",
        "set_sniffer_on" to "3c0000003c000041000001800000000000000000000000000000000000000000000000001c00240000a00001000000070000000000000000000008000100000000000000",
        "set_chan_info_6g_53_20" to "8c0000008c000041000001800000000000000000000000000000000000000000000000006c000080eda001010008000100000000000000000000000000000000000000003535000202000000000002000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "config_sniffer_6g_53_20" to "4400000044000041000001800000000000000000000000000000000000000000000000002400240000a000010000000700000000000000000100100000000300350035000100000000000000",
        "set_monitor_mode_1" to "840000008400004100000180000000000000000000000000000000000000000000000000640000800aa00101000000000000000000000000000000000000000000000000000000000100000064000080000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "set_monitor_mode_2" to "840000008400004100000180000000000000000000000000000000000000000000000000640000800aa00102000000000000000000000000000000000000000000000000000000000200000000000000f92f2f000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    )

    // --- command frames: the golden ones ---

    @Test
    fun patchCommandsMatchTheGoldenFrames() {
        assertEquals(golden["patch_sem_get"], hex(m.cmdFrame(m.CMD_PATCH_SEM_CONTROL, word(1), 1)))
        assertEquals(golden["init_download_patch"], hex(m.cmdFrame(m.CMD_PATCH_START_REQ, word(0x900000) + word(0x1000) + word(0x80000001.toInt()), 1)))
        assertEquals(golden["init_download_ram"], hex(m.cmdFrame(m.CMD_TARGET_ADDRESS_LEN_REQ, word(0x200000) + word(0x2000) + word(0x80000000.toInt()), 1)))
        assertEquals(golden["start_patch"], hex(m.cmdFrame(m.CMD_PATCH_FINISH_REQ, ByteArray(4), 1)))
        assertEquals(golden["start_firmware"], hex(m.cmdFrame(m.CMD_FW_START_REQ, word(1) + word(0), 1)))
        assertEquals(golden["nic_power_ctrl"], hex(m.cmdFrame(m.CMD_NIC_POWER_CTRL, byteArrayOf(1, 0, 0, 0), 1)))
    }

    @Test
    fun firmwareChunksMatchTheGoldenFrames() {
        // send_firmware(bytes(range(256)) * 20): a 4096-byte chunk, then the 1024-byte rest.
        val data = ByteArray(5120) { it.toByte() }
        val first = m.scatterFrame(data, 0, 4096)
        assertEquals(4 + 4096 + 4, first.size)
        assertEquals("00100000", hex(first.copyOfRange(0, 4)))
        assertEquals(hex(data.copyOfRange(0, 4096)), hex(first.copyOfRange(4, 4100)))
        assertEquals("00000000", hex(first.copyOfRange(4100, 4104)))
        val rest = m.scatterFrame(data, 4096, 1024)
        assertEquals("00040000", hex(rest.copyOfRange(0, 4)))
        assertEquals(4 + 1024 + 4, rest.size)
    }

    @Test
    fun setupCommandsMatchTheGoldenFrames() {
        assertEquals(golden["get_nic_capability"], hex(m.cmdFrame(m.ceCmd(m.CE_GET_NIC_CAPAB), ByteArray(0), 1)))
        assertEquals(golden["set_eeprom"], hex(m.cmdFrame(m.extCmd(m.EXT_EFUSE_BUFFER_MODE), byteArrayOf(0, 1, 0, 0), 1)))
        // set_monitor_mode: accept everything, then clear the drop bits (seq 1, 2).
        assertEquals(golden["set_monitor_mode_1"], hex(m.cmdFrame(m.ceCmd(m.CE_SET_RX_FILTER), m.rxFilterPayload(m.MONITOR_FILTER, 0, 0), 1)))
        assertEquals(golden["set_monitor_mode_2"], hex(m.cmdFrame(m.ceCmd(m.CE_SET_RX_FILTER), m.rxFilterPayload(0, m.FIF_BIT_CLR, m.MONITOR_DROP_CLEAR), 2)))
        assertEquals(golden["set_sniffer_on"], hex(m.uniFrame(m.UNI_SNIFFER, m.snifferEnablePayload(true), 1)))
    }

    @Test
    fun tuningCommandsMatchTheGoldenFrames() {
        // the golden frame with the RX antenna mask set to both antennas (0x3), as Linux sends it
        assertEquals(golden["set_chan_info_6g_53_20"]!!.replace("353500020200", "353500020300"), hex(m.cmdFrame(m.extCmd(m.EXT_CHANNEL_SWITCH), m.chanInfoPayload(2, 53), 1)))
        assertEquals(golden["config_sniffer_6g_53_20"], hex(m.uniFrame(m.UNI_SNIFFER, m.snifferConfigPayload(2, 53), 1)))
    }

    // --- receive descriptors ---

    private fun beacon(): ByteArray = ByteArray(40).also {
        it[0] = 0x80.toByte() // beacon
        for (k in 0 until 6) { it[4 + k] = 0xff.toByte(); it[10 + k] = (0x02 + k).toByte(); it[16 + k] = (0x02 + k).toByte() }
    }

    /** | RXD (24) | group 3 (8) | header offset | frame |, as the chip sends it. */
    private fun rxTransfer(frame: ByteArray, chfreq: Int, rcpi: IntArray = intArrayOf(120, 0, 0, 0), fcsErr: Boolean = false,
                           hdrOffset: Int = 0, type: Int = 2, flag: Int = 0, trailing: Int = 7): ByteArray {
        val len = 24 + 8 + 2 * hdrOffset + frame.size
        val b = ByteArray(len + trailing) // the transfer runs past the descriptor's own length
        le32(b, 0, len or (flag shl 16) or (type shl 27))
        le32(b, 4, (1 shl 13) or (if (fcsErr) 1 shl 27 else 0)) // group 3
        le32(b, 8, hdrOffset shl 14)
        le32(b, 12, chfreq shl 8)
        le32(b, 24, 0)
        le32(b, 28, rcpi[0] or (rcpi[1] shl 8) or (rcpi[2] shl 16) or (rcpi[3] shl 24))
        System.arraycopy(frame, 0, b, 24 + 8 + 2 * hdrOffset, frame.size)
        return b
    }

    @Test
    fun framesSignalAndChannelFromTheDescriptor() {
        val f = beacon()
        val t = rxTransfer(f, chfreq = 6, rcpi = intArrayOf(120, 0, 100, 0))
        val rx = m.parseRx(t, t.size)!!
        assertEquals(32, rx.start)
        assertEquals("ends at the descriptor's length, not the transfer's", 32 + f.size, rx.end)
        assertEquals("strongest antenna (RCPI / 2 - 110), silent ones left out", -50, rx.rssi)
        assertEquals(0, rx.band); assertEquals(6, rx.channel)
        assertEquals("antenna check: chain 0 on its own", -50, rx.chain0)
        assertNull("chain 1 heard nothing", rx.chain1)
        val two = rxTransfer(f, chfreq = 6, rcpi = intArrayOf(120, 110, 0, 0))
        val r2 = m.parseRx(two, two.size)!!
        assertEquals(-50, r2.chain0); assertEquals(-55, r2.chain1); assertEquals(-50, r2.rssi)

        val five = m.parseRx(rxTransfer(f, chfreq = 149).let { it }, rxTransfer(f, chfreq = 149).size)!!
        assertEquals(1, five.band); assertEquals(149, five.channel)
        // 6 GHz: the descriptor's channel code above 180, (code - 181) x 4 + 1.
        val six = rxTransfer(f, chfreq = 181 + 9)
        val r6 = m.parseRx(six, six.size)!!
        assertEquals(2, r6.band); assertEquals(37, r6.channel)
        assertEquals(6135, m.monitorChannel(r6.band, r6.channel))
        assertEquals(6135, MonitorFrames.frequencyOf(6135))
        assertEquals(149, m.monitorChannel(1, 149))

        val padded = rxTransfer(f, chfreq = 1, hdrOffset = 1)
        assertEquals(34, m.parseRx(padded, padded.size)!!.start)
        val noSignal = rxTransfer(f, chfreq = 1, rcpi = intArrayOf(0, 0, 0, 0))
        assertNull(m.parseRx(noSignal, noSignal.size)!!.rssi)
    }

    @Test
    fun badChecksumsAndFirmwareAnswersAreNotFrames() {
        var crc = false
        val bad = rxTransfer(beacon(), chfreq = 6, fcsErr = true)
        assertNull(m.parseRx(bad, bad.size, onBad = { crc = it }))
        assertTrue(crc)

        // A firmware answer (event) carries the command's sequence number at byte 29.
        val ev = ByteArray(64).also { le32(it, 0, 64 or (7 shl 27)); it[29] = 5 }
        assertEquals(5, m.eventSeq(ev, ev.size))
        assertNull(m.parseRx(ev, ev.size))
        // ... but an event flagged "normal MCU" is a received frame.
        val mcuFrame = rxTransfer(beacon(), chfreq = 6, type = 7, flag = 1)
        assertNull(m.eventSeq(mcuFrame, mcuFrame.size))
        assertNotNull(m.parseRx(mcuFrame, mcuFrame.size))
        assertNull("too short", m.parseRx(ByteArray(20), 20))
    }

    @Test
    fun hopsCoverAllThreeBands() {
        assertEquals(13 + 25 + 15, m.HOP.size)
        assertEquals("6 GHz: the preferred scanning channels", (0 until 15).map { 5 + 16 * it }, m.HOP.filter { it.first == 2 }.map { it.second })
    }

    @Test
    fun sixGhzRotatesUnlessSomethingWasHeardThere() {
        val rounds = (0 until 3).map { r -> m.roundPlan(r, emptySet()) }
        for (plan in rounds) {
            assertEquals("all of 2.4 and 5 GHz every round", 13 + 25, plan.count { it.first != 2 })
            assertEquals("a third of 6 GHz per round", 5, plan.count { it.first == 2 })
        }
        assertEquals("every 6 GHz channel within 3 rounds", m.HOP.filter { it.first == 2 }.toSet(),
            rounds.flatten().filter { it.first == 2 }.toSet())
        // ch 37 had traffic: it's in every round
        for (r in 0 until 3) assertTrue(2 to 37 in m.roundPlan(r, setOf(37)))
    }

    // --- firmware: the official linux-firmware images, unmodified ---

    private val dir = "src/main/assets/usbwifi"
    private fun sha256(path: String) = MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun firmwareImagesAreTheLinuxFirmwareOnes() {
        // linux-firmware e981caea6ed33c48d25b7dbf473327dbd01df163, the hashes mt76-usb-macos pins.
        assertEquals("a276c06c2b772adb50b86639d33c82824ff4c21d617feb78caea74c040b873f6", sha256("$dir/WIFI_MT7961_patch_mcu_1_2_hdr.bin"))
        assertEquals("b94217a951518a9c14095765f367bc5dd7698f2dc033941d6f18fc2ebd6a2ab9", sha256("$dir/WIFI_RAM_CODE_MT7961_1.bin"))
        assertTrue(File("$dir/LICENCE.mediatek").readText().contains("MediaTek Inc. grants permission"))
        assertTrue("the driver's licence ships in the app", File("$dir/LICENCE.mt76-usb-macos").readText().contains("The Clear BSD License"))
    }

    @Test
    fun firmwareFilesParse() {
        val patch = File("$dir/WIFI_MT7961_patch_mcu_1_2_hdr.bin").readBytes()
        val secs = m.parsePatch(patch)!!
        assertTrue(secs.isNotEmpty())
        assertTrue("every section is a patch info section", secs.all { it.type and 0xffff == 2 })
        val ram = File("$dir/WIFI_RAM_CODE_MT7961_1.bin").readBytes()
        val regions = m.parseRam(ram)!!
        assertTrue(regions.isNotEmpty())
        assertTrue(regions.sumOf { it.len.toLong() } <= ram.size)
        assertNull("truncated", m.parseRam(ram.copyOf(20)))
        assertNull("truncated", m.parsePatch(patch.copyOf(100)))
    }

    @Test
    fun downloadModes() {
        assertEquals("plain patch section", 1 shl 31, m.patchDataMode(0))
        assertEquals("no security info", 1 shl 31, m.patchDataMode(-1))
        assertEquals("AES, key 1", (1 shl 31) or 1 or 2 or 8, m.patchDataMode((1 shl 24) or 1))
        assertEquals("encrypted region, key index 2", (1 shl 31) or 1 or 8 or 4, m.ramDataMode(1 or 4))
    }
}
