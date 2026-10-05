package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.alpr.KnownCamera
import org.osmdroid.config.Configuration
import java.io.File

/** Map pieces shared by the phone map and the Android Auto map. */
object MapIcons {

    /** osmdroid: identify ourselves to the tile server (OSM tile policy), cache tiles privately. */
    fun configureOsm(context: Context) {
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
    }

    fun cameraColor(kind: KnownCamera.Kind): Int = when (kind) {
        KnownCamera.Kind.ALPR -> 0xFFB3261E.toInt()
        KnownCamera.Kind.SPEED -> 0xFFE08A00.toInt()
        KnownCamera.Kind.RED_LIGHT -> 0xFF7B1FA2.toInt()
    }

    /** A small camera glyph, distinct from the round device dots: red plate reader, amber speed, purple red-light. */
    fun cameraIcon(dp: Float, kind: KnownCamera.Kind): Bitmap {
        val w = (22 * dp).toInt(); val h = (16 * dp).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val body = RectF(1 * dp, 3 * dp, w - 1 * dp, h - 1 * dp)
        p.color = cameraColor(kind)
        c.drawRoundRect(body, 3 * dp, 3 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp; p.color = Color.WHITE
        c.drawRoundRect(body, 3 * dp, 3 * dp, p)
        c.drawCircle(w / 2f, (h + 2 * dp) / 2f, 3.5f * dp, p)
        return bmp
    }

    const val POLICE_AIRCRAFT_COLOR = 0xFF5E35B1.toInt()

    /**
     * An aircraft seen from above, nose up (the marker rotates it to its track). Police /
     * government aircraft: bold purple with a white outline; other traffic: small and grey.
     */
    fun planeIcon(dp: Float, police: Boolean, scale: Float = 1f): Bitmap {
        val s = ((if (police) 30 else 18) * dp * scale).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = s.toFloat()
        val path = android.graphics.Path().apply {
            moveTo(w * 0.50f, w * 0.04f)              // nose
            lineTo(w * 0.57f, w * 0.38f)
            lineTo(w * 0.96f, w * 0.56f)              // right wing
            lineTo(w * 0.96f, w * 0.64f)
            lineTo(w * 0.57f, w * 0.56f)
            lineTo(w * 0.55f, w * 0.80f)
            lineTo(w * 0.70f, w * 0.90f)              // right tail
            lineTo(w * 0.70f, w * 0.96f)
            lineTo(w * 0.50f, w * 0.91f)
            lineTo(w * 0.30f, w * 0.96f)
            lineTo(w * 0.30f, w * 0.90f)              // left tail
            lineTo(w * 0.45f, w * 0.80f)
            lineTo(w * 0.43f, w * 0.56f)
            lineTo(w * 0.04f, w * 0.64f)
            lineTo(w * 0.04f, w * 0.56f)              // left wing
            lineTo(w * 0.43f, w * 0.38f)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (police) POLICE_AIRCRAFT_COLOR else 0xB3707C80.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = (if (police) 1.8f else 1f) * dp * scale.coerceIn(0.7f, 1.5f)
        p.color = if (police) Color.WHITE else 0xCCFFFFFF.toInt()
        c.drawPath(path, p)
        return bmp
    }

    /** A cell tower: a mast with two radio waves on a dark disc. */
    fun towerIcon(dp: Float): Bitmap {
        val s = (20 * dp).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = s / 2f
        p.color = 0xFF5E35B1.toInt(); c.drawCircle(r, r, r - 1 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp; p.color = Color.WHITE
        c.drawCircle(r, r, r - 1 * dp, p)
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(r, r - 1 * dp, r - 3.5f * dp, r + 6 * dp, p)
        c.drawLine(r, r - 1 * dp, r + 3.5f * dp, r + 6 * dp, p)
        c.drawArc(RectF(r - 4 * dp, r - 6 * dp, r + 4 * dp, r + 2 * dp), 200f, 140f, false, p)
        c.drawArc(RectF(r - 6.5f * dp, r - 8.5f * dp, r + 6.5f * dp, r + 4.5f * dp), 205f, 130f, false, p)
        return bmp
    }

    /** A round device dot with a white ring. */
    fun dot(dp: Float, color: Int, sizeDp: Int, ringDp: Float = 2f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((ringDp * dp).toInt().coerceAtLeast(1), Color.WHITE)
        val px = (sizeDp * dp).toInt()
        setSize(px, px)
    }
}
