package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.CarColor
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
        val shownDetail = when {
            me == null || cameraId == null -> detail
            ignored -> "Alerts off - tap to turn them back on"
            else -> "$detail · tap to ignore its alerts"
        }
        val navigate = { CarUi.navigateTo(carContext, lat, lon) }
        val toggle: (() -> Unit)? = if (cameraId == null) null else {
            {
                val nowIgnored = !IgnoredCameras.contains(carContext, cameraId)
                IgnoredCameras.set(carContext, cameraId, nowIgnored)
                CarToast.makeText(carContext,
                    if (nowIgnored) "You won't be warned about this camera" else "Alerts for this camera are back on",
                    CarToast.LENGTH_SHORT).show()
                invalidate()
            }
        }
        val row = mapRow(title, shownDetail, lat, lon,
            me?.let { DeviceRegistry.metersBetween(it.latitude, it.longitude, lat, lon) },
            if (ignored) IGNORED_COLOR else color, label, if (me == null) navigate else toggle)
        // No current-location marker or anchor, so the host frames this place alone.
        return mapTemplate(title, listOf(row), action("Navigate", R.drawable.ic_car_navigate, navigate))
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
