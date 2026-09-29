package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService

/** A driver-safe list of nearby devices. Tapping a row opens its details. */
class DeviceListScreen(carContext: CarContext, private val filter: Filter) : LiveScreen(carContext) {

    enum class Filter(val title: String) {
        FLAGGED("Flagged nearby"),
        DRONES_TRACKERS("Drones & trackers"),
        ALL("All nearby")
    }

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun onGetTemplate(): Template {
        val now = System.currentTimeMillis()
        val all = if (ScanForegroundService.isRunning) DeviceRegistry.snapshot(now) else emptyList()
        val matching = CarUi.sorted(all.filter { s ->
            when (filter) {
                Filter.FLAGGED -> CarUi.isFlagged(s)
                Filter.DRONES_TRACKERS -> s.remoteId != null ||
                    s.hits.any { it.category == Category.DRONE || it.category == Category.TRACKER }
                Filter.ALL -> true
            }
        })

        val items = ItemList.Builder()
        // The car caps list length while driving; show the most important first.
        matching.take(CarUi.listLimit(carContext)).forEach { s ->
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
        items.setNoItemsMessage(
            when {
                !ScanForegroundService.isRunning -> "Not scanning - go back and tap Start"
                filter == Filter.FLAGGED -> "Nothing flagged nearby"
                filter == Filter.DRONES_TRACKERS -> "No drones or trackers nearby"
                else -> "Listening... no devices heard yet"
            }
        )

        // Title stays constant: Android Auto only treats a same-title template as a
        // refresh rather than a new step (steps are capped for driver safety).
        return ListTemplate.Builder()
            .setTitle(filter.title)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
