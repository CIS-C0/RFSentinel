package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.View
import com.rfsentinel.app.R
import com.rfsentinel.app.car.CarUi
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.nav.NavFormat
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.online.RoadMatch
import com.rfsentinel.app.online.RouteAhead
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Alerts as they come in, for screens that want to show them (the full screen map): every alert RF Sentinel
 * raises - a flagged device, a known camera, a Waze report, a cell warning - is published here once.
 */
object AlertCards {

    data class Card(
        val key: String, val mac: String?, val label: String, val evidence: String, val category: Category,
        val confidence: Int, val following: Boolean, val rssi: Int?, val lat: Double?, val lon: Double?, val atMs: Long
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Card) -> Unit>()

    /** The newest alert, so a screen that opens a moment after one can still show it. */
    @Volatile var latest: Card? = null
        private set

    /** Any thread; listeners are called on the main thread. */
    fun publish(card: Card) {
        latest = card
        main.post { listeners.forEach { runCatching { it(card) } } }
    }

    /** Calls [onAlert] for each new alert; returns the function that stops it. */
    fun addListener(onAlert: (Card) -> Unit): () -> Unit {
        listeners.add(onAlert)
        return { listeners.remove(onAlert) }
    }
}

/**
 * The alert card over the full screen map: a rounded dark card under the turn banner with what it is, how
 * sure, and where, that goes by itself after a few seconds (longer for a strong match or something following
 * you) or when dismissed; a tap on it looks at the place on the map. Respects Discreet mode (just "New
 * alert") and the alert snooze. Silent: the sound and voice are the alert's own.
 */
internal class AlertCardController(
    private val ctx: Context,
    private val b: ActivityMapBinding,
    private val fix: () -> Location?,
    private val lookAt: (Double, Double) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private val hideLater = Runnable { hide() }
    private var current: AlertCards.Card? = null

    init {
        b.alertClose.setOnClickListener { hide() }
        b.alertCard.setOnClickListener { current?.let { c -> if (c.lat != null && c.lon != null) lookAt(c.lat, c.lon) } }
    }

    fun show(c: AlertCards.Card) {
        if (System.currentTimeMillis() < Prefs.alertsSnoozedUntil(ctx)) return
        current = c
        val discreet = Prefs.discreetMode(ctx)
        val color = c.category.colorArgb

        b.alertContext.text = contextOf(c)
        b.alertTag.text = if (discreet) "" else c.category.shortTag
        b.alertTag.setTextColor(color)
        b.alertTitle.text = if (discreet) "New alert" else if (c.following) "Following you: ${c.label}" else c.label
        val detail = if (discreet) "" else listOfNotNull(distanceOf(c), c.evidence.takeIf { it.isNotBlank() }).joinToString(" · ")
        b.alertDetail.text = detail
        b.alertDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        val footer = if (discreet) "" else footerOf(c)
        b.alertFooter.text = footer
        b.alertFooter.visibility = if (footer.isBlank()) View.GONE else View.VISIBLE
        b.alertFooter.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(color), null, null, null)

        b.alertIcon.setImageResource(iconOf(c.category))
        b.alertIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        b.alertIcon.background = GradientDrawable().apply { cornerRadius = 12 * d; setColor((color and 0x00FFFFFF) or 0x33000000) }

        b.alertCard.animate().cancel()
        if (b.alertCard.visibility != View.VISIBLE) {
            b.alertCard.alpha = 0f; b.alertCard.translationY = -12 * d
            b.alertCard.visibility = View.VISIBLE
            b.alertCard.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else b.alertCard.alpha = 1f
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, if (c.following || c.confidence >= Tier.STRONG.min) LONG_MS else SHORT_MS)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        current = null
        b.alertCard.animate().cancel()
        b.alertCard.visibility = View.GONE
    }

    /** "FOLLOWING YOU", "ON YOUR ROUTE" (guided, and it is on the part still ahead), else "NEARBY". */
    private fun contextOf(c: AlertCards.Card): String {
        if (c.following) return "FOLLOWING YOU"
        val route = Navigator.route; val st = Navigator.state
        if (c.lat != null && c.lon != null && Navigator.active && route != null && st != null && st.offM <= Navigator.OFF_ROUTE_M &&
            RouteAhead.verdict(route, st.alongM, c.lat, c.lon, 2_000.0) == RoadMatch.Verdict.ON_MY_ROAD) return "ON YOUR ROUTE"
        return "NEARBY"
    }

    /** How far it is: a device's own estimate, or the straight line to a place on the map. */
    private fun distanceOf(c: AlertCards.Card): String? {
        c.mac?.let { m -> DeviceRegistry.get(m)?.distanceM?.takeIf { it > 0 }?.let { return DeviceIntel.formatDistance(it) } }
        val me = fix() ?: return null
        if (c.lat == null || c.lon == null) return null
        return NavFormat.distance(DeviceRegistry.metersBetween(me.latitude, me.longitude, c.lat, c.lon), NavFormat.miles(ctx))
    }

    /** "Strong match · 2 more flagged nearby". */
    private fun footerOf(c: AlertCards.Card): String {
        val tier = Tier.of(c.confidence).label.substringBefore(" -").replaceFirstChar { it.uppercase() } + " match"
        val others = DeviceRegistry.snapshot().count { CarUi.isFlagged(it) && !it.mac.equals(c.mac, ignoreCase = true) }
        return listOfNotNull(tier, others.takeIf { it > 0 }?.let { "$it more flagged nearby" }).joinToString(" · ")
    }

    private fun iconOf(cat: Category) = when (cat) {
        Category.DRONE -> R.drawable.ic_car_drone
        Category.CELL_ANOMALY, Category.GNSS -> R.drawable.ic_car_tower
        else -> R.drawable.ic_car_warning
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setSize((8 * d).toInt(), (8 * d).toInt())
    }

    private companion object {
        const val SHORT_MS = 8_000L
        const val LONG_MS = 12_000L
    }
}
