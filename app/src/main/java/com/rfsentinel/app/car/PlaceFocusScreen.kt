package com.rfsentinel.app.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
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
import com.rfsentinel.app.R
import com.rfsentinel.app.alpr.IgnoredCameras
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.service.DeviceRegistry

/**
 * One place on its own (a camera, a tower, an alert): the car map centres on
 * it. Android Auto only lets navigation apps pan and zoom their map, so
 * "Navigate" hands the spot to your navigation app. For a known camera, tapping
 * its row turns its alerts off or back on (like "Ignore alerts" on the phone map).
 */
class PlaceFocusScreen(
    carContext: CarContext,
    private val title: String,
    private val detail: String,
    private val lat: Double,
    private val lon: Double,
    private val color: CarColor,
    private val label: String?,
    /** OpenStreetMap id of a known camera, for its ignore toggle. */
    private val cameraId: String? = null
) : LiveScreen(carContext, periodMs = 5_000L) {

    override fun contentKey(): Any {
        val me = CarUi.currentLocation(carContext)
        return listOf(
            me?.let { (DeviceRegistry.metersBetween(it.latitude, it.longitude, lat, lon) / 10).toInt() },
            cameraId?.let { IgnoredCameras.contains(carContext, it) }
        )
    }

    override fun render(): Template {
        val me = CarUi.currentLocation(carContext)
        val ignored = cameraId?.let { IgnoredCameras.contains(carContext, it) } ?: false
        val marker = PlaceMarker.Builder().setColor(if (ignored) IGNORED_COLOR else color)
        label?.let { marker.setLabel(it) }
        val place = Place.Builder(CarLocation.create(lat, lon)).setMarker(marker.build()).build()

        val shownDetail = when {
            cameraId == null -> detail
            ignored -> "Alerts off - tap to turn them back on"
            else -> "$detail · tap to ignore its alerts"
        }
        val row = Row.Builder().setTitle(title).setMetadata(Metadata.Builder().setPlace(place).build())
        if (me != null) {
            // Map rows carry their distance as a DistanceSpan (the car formats it).
            val text = SpannableString("  · $shownDetail")
            val d = DeviceRegistry.metersBetween(me.latitude, me.longitude, lat, lon)
            text.setSpan(DistanceSpan.create(NearbyMapScreen.distanceOf(d)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            row.addText(text)
            if (cameraId != null) row.setOnClickListener {
                val nowIgnored = !IgnoredCameras.contains(carContext, cameraId)
                IgnoredCameras.set(carContext, cameraId, nowIgnored)
                CarToast.makeText(carContext,
                    if (nowIgnored) "You won't be warned about this camera" else "Alerts for this camera are back on",
                    CarToast.LENGTH_SHORT).show()
                invalidate()
            }
        } else {
            // No position yet, so no distance: Android Auto only accepts a map row
            // without a DistanceSpan when it's browsable - let it start navigation.
            row.addText(detail)
                .setBrowsable(true)
                .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
        }

        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            // Your own position off, so the map frames this place rather than both of you.
            .setCurrentLocationEnabled(false)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder()
                        .setTitle("Navigate")
                        .setIcon(CarUi.icon(carContext, R.drawable.ic_car_navigate))
                        .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
                        .build()
                ).build()
            )
            .setItemList(ItemList.Builder().addItem(row.build()).build())
            .build()
    }

    companion object {
        /** A silenced camera's marker: grey, like the faded dot on the phone map. */
        private val IGNORED_COLOR = CarColor.createCustom(0xFF9E9E9E.toInt(), 0xFF9E9E9E.toInt())

        /** A known camera, with its alerts toggle. */
        fun forCamera(carContext: CarContext, cam: KnownCamera): PlaceFocusScreen {
            val c = NearbyMapScreen.cameraColor(cam.type)
            return PlaceFocusScreen(
                carContext, cam.label,
                cam.operator ?: (cam.direction?.let { "faces $it°" } ?: "mapped in OpenStreetMap"),
                cam.lat, cam.lon, CarColor.createCustom(c, c), NearbyMapScreen.cameraLabel(cam.type), cam.osmId
            )
        }
    }
}
