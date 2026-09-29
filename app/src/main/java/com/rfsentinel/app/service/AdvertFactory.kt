package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.os.Build
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.util.MacUtil
import android.net.wifi.ScanResult as WifiScanResult

/** Converts Android scan results into the platform-neutral [Advert]. */
object AdvertFactory {

    @SuppressLint("MissingPermission") // scan results are only delivered with the scan permission
    fun fromBle(r: ScanResult): Advert? {
        val mac = r.device?.address?.let { MacUtil.normalize(it) } ?: return null
        val rec = r.scanRecord
        val mfg = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { arr ->
            for (i in 0 until arr.size()) arr.valueAt(i)?.let { mfg[arr.keyAt(i)] = it }
        }
        val addressType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            when (r.device.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> AddressType.PUBLIC
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> AddressType.ofRandom(mac)
                else -> AddressType.UNKNOWN
            }
        } else {
            AddressType.UNKNOWN
        }
        val tx = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        val phy = when (r.primaryPhy) {
            1 -> if (r.secondaryPhy == 2) "LE 1M / 2M (extended)" else if (r.isLegacy) "LE 1M (legacy)" else "LE 1M (extended)"
            3 -> "LE Coded (long range)"
            else -> null
        }
        return Advert(
            mac = mac,
            source = Advert.Source.BLE,
            rssi = r.rssi,
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
            wifi = Advert.WifiInfo(r.frequency, r.capabilities.orEmpty(), standard, ies)
        )
    }
}
