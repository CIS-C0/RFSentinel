package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.databinding.ActivityMainBinding
import com.rfsentinel.app.util.Prefs

/**
 * The main screen's views - live map, radar, list - in any mix: one fills the screen, two or
 * three stack (side by side in landscape) with a handle between each pair to share the space.
 * The Map view is the full map ([MapFragment]: every layer, button and menu), following the
 * main screen's filter chip. The mix and the sizes are remembered.
 */
class MainPanes(private val activity: AppCompatActivity, private val b: ActivityMainBinding) {

    private val density = activity.resources.displayMetrics.density
    private val mapBorder = GradientDrawable()
    private val radarBorder = GradientDrawable()

    var panes: Set<String> = Prefs.viewPanes(activity)
        private set

    val mapShown get() = "map" in panes
    val radarShown get() = "radar" in panes
    val listShown get() = "list" in panes

    private companion object { const val MAP_TAG = "main_map" }

    init {
        val radius = 16f * density
        for ((pane, border) in listOf(b.mapPane to mapBorder, b.radarPane to radarBorder)) {
            pane.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            border.cornerRadius = radius
            pane.foreground = border
        }
        b.mapPane.clipToOutline = true
        for (grip in listOf(b.gripA, b.gripB)) {
            grip.background = GradientDrawable().apply {
                cornerRadius = 3 * density
                setColor((ChipStyle.onSurface(activity) and 0x00FFFFFF) or 0x66000000)
            }
            grip.alpha = 0.7f
        }
        setupHandle(b.handleA, b.gripA) { pairA() }
        setupHandle(b.handleB, b.gripB) { "radar" to "list" }
    }

    fun setPanes(value: Set<String>) {
        Prefs.setViewPanes(activity, value)
        panes = Prefs.viewPanes(activity)
        apply()
    }

    /** Lays the chosen views out; call again after a rotation or a change of mix. */
    fun apply() {
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        b.contentArea.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val several = panes.size > 1
        b.mapPane.visibility = if (mapShown) View.VISIBLE else View.GONE
        b.radarPane.visibility = if (radarShown) View.VISIBLE else View.GONE
        b.listFrame.visibility = if (listShown) View.VISIBLE else View.GONE
        b.handleA.visibility = if (mapShown && (radarShown || listShown)) View.VISIBLE else View.GONE
        b.handleB.visibility = if (radarShown && listShown) View.VISIBLE else View.GONE
        // Framed when they share the screen; the radar alone fills it as before.
        val (accent, _) = ChipStyle.accent(activity)
        mapBorder.setStroke((2 * density).toInt(), accent)
        radarBorder.setStroke(if (several) (2 * density).toInt() else 0, accent)
        b.radarPane.clipToOutline = several
        val handle = (20 * density).toInt()
        for ((h, grip) in listOf(b.handleA to b.gripA, b.handleB to b.gripB)) {
            h.layoutParams = (h.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (landscape) handle else LinearLayout.LayoutParams.MATCH_PARENT
                height = if (landscape) LinearLayout.LayoutParams.MATCH_PARENT else handle
                weight = 0f
            }
            grip.layoutParams = (grip.layoutParams as FrameLayout.LayoutParams).apply {
                width = ((if (landscape) 5 else 40) * density).toInt()
                height = ((if (landscape) 40 else 5) * density).toInt()
            }
        }
        for (p in Prefs.PANES) {
            viewOf(p).layoutParams = (viewOf(p).layoutParams as LinearLayout.LayoutParams).apply {
                weight = Prefs.paneWeight(activity, p)
                if (landscape) { width = 0; height = LinearLayout.LayoutParams.MATCH_PARENT }
                else { width = LinearLayout.LayoutParams.MATCH_PARENT; height = 0 }
                topMargin = if (!landscape && p != "list" && p == Prefs.PANES.firstOrNull { it in panes }) (6 * density).toInt() else 0
            }
        }
        b.contentArea.requestLayout()
        applyMap()
    }

    private fun viewOf(pane: String): View = when (pane) {
        "map" -> b.mapPane
        "radar" -> b.radarPane
        else -> b.listFrame
    }

    /** The first handle sits after the map: it joins the map to whatever comes next. */
    private fun pairA(): Pair<String, String> = "map" to (if (radarShown) "radar" else "list")

    /** The full map (every layer, button and menu of the map screen), in the Map view. */
    private fun applyMap() {
        val fm = activity.supportFragmentManager
        val current = fm.findFragmentByTag(MAP_TAG) as? MapFragment
        if (mapShown && current == null) {
            fm.beginTransaction()
                .replace(b.previewMapHolder.id, MapFragment.create(android.os.Bundle().apply { putBoolean(MapFragment.ARG_EMBEDDED, true) }), MAP_TAG)
                .runOnCommit { mapFragment()?.setFilter(filter) }
                .commitAllowingStateLoss()
        } else if (!mapShown && current != null) {
            // Hidden: the map goes away entirely, so its GPS and downloads stop with it.
            fm.beginTransaction().remove(current).commitAllowingStateLoss()
        }
    }

    private fun mapFragment() = activity.supportFragmentManager.findFragmentByTag(MAP_TAG) as? MapFragment

    private var filter = DeviceFilter.ALL

    /** The main screen's filter chip also picks what the map shows. */
    fun setFilter(f: DeviceFilter) {
        filter = f
        mapFragment()?.setFilter(f)
    }

    /** A handle shares the space of the two views either side of it (each keeps at least 15 %). */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandle(handle: View, grip: View, pair: () -> Pair<String, String>) {
        var first = 0f; var second = 0f
        handle.setOnTouchListener { _, e ->
            val (p, q) = pair()
            val before = viewOf(p); val after = viewOf(q)
            val landscape = b.contentArea.orientation == LinearLayout.HORIZONTAL
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> grip.alpha = 1f
                MotionEvent.ACTION_MOVE -> {
                    val start = IntArray(2).also { before.getLocationOnScreen(it) }
                    val end = IntArray(2).also { after.getLocationOnScreen(it) }
                    val from = if (landscape) start[0] else start[1]
                    val to = if (landscape) end[0] + after.width else end[1] + after.height
                    if (to > from) {
                        val pos = (if (landscape) e.rawX else e.rawY) - from
                        val f = (pos / (to - from)).coerceIn(0.15f, 0.85f)
                        val total = Prefs.paneWeight(activity, p).let { if (first + second > 0) first + second else it + Prefs.paneWeight(activity, q) }
                        first = total * f; second = total * (1 - f)
                        (before.layoutParams as LinearLayout.LayoutParams).weight = first
                        (after.layoutParams as LinearLayout.LayoutParams).weight = second
                        b.contentArea.requestLayout()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grip.alpha = 0.7f
                    if (first + second > 0) {
                        Prefs.setPaneWeight(activity, p, first)
                        Prefs.setPaneWeight(activity, q, second)
                    }
                    first = 0f; second = 0f
                }
            }
            true
        }
    }

    /** Once a second from the main screen: the frames take the colour of the strongest alert in range. */
    fun update(alertColor: Int?) {
        val accent = ChipStyle.accent(activity).first
        val width = ((if (alertColor != null) 3 else 2) * density).toInt()
        mapBorder.setStroke(width, alertColor ?: accent)
        if (panes.size > 1) radarBorder.setStroke(width, alertColor ?: accent)
    }
}
