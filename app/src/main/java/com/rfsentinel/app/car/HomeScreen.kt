package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceFilter
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

/**
 * Android Auto home. Rows in order of importance, because a car shows only a
 * few while driving (usually 6): the threat headline (opens the flagged list),
 * the next known camera on your way, recent alerts, the map, every device
 * nearby (with the phone's filters) and More (snooze, voice, cell towers,
 * hardware). Start / Stop and the mute button sit in the header.
 */
class HomeScreen(carContext: CarContext) : LiveScreen(carContext) {

    private class Model(
        val running: Boolean,
        val devices: Int,
        val flagged: Int,
        val headline: Pair<Int, String>?,
        val camera: Pair<KnownCamera, Double>?,
        val alerts: Int,
        val newestAlert: AlertLog.Entry?,
        val silenced: String,
        val liveMap: Boolean,
        val cellSummary: String?,
        /** Scanning with the phone screen off: Android limits Bluetooth scanning then. */
        val screenOff: Boolean
    )

    private fun model(): Model {
        val running = ScanForegroundService.isRunning
        val devices = if (running) DeviceRegistry.snapshot() else emptyList()
        val alerts = AlertLog.recent(carContext)
        return Model(
            running = running,
            devices = devices.size,
            flagged = devices.count { com.rfsentinel.app.ui.LiveWindow.alerting(it) },
            headline = if (running) CarUi.threat(devices, Prefs.alertThreshold(carContext),
                com.rfsentinel.app.online.AmbientThreats.top(carContext)) else null,
            camera = CarUi.nextCamera(carContext),
            alerts = alerts.size,
            newestAlert = alerts.firstOrNull(),
            silenced = CarUi.silencedText(carContext),
            liveMap = liveMapAvailable(carContext),
            cellSummary = if (running) CellsScreen.summary() else null,
            screenOff = running && !phoneScreenOn(carContext)
        )
    }

    override fun contentKey(): Any {
        val m = model()
        val now = System.currentTimeMillis()
        return listOf(
            m.running, m.devices, m.flagged, m.headline, m.camera?.first?.osmId, m.camera?.second?.let { (it / 50).toInt() },
            m.alerts, m.newestAlert?.time, m.newestAlert?.let { CarUi.ageText(now - it.time) }, m.silenced, m.liveMap,
            m.cellSummary, Prefs.alertsMuted(carContext), m.screenOff
        )
    }

