package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceFilter

/**
 * The phone's filter chips as car rows, with live counts: pick one to list
 * those devices. The most useful filters come first (the car may show only six).
 */
class NearbyDevicesScreen(carContext: CarContext) : LiveScreen(carContext) {

    private fun counts(): List<Pair<DeviceFilter, Int>> {
        val all = if (ScanForegroundService.isRunning) DeviceRegistry.snapshot() else emptyList()
        return DeviceListScreen.CAR_FILTERS.map { f -> f to all.count { f.matches(it) } }
    }

    override fun contentKey(): Any = listOf(ScanForegroundService.isRunning, counts())

    override fun render(): Template = listTemplate("Nearby devices",
        counts().take(CarUi.listLimit(carContext)).map { (f, n) ->
            row("${DeviceListScreen.titleOf(f)} ($n)", icon = iconOf(f), browsable = true) {
                screenManager.push(DeviceListScreen(carContext, f))
            }
        })

    private fun iconOf(f: DeviceFilter) = when (f) {
        DeviceFilter.FLAGGED -> R.drawable.ic_car_warning
        DeviceFilter.TRACKERS, DeviceFilter.DRONES -> R.drawable.ic_car_drone
        DeviceFilter.FAVORITES -> R.drawable.ic_car_star
        DeviceFilter.EXTERNAL -> R.drawable.ic_car_usb
        else -> R.drawable.ic_view_list
    }
}
