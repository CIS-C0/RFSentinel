package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.receiver.AlertActionReceiver
import com.rfsentinel.app.util.Prefs

/**
 * Quick settings and status for the car: snooze alerts for 30 minutes, the
 * spoken-alert options, cell towers, the connected hardware and whether
 * AirTags count as trackers. Everything else stays in the phone's Settings.
 */
class MoreScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 5_000L) {

    override fun contentKey(): Any = listOf(
        CarUi.silencedText(carContext), Prefs.carVoice(carContext), Prefs.shortVoice(carContext),
        Prefs.excludeAirTags(carContext), CellsScreen.summary()
    )

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun render(): Template {
        val now = System.currentTimeMillis()
        val snoozed = now < Prefs.alertsSnoozedUntil(carContext)
        val voice = Prefs.carVoice(carContext)
        val short = Prefs.shortVoice(carContext)
        val noAirTags = Prefs.excludeAirTags(carContext)
        val items = ItemList.Builder()

        items.addItem(Row.Builder()
            .setTitle(if (snoozed) "Resume alert sound" else "Snooze alerts 30 min")
            .addText(if (snoozed) CarUi.silencedText(carContext) else "No alert sound or voice for half an hour")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_snooze))
            .setOnClickListener {
                if (snoozed) {
                    Prefs.setAlertsSnoozedUntil(carContext, 0L)
                    toast("Alert sound back on")
                } else {
                    AlertActionReceiver.snooze(carContext)
                    toast("Alerts snoozed for 30 minutes")
                }
                invalidate()
            }
            .build())

        items.addItem(Row.Builder()
            .setTitle("Spoken alerts in car: ${if (voice) "On" else "Off"}")
            .addText(if (voice) "Says what was detected - tap to turn off" else "Tone only - tap to hear what was detected")
            .setImage(CarUi.icon(carContext, if (voice) R.drawable.ic_car_voice else R.drawable.ic_car_voice_off))
            .setOnClickListener { Prefs.setCarVoice(carContext, !voice); invalidate() }
            .build())

        items.addItem(Row.Builder()
            .setTitle("Short spoken alerts: ${if (short) "On" else "Off"}")
            .addText(if (short) "\"Body cam\", \"Police car\", \"Speed camera, 50\"" else "Full labels, e.g. \"Axon body camera nearby\"")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_voice))
            .setOnClickListener {
                Prefs.setShortVoice(carContext, !short)
                com.rfsentinel.app.util.AlertPlayer.testVoice(carContext, !short)
                invalidate()
            }
            .build())

        items.addItem(Row.Builder()
            .setTitle("Cell towers")
            .addText(CellsScreen.summary() ?: "Serving cell, neighbours and fake-cell checks")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_tower))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CellsScreen(carContext)) }
            .build())

        items.addItem(Row.Builder()
            .setTitle("Hardware")
            .addText("ESP32, Marauder, OUI-SPY, USB WiFi adapter")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_usb))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(HardwareScreen(carContext)) }
            .build())

        items.addItem(Row.Builder()
            .setTitle("AirTags count as trackers: ${if (noAirTags) "No" else "Yes"}")
            .addText(if (noAirTags) "Apple Find My tags are left out - tap to include" else "Tap to leave Apple Find My tags out")
            .setImage(CarUi.icon(carContext, R.drawable.ic_car_drone))
            .setOnClickListener { Prefs.setExcludeAirTags(carContext, !noAirTags); invalidate() }
            .build())

        val list = ItemList.Builder()
        // Keep the car's row limit (the order above is by importance).
        val built = items.build().items.take(CarUi.listLimit(carContext))
        built.forEach { list.addItem(it) }

        return ListTemplate.Builder()
            .setTitle("More")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
}
