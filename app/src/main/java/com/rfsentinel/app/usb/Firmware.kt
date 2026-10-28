package com.rfsentinel.app.usb

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The USB WiFi and Bluetooth chips' own firmware. The full build carries it in assets/usbwifi. The FOSS build leaves out the
 * firmware that isn't free software (Ralink, MediaTek and Realtek binaries) and, with your OK, downloads those exact
 * files once from linux-firmware on kernel.org, pinned to one commit and checked against their SHA-256, into the
 * app's own storage. The free ath9k_htc firmware and the Realtek register tables (GPL-2.0) ship in both builds.
 */
object Firmware {

    /** One file: its name in assets/usbwifi, its path in linux-firmware, its SHA-256 and its maker's licence there. */
    data class Blob(val name: String, val path: String, val sha256: String, val size: Int, val licence: String)

    /** The linux-firmware commit every download comes from: the very files the full build carries. */
    const val COMMIT = "e981caea6ed33c48d25b7dbf473327dbd01df163"
    private const val REPO = "https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git"

    private val RT2870 = Blob("rt2870.bin", "rt2870.bin",
        "251b8918391eac6415d60dca239e415aad0177e885376f2a17782e64fcbbe317", 8192, "LICENCE.ralink-firmware.txt")
    private val MT7662_PATCH = Blob("mt7662_rom_patch.bin", "mediatek/mt7662_rom_patch.bin",
        "6f0e871268f6e4d99196d90d89bcc09fe493d010366260a2cfcaa5dd66095f8c", 26350, "LICENCE.ralink_a_mediatek_company_firmware")
    private val MT7662 = Blob("mt7662.bin", "mediatek/mt7662.bin",
        "f7e52492f58088cae50e51a54cca68e4abc5b74f7d0b6b731dbb4c04465a94b6", 81908, "LICENCE.ralink_a_mediatek_company_firmware")
    private val MT7961_PATCH = Blob("WIFI_MT7961_patch_mcu_1_2_hdr.bin", "mediatek/WIFI_MT7961_patch_mcu_1_2_hdr.bin",
        "a276c06c2b772adb50b86639d33c82824ff4c21d617feb78caea74c040b873f6", 92192, "LICENCE.mediatek")
    private val MT7961_RAM = Blob("WIFI_RAM_CODE_MT7961_1.bin", "mediatek/WIFI_RAM_CODE_MT7961_1.bin",
        "b94217a951518a9c14095765f367bc5dd7698f2dc033941d6f18fc2ebd6a2ab9", 792036, "LICENCE.mediatek")
    private val RTL8822B = Blob("rtl8822b_fw.bin", "rtw88/rtw8822b_fw.bin",
        "a72da690597bfa99d8eb6fc2ab090d18d8ad92ac2befd35db1c9e3662d8d8418", 161240, "LICENCE.rtlwifi_firmware.txt")

    /** USB Bluetooth controllers' firmware ([BtMediatek], [BtRealtek]): Realtek's renamed bt_*, as two chips share a name with WiFi files. */
    private val BLUETOOTH = listOf(
        Blob("BT_RAM_CODE_MT7961_1_2_hdr.bin", "mediatek/BT_RAM_CODE_MT7961_1_2_hdr.bin",
            "7a440a0f7404c73a2f03d8cc0475ac9f7fdc931b494dce37705e7d578153dbae", 533870, "LICENCE.mediatek"),
        Blob("bt_rtl8723b_fw.bin", "rtl_bt/rtl8723b_fw.bin",
            "c9c8a9f6ebd90b7449a7e4d38ee39952dc7cc47d6c1d054e42cfbb5e4d818853", 45048, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8761a_fw.bin", "rtl_bt/rtl8761a_fw.bin",
            "581e5e81f5b51a29f475f8a276be95ad3210b3e3065987541c508e7f7a83a462", 74488, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8761bu_fw.bin", "rtl_bt/rtl8761bu_fw.bin",
            "1d7a9597349ad89344fa16c1913d3e39e9a12e966e417ca16871bc79bbe59edb", 44484, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8761bu_config.bin", "rtl_bt/rtl8761bu_config.bin",
            "6c28a3f07c6a30ed208c4b64862a23f02b7d93543ea980edd24df16bab45095f", 6, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8761cu_fw.bin", "rtl_bt/rtl8761cu_fw.bin",
            "525bfa5ab9a3ed74b788f9ac494467c522a436c1bc5290ebcb05c7f920533c09", 10296, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8761cu_config.bin", "rtl_bt/rtl8761cu_config.bin",
            "3142c2732470e4edcdb68edf58cc505b581e38d56b3ec09c64eddcb1d5099507", 11, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8821a_fw.bin", "rtl_bt/rtl8821a_fw.bin",
            "3ed5077f8f02ff2b06be257691dc72061efac976cd2dc174057833624c820b9d", 37420, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8821c_fw.bin", "rtl_bt/rtl8821c_fw.bin",
            "dfe7ef772d078ec09490c02190a32fcd40b3eaf3b040049d8930eb80e6d5743c", 54636, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8821c_config.bin", "rtl_bt/rtl8821c_config.bin",
            "292717b0610ce920f66b734c299d349ea59a4c4400044078d8fc65625f17e382", 10, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8822b_fw.bin", "rtl_bt/rtl8822b_fw.bin",
            "eecef2c3b2ff03c3a41292a93a25eca6f4ec38eb4a7491a3651b530be89a04fc", 51176, "LICENCE.rtlwifi_firmware.txt"),
        Blob("bt_rtl8822b_config.bin", "rtl_bt/rtl8822b_config.bin",
            "ea082136d5285655372e3805cd50d961bd7d42085c6fc1058a115a7e844df9c1", 14, "LICENCE.rtlwifi_firmware.txt")
    )

