package com.rfsentinel.app.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceColors

/**
 * Android Auto: the devices heard around you on the car's map, like the phone
 * map - each placed where your phone was when its signal was strongest, in the
 * list / radar colour. Android Auto lets a non-navigation app show only a short
 * list of places (usually 6), so flagged devices come first, then the strongest
 * ordinary ones. "Cameras" switches to the known-camera map.
 */
class DevicesMapScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 4_000L, preciseLocation = true) {

    /** One device to show. */
    data class Item(
        val mac: String, val title: String, val detail: String,
        val lat: Double, val lon: Double, val distanceM: Double,
        val flagged: Boolean, val color: Int, val tag: String?
    )

    override fun onGetTemplate(): Template {
        val me = CarUi.currentLocation(carContext)
        val limit = CarUi.listLimit(carContext).coerceAtMost(6)
        val items = if (me == null) emptyList() else around(me.latitude, me.longitude, limit)

        val list = ItemList.Builder()
        if (items.isEmpty()) list.setNoItemsMessage(
            when {
                !ScanForegroundService.isRunning -> "Not scanning - tap Start on the RF Sentinel home screen"
                me == null -> "Waiting for your location..."
                else -> "No devices placed yet - they appear as GPS fixes come in"
            }
        )
        items.forEach { list.addItem(row(it)) }

        val template = PlaceListMapTemplate.Builder()
            .setTitle("Devices around me")
            .setHeaderAction(Action.BACK)
            .setCurrentLocationEnabled(me != null)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Cameras")
                        .setOnClickListener { screenManager.push(NearbyMapScreen(carContext, fromDevicesMap = true)) }
                        .build()
                ).build()
            )
            .setItemList(list.build())
        if (me != null) template.setAnchor(Place.Builder(CarLocation.create(me.latitude, me.longitude)).build())
        return template.build()
    }

    private fun row(it: Item): Row {
        val marker = PlaceMarker.Builder().setColor(CarColor.createCustom(it.color, it.color))
        it.tag?.let { t -> marker.setLabel(t) } // W = WiFi, B = Bluetooth, else the category's first letter
        val place = Place.Builder(CarLocation.create(it.lat, it.lon)).setMarker(marker.build()).build()
        // Map rows must carry their distance as a DistanceSpan (the car formats it).
        val text = SpannableString("  · " + it.detail)
        text.setSpan(DistanceSpan.create(NearbyMapScreen.distanceOf(it.distanceM)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return Row.Builder()
            .setTitle(it.title)
            .addText(text)
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener { screenManager.push(DeviceDetailScreen(carContext, it.mac)) }
            .build()
    }

    companion object {
        /** Ordinary devices: the radar scope colour of the default theme (cars don't use app themes). */
        const val ORDINARY_COLOR = 0xFF0B5C63.toInt()

        /**
         * Devices with a position, most relevant first: flagged (strongest
         * evidence first), then ordinary ones alternating Bluetooth and WiFi
         * (each by signal strength), with the access points of one WiFi network
         * (2.4 / 5 GHz, mesh) merged - otherwise a home router fills every slot.
         * Drones use their own Remote ID position. Pure apart from the registry;
         * unit-tested.
         */
        fun around(lat: Double, lon: Double, limit: Int): List<Item> {
            val all = candidates(lat, lon)
            val flagged = all.filter { it.item.flagged }.sortedByDescending { it.rank }
            val ordinary = all.filterNot { it.item.flagged }.sortedByDescending { it.rank }
            val wifi = ordinary.filter { it.wifi }
                .distinctBy { c -> c.network?.lowercase() ?: c.item.mac } // strongest AP of each network
            val ble = ordinary.filterNot { it.wifi }
            val mixed = ArrayList<Candidate>()
            var i = 0
            while (i < maxOf(ble.size, wifi.size)) {
                ble.getOrNull(i)?.let { mixed += it }
                wifi.getOrNull(i)?.let { mixed += it }
                i++
            }
            return (flagged + mixed).take(limit).map { it.item }
        }

        private class Candidate(val item: Item, val rank: Int, val wifi: Boolean, val network: String?)

        private fun candidates(lat: Double, lon: Double): List<Candidate> =
            DeviceRegistry.snapshot().mapNotNull { s ->
                if (WhitelistCache.contains(s.mac)) return@mapNotNull null
                val rid = s.remoteId?.takeIf { it.hasPosition }
                val pLat = rid?.latitude ?: s.bestPosition?.lat ?: return@mapNotNull null
                val pLon = rid?.longitude ?: s.bestPosition?.lon ?: return@mapNotNull null
                val best = s.best
                val flagged = DeviceColors.isFlagged(s)
                val item = Item(
                    mac = s.mac,
                    title = best?.label ?: s.name ?: s.deviceType,
                    // Short enough for one line on a car screen.
                    detail = if (best != null) "${best.category.shortTag} · ${best.tier.label}"
                        else (if (s.source == com.rfsentinel.app.detect.Advert.Source.WIFI) "WiFi" else "Bluetooth") + " · ${s.rssi} dBm",
                    lat = pLat, lon = pLon,
                    distanceM = DeviceRegistry.metersBetween(lat, lon, pLat, pLon),
                    flagged = flagged,
                    color = when {
                        best == null -> ORDINARY_COLOR
                        best.tier == Tier.WEAK -> DeviceColors.WEAK
                        else -> best.category.colorArgb
                    },
                    // Without a label Android Auto numbers markers by row, which reads like a
                    // count when several sit on the same spot: say what each one is instead.
                    tag = best?.category?.shortTag?.take(1)
                        ?: if (s.source == com.rfsentinel.app.detect.Advert.Source.WIFI) "W" else "B"
                )
                val wifi = s.source == com.rfsentinel.app.detect.Advert.Source.WIFI
                Candidate(
                    item,
                    rank = (if (flagged) (best?.confidence ?: 0) else 0) * 1_000 + (s.rssi + 200),
                    wifi = wifi,
                    network = if (wifi) s.name?.takeIf { it.isNotBlank() } else null
                )
            }
    }
}
