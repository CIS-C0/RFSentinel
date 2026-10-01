package com.rfsentinel.app.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
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

    init {
        renderer.onFollowingChanged = { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                Navigator.onUpdate = { renderer.redraw(); invalidate() }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
                Navigator.onUpdate = null
            }
        })
    }

    /** Called by the destination screen once a route has started. */
    fun onNavigationStarted() {
        renderer.recenter()
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (Navigator.active) guidanceRows(list) else summaryRows(list)

        val content = ListTemplate.Builder()
            .setTitle(if (Navigator.active) Navigator.destination?.name ?: "Navigation" else "Live map")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()

        // Map buttons: PAN (lets the car send drags), zoom, and back to following the car.
        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(CarUi.icon(carContext, R.drawable.ic_car_zoom_in))
                .setOnClickListener { renderer.zoomBy(1.0) }.build())
            .addAction(Action.Builder().setIcon(CarUi.icon(carContext, R.drawable.ic_car_zoom_out))
                .setOnClickListener { renderer.zoomBy(-1.0) }.build())
            .addAction(Action.Builder().setIcon(CarUi.icon(carContext, R.drawable.ic_car_my_location))
                .setOnClickListener { renderer.recenter(); invalidate() }.build())
            .build()

        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setMapController(MapController.Builder().setMapActionStrip(mapActions).build())
            .build()
    }

    private fun summaryRows(list: ItemList.Builder) {
        val (devices, cameras) = renderer.counts()
        list.addItem(Row.Builder()
            .setTitle(if (ScanForegroundService.isRunning) "$devices devices · $cameras cameras on the map" else "Not scanning")
            .addText(if (renderer.following) "Drag or pinch the map to look around" else "Tap ◎ to follow the car again")
            .build())
        list.addItem(Row.Builder()
            .setTitle("Navigate to...")
            .addText("Search a destination (OpenStreetMap)")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_navigate))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(DestinationScreen(carContext, this)) }
            .build())
        list.addItem(Row.Builder()
            .setTitle("Device list")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_list))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(DevicesMapScreen(carContext)) }
            .build())
        list.addItem(Row.Builder()
            .setTitle("Camera list")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(NearbyMapScreen(carContext)) }
            .build())
    }

    private fun guidanceRows(list: ItemList.Builder) {
        val p = Navigator.progress
        val step = p?.nextStep
        list.addItem(Row.Builder()
            .setTitle(Navigator.status ?: step?.instruction ?: "Follow the route")
            .addText(if (p != null) "in ${NearbyMapScreen.distanceText(p.toNextStepM)}" else "Waiting for GPS...")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_navigate))
            .build())
        val r = Navigator.route
        if (p != null && r != null && r.distanceM > 0) {
            // Time left scales with distance left (the route's own average speed).
            val leftS = r.durationS * (p.remainingM / r.distanceM)
            val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (leftS * 1000).toLong()))
            list.addItem(Row.Builder()
                .setTitle("Arrive $eta")
                .addText("${NearbyMapScreen.distanceText(p.remainingM)} · ${(leftS / 60).toInt().coerceAtLeast(1)} min")
                .build())
        }
        list.addItem(Row.Builder()
            .setTitle("Stop navigation")
            .setOnClickListener { Navigator.stop(); renderer.recenter(); invalidate() }
            .build())
    }
}