    /** Every file the FOSS build may download. */
    val ALL = listOf(RT2870, MT7662_PATCH, MT7662, MT7961_PATCH, MT7961_RAM, RTL8822B) + BLUETOOTH

    /** The non-free files a driver needs (none for the drivers that load no firmware or only free files). */
    fun needed(driver: UsbWifi.Driver): List<Blob> = when (driver) {
        UsbWifi.Driver.RT3070 -> listOf(RT2870)
        UsbWifi.Driver.MT7612U -> listOf(MT7662_PATCH, MT7662)
        UsbWifi.Driver.MT7921U -> listOf(MT7961_PATCH, MT7961_RAM)
        UsbWifi.Driver.RTL88X2BU -> listOf(RTL8822B)
        else -> emptyList()
    }

    fun url(b: Blob) = "$REPO/plain/${b.path}?id=$COMMIT"
    fun licenceUrl(b: Blob) = "$REPO/plain/LICENSES/${b.licence}?id=$COMMIT"

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "firmware")

    private fun inAssets(context: Context, name: String) =
        runCatching { context.assets.open("usbwifi/$name").close(); true }.getOrDefault(false)

    /** The files of [driver] that are neither in this APK nor downloaded yet. */
    fun missing(context: Context, driver: UsbWifi.Driver): List<Blob> =
        needed(driver).filter { !inAssets(context, it.name) && !File(dir(context), it.name).isFile }

    /** Of the files named [names] (a USB Bluetooth controller's), those neither in this APK nor downloaded yet. */
    fun missingFiles(context: Context, names: List<String>): List<Blob> =
        ALL.filter { it.name in names && !inAssets(context, it.name) && !File(dir(context), it.name).isFile }

    /** A file from assets/usbwifi, or one downloaded earlier (checked again before use). */
    fun read(context: Context, name: String): ByteArray {
        runCatching { return context.assets.open("usbwifi/$name").use { it.readBytes() } }
        val blob = ALL.firstOrNull { it.name == name } ?: throw IOException("Missing firmware $name")
        val f = File(dir(context), name)
        if (!f.isFile) throw IOException("$name isn't downloaded yet")
        val bytes = f.readBytes()
        if (sha256(bytes) != blob.sha256) { f.delete(); throw IOException("$name is damaged: download it again") }
        return bytes
    }

    /** Downloads [blobs] that are missing; each is checked against its SHA-256 before it's kept. Blocking. */
    fun download(context: Context, blobs: List<Blob>) {
        val d = dir(context).apply { mkdirs() }
        for (b in blobs) {
            val target = File(d, b.name)
            if (target.isFile) continue
            val c = URL(url(b)).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "RFSentinel")
            val bytes = try {
                if (c.responseCode != 200) throw IOException("kernel.org answered ${c.responseCode} for ${b.name}")
                c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(b.size)
                    val buf = ByteArray(16_384)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > b.size) throw IOException("${b.name} is larger than expected")
                    }
                    out.toByteArray()
                }
            } finally { c.disconnect() }
            if (sha256(bytes) != b.sha256) throw IOException("${b.name} doesn't match its fingerprint: not kept")
            val part = File(d, b.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) { part.delete(); throw IOException("Couldn't save ${b.name}") }
        }
    }

    /** Deletes the downloaded firmware (Settings). */
    fun clear(context: Context) { dir(context).deleteRecursively() }

    internal fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
