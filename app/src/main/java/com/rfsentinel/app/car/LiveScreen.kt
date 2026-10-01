package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A car screen that re-renders on a timer while visible. Android Auto treats a
 * refresh of the same template type and title as an update, not a new step, so
 * this doesn't use up the driver's navigation-depth budget.
 */
abstract class LiveScreen(
    carContext: CarContext,
    private val periodMs: Long = 3_000L,
    /** Map screens: precise GPS while shown, so devices are placed where they were heard. */
    preciseLocation: Boolean = false
) : Screen(carContext) {
    init {
        if (preciseLocation) lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) =
                com.rfsentinel.app.service.ScanForegroundService.mapShown(carContext)
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) =
                com.rfsentinel.app.service.ScanForegroundService.mapHidden(carContext)
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(periodMs)
                    invalidate()
                }
            }
        }
    }
}
