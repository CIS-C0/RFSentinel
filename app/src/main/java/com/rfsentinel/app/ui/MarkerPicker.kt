package com.rfsentinel.app.ui

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.rfsentinel.app.util.Prefs

/**
 * Settings > Map > Position marker: pick the style (an arrow or a car), its colour and its size, with the result
 * shown on a light and a dark ground as you choose. Each change is saved at once; the map shows it next time it
 * is on screen.
 */
object MarkerPicker {

    /** The marker's height on the map at size 1.0. */
    const val BASE_DP = 56f

    fun heightPx(context: android.content.Context, size: Float) = (BASE_DP * size * context.resources.displayMetrics.density).toInt()

    /** What the map uses: the custom theme's own marker when it has one, else the choice made in Settings. */
    class Choice(val style: String, val color: String, val size: Float) {
        val key get() = "$style|$color|$size"
    }

    fun effective(context: android.content.Context): Choice {
        val t = ThemeManager.custom(context)
        return if (t?.markerStyle != null) Choice(t.markerStyle, t.markerColor ?: "blue", t.markerSize ?: 1.0f)
        else Choice(Prefs.markerStyle(context), Prefs.markerColor(context), Prefs.markerSize(context))
    }

    /** The marker the map draws now. */
    fun current(context: android.content.Context): android.graphics.Bitmap {
        val c = effective(context)
        return MarkerArt.render(MarkerArt.Style.of(c.style), MarkerArt.colorOf(c.color).argb, heightPx(context, c.size))
    }

    /** "Sedan · Blue", for the Settings button. */
    fun summary(context: android.content.Context) =
        MarkerArt.Style.of(Prefs.markerStyle(context)).label + " · " + MarkerArt.colorOf(Prefs.markerColor(context)).label

    /** Settings: picks the marker the map uses when no custom theme sets its own. */
    fun show(activity: AppCompatActivity, onChanged: () -> Unit) =
        pick(activity, Choice(Prefs.markerStyle(activity), Prefs.markerColor(activity), Prefs.markerSize(activity))) { c ->
            Prefs.setMarker(activity, c.style, c.color, c.size); onChanged()
        }

    /** The picker itself: starts from [start], reports every change through [onChanged] (the caller saves it). */
    fun pick(activity: AppCompatActivity, start: Choice, onChanged: (Choice) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun px(dp: Float) = (dp * d).toInt()
        var style = MarkerArt.Style.of(start.style)
        var color = MarkerArt.colorOf(start.color)
        var size = start.size.coerceIn(0.6f, 2.0f)

        // The marker, as big as it is on the map, on a light and a dark ground.
        fun ground(argb: Int) = ImageView(activity).apply {
            background = GradientDrawable().apply { setColor(argb); cornerRadius = px(10f).toFloat() }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(px(8f), px(8f), px(8f), px(8f))
        }
        val light = ground(0xFFE9EDF1.toInt()); val dark = ground(0xFF2B3446.toInt())
        val previews = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val lp = { LinearLayout.LayoutParams(0, px(BASE_DP * 2f + 16f), 1f).apply { marginStart = px(4f); marginEnd = px(4f) } }
            addView(light, lp()); addView(dark, lp())
        }

        val styleCells = HashMap<MarkerArt.Style, Pair<LinearLayout, ImageView>>()
        val grid = GridLayout(activity).apply { columnCount = 5 }
        for (s in MarkerArt.Style.entries) {
            val img = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = TextView(activity).apply { text = s.label; textSize = 10f; gravity = Gravity.CENTER; maxLines = 1 }
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(2f), px(4f), px(2f), px(4f))
                addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72f)))
                addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                contentDescription = s.label
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
            styleCells[s] = cell to img
        }

        val swatches = LinkedHashMap<String, View>()
        val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(2f), px(6f), px(2f), px(6f)) }
        for (sw in MarkerArt.colors) {
            val v = View(activity).apply { contentDescription = sw.label }
            colorRow.addView(v, LinearLayout.LayoutParams(px(36f), px(36f)).apply { marginEnd = px(8f) })
            swatches[sw.key] = v
        }

        val sizeText = TextView(activity).apply { textSize = 14f; setPadding(0, px(8f), 0, 0) }
        // Whole numbers on the slider (6 = 0.6 times ... 20 = 2 times): no rounding errors.
        val slider = Slider(activity).apply { valueFrom = 6f; valueTo = 20f; stepSize = 1f; value = Math.round(size * 10f).toFloat() }

        fun refresh() {
            onChanged(Choice(style.key, color.key, size))
            val real = MarkerArt.render(style, color.argb, heightPx(activity, size))
            val show = if (real.height > px(BASE_DP * 2f)) MarkerArt.render(style, color.argb, px(BASE_DP * 2f)) else real
            light.setImageDrawable(BitmapDrawable(activity.resources, show)); dark.setImageDrawable(BitmapDrawable(activity.resources, show))
            val accent = ChipStyle.accent(activity).first
            for ((s, pair) in styleCells) {
                pair.second.setImageDrawable(BitmapDrawable(activity.resources, MarkerArt.render(s, color.argb, px(72f))))
                pair.first.background = if (s == style) GradientDrawable().apply { setStroke(px(2f), accent); cornerRadius = px(8f).toFloat() } else null
            }
            for ((key, v) in swatches) {
                val sw = MarkerArt.colorOf(key)
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(sw.argb)
                    setStroke(if (key == color.key) px(3f) else px(1f), if (key == color.key) accent else 0x55000000)
                }
            }
            sizeText.text = "Size: " + "%.1f".format(java.util.Locale.US, size) + "x" + if (size == 1.0f) " (standard)" else ""
        }
        for ((s, pair) in styleCells) pair.first.setOnClickListener { style = s; refresh() }
        for ((key, v) in swatches) v.setOnClickListener { color = MarkerArt.colorOf(key); refresh() }
        slider.addOnChangeListener { _, v, fromUser -> if (fromUser) { size = v / 10f; refresh() } }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16f), px(8f), px(16f), px(4f))
            addView(previews)
            addView(TextView(activity).apply { text = "Style"; textSize = 14f; setPadding(0, px(12f), 0, px(2f)) })
            addView(grid)
            addView(TextView(activity).apply { text = "Colour"; textSize = 14f; setPadding(0, px(8f), 0, 0) })
            addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(colorRow) })
            addView(sizeText)
            addView(slider)
        }
        refresh()
        AlertDialog.Builder(activity)
            .setTitle("Position marker")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ -> onChanged(Choice("arrow", "blue", 1.0f)) }
            .show()
    }
}
