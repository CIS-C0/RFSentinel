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
import kotlin.math.roundToInt

/**
 * Android Auto: the closest known plate, speed and red-light cameras (OpenStreetMap, downloaded on
 * the phone) and the drones broadcasting a Remote ID position, on the car's own
 * map with distances. The car host draws the map; we only supply places.
 */
class NearbyMapScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 5_000L) {

    /** One row: a camera or a drone. */
    data class Item(val title: String, val detail: String, val lat: Double, val lon: Double, val distanceM: Double,
                    val drone: Boolean, val mac: String?, val kind: com.rfsentinel.app.alpr.KnownCamera.Kind? = null)

    override fun onGetTemplate(): Template {
        val me = myLocation()
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
            .setTitle("Cameras & drones")
            .setHeaderAction(Action.BACK)
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
                    .setColor(if (it.drone) CarColor.BLUE else if (it.kind == com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR) CarColor.RED else CarColor.YELLOW)
                    .setLabel(when (it.kind) {
                        null -> "D"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.SPEED -> "S"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.RED_LIGHT -> "R"
                        com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR -> "P"
                    })
                    .build()
            ).build()
        return Row.Builder()
            .setTitle(it.title)
            .addText(distanceText(it.distanceM) + " · " + it.detail)
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener {
                if (it.drone && it.mac != null) screenManager.push(DeviceDetailScreen(carContext, it.mac))
                else CarToast.makeText(carContext, "${it.title}: ${distanceText(it.distanceM)} away", CarToast.LENGTH_LONG).show()
            }
            .build()
    }

    @SuppressLint("MissingPermission") // checked via Permissions
    private fun myLocation(): Location? {
        ScanForegroundService.lastFix?.let { return it }
        if (!Permissions.granted(carContext, android.Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = carContext.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    companion object {
        const val SEARCH_RADIUS_M = 5_000.0

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
            // Drones first (they move and matter now), then cameras by distance.
            return (drones.sortedBy { it.distanceM } + cams).take(limit)
        }

        fun distanceText(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)
    }
}
