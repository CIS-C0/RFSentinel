package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
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

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun render(): Template {
        val now = System.currentTimeMillis()
        val items = ItemList.Builder()
        // The car caps list length while driving; the most important come first.
        matching(now).forEach { s ->
            items.addItem(
                Row.Builder()
                    .setTitle(CarUi.title(s))
                    .addText(CarUi.tagLine(s))
                    .addText(CarUi.signalLine(s, now))
                    .setImage(
                        CarUi.icon(
                            carContext,
                            if (CarUi.isFlagged(s)) R.drawable.ic_car_warning else R.drawable.ic_tile_scan,
                            CarUi.colorFor(s)
                        )
                    )
                    .setOnClickListener { screenManager.push(DeviceDetailScreen(carContext, s.mac)) }
                    .build()
            )
        }
        items.setNoItemsMessage(emptyMessage(filter))

        // Title stays constant: Android Auto only treats a same-title template as a
        // refresh rather than a new step (steps are capped for driver safety).
        return ListTemplate.Builder()
            .setTitle(titleOf(filter))
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    companion object {
        /** The filters offered in the car (cell towers have their own screen). */
        val CAR_FILTERS = DeviceFilter.entries.filter { it != DeviceFilter.CELLS && it != DeviceFilter.WAZE }

        fun titleOf(f: DeviceFilter) = when (f) {
            DeviceFilter.ALL -> "All nearby"
            DeviceFilter.FLAGGED -> "Flagged nearby"
            DeviceFilter.NEW -> "New devices"
            DeviceFilter.EXTERNAL -> "Heard by external hardware"
            else -> f.label
        }

        fun emptyMessage(f: DeviceFilter) = when {
            !ScanForegroundService.isRunning -> "Not scanning - go back and tap Start"
            f == DeviceFilter.FLAGGED -> "Nothing flagged nearby"
            f == DeviceFilter.TRACKERS -> "No trackers nearby"
            f == DeviceFilter.DRONES -> "No drones nearby"
            f == DeviceFilter.FAVORITES -> "No favorites nearby"
            f == DeviceFilter.EXTERNAL -> "Nothing from an ESP32 board or USB WiFi adapter - see More > Hardware"
            f == DeviceFilter.ALL -> "Listening... no devices heard yet"
            else -> "Nothing here right now"
        }
    }
}
