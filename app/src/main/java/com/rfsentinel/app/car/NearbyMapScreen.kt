package com.rfsentinel.app.car

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android Auto: the closest known plate, speed and red-light cameras (OpenStreetMap, downloaded on
 * the phone) and the drones broadcasting a Remote ID position, on the car's own
 * map with distances. The car host draws the map; we only supply places.
 */
class NearbyMapScreen(
    carContext: CarContext,
    /** Opened from the devices map: its "Devices" button goes back instead of stacking another. */
    private val fromDevicesMap: Boolean = false
) : LiveScreen(carContext, periodMs = 5_000L, preciseLocation = true) {

    /** One row: a camera or a drone. */
    data class Item(val title: String, val detail: String, val lat: Double, val lon: Double, val distanceM: Double,
                    val drone: Boolean, val mac: String?, val kind: com.rfsentinel.app.alpr.KnownCamera.Kind? = null,
                    /** Flagged device: its list / radar colour (category, or amber when weak). */
                    val deviceColor: Int? = null)

    override fun onGetTemplate(): Template {
        val me = CarUi.currentLocation(carContext)
        if (me != null) autoDownloadAround(me)
        val limit = CarUi.listLimit(carContext).coerceAtMost(6)
        val items = if (me == null) emptyList() else nearby(me.latitude, me.longitude, limit)

        val list = ItemList.Builder()
        if (me == null) {
            list.setNoItemsMessage("Waiting for your location...")
        } else if (items.isEmpty()) {
            list.setNoItemsMessage(
                if (AlprStore.cameras.isEmpty()) "No known cameras downloaded - on your phone: Map > menu > Download known cameras"
                else "No known cameras or drones within ${(SEARCH_RADIUS_M / 1000).toInt()} km"
            )
        }
        items.forEach { it -> list.addItem(row(it)) }

        val template = PlaceListMapTemplate.Builder()
            .setTitle("Cameras nearby")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Devices").setOnClickListener {
                        if (fromDevicesMap) screenManager.pop() else screenManager.push(DevicesMapScreen(carContext))
                    }.build()
                ).build()
            )
            .setCurrentLocationEnabled(me != null)
            .setItemList(list.build())
        // Centre on the driver, not on 0°,0°, when there is nothing to show.
        if (me != null) template.setAnchor(Place.Builder(CarLocation.create(me.latitude, me.longitude)).build())
        return template.build()
    }

    private fun row(it: Item): Row {
        val place = Place.Builder(CarLocation.create(it.lat, it.lon))
            .setMarker(
                PlaceMarker.Builder()
                    .setColor(when {
                        it.drone -> CarColor.BLUE
                        it.deviceColor != null -> CarColor.createCustom(it.deviceColor, it.deviceColor)
                        it.kind == com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR -> CarColor.RED
                        else -> CarColor.YELLOW
                    })
                    .setLabel(when (it.kind) {
                        null -> if (it.drone) "D" else "!"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.SPEED -> "S"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.RED_LIGHT -> "R"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR -> "P"
                    })
                    .build()
            ).build()
        // Android Auto requires every place row on a map template to carry its distance
        // as a DistanceSpan (the host formats it in the driver's units); plain text throws.
        val text = android.text.SpannableString("  · " + it.detail)
        text.setSpan(androidx.car.app.model.DistanceSpan.create(distanceOf(it.distanceM)), 0, 1,
            android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return Row.Builder()
            .setTitle(it.title)
            .addText(text)
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener {
                if (it.mac != null) screenManager.push(DeviceDetailScreen(carContext, it.mac))
                else CarToast.makeText(carContext, "${it.title}: ${distanceText(it.distanceM)} away", CarToast.LENGTH_LONG).show()
            }
            .build()
    }

    /** Fetches the cameras within ~20 km when that area isn't cached yet, then refreshes. */
    private fun autoDownloadAround(me: Location) {
        if (!com.rfsentinel.app.util.Prefs.autoCameras(carContext)) return
        val dLat = 0.18
        val dLon = 0.18 / kotlin.math.max(0.1, kotlin.math.cos(Math.toRadians(me.latitude)))
        val (s, w, n, e) = listOf(me.latitude - dLat, me.longitude - dLon, me.latitude + dLat, me.longitude + dLon)
        if (!AlprStore.needsDownload(s, w, n, e)) return
        lifecycleScope.launch {
            if (AlprStore.autoDownload(carContext, s, w, n, e)) invalidate()
        }
    }

    companion object {
        /** The list shows only the nearest few, so look well ahead along the road. */
        const val SEARCH_RADIUS_M = 20_000.0

        /** Nearest known cameras and positioned drones, closest first (pure; unit-tested). */
        fun nearby(lat: Double, lon: Double, limit: Int): List<Item> {
            val cams = KnownCameras.near(AlprStore.cameras, lat, lon, SEARCH_RADIUS_M).map { (c, d) ->
                Item(c.label, c.operator ?: (c.direction?.let { "faces $it°" } ?: "mapped in OpenStreetMap"),
                    c.lat, c.lon, d, drone = false, mac = null, kind = c.type)
            }
            val drones = DeviceRegistry.snapshot().mapNotNull { s ->
                val r = s.remoteId ?: return@mapNotNull null
                val dLat = r.latitude ?: return@mapNotNull null
                val dLon = r.longitude ?: return@mapNotNull null
                val d = DeviceRegistry.metersBetween(lat, lon, dLat, dLon)
                Item("Drone" + (r.uasId?.let { " $it" } ?: ""),
                    listOfNotNull(r.heightM?.let { "${it.roundToInt()} m up" }, r.speedMs?.let { "${(it * 3.6).roundToInt()} km/h" })
                        .joinToString(" · ").ifEmpty { "Remote ID" },
                    dLat, dLon, d, drone = true, mac = s.mac)
            }
            // Flagged devices where your phone heard them best (the same spot as on the phone map).
            val devices = DeviceRegistry.snapshot().mapNotNull { s ->
                val best = s.best ?: return@mapNotNull null
                if (com.rfsentinel.app.data.WhitelistCache.contains(s.mac) || s.remoteId?.hasPosition == true) return@mapNotNull null
                val pos = s.bestPosition ?: return@mapNotNull null
                val d = DeviceRegistry.metersBetween(lat, lon, pos.lat, pos.lon)
                if (d > SEARCH_RADIUS_M) return@mapNotNull null
                Item(best.label, best.category.shortTag + " \u00b7 heard here", pos.lat, pos.lon, d, drone = false, mac = s.mac,
                    deviceColor = if (best.tier == com.rfsentinel.app.detect.Tier.WEAK) com.rfsentinel.app.ui.DeviceColors.WEAK else best.category.colorArgb)
            }
            // Drones first (they move and matter now), then flagged devices, then cameras by distance.
            return (drones.sortedBy { it.distanceM } + devices.sortedBy { it.distanceM } + cams).take(limit)
        }

        /** Metres below 1 km (rounded to 10 m), else kilometres with one decimal. */
        fun distanceOf(m: Double): androidx.car.app.model.Distance =
            if (m < 1000) androidx.car.app.model.Distance.create(((m / 10).roundToInt() * 10).toDouble(), androidx.car.app.model.Distance.UNIT_METERS)
            else androidx.car.app.model.Distance.create(m / 1000, androidx.car.app.model.Distance.UNIT_KILOMETERS_P1)

        fun distanceText(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)
    }
}
