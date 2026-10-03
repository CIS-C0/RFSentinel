package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.data.TrackerMutes
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs

/**
 * Ignoring a tracker from the car, with the phone's three choices: AirTags and
 * other tags change their address about daily, so they can't be whitelisted.
 * Only ignore a tag you know - a hidden tracker would stay silent too.
 */
class TrackerIgnoreScreen(carContext: CarContext, private val mac: String) : SafeScreen(carContext) {

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun buildTemplate(): Template {
        val items = ItemList.Builder()
        items.addItem(Row.Builder()
            .setTitle("It's mine - keep ignoring it")
            .addText("Follows it when its address changes (best effort)")
            .setOnClickListener { mute(follow = true, "Ignored - followed when its address changes") }
            .build())
        items.addItem(Row.Builder()
            .setTitle("Ignore it today only")
            .addText("Until its address changes, at most 24 h")
            .setOnClickListener { mute(follow = false, "Ignored until its address changes (at most 24 h)") }
            .build())
        items.addItem(Row.Builder()
            .setTitle("Pause all tracker follow warnings for 24 h")
            .addText("Only ignore a tag you know - a hidden tracker would stay silent too")
            .setOnClickListener {
                Prefs.setTrackerFollowPausedUntil(carContext, System.currentTimeMillis() + 24 * 3_600_000L)
                done("Tracker follow warnings paused for 24 hours")
            }
            .build())
        return ListTemplate.Builder()
            .setTitle("Ignore this tracker?")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun mute(follow: Boolean, message: String) {
        val snap = DeviceRegistry.get(mac)
        if (snap == null) { done("Tracker no longer in range"); return }
        TrackerMutes.mute(carContext, mac, snap.best?.label ?: "Tracker", snap.rssi, follow)
        done(message)
    }

    private fun done(message: String) {
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
        // Back to the device (only if this screen is still on top).
        runCatching { if (screenManager.top === this) screenManager.pop() }
    }
}
