package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.abs

/**
 * A small floating map drawn over other apps (Google Maps, Waze...) while scanning:
 * centred on you, with the same device dots as RF Sentinel's map (flagged ones larger,
 * on top), a border in the threat colour and the flagged count. Drag to move, tap to
 * open the full map, ✕ to turn it off. Needs "Display over other apps"; hidden while
 * RF Sentinel itself is on screen. While it shows, location runs in precise mode, like
 * the full map.
 */
object FloatingMap {

    /** One device on the mini map. */
    data class Dot(val lat: Double, val lon: Double, val color: Int, val flagged: Boolean)

    private const val SIZE_DP = 170
    private const val MIN_DP = 120
    private const val ZOOM = 16.0
    private const val MIN_ZOOM = 11.0
    private const val MAX_ZOOM = 19.0
    private const val ME_COLOR = 0xFF1A73E8.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var map: MapView? = null
    private var badge: TextView? = null
    private var waiting: TextView? = null
    private var border: GradientDrawable? = null
    private var dots: PointsOverlay<Unit>? = null
    private var me: PointsOverlay<Unit>? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** Shows or updates the mini map (any thread). [here] is your last fix, null while waiting for GPS. */
    fun update(context: Context, level: ThreatBubble.Level, flagged: Int, here: Location?, devices: List<Dot>) = main.post {
        val app = context.applicationContext
        if (!canShow(app)) { removeNow(app); return@post }
        if (root == null) add(app) ?: return@post
        border?.setStroke(dp(app, 3f), level.color)
        badge?.apply {
            text = if (flagged == 0) "✓" else if (flagged > 99) "99+" else flagged.toString()
            background = GradientDrawable().apply { cornerRadius = dp(app, 11f).toFloat(); setColor(level.color) }
        }
        dots?.points = devices.sortedBy { it.flagged }.map {
            PointsOverlay.Point(it.lat, it.lon, Unit, color = it.color, sizeDp = if (it.flagged) 15f else 9f)
        }
        val m = map ?: return@post
        if (here != null) {
            val p = GeoPoint(here.latitude, here.longitude)
            me?.points = listOf(PointsOverlay.Point(p.latitude, p.longitude, Unit, color = ME_COLOR, sizeDp = 14f))
            m.controller.setCenter(p)
            waiting?.visibility = View.GONE
        } else waiting?.visibility = View.VISIBLE
        m.invalidate()
    }

    fun hide(context: Context) = main.post { removeNow(context.applicationContext) }

    private fun removeNow(context: Context) {
        val r = root ?: return
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
        runCatching { map?.onDetach() }
        root = null; map = null; badge = null; waiting = null; border = null; dots = null; me = null
        ScanForegroundService.mapHidden(context)
    }

