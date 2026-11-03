package com.rfsentinel.app.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.R
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.service.ScanForegroundService
import java.text.DateFormat
import java.util.Date

/**
 * Android Auto (car API 7+): RF Sentinel's own map, which the driver can drag
 * and zoom freely - every device heard around you in the list / radar colours,
 * the known cameras and your position, nothing to select. Beside it: a summary,
 * or turn-by-turn guidance to a destination found in OpenStreetMap.
 */
class LiveMapScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 3_000L, preciseLocation = true) {

    private val renderer = CarMapRenderer(carContext, lifecycleScope)
    private var removeNavListener: (() -> Unit)? = null

    init {
        renderer.onFollowingChanged = { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                removeNavListener = Navigator.addListener { renderer.redraw(); invalidate() }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
                removeNavListener?.invoke(); removeNavListener = null
            }
        })
    }

    /** Called by the destination screen once a route has started. */
    fun onNavigationStarted() {
        renderer.recenter()
        invalidate()
    }

    override fun contentKey(): Any {
        val s = Navigator.state
        return if (Navigator.active) listOf("nav", Navigator.status, s?.stepIndex,
            s?.toManeuverM?.let { (it / 10).toInt() }, s?.remainingM?.let { (it / 100).toInt() })
        else listOf(ScanForegroundService.isRunning, renderer.counts(), renderer.following)
    }

    override fun render(): Template {
        val content = listTemplate(
            if (Navigator.active) Navigator.destination?.name ?: "Navigation" else "Live map",
            if (Navigator.active) guidanceRows() else summaryRows()
        )
        // PAN lets the car send drags. The other buttons zoom and resume following.
        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(action(icon = R.drawable.ic_car_zoom_in) { renderer.zoomBy(1.0) })
            .addAction(action(icon = R.drawable.ic_car_zoom_out) { renderer.zoomBy(-1.0) })
            .addAction(action(icon = R.drawable.ic_car_my_location) { renderer.recenter(); invalidate() })
            .build()
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setMapController(MapController.Builder().setMapActionStrip(mapActions).build())
            .build()
    }

    private fun summaryRows(): List<Row> {
        val (devices, cameras) = renderer.counts()
        return listOf(
            row(if (ScanForegroundService.isRunning) "$devices device${if (devices == 1) "" else "s"} · $cameras camera${if (cameras == 1) "" else "s"} on the map" else "Not scanning",
                if (renderer.following) "Drag or pinch the map to look around" else "Tap ◎ to follow the car again"),
            row("Navigate to...", "Search a destination (OpenStreetMap data)",
                icon = R.drawable.ic_car_navigate, browsable = true) { screenManager.push(DestinationScreen(carContext, this)) },
            row("Device list", icon = R.drawable.ic_view_list, browsable = true) { screenManager.push(DevicesMapScreen(carContext)) },
            row("Camera list", icon = R.drawable.ic_car_warning, browsable = true) { screenManager.push(NearbyMapScreen(carContext)) }
        )
    }

    private fun guidanceRows(): List<Row> {
        val s = Navigator.state
        val r = Navigator.route
        val banner = if (r != null && s != null) com.rfsentinel.app.nav.Prompts.banner(r, s.stepIndex, s.toManeuverM) else null
        val next = r?.steps?.getOrNull((s?.stepIndex ?: 0) + 1)
        val turn = banner?.text?.takeIf { it.isNotBlank() } ?: next?.instruction?.removeSuffix(".")
        val rows = mutableListOf(row(Navigator.status ?: turn ?: "Follow the route",
            if (s != null) "in ${NearbyMapScreen.distanceText(s.toManeuverM)}" else "Waiting for GPS...",
            icon = R.drawable.ic_car_navigate))
        if (s != null) {
            val left = Navigator.remainingS ?: s.remainingS
            val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (left * 1000).toLong()))
            rows += row("Arrive $eta", "${NearbyMapScreen.distanceText(s.remainingM)} · ${com.rfsentinel.app.nav.NavFormat.duration(left)}")
        }
        rows += row("Stop navigation") { Navigator.stop(); renderer.recenter(); invalidate() }
        return rows
    }
}
