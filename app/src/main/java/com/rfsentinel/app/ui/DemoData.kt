package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.EvidenceFusion
import com.rfsentinel.app.detect.RemoteId
import com.rfsentinel.app.detect.SignatureEngine
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY: fills the live list with made-up devices so screenshots
 * show what real detections look like. Every address suffix and name is
 * invented, there is no location data, and the adverts go through the real
 * detection pipeline (signatures, watchlist, fusion, patrol-cluster).
 * Started with: am start -n com.rfsentinel.app/.MainActivity --ez demo true
 */
object DemoData {

    private const val DEMO_DRONE = "60:60:1F:A2:44:18"

    /** A drone circling ~120 m from [anchor], with its operator ~80 m the other way. */
    private fun demoRemoteId(anchor: Pair<Double, Double>, secondsAgo: Int): RemoteId.Info {
        val angle = Math.toRadians(135.0 + secondsAgo * 2.0)
        val dLat = 0.00108 * kotlin.math.cos(angle)   // ~120 m
        val dLon = 0.00108 * kotlin.math.sin(angle) / kotlin.math.cos(Math.toRadians(anchor.first))
        return RemoteId.Info(
            uasId = "1581F5FJDEMO00042", idType = "Serial number (ANSI/CTA-2063-A)",
            uaType = "Helicopter / multirotor", status = "Airborne",
            latitude = anchor.first + dLat, longitude = anchor.second + dLon,
            heightM = 62.0, altitudeGeoM = 95.0, speedMs = 7.5, verticalSpeedMs = 0.0,
            directionDeg = ((Math.toDegrees(angle) + 90) % 360).toInt(),
            operatorLatitude = anchor.first - 0.0007, operatorLongitude = anchor.second - 0.0004,
            operatorId = "DEMO-OP-0001"
        )
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ble(mac: String, name: String? = null, mfg: Map<Int, ByteArray> = emptyMap(), uuids: List<Int> = emptyList(),
                    data: Map<Int, ByteArray> = emptyMap(), type: AddressType = AddressType.PUBLIC) = { rssi: Int, t: Long ->
        Advert(mac, Advert.Source.BLE, rssi, name, mfg, uuids.map { Advert.uuid16(it) },
            data.mapKeys { Advert.uuid16(it.key) }, null, null, type, true, "LE 1M (legacy)", null, t)
    }

    private fun wifi(mac: String, ssid: String?, mhz: Int = 5180) = { rssi: Int, t: Long ->
        Advert(mac, Advert.Source.WIFI, rssi, ssid, addressType = AddressType.ofWifi(mac),
            wifi = Advert.WifiInfo(mhz, "[WPA2-PSK-CCMP][ESS]", "WiFi 6 (802.11ax)", emptyList()), timestamp = t)
    }

    /** (advert factory, base RSSI, moves with the "patrol car" group). */
    private val devices: List<Triple<(Int, Long) -> Advert, Int, Boolean>> = listOf(
        Triple(ble("00:25:DF:4A:19:C2", mfg = mapOf(0x034D to "AXJANUSBWCDEVICE".reversed().toByteArray())), -61, true),
        Triple(ble("F4:60:77:5B:21:9E", "XXRBJ224511452", uuids = listOf(0xFE79)), -66, true),
        Triple(ble("4C:CC:34:12:7F:03", mfg = mapOf(0x04EC to b(1, 0, 3))), -72, true),
        Triple(wifi("00:30:44:4F:21:A0", "IBR900-21A"), -69, true),
        Triple(wifi("B4:1E:52:3C:88:10", "Flock-3C8810", 2437), -77, false),
        Triple(ble("60:60:1F:A2:44:18"), -80, false),
        Triple(ble("7A:31:C4:0B:9E:22", mfg = mapOf(0x004C to (b(0x12, 0x19) + ByteArray(25))), type = AddressType.NON_RESOLVABLE), -63, false),
        Triple(ble("D2:5E:91:3A:07:BC", mfg = mapOf(0x0D53 to b(1, 2, 3)), type = AddressType.RANDOM_STATIC), -75, false),
        Triple(ble("5C:1B:2E:88:40:11", mfg = mapOf(0x004C to (b(0x07, 0x19, 0x01, 0x14, 0x20) + ByteArray(22))), type = AddressType.RESOLVABLE_PRIVATE), -55, false),
        Triple(ble("6E:0A:77:12:C3:95", "Pixel 9", mfg = mapOf(0x00E0 to b(0, 1)), type = AddressType.RESOLVABLE_PRIVATE), -68, false),
        Triple(ble("C8:3F:26:77:10:04", "Forerunner 265", uuids = listOf(0x180D)), -79, false),
        Triple(ble("D8:E0:E1:5A:61:2F", "JBL Flip 6"), -84, false),
        Triple(wifi("9C:3D:CF:61:0A:44", "NETGEAR-5G"), -73, false),
        Triple(wifi("9E:3D:CF:61:0A:45", null), -74, false),
        Triple(wifi("E4:5F:01:2B:3C:4D", "Cafe_Guest", 2437), -86, false)
    )

    /**
     * @param anchor your phone's current position, if known: the demo drone's
     * Remote ID position is placed relative to it at runtime, so no coordinates
     * are stored in the code.
     */
    /**
     * Two weeks of made-up match history around [anchor] (only if the log is
     * empty), so the history map and timeline have something to show. Weighted
     * toward weekday rush hours like a real commute would be.
     */
    suspend fun seedHistory(context: android.content.Context, anchor: Pair<Double, Double>) {
        val dao = com.rfsentinel.app.data.AppDatabase.getInstance(context).detectionDao()
        if (dao.count() > 0) return
        val rnd = kotlin.random.Random(42)
        val kinds = listOf(
            Triple("B4:1E:52:3C:88:10", "Flock Safety camera", "ALPR"),
            Triple("00:25:DF:4A:19:C2", "Axon body camera", "BODY_CAM"),
            Triple("4C:CC:34:12:7F:03", "Motorola Solutions equipment", "PUBLIC_SAFETY"),
            Triple("60:60:1F:A2:44:18", "DJI equipment (drone or controller)", "DRONE")
        )
        // A few "hot spots" (e.g. a camera on a commute route) plus scattered encounters.
        val spots = List(4) { anchor.first + rnd.nextDouble(-0.02, 0.02) to anchor.second + rnd.nextDouble(-0.03, 0.03) }
        val now = System.currentTimeMillis()
        repeat(90) { i ->
            val day = rnd.nextInt(14)
            val hour = if (rnd.nextFloat() < 0.6f) listOf(7, 8, 16, 17).random(rnd) else rnd.nextInt(6, 23)
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now - day * 86_400_000L
                set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, rnd.nextInt(60))
            }
            val (mac, label, cat) = if (i % 3 == 0) kinds[0] else kinds.random(rnd)
            val base = if (rnd.nextFloat() < 0.7f) spots.random(rnd) else
                anchor.first + rnd.nextDouble(-0.04, 0.04) to anchor.second + rnd.nextDouble(-0.06, 0.06)
            dao.insert(com.rfsentinel.app.data.DetectionEntity(
                mac = mac.dropLast(2) + String.format("%02X", i % 256), label = label, source = "BLE", rssi = -70,
                timestamp = cal.timeInMillis,
                latitude = base.first + rnd.nextDouble(-0.0015, 0.0015),
                longitude = base.second + rnd.nextDouble(-0.002, 0.002),
                category = cat, confidence = 80, evidence = "Demo data"
            ))
        }
    }

    fun populate(anchor: Pair<Double, Double>? = null) {
        if (!VendorDb.loaded) Thread.sleep(1500)
        DeviceRegistry.startSession()
        val now = System.currentTimeMillis()
        // 45 s of history so the signal graphs, radar and co-location analysis have data.
        for (s in 45 downTo 0) {
            val t = now - s * 1000L
            devices.forEachIndexed { i, (make, base, group) ->
                // The group rises and falls together; everything else moves out of step with it.
                val wobble = if (group) 7 * sin(s / 4.0) else -6 * sin(s / 4.0 + i * 0.3)
                val a = make(base + wobble.toInt(), t)
                val vendor = VendorDb.macVendor(a.mac) ?: a.manufacturerData.keys.firstNotNullOfOrNull { VendorDb.company(it) }
                val raw = SignatureEngine.classify(a) +
                    OuiWatchlist.hits(a.mac, a.name, listOfNotNull(vendor)) +
                    listOfNotNull(DeviceRegistry.clusterHit(a.mac, t))
                val rid = if (a.mac == DEMO_DRONE && anchor != null) demoRemoteId(anchor, s) else null
                DeviceRegistry.report(a, EvidenceFusion.fuse(raw), DeviceIntel.identify(a, VendorDb.macVendor(a.mac)), vendor, rid, null, t)
            }
        }
    }
}
