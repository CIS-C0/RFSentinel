package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
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
            flagged = devices.count { CarUi.isFlagged(it) },
            headline = if (running) CarUi.threat(devices, Prefs.alertThreshold(carContext)) else null,
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

    @Suppress("DEPRECATION") // setTitle/setActionStrip keep compatibility with older Android Auto hosts
    override fun render(): Template {
        val m = model()
        val now = System.currentTimeMillis()
        val rows = ArrayList<Row>()

        // 1. Headline: the threat level; tap for the flagged devices.
        rows += if (m.running) {
            val (color, text) = m.headline!!
            Row.Builder()
                .setTitle(text)
                .addText(listOf("Scanning · ${m.devices} nearby · ${m.flagged} flagged", m.silenced,
                    if (m.screenOff) SCREEN_OFF_SHORT else "")
                    .filter { it.isNotEmpty() }.joinToString(" · "))
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, color))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(DeviceListScreen(carContext, DeviceFilter.FLAGGED)) }
                .build()
        } else {
            Row.Builder()
                .setTitle("Not scanning")
                .addText("Tap Start to listen for nearby equipment")
                .setImage(CarUi.icon(carContext, R.drawable.ic_tile_scan))
                .build()
        }

        // 2. The next known camera on your way (works without scanning too).
        m.camera?.let { (cam, d) ->
            rows += Row.Builder()
                .setTitle("${cam.label} ahead")
                .addText(NearbyMapScreen.distanceText(d) + (cam.operator?.let { " · $it" } ?: ""))
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, NearbyMapScreen.cameraColor(cam.type)))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(PlaceFocusScreen.forCamera(carContext, cam)) }
                .build()
        }

        // 3. Recent alerts (still there after the device has left).
        rows += Row.Builder()
            .setTitle("Recent alerts (${m.alerts})")
            .addText(m.newestAlert?.let { "${it.label} · ${CarUi.ageText(now - it.time)}" } ?: "None yet")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_history))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(RecentAlertsScreen(carContext)) }
            .build()

        // 4. Map: our own pannable map when the car allows it, else the car-drawn one.
        rows += Row.Builder()
            .setTitle(if (m.liveMap) "Live map & navigation" else "Map: devices & cameras around me")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_navigate))
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.push(if (m.liveMap) LiveMapScreen(carContext) else DevicesMapScreen(carContext))
            }
            .build()

        // 5. Everything nearby, filtered like the phone's chips.
        rows += Row.Builder()
            .setTitle("Nearby devices (${m.devices})")
            .addText("Flagged, trackers, drones, favorites, new, external...")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_drone))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(NearbyDevicesScreen(carContext)) }
            .build()

        // 6. Cell towers when there is room (no camera row), then More.
        if (m.camera == null && m.cellSummary != null) {
            rows += Row.Builder()
                .setTitle("Cell towers")
                .addText(m.cellSummary)
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CellsScreen(carContext)) }
                .build()
        }
        rows += Row.Builder()
            .setTitle("More")
            .addText("Snooze alerts, voice, cell towers, hardware")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_more))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(MoreScreen(carContext)) }
            .build()

        // The car shows only its row limit while driving: More must stay reachable.
        val limit = CarUi.listLimit(carContext)
        val shown = if (rows.size <= limit) rows else rows.take(limit - 1) + rows.last()
        val items = ItemList.Builder()
        shown.forEach { items.addItem(it) }

        val running = m.running
        val startStop = Action.Builder()
            .setTitle(if (running) "Stop" else "Start")
            .setIcon(CarUi.icon(carContext, if (running) R.drawable.ic_car_stop else R.drawable.ic_car_play))
            .setOnClickListener { if (ScanForegroundService.isRunning) stop() else start() }
            .build()

        // Master mute: alert tone + voice (plays through the car speakers otherwise).
        // Icon-only: Android Auto allows a single titled button in this strip.
        val muted = Prefs.alertsMuted(carContext)
        val muteButton = Action.Builder()
            .setIcon(CarUi.icon(carContext, if (muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume))
            .setOnClickListener {
                val nowMuted = !Prefs.alertsMuted(carContext)
                if (nowMuted) com.rfsentinel.app.util.Voice.silence()
                Prefs.setAlertsMuted(carContext, nowMuted)
                // Unmuting also ends a snooze: the driver wants sound back now.
                if (!nowMuted) Prefs.setAlertsSnoozedUntil(carContext, 0L)
                toast(if (nowMuted) "Alert sound muted" else "Alert sound on - plays through the car speakers")
                invalidate()
            }
            .build()

        return ListTemplate.Builder()
            .setTitle("RF Sentinel")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(startStop).addAction(muteButton).build())
            .setSingleList(items.build())
            .build()
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
