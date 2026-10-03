package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.detect.CellAnalyzer
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.CellMonitor
import com.rfsentinel.app.service.CellTowerStore
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs

/**
 * Cell towers in the car: the fake-cell (IMSI catcher) check status, the cell
 * serving the phone and its neighbours, live from the scanner's 15 s snapshot.
 * Read passively from what the modem reports; nothing is looked up online.
 */
class CellsScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 5_000L) {

    override fun contentKey(): Any = listOf(
        ScanForegroundService.isRunning, CellTowerStore.currentAt, recentWarning()?.second?.key,
        Prefs.categoryEnabled(carContext, Category.CELL_ANOMALY)
    )

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun render(): Template {
        val items = ItemList.Builder()
        val running = ScanForegroundService.isRunning
        val checks = Prefs.categoryEnabled(carContext, Category.CELL_ANOMALY)

        // 1. Fake-cell check status.
        val warning = recentWarning()
        items.addItem(
            when {
                warning != null -> Row.Builder()
                    .setTitle(warning.second.title)
                    .addText(CarUi.ageText(System.currentTimeMillis() - warning.first) + " · " + warning.second.detail.take(90))
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, CarUi.DANGER_COLOR))
                    .build()
                !checks -> Row.Builder()
                    .setTitle("Fake cell tower checks are off")
                    .addText("Turn on the category in Settings on your phone")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower))
                    .build()
                !running -> Row.Builder()
                    .setTitle("Not scanning")
                    .addText("Cells are checked every 15 s while scanning")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower))
                    .build()
                else -> Row.Builder()
                    .setTitle("No fake-cell signs")
                    .addText("Checked every 15 s - a hint, not proof")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower, CarUi.CLEAR_COLOR))
                    .build()
            }
        )

        // 2. The serving cell, then the strongest neighbours.
        val cells = if (running) CellTowerStore.current else emptyList()
        cells.sortedWith(compareByDescending<CellAnalyzer.Cell> { it.registered }.thenByDescending { it.dbm ?: -999 })
            .take((CarUi.listLimit(carContext) - 1).coerceAtLeast(0))
            .forEach { c -> items.addItem(cellRow(c)) }

        return ListTemplate.Builder()
            .setTitle("Cell towers")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun cellRow(c: CellAnalyzer.Cell): Row {
        val row = Row.Builder()
            .setTitle(c.rat.label + if (c.registered) " · serving" else " · neighbour")
            .addText(listOfNotNull(
                c.operator,
                if (c.mcc != null || c.mnc != null) "${c.mcc ?: "?"}-${c.mnc ?: "?"}" else null,
                c.cellId?.let { "cell $it" } ?: c.pci?.let { "PCI $it" },
                c.dbm?.let { "$it dBm" }
            ).joinToString(" · ").ifEmpty { "No IDs reported" })
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower, if (c.registered) CarUi.CLEAR_COLOR else null))
        // A tower with an estimated spot (where its signal was strongest) opens on the map.
        if (c.cellId != null) {
            row.setOnClickListener {
                val t = CellTowerStore.all(carContext).firstOrNull { it.key == c.key }
                val lat = t?.bestLat
                val lon = t?.bestLon
                if (lat != null && lon != null) {
                    screenManager.push(PlaceFocusScreen(carContext, "${t.ratLabel} cell ${t.cellId}",
                        "Estimated: where its signal was strongest", lat, lon, CarColor.createCustom(TOWER_COLOR, TOWER_COLOR), "T"))
                } else {
                    androidx.car.app.CarToast.makeText(carContext, "No position for this tower yet",
                        androidx.car.app.CarToast.LENGTH_SHORT).show()
                }
            }
        }
        return row.build()
    }

    private fun recentWarning() = CellMonitor.lastAnomaly?.takeIf { System.currentTimeMillis() - it.first < WARNING_MS }

    companion object {
        /** A warning stays on top this long. */
        const val WARNING_MS = 15 * 60_000L
        const val TOWER_COLOR = 0xFF6A1B9A.toInt()

        /** One line for the home screen ("4G (LTE) · Network · -95 dBm · 4 neighbours"), null with no cells. */
        fun summary(now: Long = System.currentTimeMillis()): String? {
            CellMonitor.lastAnomaly?.takeIf { now - it.first < WARNING_MS }?.let { return "Warning: " + it.second.title }
            val cells = CellTowerStore.current
            if (cells.isEmpty()) return null
            val serving = cells.firstOrNull { it.registered }
            val neighbours = cells.count { !it.registered }
            return listOfNotNull(
                serving?.rat?.label, serving?.operator, serving?.dbm?.let { "$it dBm" },
                "$neighbours neighbour${if (neighbours == 1) "" else "s"}"
            ).joinToString(" · ")
        }
    }
}
