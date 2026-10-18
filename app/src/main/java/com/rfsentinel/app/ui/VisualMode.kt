package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import com.rfsentinel.app.R

/**
 * Visual or list, per screen: the visual style draws gauges, bars and charts (after Wardrive Go's);
 * the list style is the plain text the screens always had. Each screen remembers its own choice,
 * switched from its top bar or from Settings > Appearance.
 */
object VisualMode {

    enum class Screen(val key: String, val label: String) {
        DEVICE_LIST("list", "Device list"),
        STATUS("status", "Status bar"),
        DEVICE_DETAIL("detail", "Device details"),
        SATELLITES("gnss", "Satellites"),
        CELLS("cells", "Cell towers"),
        WAZE("waze", "Waze status"),
        AIRCRAFT("aircraft", "Aircraft"),
        TRIPS("trips", "Recorded traces")
    }

    private const val PREFS = "rf_sentinel_prefs"
    private const val MENU_ID = 0x7653

    fun on(context: Context, screen: Screen): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("visual_" + screen.key, true)

    fun set(context: Context, screen: Screen, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("visual_" + screen.key, on).apply()

    /** The top bar's list / visual button: shows the style you'd switch to. */
    fun addMenu(menu: Menu, context: Context, screen: Screen) {
        val visual = on(context, screen)
        menu.add(0, MENU_ID, 0, if (visual) "List view" else "Visual view").apply {
            setIcon(if (visual) com.rfsentinel.app.R.drawable.ic_view_list else com.rfsentinel.app.R.drawable.ic_view_visual)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon?.mutate()?.setTint(ChipStyle.onSurface(context))
        }
    }

    /** True when [item] was the list / visual button (the choice is flipped and [apply] runs). */
    fun handle(item: MenuItem, context: Context, screen: Screen, apply: () -> Unit): Boolean {
        if (item.itemId != MENU_ID) return false
        set(context, screen, !on(context, screen))
        apply()
        return true
    }

    // ---- shared look -------------------------------------------------------

    fun tint(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** The antenna check's cards: a light fill of [color] with a thin edge. */
    fun card(context: Context, color: Int = ChipStyle.accent(context).first, fillAlpha: Int = 0x14, strokeAlpha: Int = 0x66,
             radiusDp: Float = 16f) = GradientDrawable().apply {
        val dp = context.resources.displayMetrics.density
        cornerRadius = radiusDp * dp
        setColor(tint(color, fillAlpha))
        if (strokeAlpha > 0) setStroke((1.2f * dp).toInt().coerceAtLeast(1), tint(color, strokeAlpha))
    }

    /** A small rounded label ("5 GHz ch 36", "LTE"), like the antenna check's channel chip. */
    fun chip(view: TextView, color: Int) {
        // Restyled every refresh: skip when nothing changed, so the screen can go idle (TalkBack, tests).
        if (view.getTag(R.id.visual_chip_color) == color) return
        view.setTag(R.id.visual_chip_color, color)
        val dp = view.resources.displayMetrics.density
        view.background = card(view.context, color, 0x26, 0x99, 12f)
        view.setTextColor(color)
        view.setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
    }

    /** Sets [text] only when it differs, so a refresh that changes nothing sends no update to TalkBack. */
    fun text(view: TextView, text: CharSequence) {
        if (view.text.toString() != text.toString()) view.text = text
    }

    fun chip(context: Context, text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        chip(this, color)
    }

    /** A stat tile: a big number over a small caption. */
    fun tile(context: Context, value: String, caption: String, color: Int): View {
        val dp = context.resources.displayMetrics.density
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = card(context, color, 0x1E, 0x55, 14f)
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
            addView(TextView(context).apply {
                text = value; textSize = 22f; setTextColor(color); gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1
            })
            addView(TextView(context).apply {
                text = caption; textSize = 11f; gravity = android.view.Gravity.CENTER; alpha = 0.75f
            })
        }
    }

    /** A row of [tiles] sharing the width equally, with a small gap between them. */
    fun tileRow(context: Context, tiles: List<View>): android.widget.LinearLayout {
        val dp = context.resources.displayMetrics.density
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            tiles.forEachIndexed { i, t ->
                addView(t, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i > 0) marginStart = (6 * dp).toInt()
                })
            }
        }
    }

    /** A colour from red (weak) through amber to green (strong) for a 0..1 [level]. */
    fun levelColor(level: Float): Int = when {
        level >= 0.6f -> 0xFF2E9E5B.toInt()
        level >= 0.35f -> 0xFFE0A33A.toInt()
        else -> 0xFFD0503C.toInt()
    }

    /** Signal strength 0..1 over -100..-30 dBm. */
    fun dbmLevel(dbm: Int?): Float = dbm?.let { ((it + 100) / 70f).coerceIn(0f, 1f) } ?: 0f
}
