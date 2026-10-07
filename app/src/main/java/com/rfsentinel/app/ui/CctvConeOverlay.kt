package com.rfsentinel.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.rfsentinel.app.alpr.Cctv
import com.rfsentinel.app.alpr.CctvCamera
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * The area each CCTV camera watches: a translucent wedge toward `camera:direction`, as wide
 * as `camera:angle` and reaching about 5x the mounting height (see [Cctv.viewRangeM]);
 * a faint circle for dome / panning cameras with no mapped direction. Drawn under the icons.
 */
class CctvConeOverlay(private val density: Float) : Overlay() {

    @Volatile var cameras: List<CctvCamera> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val px = android.graphics.Point()
    private val path = Path()
    private val oval = RectF()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val zoom = mapView.zoomLevelDouble
        val w = mapView.width; val h = mapView.height
        edge.strokeWidth = 1f * density
        for (c in cameras) {
            proj.toPixels(GeoPoint(c.lat, c.lon), px)
            val x = px.x.toFloat(); val y = px.y.toFloat()
            val r = proj.metersToPixels(Cctv.viewRangeM(c).toFloat(), c.lat, zoom)
            if (x < -w - r || y < -h - r || x > 2 * w + r || y > 2 * h + r) continue
            val color = if (c.isPrivate) PRIVATE_COLOR else PUBLIC_COLOR
            fill.color = (color and 0x00FFFFFF) or 0x33000000
            edge.color = (color and 0x00FFFFFF) or 0x88000000.toInt()
            val dir = c.direction
            if (dir == null) {
                // No direction: only domes / panning cameras get a (fainter, smaller) all-round area.
                if (c.cameraType != "dome" && c.cameraType != "panning") continue
                fill.color = (color and 0x00FFFFFF) or 0x1A000000
                canvas.drawCircle(x, y, r * 0.6f, fill)
                continue
            }
            val sweep = Cctv.viewAngle(c).toFloat()
            // A full-circle view: Android treats a 360° arc as empty.
            if (sweep >= 359f) { canvas.drawCircle(x, y, r, fill); canvas.drawCircle(x, y, r, edge); continue }
            oval.set(x - r, y - r, x + r, y + r)
            path.reset()
            path.moveTo(x, y)
            // Bearings run clockwise from north; Android arcs start at 3 o'clock.
            path.arcTo(oval, dir - 90f - sweep / 2f, sweep)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }
    }

    companion object {
        const val PUBLIC_COLOR = 0xFF455A64.toInt()   // slate
        const val PRIVATE_COLOR = 0xFF8D6E63.toInt()  // brown-grey
    }
}
