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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs

/**
 * A small floating map drawn over other apps (Google Maps, Waze...) while scanning:
 * centred on you, with the same device dots as RF Sentinel's map (flagged ones larger,
 * on top), a border in the threat colour and the flagged count. Drag to move, tap to
 * open the full map, ✕ to turn it off. Needs "Display over other apps"; hidden while
 * RF Sentinel itself is on screen. While it shows, location runs in precise mode, like
 * the full map. Always OpenFreeMap (MapLibre), whichever map Settings picks for the app: a window
 * this small has no room for another provider's logo and info button.
 */
object FloatingMap {

    /** One device on the mini map. */
    data class Dot(val lat: Double, val lon: Double, val color: Int, val flagged: Boolean)

    /** A known camera (OpenStreetMap) on the mini map; faded when you turned its alerts off. */
    data class Cam(val lat: Double, val lon: Double, val kind: com.rfsentinel.app.alpr.KnownCamera.Kind, val ignored: Boolean)

    private const val SIZE_DP = 170
    private const val MIN_DP = 120
    /** Zoom levels as saved (the old raster map's); the vector map shows the same scale one level lower. */
    private const val ZOOM = 16.0
    private const val MIN_ZOOM = 11.0
    private const val MAX_ZOOM = 19.0
    private const val ZOOM_SHIFT = 1.0
    private const val ME_COLOR = 0xFF1A73E8.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var badge: TextView? = null
    private var waiting: TextView? = null
    private var border: GradientDrawable? = null
    /** The latest dots and position, drawn as soon as the map's style is in (and with every update). */
    private var lastDevices: List<Dot> = emptyList()
    private var lastCameras: List<Cam> = emptyList()
    private var lastHere: Location? = null
    /** Saved-scale zoom (see [ZOOM_SHIFT]). */
    private var zoom = ZOOM
    /** The map window's place and size, which the live read card follows. */
    private var mapLp: WindowManager.LayoutParams? = null
    /** The live read card (Locate or pinpointing): a small window of its own just under the map. */
    private var live: LiveReadOverlay? = null

    /** True while the mini map is on screen (the threat bubble then leaves the live read card to it). */
    val showing get() = root != null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** Shows or updates the mini map (any thread). [here] is your last fix, null while waiting for GPS. */
    fun update(context: Context, level: ThreatBubble.Level, flagged: Int, here: Location?, devices: List<Dot>,
               cameras: List<Cam> = emptyList()) = main.post {
        val app = context.applicationContext
        if (!canShow(app)) { removeNow(app); return@post }
        if (root == null) add(app) ?: return@post
        border?.setStroke(dp(app, 3f), level.color)
        badge?.apply {
            text = if (flagged == 0) "✓" else if (flagged > 99) "99+" else flagged.toString()
            background = GradientDrawable().apply { cornerRadius = dp(app, 11f).toFloat(); setColor(level.color) }
        }
        lastDevices = devices; lastCameras = cameras; lastHere = here
        waiting?.visibility = if (here != null) View.GONE else View.VISIBLE
        draw()
    }

