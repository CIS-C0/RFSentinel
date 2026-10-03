package com.rfsentinel.app.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A car screen that never takes the car app down: if building its template
 * fails (bad data, a template limit the car enforces), it shows a short message
 * with Back instead, and the error is logged.
 */
abstract class SafeScreen(carContext: CarContext) : Screen(carContext) {

    /** Builds this screen's template (may throw; [onGetTemplate] catches it). */
    protected abstract fun buildTemplate(): Template

    final override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (e: Exception) {
        Log.w("CarScreen", "${javaClass.simpleName} failed to render", e)
        lastError = "${javaClass.simpleName}: ${e.javaClass.simpleName}"
        MessageTemplate.Builder("Something went wrong showing this screen. Go back and try again.")
            .setTitle("RF Sentinel")
            .setHeaderAction(Action.BACK)
            .build()
    }

    companion object {
        /** Last screen error, for tests and troubleshooting. */
        @Volatile var lastError: String? = null
    }
}

/**
 * A car screen that keeps itself current while visible. Every [periodMs] it
 * asks [contentKey] what it would show; only when that changed does it re-render
 * (Android Auto throttles apps that refresh too often, and redrawing an
 * unchanged map makes some cars flicker). A refresh of the same template type
 * and title doesn't count as a new step against the driver's screen budget.
 */
abstract class LiveScreen(
    carContext: CarContext,
    private val periodMs: Long = 3_000L,
    /** Map screens: precise GPS while shown, so devices are placed where they were heard. */
    preciseLocation: Boolean = false
) : SafeScreen(carContext) {

    /**
     * A cheap summary of what this screen shows (counts, rounded distances...);
     * equal keys mean nothing visible changed. Null: refresh every period.
     */
    protected open fun contentKey(): Any? = null

    private var shownKey: Any? = null

    /** Builds the template and remembers the content it was built from. */
    protected abstract fun render(): Template

    final override fun buildTemplate(): Template {
        shownKey = runCatching { contentKey() }.getOrNull()
        return render()
    }

    /** True when the content changed since the last render (always true without a key). */
    internal fun needsRefresh(): Boolean {
        val key = runCatching { contentKey() }.getOrNull() ?: return true
        return key != shownKey
    }

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
                    if (needsRefresh()) invalidate()
                }
            }
        }
    }
}
