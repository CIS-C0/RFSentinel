package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.receiver.AlertActionReceiver
import com.rfsentinel.app.util.Prefs

/**
 * Quick settings and status for the car: snooze alerts (the length set under Alerts), the
 * spoken-alert options, cell towers, the connected hardware and whether
 * AirTags count as trackers. Everything else stays in the phone's Settings.
 */
class MoreScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 5_000L) {

    override fun contentKey(): Any = listOf(
        CarUi.silencedText(carContext), Prefs.carVoice(carContext), Prefs.shortVoice(carContext),
        Prefs.excludeAirTags(carContext), CellsScreen.summary()
    )

    override fun render(): Template {
        val snoozed = System.currentTimeMillis() < Prefs.alertsSnoozedUntil(carContext)
        val mins = Prefs.snoozeMinutes(carContext)
        val voice = Prefs.carVoice(carContext)
        val short = Prefs.shortVoice(carContext)
        val noAirTags = Prefs.excludeAirTags(carContext)
        val rows = listOf(
            row(if (snoozed) "Resume alert sound" else "Snooze alerts $mins min",
                if (snoozed) CarUi.silencedText(carContext) else "No alert sound or voice for $mins minutes",
                icon = R.drawable.ic_car_snooze) {
                if (snoozed) {
                    Prefs.setAlertsSnoozedUntil(carContext, 0L)
                    toast("Alert sound back on")
                } else {
                    AlertActionReceiver.snooze(carContext)
                    toast("Alerts snoozed for $mins minutes")
                }
                invalidate()
            },
            row("Save a snapshot", "Keeps the device list as it is now, to look at later on the phone",
                icon = R.drawable.ic_car_usb) {
                com.rfsentinel.app.service.ScanForegroundService.snapshotNow(carContext) { toast(it) }
            },
            row("Spoken alerts in car: ${if (voice) "On" else "Off"}",
                if (voice) "Says what was detected - tap to turn off" else "Tone only - tap to hear what was detected",
                icon = if (voice) R.drawable.ic_car_voice else R.drawable.ic_car_voice_off) {
                Prefs.setCarVoice(carContext, !voice)
                invalidate()
            },
            row("Short spoken alerts: ${if (short) "On" else "Off"}",
                if (short) "\"Body cam\", \"Police car\", \"Speed camera, 50\"" else "Full labels, e.g. \"Axon body camera nearby\"",
                icon = R.drawable.ic_car_voice) {
                Prefs.setShortVoice(carContext, !short)
                com.rfsentinel.app.util.AlertPlayer.testVoice(carContext, !short)
                invalidate()
            },
            row("Cell towers", CellsScreen.summary() ?: "Serving cell, neighbours and fake-cell checks",
                icon = R.drawable.ic_car_tower, browsable = true) { screenManager.push(CellsScreen(carContext)) },
            row("Hardware", "ESP32, Marauder, OUI-SPY, USB WiFi adapter",
                icon = R.drawable.ic_car_usb, browsable = true) { screenManager.push(HardwareScreen(carContext)) },
            row("AirTags count as trackers: ${if (noAirTags) "No" else "Yes"}",
                if (noAirTags) "Apple Find My tags are left out - tap to include" else "Tap to leave Apple Find My tags out",
                icon = R.drawable.ic_car_drone) {
                Prefs.setExcludeAirTags(carContext, !noAirTags)
                invalidate()
            }
        )
        return listTemplate("More", rows.take(CarUi.listLimit(carContext)))
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
}
