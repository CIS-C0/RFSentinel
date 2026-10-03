package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry

/**
 * The latest alerts, newest first - still listed after the device has left,
 * so a pop-up missed while driving can be looked at later. A device opens its
 * details (its last alert once out of range), a camera its place on the map,
 * a cell warning the cell towers.
 */
class RecentAlertsScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 5_000L) {

    private fun entries() = AlertLog.recent(carContext).take(CarUi.listLimit(carContext))

    override fun contentKey(): Any {
        val now = System.currentTimeMillis()
        return entries().map { "${it.key}|${it.time}|${CarUi.ageText(now - it.time)}" }
    }

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun render(): Template {
        val now = System.currentTimeMillis()
        val me = CarUi.currentLocation(carContext)
        val items = ItemList.Builder()
        entries().forEach { e ->
            val color = when {
                e.tierEnum == Tier.WEAK -> CarUi.WEAK_COLOR
                else -> e.categoryEnum?.colorArgb ?: CarUi.DANGER_COLOR
            }
            val where = if (me != null && e.lat != null && e.lon != null)
                " · " + NearbyMapScreen.distanceText(DeviceRegistry.metersBetween(me.latitude, me.longitude, e.lat, e.lon)) + " away"
            else ""
            val row = Row.Builder()
                .setTitle((if (e.following) "FOLLOWING · " else "") + e.label)
                .addText("${e.categoryEnum?.shortTag ?: "Alert"} · ${CarUi.ageText(now - e.time)}$where")
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, color))
            target(e, CarColor.createCustom(color, color))?.let { open -> row.setOnClickListener { open() } }
            items.addItem(row.build())
        }
        items.setNoItemsMessage("No alerts yet")
        return ListTemplate.Builder()
            .setTitle("Recent alerts")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    /** Where tapping an alert goes, or null when there is nothing more to show. */
    private fun target(e: AlertLog.Entry, color: CarColor): (() -> Unit)? {
        e.mac?.let { mac -> return { screenManager.push(DeviceDetailScreen(carContext, mac)) } }
        if (e.key.startsWith("alpr:")) {
            val cam = AlprStore.cameras.firstOrNull { "alpr:" + it.osmId == e.key }
            if (cam != null) return { screenManager.push(PlaceFocusScreen.forCamera(carContext, cam)) }
        }
        if (e.key.startsWith("cell")) return { screenManager.push(CellsScreen(carContext)) }
        val lat = e.lat ?: return null
        val lon = e.lon ?: return null
        return { screenManager.push(PlaceFocusScreen(carContext, e.label, e.evidence.take(80), lat, lon, color, "!")) }
    }
}
