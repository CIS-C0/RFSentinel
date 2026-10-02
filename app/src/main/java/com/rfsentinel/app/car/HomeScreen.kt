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
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

/**
 * Android Auto home: threat headline, live status, Start/Stop button, and
 * navigation rows into the device lists.
 */
class HomeScreen(carContext: CarContext) : LiveScreen(carContext) {

    @Suppress("DEPRECATION") // setTitle/setActionStrip keep compatibility with older Android Auto hosts
    override fun onGetTemplate(): Template {
        val running = ScanForegroundService.isRunning
        val devices = if (running) DeviceRegistry.snapshot() else emptyList()
        val flagged = devices.count { CarUi.isFlagged(it) }
        val dronesTrackers = devices.count { s ->
            s.remoteId != null || s.hits.any { it.category == Category.DRONE || it.category == Category.TRACKER }
        }

        val items = ItemList.Builder()

        // 1. Headline
        if (running) {
            val (color, text) = CarUi.threat(devices, Prefs.alertThreshold(carContext))
            items.addItem(
                Row.Builder()
                    .setTitle(text)
                    .addText("Scanning · ${devices.size} nearby · $flagged flagged")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_warning, color))
                    .build()
            )
        } else {
            items.addItem(
                Row.Builder()
                    .setTitle("Not scanning")
                    .addText("Tap Start to listen for nearby equipment")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_tile_scan))
                    .build()
            )
        }

        // 2-4. Navigation into the lists
        items.addItem(navRow("Flagged nearby", flagged, R.drawable.ic_car_warning, DeviceListScreen.Filter.FLAGGED))
        items.addItem(navRow("Drones & trackers", dronesTrackers, R.drawable.ic_car_drone, DeviceListScreen.Filter.DRONES_TRACKERS))
        items.addItem(navRow("All nearby devices", devices.size, R.drawable.ic_car_list, DeviceListScreen.Filter.ALL))
        // Our own map needs Car API 7 and the surface permission (not every build declares it).
        val liveMap = runCatching {
            carContext.carAppApiLevel >= androidx.car.app.versioning.CarAppApiLevels.LEVEL_7 &&
                carContext.checkSelfPermission("androidx.car.app.ACCESS_SURFACE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        items.addItem(
            Row.Builder()
                .setTitle(if (liveMap) "Live map & navigation" else "Map: devices & cameras around me")
                .setImage(CarUi.icon(carContext, R.drawable.ic_car_navigate))
                .setBrowsable(true)
                .setOnClickListener {
                    // Our own pannable map needs car API 7; older cars get the car-drawn map.
                    screenManager.push(if (liveMap) LiveMapScreen(carContext) else DevicesMapScreen(carContext))
                }
                .build()
        )

        // 5. Spoken announcements in the car (alongside the alert tone)
        val voice = Prefs.carVoice(carContext)
        val muted = Prefs.alertsMuted(carContext)
        items.addItem(
            Row.Builder()
                .setTitle("Spoken alerts in car: ${if (voice) "On" else "Off"}")
                .addText(
                    when {
                        muted -> "All alert sound is muted - tap the speaker button to unmute"
                        voice -> "Says e.g. \"Axon body camera nearby\" - tap to turn off"
                        else -> "Tone only - tap to hear what was detected"
                    }
                )
                .setImage(CarUi.icon(carContext, if (voice && !muted) R.drawable.ic_car_voice else R.drawable.ic_car_voice_off))
                .setOnClickListener {
                    Prefs.setCarVoice(carContext, !voice)
                    invalidate()
                }
                .build()
        )

        val startStop = Action.Builder()
            .setTitle(if (running) "Stop" else "Start")
            .setIcon(CarUi.icon(carContext, if (running) R.drawable.ic_car_stop else R.drawable.ic_car_play))
            .setOnClickListener { if (ScanForegroundService.isRunning) stop() else start() }
            .build()

        // Master mute: alert tone + voice (plays through the car speakers otherwise).
        // Icon-only: Android Auto allows a single titled button in this strip.
        val muteButton = Action.Builder()
            .setIcon(CarUi.icon(carContext, if (muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume))
            .setOnClickListener {
                val nowMuted = !Prefs.alertsMuted(carContext)
                Prefs.setAlertsMuted(carContext, nowMuted)
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

    private fun navRow(title: String, count: Int, icon: Int, filter: DeviceListScreen.Filter) =
        Row.Builder()
            .setTitle("$title ($count)")
            .setImage(CarUi.icon(carContext, icon))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(DeviceListScreen(carContext, filter)) }
            .build()

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
            toast("Scanning started")
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
}
