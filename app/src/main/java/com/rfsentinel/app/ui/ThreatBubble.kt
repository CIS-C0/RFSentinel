package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.edit
import com.rfsentinel.app.MainActivity
import kotlin.math.abs
import kotlin.math.min

/**
 * A small floating bubble drawn over other apps (e.g. Waze, Google Maps) while
 * scanning: green = all clear, orange = a probable match, red = a strong match
 * or something following you, with the number of flagged devices. Tap to open
 * the app, drag to move. Needs the "Display over other apps" permission; hidden
 * while RF Sentinel itself is on screen. Each new alert also shows a small card
 * next to the bubble for a few seconds saying what was detected.
 */
object ThreatBubble {

    enum class Level(val color: Int) { CLEAR(0xFF2E7D32.toInt()), WEAK(0xFF8D6E00.toInt()), PROBABLE(0xFFE08A00.toInt()), DANGER(0xFFC62828.toInt()) }

    private val main = Handler(Looper.getMainLooper())
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var popupView: TextView? = null
    private val hidePopup = Runnable { popupView?.let { removePopupNow(it.context) } }
    private const val POPUP_MS = 6_000L

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** Shows or updates the bubble (any thread). */
    fun update(context: Context, level: Level, count: Int) = main.post {
        val app = context.applicationContext
        if (!canShow(app)) { removeNow(app); return@post }
        val v = view ?: add(app) ?: return@post
        v.set(level, count)
    }

    fun hide(context: Context) = main.post { removeNow(context.applicationContext) }

    /**
     * Shows what was just detected in a small card beside the bubble (any thread);
     * a newer alert replaces it. Only while the bubble itself is showing.
     */
    fun popup(context: Context, title: String, detail: String?, color: Int) = main.post {
        val app = context.applicationContext
        val lp = params ?: return@post
        if (view == null || !canShow(app)) return@post
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = app.resources.displayMetrics.density
        val text = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!detail.isNullOrBlank()) {
                val start = length
                append("\n").append(detail)
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(0xFFCFD8DC.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Beside the bubble, on whichever side has more room.
        val size = lp.width
        val onLeft = lp.x + size / 2 < app.resources.displayMetrics.widthPixels / 2
        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            lp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
            x = if (onLeft) lp.x + size + (6 * d).toInt() else app.resources.displayMetrics.widthPixels - lp.x + (6 * d).toInt()
            y = lp.y + (4 * d).toInt()
        }
        val pv = popupView ?: TextView(app).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxWidth = (240 * d).toInt()
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        pv.text = text
        pv.background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(0xEE202124.toInt())
            setStroke((2 * d).toInt(), color or 0xFF000000.toInt())
        }
        if (popupView == null) {
            if (runCatching { wm.addView(pv, plp) }.isFailure) return@post
            popupView = pv
        } else runCatching { wm.updateViewLayout(pv, plp) }
        main.removeCallbacks(hidePopup)
        main.postDelayed(hidePopup, POPUP_MS)
    }

    private fun removePopupNow(context: Context) {
        main.removeCallbacks(hidePopup)
        val pv = popupView ?: return
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(pv) }
        popupView = null
    }

    private fun removeNow(context: Context) {
        removePopupNow(context)
        val v = view ?: return
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        view = null; params = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(context: Context): BubbleView? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = context.resources.displayMetrics.density
        val size = (52 * d).toInt()
        val prefs = context.getSharedPreferences("threat_bubble", Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (8 * d).toInt())
            y = prefs.getInt("y", (180 * d).toInt())
        }
        val v = BubbleView(context)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 8 * d || abs(dy) > 8 * d)) { moved = true; removePopupNow(context) }
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, lp) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit { putInt("x", lp.x); putInt("y", lp.y) }
                    else context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            true
        }
        return runCatching { wm.addView(v, lp); view = v; params = lp; v }.getOrNull()
    }

    /** The drawn bubble: coloured disc, white ring, count or check mark. */
    private class BubbleView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var level = Level.CLEAR
        private var count = 0
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFFFFFF.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 20 * d
        }

        init { contentDescription = "RF Sentinel threat level" }

        fun set(level: Level, count: Int) {
            if (level == this.level && count == this.count) return
            this.level = level; this.count = count
            contentDescription = "RF Sentinel: ${if (count == 0) "all clear" else "$count flagged"}"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f - 2 * d
            fill.color = (level.color and 0x00FFFFFF) or 0xE6000000.toInt()
            canvas.drawCircle(width / 2f, height / 2f, r, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ring)
            val label = if (count == 0) "✓" else if (count > 99) "99+" else count.toString()
            text.textSize = if (label.length > 2) 14 * d else 20 * d
            canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