    /** The cameras, the devices (flagged ones larger, on top) and you, centred. */
    private fun draw() {
        val m = map ?: return
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return
        runCatching {
            style.getSourceAs<GeoJsonSource>(CAMS)?.setGeoJson(FeatureCollection.fromFeatures(lastCameras.map { c ->
                Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
                    addStringProperty("icon", "fm-cam-${c.kind.name}"); addNumberProperty("opacity", if (c.ignored) 0.35f else 1f)
                }
            }))
            style.getSourceAs<GeoJsonSource>(DOTS)?.setGeoJson(FeatureCollection.fromFeatures(lastDevices.sortedBy { it.flagged }.map { d ->
                Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat)).apply {
                    addStringProperty("color", String.format("#%06X", d.color and 0xFFFFFF))
                    addNumberProperty("r", if (d.flagged) 7.5f else 4.5f)
                    addNumberProperty("k", if (d.flagged) 1f else 0f)
                }
            }))
            val here = lastHere
            style.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(FeatureCollection.fromFeatures(
                if (here == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(here.longitude, here.latitude)))))
            if (here != null) m.moveCamera(CameraUpdateFactory.newLatLng(LatLng(here.latitude, here.longitude)))
        }
    }

    private const val CAMS = "fm-cams"; private const val DOTS = "fm-dots"; private const val ME = "fm-me"

    fun hide(context: Context) = main.post { removeNow(context.applicationContext) }

    private fun removeNow(context: Context) {
        val r = root ?: return
        live?.stop(); live = null
        mapLp = null
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
        view?.let { v -> runCatching { v.onPause(); v.onStop(); v.onDestroy() } }
        root = null; view = null; map = null; badge = null; waiting = null; border = null
        MapSafety.disarm(context)
        ScanForegroundService.mapHidden(context)
    }

    private fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).toInt()

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** The card just under the map, its right edge on the map's, at least as wide as the map; above it near the bottom. */
    private fun placeLive(context: Context, lp: WindowManager.LayoutParams, h: Int) {
        val m = mapLp ?: return
        val dm = context.resources.displayMetrics
        val gap = dp(context, 6f)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.width = maxOf(m.width, dp(context, 240f)).coerceAtMost(dm.widthPixels - dp(context, 16f))
        // Gravity END: x is from the right edge; kept on screen when the map is near the left.
        lp.x = m.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = if (m.y + m.height + gap + h <= dm.heightPixels) m.y + m.height + gap else (m.y - gap - h).coerceAtLeast(0)
    }

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
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("x", dp(context, 8f))
            y = prefs.getInt("y", dp(context, 120f))
        }

        // A start-up the app did not survive (a Vulkan phone that cannot run it): first OpenGL, then this stays off.
        if (MapSafety.recover(context) == MapSafety.Recovery.MAP_OFF) { Prefs.setFloatingMap(context, false); return null }
        MapSafety.arm(context, MapEngine.Provider.OPENFREEMAP)
        MapLibreEngine.start(context)
        val radius = 18f * d
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.BLACK) }.also { border = it }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            elevation = 6f * d
        }
        zoom = prefs.getFloat("zoom", ZOOM.toFloat()).toDouble().coerceIn(MIN_ZOOM, MAX_ZOOM)
        // A window over other apps: the texture view draws there reliably; the map engine needs its
        // lifecycle by hand (no activity owns it).
        val m = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .camera(CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(zoom - ZOOM_SHIFT).build())
            .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            .scrollGesturesEnabled(false).zoomGesturesEnabled(false).rotateGesturesEnabled(false).tiltGesturesEnabled(false))
        m.onCreate(null)
        m.getMapAsync { lm ->
            main.postDelayed({ if (root != null) MapSafety.worked(context) }, MapSafety.SETTLE_MS) // its start-up is over
            map = lm
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            lm.setStyle(Style.Builder().fromUri(if (night) MapLibreEngine.DARK_STYLE else MapLibreEngine.LIGHT_STYLE)) { style ->
                runCatching {
                    com.rfsentinel.app.alpr.KnownCamera.Kind.entries.forEach { style.addImage("fm-cam-${it.name}", MapIcons.cameraIcon(d * 0.85f, it)) }
                    listOf(CAMS, DOTS, ME).forEach { style.addSource(GeoJsonSource(it)) }
                    style.addLayer(SymbolLayer(CAMS, CAMS).withProperties(PropertyFactory.iconImage(get("icon")),
                        PropertyFactory.iconOpacity(get("opacity")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
                    style.addLayer(CircleLayer(DOTS, DOTS).withProperties(PropertyFactory.circleColor(toColor(get("color"))),
                        PropertyFactory.circleRadius(get("r")), PropertyFactory.circleSortKey(get("k")),
                        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.2f)))
                    style.addLayer(CircleLayer(ME, ME).withProperties(PropertyFactory.circleColor(String.format("#%06X", ME_COLOR and 0xFFFFFF)),
                        PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
                    draw()
                }
            }
        }
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
                        zoom = (zoom + Math.log((now / pinchSpan).toDouble()) / Math.log(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        map?.moveCamera(CameraUpdateFactory.zoomTo(zoom - ZOOM_SHIFT))
                        pinchSpan = now
                    }
                } else if (!pinched && e.pointerCount == 1) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 * d || abs(dy) > 8 * d) moved = true
                    // Gravity END: x grows to the left.
                    if (moved) { lp.x = startX - dx.toInt(); lp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(frame, lp) }; live?.replace() }
                }
                MotionEvent.ACTION_UP -> {
                    if (pinched) prefs.edit { putFloat("zoom", zoom.toFloat()) }
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
                    if (n != lp.width) { lp.width = n; lp.height = n; runCatching { wm.updateViewLayout(frame, lp) }; live?.replace() }
                }
                MotionEvent.ACTION_UP -> prefs.edit { putInt("size", lp.width) }
            }
            true
        }

        return runCatching {
            wm.addView(frame, lp)
            m.onStart(); m.onResume()
            root = frame; view = m; badge = count; waiting = wait; mapLp = lp
            ScanForegroundService.mapShown(context)
            live = LiveReadOverlay(context) { lp, h -> placeLive(context, lp, h) }.also { it.start() }
            frame
        }.getOrNull()
    }

    private fun turnOff(context: Context) {
        Prefs.setFloatingMap(context, false)
        removeNow(context)
        Toast.makeText(context, "Floating map off - turn it back on in Settings", Toast.LENGTH_SHORT).show()
    }
}
