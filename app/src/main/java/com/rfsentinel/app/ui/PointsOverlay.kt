package com.rfsentinel.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws many points straight onto the map canvas, each centred exactly on its
 * coordinate and re-projected on every frame, so points stay pinned to the map
 * while it pans, zooms or rotates (no per-point Marker objects lagging behind,
 * no fanning out). Idea from Wardrive Go's markers overlay (GPL-3.0), written
 * for RF Sentinel's point styles.
 *
 * A tap reports every point under the finger, nearest first, so points heard from
 * the same spot can be picked from a list instead of being spread apart.
 */
class PointsOverlay<T>(
    private val density: Float,
    private val onTap: (List<T>) -> Unit
) : Overlay() {

    /** One point: a filled dot with a ring, or an [icon] bitmap centred on the coordinate. */
    class Point<T>(
        val lat: Double,
        val lon: Double,
        val payload: T,
        val color: Int = 0,
        val sizeDp: Float = 16f,
        val ringColor: Int = 0xFFFFFFFF.toInt(),
        val icon: Bitmap? = null,
        val alpha: Int = 255
    )

    @Volatile var points: List<Point<T>> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val px = android.graphics.Point()
    private val tapRadius = 22f * density

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val margin = 40 * density
        val w = mapView.width; val h = mapView.height
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lon), px)
            // Rotation and scaling are applied to the canvas by osmdroid; cull generously.
            val x = px.x.toFloat(); val y = px.y.toFloat()
            if (x < -w - margin || y < -h - margin || x > 2 * w + margin || y > 2 * h + margin) continue
            val icon = p.icon
            if (icon != null) {
                bitmapPaint.alpha = p.alpha
                canvas.drawBitmap(icon, x - icon.width / 2f, y - icon.height / 2f, bitmapPaint)
            } else {
                val r = p.sizeDp * density / 2
                ring.color = p.ringColor; ring.alpha = p.alpha
                canvas.drawCircle(x, y, r, ring)
                fill.color = p.color; fill.alpha = p.alpha
                canvas.drawCircle(x, y, r - 2 * density, fill)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val hits = ArrayList<Pair<Float, T>>()
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lon), px)
            val r = maxOf(tapRadius, (p.icon?.width ?: 0) / 2f, p.sizeDp * density / 2)
            val dx = e.x - px.x; val dy = e.y - px.y
            val d2 = dx * dx + dy * dy
            if (d2 <= r * r) hits += d2 to p.payload
        }
        if (hits.isEmpty()) return false
        onTap(hits.sortedBy { it.first }.map { it.second })
        return true
    }
}
