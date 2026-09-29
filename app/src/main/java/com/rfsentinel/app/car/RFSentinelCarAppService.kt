package com.rfsentinel.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point (Car App Library). The car shows template screens
 * driven by this service; scanning itself still runs in ScanForegroundService
 * on the phone.
 */
class RFSentinelCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            // Lets the Desktop Head Unit and other test hosts connect to debug builds.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val mac = intent.getStringExtra(EXTRA_MAC)
            if (mac == null) return HomeScreen(carContext)
            // Opened from an alert: Home underneath, device on top (Back returns home).
            carContext.getCarService(ScreenManager::class.java).push(HomeScreen(carContext))
            return DeviceDetailScreen(carContext, mac)
        }

        override fun onNewIntent(intent: Intent) {
            intent.getStringExtra(EXTRA_MAC)?.let { mac: String ->
                val screenManager = carContext.getCarService(ScreenManager::class.java)
                screenManager.popToRoot()
                screenManager.push(DeviceDetailScreen(carContext, mac))
            }
        }
    }

    companion object {
        const val EXTRA_MAC = "com.rfsentinel.app.car.MAC"
    }
}
