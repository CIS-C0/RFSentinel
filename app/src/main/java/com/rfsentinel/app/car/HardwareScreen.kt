package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.esp.EspBoards
import com.rfsentinel.app.esp.OuiSpyBle
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.usb.UsbWifi
import com.rfsentinel.app.util.Prefs

/**
 * What is listening: the phone's own scanners (and the watchdog that restarts a
 * stalled one), ESP32 boards on USB (OUI-Spy, GhostESP, Marauder, through a
 * Flipper Zero), an OUI-SPY board over Bluetooth and a USB WiFi adapter.
 * Read-only: pairing and settings stay on the phone.
 */
class HardwareScreen(carContext: CarContext) : LiveScreen(carContext, periodMs = 3_000L) {

    private fun lines(): List<Triple<String, String, Int>> {
        val running = ScanForegroundService.isRunning
        val now = System.currentTimeMillis()
        val watchdog = ScanForegroundService.lastWatchdogRestart
        val phone = when {
            !running -> "Not scanning"
            watchdog != null && now - watchdog.first < 30 * 60_000L ->
                "Working · ${watchdog.second} scan restarted ${CarUi.ageText(now - watchdog.first)} (it had stalled)"
            else -> "Working · Bluetooth and WiFi"
        }
        val board = Prefs.ouiSpyBoard(carContext)
        return listOf(
            Triple("Phone radios", phone, R.drawable.ic_tile_scan),
            Triple("ESP32 on USB", EspBoards.status.ifBlank {
                if (running) "None connected - OUI-Spy, GhostESP or Marauder on an OTG cable" else "Connects while scanning"
            }, R.drawable.ic_car_usb),
            Triple("OUI-SPY over Bluetooth",
                if (board == null) "Not paired (pair it in Settings on the phone)"
                else "$board · " + OuiSpyBle.status.ifBlank { "connects while scanning" }, R.drawable.ic_car_usb),
            Triple("USB WiFi adapter", UsbWifi.status.ifBlank {
                if (running) "None connected (RTL8811AU / 8821AU or RTL8812BU / 8822BU)" else "Connects while scanning"
            }, R.drawable.ic_car_usb)
        )
    }

    override fun contentKey(): Any = lines()

    @Suppress("DEPRECATION") // setTitle keeps compatibility with older Android Auto hosts
    override fun render(): Template {
        val items = ItemList.Builder()
        lines().take(CarUi.listLimit(carContext)).forEach { (title, text, icon) ->
            items.addItem(Row.Builder().setTitle(title).addText(text.take(120)).setImage(CarUi.icon(carContext, icon)).build())
        }
        return ListTemplate.Builder()
            .setTitle("Hardware")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
