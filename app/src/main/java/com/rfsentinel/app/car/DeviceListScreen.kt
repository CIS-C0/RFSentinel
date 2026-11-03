package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceFilter

/**
 * A driver-safe list of nearby devices, filtered like the phone's chips
 * (Flagged, Trackers, Drones, Favorites, New, Bluetooth, WiFi, External).
 * Tapping a row opens its details.
 */
class DeviceListScreen(carContext: CarContext, private val filter: DeviceFilter) : LiveScreen(carContext) {

    private fun matching(now: Long): List<DeviceRegistry.Snapshot> {
        val all = if (ScanForegroundService.isRunning) DeviceRegistry.snapshot(now) else emptyList()
        return CarUi.sorted(all.filter { filter.matches(it) }).take(CarUi.listLimit(carContext))
    }

    override fun contentKey(): Any {
        val now = System.currentTimeMillis()
        return listOf(ScanForegroundService.isRunning) + matching(now).map { CarUi.rowKey(it, now) }
    }

    override fun render(): Template {
        val now = System.currentTimeMillis()
        // The title stays constant so Android Auto treats updates as refreshes.
        return listTemplate(titleOf(filter), matching(now).map { s ->
            row(CarUi.title(s), CarUi.tagLine(s), CarUi.signalLine(s, now),
                icon = if (CarUi.isFlagged(s)) R.drawable.ic_car_warning else R.drawable.ic_tile_scan,
                color = CarUi.colorFor(s)) { screenManager.push(DeviceDetailScreen(carContext, s.mac)) }
        }, emptyMessage(filter))
    }

    companion object {
        /** The filters offered in the car (cell towers have their own screen). */
        val CAR_FILTERS = DeviceFilter.entries.filter { it != DeviceFilter.CELLS && it != DeviceFilter.WAZE && it != DeviceFilter.RADIO && it != DeviceFilter.AIRCRAFT &&
            it !in DeviceFilter.KINDS && it != DeviceFilter.OTHER }

        fun titleOf(f: DeviceFilter) = when (f) {
            DeviceFilter.ALL -> "All nearby"
            DeviceFilter.FLAGGED -> "Flagged nearby"
            DeviceFilter.NEW -> "New devices"
            DeviceFilter.EXTERNAL -> "Heard by external hardware"
            DeviceFilter.SENTINELS -> "Heard by linked Sentinels"
            else -> f.label
        }

        fun emptyMessage(f: DeviceFilter) = when {
            !ScanForegroundService.isRunning -> "Not scanning - go back and tap Start"
            f == DeviceFilter.FLAGGED -> "Nothing flagged nearby"
            f == DeviceFilter.TRACKERS -> "No trackers nearby"
            f == DeviceFilter.DRONES -> "No drones nearby"
            f == DeviceFilter.FAVORITES -> "No favorites nearby"
            f == DeviceFilter.EXTERNAL -> "Nothing from an ESP32 board or USB WiFi adapter - see More > Hardware"
            f == DeviceFilter.SENTINELS -> "Nothing from linked Sentinels - link phones in Settings > Sentinel Link"
            f == DeviceFilter.ALL -> "Listening... no devices heard yet"
            else -> "Nothing here right now"
        }
    }
}
