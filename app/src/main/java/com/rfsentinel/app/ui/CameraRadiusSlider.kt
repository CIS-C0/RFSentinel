package com.rfsentinel.app.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.rfsentinel.app.util.Prefs

/**
 * "Download radius: N km" with a 10-200 km slider, saved as you slide. The same
 * setting as in Settings; used by the setup wizard and the map menu.
 */
object CameraRadiusSlider {

    fun text(km: Int) = "Download radius: $km km" +
        if (km > 100) " (larger areas take longer and use more data)" else ""

    /** A label + slider block; [onChange] runs after each change (already saved). */
    fun create(context: Context, onChange: (Int) -> Unit = {}): LinearLayout {
        val label = TextView(context).apply { text = text(Prefs.cameraRadiusKm(context)) }
        val slider = Slider(context).apply {
            valueFrom = 10f; valueTo = 200f; stepSize = 10f
            value = Prefs.cameraRadiusKm(context).toFloat()
            contentDescription = "Camera download radius in kilometres"
            setLabelFormatter { "${it.toInt()} km" }
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                Prefs.setCameraRadiusKm(context, v.toInt())
                label.text = text(v.toInt())
                onChange(v.toInt())
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
