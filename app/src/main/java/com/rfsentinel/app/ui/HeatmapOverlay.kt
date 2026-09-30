package com.rfsentinel.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * A classic density heatmap: every point adds a soft blob of "heat" to an
 * intensity buffer (drawn at quarter resolution for speed), and the summed
 * intensity is coloured blue -> green -> yellow -> red. Recomputed only when
 * the view moves or zooms.
 */
class HeatmapOverlay(private val radiusDp: Float) : Overlay() {

    var points: List<GeoPoint> = emptyList()
        set(value) { field = value; cacheKey = null }

    private val palette = IntArray(256) { i -> heatColor(i / 255f) }
    private var cache: Bitmap? = null
    private var cacheKey: String? = null
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val w = mapView.width; val h = mapView.height
        if (w == 0 || h == 0) return
        val key = "${mapView.boundingBox}|${mapView.zoomLevelDouble}|$w|$h|${points.size}"
        if (key != cacheKey) {
            cache = render(mapView, w, h)
            cacheKey = key
        }
        val bmp = cache ?: return
        // Map rotation isn't enabled, so the overlay canvas is in screen coordinates.
        canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), blit)
    }

    private fun render(mapView: MapView, w: Int, h: Int): Bitmap {
        val scale = 4
        val bw = (w / scale).coerceAtLeast(1); val bh = (h / scale).coerceAtLeast(1)
        val intensity = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val c = Canvas(intensity)
        val r = radiusDp * mapView.resources.displayMetrics.density / scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pt = android.graphics.Point()
        val proj = mapView.projection
        for (p in points) {
            proj.toPixels(p, pt)
            val x = pt.x / scale.toFloat(); val y = pt.y / scale.toFloat()
            if (x < -r || y < -r || x > bw + r || y > bh + r) continue
            // Each point adds up to ~25% opacity; overlapping points build up heat.
            paint.shader = RadialGradient(x, y, r, 0x40000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
        val alpha = ByteArray(bw * bh)
        intensity.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
        intensity.recycle()
        val colors = IntArray(bw * bh)
        for (i in alpha.indices) {
            val a = alpha[i].toInt() and 0xFF
            colors[i] = if (a < 4) Color.TRANSPARENT else palette[a]
        }
        return Bitmap.createBitmap(colors, bw, bh, Bitmap.Config.ARGB_8888)
    }

    /** 0..1 -> translucent blue, cyan, green, yellow, opaque red. */
    private fun heatColor(t: Float): Int {
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val cols = intArrayOf(0x2020A0FF, 0x7000E5FF, 0x9000D060.toInt(), 0xB0FFE000.toInt(), 0xE0FF2020.toInt())
        val i = stops.indexOfLast { it <= t }.coerceIn(0, stops.size - 2)
        val f = ((t - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int, s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).toInt() shl s
        val a = cols[i]; val b = cols[i + 1]
        return lerp(a, b, 24) or lerp(a, b, 16) or lerp(a, b, 8) or lerp(a, b, 0)
    }
}
