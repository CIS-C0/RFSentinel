package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
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

    override fun buildTemplate(): Template = listTemplate("Ignore this tracker?", listOf(
        row("It's mine - keep ignoring it", "Follows it when its address changes (best effort)") {
            mute(follow = true, "Ignored - followed when its address changes")
        },
        row("Ignore it today only", "Until its address changes, at most 24 h") {
            mute(follow = false, "Ignored until its address changes (at most 24 h)")
        },
        row("Pause all tracker follow warnings for 24 h", "Only ignore a tag you know - a hidden tracker would stay silent too") {
            Prefs.setTrackerFollowPausedUntil(carContext, System.currentTimeMillis() + 24 * 3_600_000L)
            done("Tracker follow warnings paused for 24 hours")
        }
    ))

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
