package com.rfsentinel.app.car

import android.util.Log
import android.location.Location
import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
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

    protected fun row(
        title: CharSequence, vararg text: CharSequence, @DrawableRes icon: Int? = null,
        color: Int? = null, browsable: Boolean = false, onClick: (() -> Unit)? = null
    ): Row = Row.Builder().setTitle(title).apply {
        text.forEach { addText(it) }
        icon?.let { setImage(CarUi.icon(carContext, it, color)) }
        if (browsable) setBrowsable(true)
        onClick?.let { setOnClickListener { it() } }
    }.build()

    protected fun action(title: String? = null, @DrawableRes icon: Int? = null, onClick: () -> Unit): Action =
        Action.Builder().apply {
            title?.let { setTitle(it) }
            icon?.let { setIcon(CarUi.icon(carContext, it)) }
            setOnClickListener { onClick() }
        }.build()

    protected fun itemList(rows: List<Row>, noItems: String? = null): ItemList = ItemList.Builder().apply {
        rows.forEach { addItem(it) }
        noItems?.let { setNoItemsMessage(it) }
    }.build()

    @Suppress("DEPRECATION") // Older Android Auto hosts use these title/header APIs.
    protected fun listTemplate(
        title: String, rows: List<Row>, noItems: String? = null,
        header: Action = Action.BACK, actions: ActionStrip? = null
    ): ListTemplate = ListTemplate.Builder().setTitle(title).setHeaderAction(header)
        .setSingleList(itemList(rows, noItems)).apply { actions?.let { setActionStrip(it) } }.build()

    protected fun mapTemplate(
        title: String, rows: List<Row>, action: Action, location: Location? = null, noItems: String? = null
    ): PlaceListMapTemplate = PlaceListMapTemplate.Builder()
        .setTitle(title).setHeaderAction(Action.BACK).setCurrentLocationEnabled(location != null)
        .setActionStrip(ActionStrip.Builder().addAction(action).build())
        .setItemList(itemList(rows, noItems)).apply {
            location?.let { setAnchor(Place.Builder(CarLocation.create(it.latitude, it.longitude)).build()) }
        }.build()

    protected fun mapRow(
        title: String, detail: String, lat: Double, lon: Double, distanceM: Double?,
        color: CarColor, label: String?, onClick: (() -> Unit)? = null
    ): Row {
        val marker = PlaceMarker.Builder().setColor(color).apply { label?.let { setLabel(it) } }.build()
        val place = Place.Builder(CarLocation.create(lat, lon)).setMarker(marker).build()
        // The host formats the distance. Rows without one must be browsable.
        val text = if (distanceM == null) detail else SpannableString("  · $detail").apply {
            setSpan(DistanceSpan.create(NearbyMapScreen.distanceOf(distanceM)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        return Row.Builder().setTitle(title).addText(text).setMetadata(Metadata.Builder().setPlace(place).build())
            .apply {
                if (distanceM == null) setBrowsable(true)
                onClick?.let { setOnClickListener { it() } }
            }.build()
    }

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