    private fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun add(context: Context): FrameLayout? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = context.resources.displayMetrics.density
        val prefs = context.getSharedPreferences("floating_map", Context.MODE_PRIVATE)
        // The corner handle resizes it, from MIN_DP up to almost the screen width; size and zoom are remembered.
        val minSize = dp(context, MIN_DP.toFloat())
        val maxSize = (context.resources.displayMetrics.widthPixels - dp(context, 16f)).coerceAtLeast(minSize)
        val size = prefs.getInt("size", dp(context, SIZE_DP.toFloat())).coerceIn(minSize, maxSize)
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("x", dp(context, 8f))
            y = prefs.getInt("y", dp(context, 120f))
        }

        MapIcons.configureOsm(context)
        val radius = 18f * d
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.BLACK) }.also { border = it }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            elevation = 6f * d
        }
        val m = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(prefs.getFloat("zoom", ZOOM.toFloat()).toDouble().coerceIn(MIN_ZOOM, MAX_ZOOM))
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        val dotLayer = PointsOverlay<Unit>(d) {}
        val meLayer = PointsOverlay<Unit>(d) {}
        m.overlays.add(dotLayer)
        m.overlays.add(meLayer)
        val inset = dp(context, 3f)
        frame.addView(m, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(inset, inset, inset, inset)
        })
        val wait = TextView(context).apply {
            text = "Waiting for GPS"
            setTextColor(Color.WHITE); textSize = 11f
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))
        }
        frame.addView(wait, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(context, 8f) })
        val count = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            minWidth = dp(context, 22f); minHeight = dp(context, 22f)
            setPadding(dp(context, 5f), 0, dp(context, 5f), 0)
            contentDescription = "Flagged devices"
        }
        frame.addView(count, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(context, 7f), dp(context, 7f), 0, 0) })
        val close = TextView(context).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA000000.toInt()) }
            contentDescription = "Turn off the floating map"
        }
        val closeSize = dp(context, 24f)
        frame.addView(close, FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(context, 6f), dp(context, 6f), 0)
        })

        // Touches go to the window, not the map: drag moves it, pinch resizes it, tap opens the full map.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var pinched = false
        // Pinch is tracked by hand: ScaleGestureDetector stops below a minimum finger span
        // (~2.5 cm), which a small window reaches quickly when zooming out.
        var pinchSpan = 0f
        fun span(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
        val onTouch = View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { pinched = true; pinchSpan = span(e) }
                MotionEvent.ACTION_POINTER_UP -> pinchSpan = 0f
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; pinched = false }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2 && pinchSpan > 0f) {
                    // One zoom level per doubling of the finger spread, in or out.
                    val now = span(e)
                    if (now > 1f) {
                        val z = (m.zoomLevelDouble + Math.log((now / pinchSpan).toDouble()) / Math.log(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        m.controller.setZoom(z)
                        pinchSpan = now
                    }
                } else if (!pinched && e.pointerCount == 1) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 * d || abs(dy) > 8 * d) moved = true
                    // Gravity END: x grows to the left.
                    if (moved) { lp.x = startX - dx.toInt(); lp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(frame, lp) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (pinched) prefs.edit { putFloat("zoom", m.zoomLevelDouble.toFloat()) }
                    else if (moved) prefs.edit { putInt("x", lp.x); putInt("y", lp.y) }
                    else if (v === close) turnOff(context)
                    else context.startActivity(Intent(context, MapActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            true
        }
        val cover = View(context).apply {
            setOnTouchListener(onTouch)
            contentDescription = "RF Sentinel map: tap to open, drag to move, pinch to zoom"
        }
        frame.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        close.bringToFront()
        close.setOnTouchListener(onTouch)

        val handleSize = dp(context, 26f)
        val handle = TextView(context).apply {
            text = "⤡"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA000000.toInt()) }
            contentDescription = "Resize the floating map"
        }
        frame.addView(handle, FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(context, 5f), 0, 0, dp(context, 5f))
        })
        var resizeDownX = 0f; var resizeDownY = 0f; var startSize = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { resizeDownX = e.rawX; resizeDownY = e.rawY; startSize = lp.width }
                MotionEvent.ACTION_MOVE -> {
                    // Out (left / down) grows it, in (right / up) shrinks it; it stays square.
                    val grow = maxOf(resizeDownX - e.rawX, e.rawY - resizeDownY)
                    val n = (startSize + grow).toInt().coerceIn(minSize, maxSize)
                    if (n != lp.width) { lp.width = n; lp.height = n; runCatching { wm.updateViewLayout(frame, lp) } }
                }
                MotionEvent.ACTION_UP -> prefs.edit { putInt("size", lp.width) }
            }
            true
        }

        return runCatching {
            wm.addView(frame, lp)
            m.onResume()
            root = frame; map = m; badge = count; waiting = wait; dots = dotLayer; me = meLayer
            ScanForegroundService.mapShown(context)
            frame
        }.getOrNull()
    }

    private fun turnOff(context: Context) {
        Prefs.setFloatingMap(context, false)
        removeNow(context)
        Toast.makeText(context, "Floating map off - turn it back on in Settings", Toast.LENGTH_SHORT).show()
    }
}
