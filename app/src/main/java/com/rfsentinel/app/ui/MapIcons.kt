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

    /** A round device dot with a white ring. */
    fun dot(dp: Float, color: Int, sizeDp: Int, ringDp: Float = 2f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((ringDp * dp).toInt().coerceAtLeast(1), Color.WHITE)
        val px = (sizeDp * dp).toInt()
        setSize(px, px)
    }
}
