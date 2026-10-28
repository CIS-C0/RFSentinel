package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.os.Build
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.util.MacUtil
import android.net.wifi.ScanResult as WifiScanResult

/** Converts Android scan results into the platform-neutral [Advert]. */
object AdvertFactory {

    /** Apple's Bluetooth company ID: iPhones, AirPods, AirTags and Find My items always advertise from random addresses. */
    private const val APPLE = 0x004C

    @SuppressLint("MissingPermission") // scan results are only delivered with the scan permission
    fun fromBle(r: ScanResult): Advert? {
        val mac = r.device?.address?.let { MacUtil.normalize(it) } ?: return null
        val rec = r.scanRecord
        val mfg = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { arr ->
            for (i in 0 until arr.size()) arr.valueAt(i)?.let { mfg[arr.keyAt(i)] = it }
        }
        val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            when (r.device.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> AddressType.PUBLIC
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> AddressType.ofRandom(mac)
                else -> AddressType.UNKNOWN
            }
        } else {
            AddressType.UNKNOWN
        }
        // Not reported (older Android, or the Bluetooth chip didn't say): worked out from the address.
        val addressType = if (reported != AddressType.UNKNOWN) reported
            else AddressType.guess(mac, registered = VendorDb.macVendor(mac) != null, alwaysRandom = APPLE in mfg)
        val tx = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        val phy = when (r.primaryPhy) {
            1 -> if (r.secondaryPhy == 2) "LE 1M / 2M (extended)" else if (r.isLegacy) "LE 1M (legacy)" else "LE 1M (extended)"
            3 -> "LE Coded (long range)"
            else -> null
        }
        return Advert(
            mac = mac,
            source = Advert.Source.BLE,
            rssi = if (r.rssi >= 127) -100 else r.rssi, // 127: no RSSI reported; counted as far rather than +127 dBm
            name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull(),
            manufacturerData = mfg,
            serviceUuids = rec?.serviceUuids?.map { it.uuid }.orEmpty(),
            serviceData = rec?.serviceData?.mapKeys { it.key.uuid }.orEmpty(),
            rawBytes = rec?.bytes,
            txPower = tx,
            addressType = addressType,
            connectable = r.isConnectable,
            phy = phy
        )
    }

    /**
     * An advertising report from a USB Bluetooth controller ([com.rfsentinel.app.usb.UsbBluetooth]): its AD structures
     * read the way Android's ScanRecord reads them, so it looks the same as one the phone's own Bluetooth heard.
     */
    fun fromHci(r: com.rfsentinel.app.usb.UsbBluetooth.Report, now: Long = System.currentTimeMillis()): Advert {
        val mac = r.mac
        val mfg = HashMap<Int, ByteArray>()
        val uuids = ArrayList<java.util.UUID>()
        val serviceData = HashMap<java.util.UUID, ByteArray>()
        var name: String? = null
        var tx: Int? = null
        fun uuidLe(b: ByteArray, at: Int, len: Int): java.util.UUID = when (len) {
            2 -> Advert.uuid16((b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8))
            4 -> java.util.UUID.fromString(String.format("%08x", (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or
                ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)) + "-0000-1000-8000-00805f9b34fb")
            else -> {
                var msb = 0L; var lsb = 0L
                for (i in 0 until 8) lsb = lsb or ((b[at + i].toLong() and 0xff) shl (8 * i))
                for (i in 0 until 8) msb = msb or ((b[at + 8 + i].toLong() and 0xff) shl (8 * i))
                java.util.UUID(msb, lsb)
            }
        }
        for (ad in com.rfsentinel.app.detect.AdStructure.parse(r.data)) {
            val d = ad.data
            when (ad.type) {
                0x02, 0x03 -> for (i in 0..d.size - 2 step 2) uuids += uuidLe(d, i, 2)
                0x04, 0x05 -> for (i in 0..d.size - 4 step 4) uuids += uuidLe(d, i, 4)
                0x06, 0x07 -> for (i in 0..d.size - 16 step 16) uuids += uuidLe(d, i, 16)
                0x08, 0x09 -> if (name == null || ad.type == 0x09) name = String(d, Charsets.UTF_8)
                0x0A -> if (d.isNotEmpty()) tx = d[0].toInt()
                0x16 -> if (d.size >= 2) serviceData[uuidLe(d, 0, 2)] = d.copyOfRange(2, d.size)
                0x20 -> if (d.size >= 4) serviceData[uuidLe(d, 0, 4)] = d.copyOfRange(4, d.size)
                0x21 -> if (d.size >= 16) serviceData[uuidLe(d, 0, 16)] = d.copyOfRange(16, d.size)
                0xFF -> if (d.size >= 2) mfg[(d[0].toInt() and 0xff) or ((d[1].toInt() and 0xff) shl 8)] = d.copyOfRange(2, d.size)
            }
        }
        // 0 public, 1 random, 2 / 3 the same resolved from a private address.
        val reported = when (r.addrType) {
            0, 2 -> AddressType.PUBLIC
            1, 3 -> AddressType.ofRandom(mac)
            else -> AddressType.UNKNOWN
        }
        val addressType = if (reported != AddressType.UNKNOWN) reported
            else AddressType.guess(mac, registered = VendorDb.macVendor(mac) != null, alwaysRandom = APPLE in mfg)
        return Advert(
            mac = mac,
            source = Advert.Source.BLE,
            rssi = if (r.rssi >= 127) -100 else r.rssi,
            name = name,
            manufacturerData = mfg,
            serviceUuids = uuids,
            serviceData = serviceData,
            rawBytes = r.data,
            txPower = tx,
            addressType = addressType,
            connectable = r.connectable,
            phy = r.phy,
            timestamp = now
        )
    }

    fun fromWifi(r: WifiScanResult): Advert? {
        val bssid = r.BSSID ?: return null
        val mac = MacUtil.normalize(bssid)
        @Suppress("DEPRECATION") // wifiSsid replacement is API 33+ and returns a WifiSsid object
        val ssid = r.SSID?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val ies = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            r.informationElements.mapNotNull { ie ->
                val buf = ie.bytes
                val bytes = ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                ie.id to bytes
            }
        } else {
            emptyList()
        }
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (r.wifiStandard) {
                WifiScanResult.WIFI_STANDARD_11N -> "WiFi 4 (802.11n)"
                WifiScanResult.WIFI_STANDARD_11AC -> "WiFi 5 (802.11ac)"
                WifiScanResult.WIFI_STANDARD_11AX -> "WiFi 6 (802.11ax)"
                WifiScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
                WifiScanResult.WIFI_STANDARD_11BE -> "WiFi 7 (802.11be)"
                WifiScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                else -> null
            }
        } else {
            null
        }
        return Advert(
            mac = mac,
            source = Advert.Source.WIFI,
            rssi = r.level,
            name = ssid,
            addressType = AddressType.ofWifi(mac),
            wifi = Advert.WifiInfo(r.frequency, r.capabilities.orEmpty(), standard, ies),
            // When the AP was actually heard, not when the cached result was read.
            timestamp = System.currentTimeMillis() -
                ((android.os.SystemClock.elapsedRealtime() * 1000 - r.timestamp) / 1000).coerceAtLeast(0)
        )
    }
}