    override fun render(): Template {
        val m = model()
        val now = System.currentTimeMillis()
        val rows = ArrayList<Row>()
        rows += if (m.running) {
            val (color, text) = m.headline!!
            row(text, listOf("Scanning · ${m.devices} nearby · ${m.flagged} flagged", m.silenced,
                if (m.screenOff) SCREEN_OFF_SHORT else "").filter { it.isNotEmpty() }.joinToString(" · "),
                icon = R.drawable.ic_car_warning, color = color, browsable = true) {
                screenManager.push(DeviceListScreen(carContext, DeviceFilter.FLAGGED))
            }
        } else {
            row("Not scanning", "Tap Start to listen for nearby equipment", icon = R.drawable.ic_tile_scan)
        }
        m.camera?.let { (cam, d) ->
            rows += row("${cam.label} ahead", NearbyMapScreen.distanceText(d) + (cam.operator?.let { " · $it" } ?: ""),
                icon = R.drawable.ic_car_warning, color = NearbyMapScreen.cameraColor(cam.type), browsable = true) {
                screenManager.push(PlaceFocusScreen.forCamera(carContext, cam))
            }
        }
        rows += row("Recent alerts (${m.alerts})",
            m.newestAlert?.let { "${it.label} · ${CarUi.ageText(now - it.time)}" } ?: "None yet",
            icon = R.drawable.ic_car_history, browsable = true) { screenManager.push(RecentAlertsScreen(carContext)) }
        rows += row(if (m.liveMap) "Live map & navigation" else "Map: devices & cameras around me",
            icon = R.drawable.ic_car_navigate, browsable = true) {
            screenManager.push(if (m.liveMap) LiveMapScreen(carContext) else DevicesMapScreen(carContext))
        }
        rows += row("Nearby devices (${m.devices})", "Flagged, trackers, drones, favorites, new, external...",
            icon = R.drawable.ic_car_drone, browsable = true) { screenManager.push(NearbyDevicesScreen(carContext)) }
        if (m.camera == null && m.cellSummary != null) {
            rows += row("Cell towers", m.cellSummary, icon = R.drawable.ic_car_tower, browsable = true) {
                screenManager.push(CellsScreen(carContext))
            }
        }
        rows += row("More", "Snooze alerts, voice, cell towers, hardware", icon = R.drawable.ic_car_more, browsable = true) {
            screenManager.push(MoreScreen(carContext))
        }
        // More must stay reachable even when the host limits the row count.
        val limit = CarUi.listLimit(carContext)
        val shown = if (rows.size <= limit) rows else rows.take(limit - 1) + rows.last()
        val startStop = action(if (m.running) "Stop" else "Start",
            if (m.running) R.drawable.ic_car_stop else R.drawable.ic_car_play) {
            if (ScanForegroundService.isRunning) stop() else start()
        }
        // Icon-only: Android Auto permits a single titled button in this strip.
        val muted = Prefs.alertsMuted(carContext)
        val muteButton = action(icon = if (muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume) {
            val nowMuted = !Prefs.alertsMuted(carContext)
            if (nowMuted) com.rfsentinel.app.util.Voice.silence()
            Prefs.setAlertsMuted(carContext, nowMuted)
            if (!nowMuted) Prefs.setAlertsSnoozedUntil(carContext, 0L)
            toast(if (nowMuted) "Alert sound muted" else "Alert sound on - plays through the car speakers")
            invalidate()
        }
        return listTemplate("RF Sentinel", shown, header = Action.APP_ICON,
            actions = ActionStrip.Builder().addAction(startStop).addAction(muteButton).build())
    }

    private fun start() {
        val missing = Permissions.missingRequired(carContext)
        if (missing.isNotEmpty()) {
            // Shows the Android permission dialog on the phone screen.
            try {
                carContext.requestPermissions(missing) { granted, _ ->
                    if (Permissions.missingRequired(carContext).isEmpty() && granted.isNotEmpty()) start()
                    else toast("Grant location and nearby-devices on your phone")
                }
            } catch (e: Exception) {
                toast("Open RF Sentinel on your phone to grant permissions")
            }
            return
        }
        try {
            ScanForegroundService.start(carContext)
            // Android cuts Bluetooth scanning back while the phone screen is off: say so up front.
            toast(if (phoneScreenOn(carContext)) "Scanning started" else SCREEN_OFF_WARNING)
        } catch (e: Exception) {
            toast("Couldn't start - open RF Sentinel on your phone")
        }
        invalidate()
    }

    private fun stop() {
        ScanForegroundService.stop(carContext)
        toast("Scanning stopped")
        invalidate()
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()

    companion object {
        const val SCREEN_OFF_SHORT = "Phone screen off: Bluetooth limited"
        /** Fits the two lines a car toast shows. */
        const val SCREEN_OFF_WARNING = "Phone screen off: Android limits Bluetooth scanning. WiFi, cells and cameras still work."

        /** True while the phone's own screen is on (Android Auto projects to the car with it off). */
        fun phoneScreenOn(context: android.content.Context): Boolean = runCatching {
            context.getSystemService(android.os.PowerManager::class.java).isInteractive
        }.getOrDefault(true)

        /** Our own pannable map needs car API 7 and the surface permission (not every build declares it). */
        fun liveMapAvailable(carContext: CarContext): Boolean = runCatching {
            carContext.carAppApiLevel >= androidx.car.app.versioning.CarAppApiLevels.LEVEL_7 &&
                carContext.checkSelfPermission("androidx.car.app.ACCESS_SURFACE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
}
