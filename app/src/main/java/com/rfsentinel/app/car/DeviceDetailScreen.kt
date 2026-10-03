package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.TrackerMutes
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.ProximityUtil
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One device on the car screen: why it's flagged, live signal, which radio heard
 * it, and buttons (whitelist / ignore a tracker, watch, favorite, navigate to a
 * drone). Once the device is out of range, its last alert is shown instead.
 */
class DeviceDetailScreen(carContext: CarContext, private val mac: String) : LiveScreen(carContext, 2_000L) {

    override fun contentKey(): Any {
        val now = System.currentTimeMillis()
        val s = DeviceRegistry.get(mac) ?: return listOf("gone", AlertLog.latestFor(carContext, mac)?.time,
            WhitelistCache.inTable(mac))
        return listOf(
            CarUi.rowKey(s, now), s.best?.evidence, s.bestRssi, trend(s), s.remoteId?.latitude, s.remoteId?.longitude,
            WhitelistCache.inTable(mac), TrackerMutes.isMuted(mac), OuiWatchlist.customEntry(mac) != null, Favorites.contains(mac)
        )
    }

    @Suppress("DEPRECATION") // setTitle/setActionStrip keep compatibility with older Android Auto hosts
    override fun render(): Template {
        val s = DeviceRegistry.get(mac) ?: return outOfRange()

        val best = s.best
        val rows = mutableListOf<Row>()
        rows += Row.Builder()
            .setTitle(CarUi.title(s))
            .addText(CarUi.tagLine(s))
            .setImage(CarUi.icon(carContext, if (CarUi.isFlagged(s)) R.drawable.ic_car_warning else R.drawable.ic_tile_scan, CarUi.colorFor(s)))
            .build()
        if (best != null) {
            rows += Row.Builder().setTitle("Why").addText(best.evidence.take(120)).build()
        }
        rows += Row.Builder()
            .setTitle("Signal ${s.rssi} dBm · ${ProximityUtil.band(s.rssi)}${trend(s)}")
            .addText("${DeviceIntel.formatDistance(s.distanceM)} (rough) · best ${s.bestRssi} dBm")
            .build()
        s.remoteId?.takeIf { it.hasPosition }?.let { r ->
            rows += Row.Builder()
                .setTitle("Drone " + (r.uasId ?: ""))
                .addText(
                    listOfNotNull(
                        r.heightM?.let { String.format(Locale.US, "%.0f m high", it) },
                        r.speedMs?.let { String.format(Locale.US, "%.0f km/h", it * 3.6) },
                        r.status
                    ).joinToString(" · ").ifEmpty { "Position known" }
                )
                .build()
        }
        rows += Row.Builder()
            .setTitle(s.vendor ?: s.deviceType)
            .addText("$mac · ${s.addressType.label} · heard by ${heardBy(s)}")
            .build()

        val pane = Pane.Builder()
        rows.take(CarUi.paneLimit(carContext)).forEach { pane.addRow(it) }

        // Primary buttons (a pane allows two).
        val whitelisted = WhitelistCache.inTable(mac)
        val ignored = TrackerMutes.isMuted(mac)
        val tracker = best?.category == Category.TRACKER && s.addressType != AddressType.PUBLIC
        pane.addAction(
            Action.Builder()
                .setTitle(when { ignored -> "Stop ignoring"; whitelisted -> "Un-whitelist"; tracker -> "Ignore..."; else -> "Whitelist" })
                .setOnClickListener {
                    when {
                        ignored -> { TrackerMutes.unmute(carContext, mac); toast("No longer ignored") }
                        // Its address changes daily, so it can't be whitelisted: offer the phone's choices.
                        tracker && !whitelisted -> screenManager.push(TrackerIgnoreScreen(carContext, mac))
                        else -> toggleWhitelist(whitelisted, best?.label ?: s.name ?: "")
                    }
                    invalidate()
                }
                .build()
        )
        val watched = OuiWatchlist.customEntry(mac) != null
        pane.addAction(
            Action.Builder()
                .setTitle(if (watched) "Unwatch" else "Watch")
                .setBackgroundColor(if (watched) CarColor.DEFAULT else CarColor.createCustom(CarUi.DANGER_COLOR, CarUi.DANGER_COLOR))
                .setOnClickListener { toggleWatch(watched, s.name) }
                .build()
        )

        // Action strip: favorite, and navigate to a drone's broadcast position.
        val strip = ActionStrip.Builder()
        val fav = Favorites.contains(mac)
        strip.addAction(
            Action.Builder()
                .setIcon(CarUi.icon(carContext, if (fav) R.drawable.ic_car_star else R.drawable.ic_car_star_outline))
                .setOnClickListener {
                    lifecycleScope.launch {
                        Favorites.set(carContext, mac, !fav, s.name, s.vendor, s.deviceType)
                        toast(if (fav) "Removed from favorites" else "Added to favorites")
                        invalidate()
                    }
                }
                .build()
        )
        s.remoteId?.let { r ->
            val lat = r.latitude ?: r.operatorLatitude
            val lon = r.longitude ?: r.operatorLongitude
            if (lat != null && lon != null) {
                strip.addAction(
                    Action.Builder()
                        .setTitle("Navigate")
                        .setIcon(CarUi.icon(carContext, R.drawable.ic_car_navigate))
                        .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("Device details")
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip.build())
            .build()
    }

    /** No longer heard: what its last alert said, with Whitelist and (when known) Navigate. */
    @Suppress("DEPRECATION")
    private fun outOfRange(): Template {
        val e = AlertLog.latestFor(carContext, mac)
            ?: return MessageTemplate.Builder("This device is no longer in range.")
                .setTitle("Device details")
                .setHeaderAction(Action.BACK)
                .build()
        val now = System.currentTimeMillis()
        val color = if (e.tierEnum == com.rfsentinel.app.detect.Tier.WEAK) CarUi.WEAK_COLOR
            else e.categoryEnum?.colorArgb ?: CarUi.DANGER_COLOR
        val pane = Pane.Builder()
        listOf(
            Row.Builder().setTitle((if (e.following) "FOLLOWING · " else "") + e.label)
                .addText("Out of range · last alert ${CarUi.ageText(now - e.time)}" + (e.rssi?.let { " · $it dBm" } ?: ""))
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, color)).build(),
            Row.Builder().setTitle("Why").addText(e.evidence.take(120)).build(),
            Row.Builder().setTitle(mac).addText("${e.categoryEnum?.title ?: e.category} · ${e.confidence}%").build()
        ).take(CarUi.paneLimit(carContext)).forEach { pane.addRow(it) }

        val whitelisted = WhitelistCache.inTable(mac)
        pane.addAction(
            Action.Builder()
                .setTitle(if (whitelisted) "Un-whitelist" else "Whitelist")
                .setOnClickListener { toggleWhitelist(whitelisted, e.label) }
                .build()
        )
        val lat = e.lat
        val lon = e.lon
        if (lat != null && lon != null) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Navigate there")
                    .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Device details")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun trend(s: DeviceRegistry.Snapshot): String =
        s.history.takeLast(6).dropLast(1).map { it.rssi }.average().let { avg ->
            when {
                avg.isNaN() -> ""
                s.rssi > avg + 3 -> " · getting closer"
                s.rssi < avg - 3 -> " · moving away"
                else -> " · steady"
            }
        }

    private fun heardBy(s: DeviceRegistry.Snapshot): String = listOfNotNull(
        if (com.rfsentinel.app.esp.HeardBy.phone.recent(mac) || (!com.rfsentinel.app.esp.HeardBy.esp.recent(mac) &&
                !com.rfsentinel.app.esp.HeardBy.usb.recent(mac)))
            "phone " + if (s.source == com.rfsentinel.app.detect.Advert.Source.WIFI) "WiFi" else "Bluetooth" else null,
        if (com.rfsentinel.app.esp.HeardBy.esp.recent(mac)) "ESP32" else null,
        if (com.rfsentinel.app.esp.HeardBy.usb.recent(mac)) "USB WiFi" else null
    ).joinToString(" + ")

    private fun toggleWhitelist(whitelisted: Boolean, label: String) {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(carContext).whitelistDao()
            if (whitelisted) dao.remove(WhitelistEntity(mac)) else dao.add(WhitelistEntity(mac, label))
            toast(if (whitelisted) "Removed from whitelist" else "Whitelisted - won't alert again")
            invalidate()
        }
    }

    private fun toggleWatch(watched: Boolean, name: String?) {
        if (watched) {
            OuiWatchlist.removeCustomEntry(carContext, mac)
            toast("Removed from watchlist")
        } else {
            OuiWatchlist.addCustomEntry(
                carContext, OuiEntry(mac, name ?: "Watched device $mac", "added from Android Auto", "custom")
            )
            toast("Watching this device")
        }
        invalidate()
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
}
