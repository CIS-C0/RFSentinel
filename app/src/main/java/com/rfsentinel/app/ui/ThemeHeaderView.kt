package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The themed wordmark at the top of the main screen. Every styled theme has
 * its own original, hand-drawn treatment (no third-party artwork):
 *
 *  - GLITCH: RGB-split title that jitters and slices (DedSec)
 *  - HUD: red cockpit head-up display with a scrolling heading tape
 *  - NIGHT_VISION: phosphor glow, grain and a round scope vignette
 *  - CRT: amber terminal with a blinking block cursor and a rolling bar
 *  - SUNSET: striped synthwave sun over a scrolling wireframe grid
 *  - STENCIL: military stencil callsign, Zulu clock and a reticle
 *  - BLUEPRINT: drafting grid, dimension line and a title block
 *  - MASTHEAD: newspaper front page with rules and a dateline
 *
 * Colours come from the current theme's attributes, so it follows the palette.
 */
class ThemeHeaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style { GLITCH, FSOCIETY, HUD, NIGHT_VISION, CRT, SUNSET, STENCIL, BLUEPRINT, MASTHEAD }

    var style: Style = Style.GLITCH
        set(value) { field = value; wordmark = null; setupPaints(); requestLayout(); invalidate() }

    /** Theme banner art, drawn across the GLITCH / FSOCIETY header behind the text. */
    var poster: android.graphics.Bitmap? = null
        set(value) { field = value; requestLayout(); invalidate() }

    private val d = resources.displayMetrics.density
    private val mono = runCatching { ResourcesCompat.getFont(context, R.font.share_tech_mono) }.getOrNull()

    private fun attr(id: Int, fallback: Int) = MaterialColors.getColor(context, id, fallback)
    private val primary get() = attr(androidx.appcompat.R.attr.colorPrimary, 0xFF00F0D8.toInt())
    private val secondary get() = attr(com.google.android.material.R.attr.colorSecondary, 0xFFFF2E88.toInt())
    private val trace get() = attr(R.attr.rfTraceColor, 0xFFFF2E88.toInt())
    private val dim get() = attr(android.R.attr.textColorSecondary, 0xFF888888.toInt())
    private val bg get() = attr(android.R.attr.colorBackground, 0xFF000000.toInt())

    private val title = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var glitchUntil = 0L
    private var nextGlitch = System.currentTimeMillis() + 1500
    private val start = System.currentTimeMillis()

    init { setupPaints() }

    private fun face(family: String, style: Int = Typeface.BOLD) = Typeface.create(family, style)

    private fun setupPaints() {
        title.reset(); title.isAntiAlias = true
        sub.reset(); sub.isAntiAlias = true
        title.textSize = 30 * d
        sub.textSize = 12 * d
        sub.color = dim
        when (style) {
            Style.GLITCH, Style.FSOCIETY -> { title.typeface = mono; title.isFakeBoldText = true; sub.typeface = mono; sub.color = 0xFF7FB8B0.toInt() }
            Style.HUD -> { title.typeface = face("sans-serif-condensed"); title.letterSpacing = 0.25f; sub.typeface = face("sans-serif-condensed", Typeface.NORMAL); sub.letterSpacing = 0.2f }
            Style.NIGHT_VISION -> { title.typeface = mono; title.letterSpacing = 0.1f; sub.typeface = mono }
            Style.CRT -> { title.typeface = face("monospace"); sub.typeface = face("monospace", Typeface.NORMAL) }
            Style.SUNSET -> { title.typeface = face("sans-serif-black", Typeface.BOLD_ITALIC); title.textSize = 32 * d; sub.typeface = face("sans-serif-medium", Typeface.ITALIC); sub.letterSpacing = 0.3f }
            Style.STENCIL -> { title.typeface = face("sans-serif-condensed", Typeface.BOLD); title.letterSpacing = 0.18f; sub.typeface = face("monospace", Typeface.NORMAL) }
            Style.BLUEPRINT -> { title.typeface = face("serif-monospace"); title.letterSpacing = 0.12f; sub.typeface = face("serif-monospace", Typeface.NORMAL); sub.textSize = 10 * d }
            Style.MASTHEAD -> { title.typeface = face("serif", Typeface.BOLD); title.textSize = 34 * d; title.textAlign = Paint.Align.CENTER; sub.typeface = face("serif", Typeface.NORMAL); sub.textSize = 10 * d }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = when (style) {
            Style.GLITCH, Style.FSOCIETY -> if (poster != null) 190 * d else 125 * d
            Style.SUNSET -> 96 * d
            Style.BLUEPRINT, Style.MASTHEAD -> 84 * d
            Style.HUD, Style.STENCIL -> 70 * d
            else -> title.textSize + sub.textSize + 14 * d
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h.toInt(), heightMeasureSpec))
    }

    /** Shrinks the title so [text] fits in [maxWidth]. */
    private fun fit(text: String, maxWidth: Float, base: Float) {
        title.textSize = base
        val w = title.measureText(text)
        if (w > maxWidth && w > 0) title.textSize = base * maxWidth / w
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val next = when (style) {
            Style.GLITCH, Style.FSOCIETY -> glitch(canvas, now)
            Style.HUD -> hud(canvas, now)
            Style.NIGHT_VISION -> nightVision(canvas, now)
            Style.CRT -> crt(canvas, now)
            Style.SUNSET -> sunset(canvas, now)
            Style.STENCIL -> stencil(canvas, now)
            Style.BLUEPRINT -> { blueprint(canvas); 0L }
            Style.MASTHEAD -> { masthead(canvas); 0L }
        }
        if (next > 0 && isShown) postInvalidateDelayed(next)
    }

    // ---- GLITCH (DedSec) / FSOCIETY (Mr. Robot) ------------------------------------
    // Sits beside the theme's bundled poster: a sliced, glitching wordmark, a boxed
    // label and a retro OS popup where terminal lines type themselves. Each theme
    // has its own skin (colours, lettering and lines) - unofficial fan homages.

    private val whiteC = 0xFFF4F4F4.toInt()
    private val blackC = 0xFF050505.toInt()

    /**
     * @param cuts horizontal bands through the wordmark: (start as fraction of height,
     *   sideways shift in dp); a NaN shift leaves that band empty (a stripe through the letters)
     */
    private class Skin(
        val accent: Int, val ink: Int, val flash: Int, val label: String, val window: String,
        val font: Typeface, val spacing: Float, val stretch: Float,
        val cuts: List<Pair<Float, Float>>, val lines: List<String>
    )

    // Nods to Watch Dogs (ctOS, Blume, DedSec).
    private val dedsec = Skin(
        0xFF0070F0.toInt(), whiteC, 0xFF0070F0.toInt(), "WE ARE DEDSEC", "rf_sentinel.exe",
        Typeface.create("sans-serif-condensed", Typeface.BOLD), 0.03f, 1.35f,
        listOf(0.0f to 0f, 0.42f to -4f, 0.50f to 0f, 0.74f to 3f, 0.80f to 0f),
        listOf(
            "> ctOS 2.0 uplink... bypassed [OK]",
            "> sniff --ble --wifi --passive",
            "> blume_profiler.exe: not on my watch",
            "> the system is a lie_",
            "> rx_only=true  tx=0  // listen, never touch",
            "> !nvite.exe: dedsec wants you",
            "> nudle maps: location leak blocked",
            "> hacking is our weapon_",
            "> wrench.exe: (^_^) let's break stuff",
            "> ctOS profile: [REDACTED]",
            "> the city watches you. watch back_",
            "> better a free world_",
            "> we are dedsec. join us_"
        )
    )

    // Nods to Mr. Robot (fsociety, E Corp, the Dark Army) and lines from the show.
    private val fsociety = Skin(
        0xFFC8221A.toInt(), 0xFFC8221A.toInt(), whiteC, "HELLO, FRIEND", "fsociety.dat",
        Typeface.create("sans-serif", Typeface.BOLD), 0.08f, 1.25f,
        listOf(0.0f to 0f, 0.45f to Float.NaN, 0.50f to 0f),
        listOf(
            "$ whoami  -> root",
            "$ echo \"hello, friend.\"",
            "$ control is an illusion.",
            "$ is any of it real?",
            "$ we are fsociety. we are finally free.",
            "$ our democracy has been hacked.",
            "$ bonsoir, elliot.",
            "$ people always make the best exploits.",
            "$ give a man a bank, he can rob the world.",
            "$ a bug is never just a mistake.",
            "$ hello, friend? that's lame.",
            "$ we're here to make a change.",
            "$ dreams don't work unless you do.",
            "$ the system is a lie.",
            "$ e corp: our business is life itself",
            "$ evil corp ledger... encrypted [OK]",
            "$ ./fuxsocy.py --stage 2",
            "$ steel mountain hvac: compromised",
            "$ raspberry_pi: planted. listening.",
            "$ dark army: five/nine is coming",
            "$ allsafe ids: 0 alerts. lol.",
            "$ ecoin wallet: frozen",
            "$ sniff --ble --wifi --passive",
            "$ rx_only=true  tx=0  // never touch",
            "$ mr. robot is watching_",
            "$ the world is a hoax. stay awake.",
            "$ fsociety: we are legion_"
        )
    )

    private val skin get() = if (style == Style.FSOCIETY) fsociety else dedsec

    private val sticker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val posterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The wordmark in heavy condensed white letters, rendered once and then drawn in slices. */
    private var wordmark: android.graphics.Bitmap? = null
    private val pixelPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private fun wordmarkBitmap(): android.graphics.Bitmap {
        wordmark?.let { return it }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = skin.font
            textSize = 120f; color = skin.ink; letterSpacing = skin.spacing
            // A light stroke on top of the bold face makes it chunkier, like the game's logo.
            style = Paint.Style.FILL_AND_STROKE; strokeWidth = 3f
        }
        // Crop tightly to the ink so the letters fill the box.
        val b = android.graphics.Rect()
        p.getTextBounds("RF_SENTINEL", 0, 11, b)
        val bmp = android.graphics.Bitmap.createBitmap(b.width() + 8, b.height() + 8, android.graphics.Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText("RF_SENTINEL", 4f - b.left, 4f - b.top, p)
        return bmp.also { wordmark = it }
    }

    private fun glitch(canvas: Canvas, now: Long): Long {
        if (now >= nextGlitch) {
            glitchUntil = now + 220
            nextGlitch = now + 1800 + Random.nextLong(2600)
        }
        val glitching = now < glitchUntil
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(blackC)

        val bottomBar = 30 * d
        // Wide banner art fills the header; the bottom fades to black under the
        // wordmark, stamp and terminal so they stay readable.
        val art = poster
        val ox = 0f
        if (art != null) {
            posterPaint.isFilterBitmap = true
            // Centre-crop to the header's shape so the banner isn't squashed.
            val crop = if (art.width * h > w * art.height) {
                val cw = (art.height * w / h).toInt()
                android.graphics.Rect((art.width - cw) / 2, 0, (art.width + cw) / 2, art.height)
            } else {
                val ch = (art.width * h / w).toInt()
                android.graphics.Rect(0, (art.height - ch) / 2, art.width, (art.height + ch) / 2)
            }
            canvas.drawBitmap(art, crop, RectF(0f, 0f, w, h), posterPaint)
            if (glitching) repeat(3) {
                val sy0 = Random.nextFloat() * h; val sh = Random.nextInt(3, 12) * d
                val srcY = crop.top + (sy0 / h * crop.height()).toInt()
                val srcH = (sh / h * crop.height()).toInt().coerceAtLeast(1)
                val off = Random.nextInt(-8, 9) * d
                canvas.drawBitmap(art, android.graphics.Rect(crop.left, srcY, crop.right, (srcY + srcH).coerceAtMost(crop.bottom)), RectF(off, sy0, w + off, sy0 + sh), posterPaint)
            }
            fill.color = blackC  // opaque: the paint alpha scales the gradient
            fill.shader = android.graphics.LinearGradient(0f, h * 0.36f, 0f, h * 0.58f, 0x00050505, 0xF0050505.toInt(), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.36f, w, h, fill)
            fill.shader = null
        }
        val sx = ox + 8 * d; val sy = 6 * d

        // Wordmark: at the top on its own, or low over the banner's dark fade.
        val tx = sx
        val bmp = wordmarkBitmap()
        val markW = if (art != null) min(w * 0.5f, 190 * d) else min(w - tx - 8 * d, 230 * d)
        // Stretched taller than the font, like the game's tall condensed logo.
        val markH = markW * bmp.height / bmp.width * skin.stretch
        val top = if (art != null) h - bottomBar - 10 * d - markH - 4 * d else sy + 2 * d
        val dst = RectF(tx, top, tx + markW, top + markH)
        // Sliced like a torn screen: bands shifted sideways with thin gaps between them.
        val markTop = dst.top + 4 * d
        val markBox = RectF(dst.left, markTop, dst.right, markTop + markH)
        val cuts = skin.cuts
        for (i in cuts.indices) {
            val (f0, shift) = cuts[i]
            if (shift.isNaN()) continue
            val f1 = if (i + 1 < cuts.size) cuts[i + 1].first else 1f
            val y0 = markBox.top + f0 * markH; val y1 = markBox.top + f1 * markH - 0.8f * d
            if (y1 <= y0) continue
            val off = (if (glitching) shift + Random.nextInt(-8, 9) else shift) * d
            canvas.save()
            canvas.clipRect(0f, y0, w, y1)
            if (glitching && i % 2 == 1) {
                pixelPaint.colorFilter = android.graphics.PorterDuffColorFilter(skin.flash, android.graphics.PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(bmp, null, RectF(markBox.left + off + 3 * d, markBox.top, markBox.right + off + 3 * d, markBox.bottom), pixelPaint)
                pixelPaint.colorFilter = null
            }
            canvas.drawBitmap(bmp, null, RectF(markBox.left + off, markBox.top, markBox.right + off, markBox.bottom), pixelPaint)
            canvas.restore()
        }
        dst.bottom = markBox.bottom

        // Boxed label: hard white frame, like a stamped sign.
        sticker.typeface = mono; sticker.textSize = 13 * d; sticker.isFakeBoldText = true
        sticker.textAlign = Paint.Align.LEFT
        val label = skin.label
        val lw = sticker.measureText(label) + 16 * d
        val lh = 22 * d
        // Under the wordmark, or beside it when it sits over the banner.
        val lx = if (art != null) dst.right + 12 * d else tx
        val ly = if (art != null) dst.bottom - lh else dst.bottom + 10 * d
        if (ly + lh < h - bottomBar - 2 * d + 1 && lx + lw < w - 4 * d) {
            fill.color = blackC
            canvas.drawRect(lx, ly, lx + lw, ly + lh, fill)
            line.color = whiteC; line.strokeWidth = 2 * d
            canvas.drawRect(lx, ly, lx + lw, ly + lh, line)
            sticker.color = whiteC
            canvas.drawText(label, lx + 8 * d, ly + lh * 0.7f, sticker)
        }

        // Retro OS popup along the bottom: accent title bar with _ [] X, the terminal line typing inside.
        val wy = h - bottomBar
        val barH = 11 * d
        fill.color = blackC
        canvas.drawRect(ox + 2 * d, wy, w - 2 * d, h - 2 * d, fill)
        fill.color = skin.accent
        canvas.drawRect(ox + 2 * d, wy, w - 2 * d, wy + barH, fill)
        line.color = whiteC; line.strokeWidth = 1.5f * d
        canvas.drawRect(ox + 2 * d, wy, w - 2 * d, h - 2 * d, line)
        sub.color = blackC; sub.textSize = 9 * d; sub.typeface = mono; sub.isFakeBoldText = true
        canvas.drawText(skin.window, ox + 7 * d, wy + barH * 0.78f, sub)
        val box = 7 * d
        for (i in 0 until 3) {
            val bx = w - 8 * d - (3 - i) * (box + 3 * d)
            fill.color = whiteC
            canvas.drawRect(bx, wy + 2 * d, bx + box, wy + 2 * d + box, fill)
            line.color = blackC; line.strokeWidth = 1 * d
            when (i) {
                0 -> canvas.drawLine(bx + 1.5f * d, wy + 2 * d + box - 2 * d, bx + box - 1.5f * d, wy + 2 * d + box - 2 * d, line)
                1 -> canvas.drawRect(bx + 1.5f * d, wy + 3.5f * d, bx + box - 1.5f * d, wy + 2 * d + box - 1.5f * d, line)
                else -> {
                    canvas.drawLine(bx + 1.5f * d, wy + 3.5f * d, bx + box - 1.5f * d, wy + 2 * d + box - 1.5f * d, line)
                    canvas.drawLine(bx + box - 1.5f * d, wy + 3.5f * d, bx + 1.5f * d, wy + 2 * d + box - 1.5f * d, line)
                }
            }
        }
        val cycle = (now - start) / 4500
        val lines = skin.lines
        val lineText = lines[(cycle % lines.size).toInt()]
        val typed = (((now - start) % 4500) / 45).toInt().coerceAtMost(lineText.length)
        sub.color = whiteC; sub.textSize = 11 * d; sub.isFakeBoldText = false
        val cursor = if ((now / 450) % 2 == 0L) "█" else " "
        canvas.save(); canvas.clipRect(ox + 3 * d, wy, w - 3 * d, h)
        canvas.drawText(lineText.take(typed) + cursor, ox + 7 * d, h - 8 * d, sub)
        canvas.restore()
        sub.textSize = 12 * d

        // Glitch burst: white and accent slabs.
        if (glitching) {
            repeat(8) {
                fill.color = if (Random.nextInt(3) == 0) skin.accent else whiteC
                val bx = ox + Random.nextFloat() * (w - ox); val by = Random.nextFloat() * (h - bottomBar)
                canvas.drawRect(bx, by, bx + Random.nextInt(6, 60) * d, by + Random.nextInt(1, 5) * d, fill)
            }
        }
        scanlines(canvas, 0x10FFFFFF)
        return if (glitching) 40 else 90
    }

    // ---- HUD (Night Drive) ----------------------------------------------------

    private fun hud(canvas: Canvas, now: Long): Long {
        val w = width.toFloat(); val h = height.toFloat()
        val c = primary
        // Corner brackets.
        line.color = c; line.strokeWidth = 2 * d
        val k = 12 * d; val m = 2 * d
        path.reset()
        path.moveTo(m, m + k); path.lineTo(m, m); path.lineTo(m + k, m)
        path.moveTo(w - m - k, m); path.lineTo(w - m, m); path.lineTo(w - m, m + k)
        path.moveTo(m, h - m - k); path.lineTo(m, h - m); path.lineTo(m + k, h - m)
        path.moveTo(w - m - k, h - m); path.lineTo(w - m, h - m); path.lineTo(w - m, h - m - k)
        canvas.drawPath(path, line)

        val text = "RF SENTINEL"
        fit(text, w - 40 * d, 26 * d)
        title.color = c
        title.setShadowLayer(6 * d, 0f, 0f, c)
        title.textAlign = Paint.Align.CENTER
        canvas.drawText(text, w / 2, 8 * d + title.textSize, title)
        title.clearShadowLayer(); title.textAlign = Paint.Align.LEFT

        // Heading tape: ticks scroll slowly, a fixed caret marks the centre.
        val tapeY = h - 18 * d
        val offset = ((now - start) / 60f * d) % (10 * d)
        line.strokeWidth = 1 * d; line.color = dim
        var x = 20 * d - offset
        var i = 0
        while (x < w - 20 * d) {
            val tall = ((now - start) / 600 + i) % 5 == 0L
            canvas.drawLine(x, tapeY, x, tapeY - if (tall) 8 * d else 4 * d, line)
            x += 10 * d; i++
        }
        fill.color = c
        path.reset(); path.moveTo(w / 2, tapeY + 1 * d); path.lineTo(w / 2 - 5 * d, tapeY + 8 * d); path.lineTo(w / 2 + 5 * d, tapeY + 8 * d); path.close()
        canvas.drawPath(path, fill)
        sub.color = dim; sub.textSize = 10 * d
        canvas.drawText("NIGHT DRIVE", 20 * d, h - 5 * d, sub)
        sub.textAlign = Paint.Align.RIGHT
        canvas.drawText("RX ONLY", w - 20 * d, h - 5 * d, sub)
        sub.textAlign = Paint.Align.LEFT
        return 80
    }

    // ---- NIGHT_VISION -----------------------------------------------------------

    private fun nightVision(canvas: Canvas, now: Long): Long {
        val w = width.toFloat(); val h = height.toFloat()
        val c = primary
        // Slight flicker of the intensifier tube.
        val flicker = if (Random.nextInt(30) == 0) 0.75f else 1f
        val text = "RF SENTINEL"
        fit(text, w - 16 * d, 30 * d)
        title.color = c; title.alpha = (255 * flicker).toInt()
        title.setShadowLayer(10 * d, 0f, 0f, c)
        val base = title.textSize + 2 * d
        canvas.drawText(text, 6 * d, base, title)
        title.clearShadowLayer(); title.alpha = 255
        sub.color = dim
        canvas.drawText("GEN-3 // GAIN AUTO // IR OFF", 6 * d, base + sub.textSize + 6 * d, sub)
        // Grain.
        fill.color = c
        repeat(90) {
            fill.alpha = Random.nextInt(30, 110)
            val gx = Random.nextFloat() * w; val gy = Random.nextFloat() * h
            canvas.drawRect(gx, gy, gx + d, gy + d, fill)
        }
        fill.alpha = 255
        // Scope vignette: dark edges, bright centre.
        fill.shader = RadialGradient(w / 2, h / 2, w * 0.62f, intArrayOf(0x00000000, 0x00000000, bg), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        return 90
    }

    // ---- CRT (Amber) --------------------------------------------------------------

    private fun crt(canvas: Canvas, now: Long): Long {
        val w = width.toFloat(); val h = height.toFloat()
        val c = primary
        val text = "RFSENTINEL"
        fit(text, w - 40 * d, 30 * d)
        title.color = c
        title.setShadowLayer(5 * d, 0f, 0f, c)
        val base = title.textSize + 2 * d
        canvas.drawText(text, 4 * d, base, title)
        // Blinking block cursor.
        if ((now - start) / 530 % 2 == 0L) {
            val cx = 4 * d + title.measureText(text) + 4 * d
            fill.color = c
            canvas.drawRect(cx, base - title.textSize * 0.72f, cx + title.textSize * 0.55f, base + 2 * d, fill)
        }
        title.clearShadowLayer()
        sub.color = dim
        canvas.drawText("C:\\> SCAN /PASSIVE /ALL", 4 * d, base + sub.textSize + 6 * d, sub)
        scanlines(canvas, (c and 0x00FFFFFF) or 0x16000000)
        // A slow rolling refresh bar.
        val y = ((now - start) / 18f * d) % (h + 20 * d) - 20 * d
        fill.shader = LinearGradient(0f, y, 0f, y + 20 * d, 0x00000000, (c and 0x00FFFFFF) or 0x22000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, y, w, y + 20 * d, fill)
        fill.shader = null
        return 90
    }

    // ---- SUNSET (Synthwave) -----------------------------------------------------------

    private fun sunset(canvas: Canvas, now: Long): Long {
        val w = width.toFloat(); val h = height.toFloat()
        val horizon = h * 0.62f
        // Striped sun on the right.
        val r = h * 0.42f
        val sx = w - r - 8 * d
        fill.shader = LinearGradient(0f, horizon - r, 0f, horizon, 0xFFFFD166.toInt(), trace, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(sx - r, horizon - r, sx + r, horizon)
        canvas.drawCircle(sx, horizon, r, fill)
        canvas.restore()
        fill.shader = null
        fill.color = bg
        var stripe = 0
        var y = horizon - r * 0.45f
        while (y < horizon) {
            val gap = 1.2f * d + stripe * 0.9f * d
            canvas.drawRect(sx - r, y, sx + r, y + gap, fill)
            y += gap + 5 * d; stripe++
        }
        // Wireframe grid below the horizon, scrolling toward you.
        line.color = secondary; line.strokeWidth = 1 * d
        canvas.drawLine(0f, horizon, w, horizon, line)
        val phase = ((now - start) % 1600) / 1600f
        for (i in 0 until 6) {
            val t = (i + phase) / 6f
            val gy = horizon + (h - horizon) * t * t
            line.alpha = (80 + 175 * t).toInt()
            canvas.drawLine(0f, gy, w, gy, line)
        }
        line.alpha = 200
        val vx = w / 2
        for (i in -8..8) canvas.drawLine(vx + i * 12 * d, horizon, vx + i * 60 * d, h, line)
        line.alpha = 255
        // Chrome title.
        val text = "RF SENTINEL"
        fit(text, sx - r - 8 * d, 32 * d)
        val base = 6 * d + title.textSize
        title.shader = LinearGradient(0f, base - title.textSize, 0f, base, secondary, primary, Shader.TileMode.CLAMP)
        title.setShadowLayer(8 * d, 0f, 0f, primary)
        canvas.drawText(text, 4 * d, base, title)
        title.shader = null; title.clearShadowLayer()
        sub.color = secondary
        canvas.drawText("PASSIVE  RF  1986", 6 * d, base + sub.textSize + 4 * d, sub)
        return 50
    }

    // ---- STENCIL (Tactical) -------------------------------------------------------------

    private fun stencil(canvas: Canvas, now: Long): Long {
        val w = width.toFloat(); val h = height.toFloat()
        val c = trace
        val reticleR = h * 0.36f
        val text = "RF-SENTINEL"
        fit(text, w - reticleR * 2 - 40 * d, 30 * d)
        title.color = c
        val base = 6 * d + title.textSize
        // Stencil bridges: draw the text, then cut thin horizontal gaps through it.
        canvas.drawText(text, 16 * d, base, title)
        fill.color = bg
        canvas.drawRect(16 * d, base - title.textSize * 0.42f, 16 * d + title.measureText(text), base - title.textSize * 0.36f, fill)
        // Chevrons.
        line.color = primary; line.strokeWidth = 2.5f * d
        for (i in 0 until 2) {
            val x = 2 * d + i * 5 * d
            canvas.drawLine(x, base - title.textSize * 0.7f, x + 5 * d, base - title.textSize * 0.4f, line)
            canvas.drawLine(x + 5 * d, base - title.textSize * 0.4f, x, base - title.textSize * 0.1f, line)
        }
        // Zulu clock (time only - no location anywhere).
        val zulu = SimpleDateFormat("HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now)) + "Z"
        sub.color = dim; sub.textSize = 11 * d
        canvas.drawText("CALLSIGN SENTINEL-1 · RX ONLY · $zulu", 16 * d, base + sub.textSize + 8 * d, sub)
        // Reticle with a slow sweep and blinking centre.
        val cx = w - reticleR - 8 * d; val cy = h / 2
        line.color = primary; line.strokeWidth = 1.5f * d
        canvas.drawCircle(cx, cy, reticleR, line)
        canvas.drawCircle(cx, cy, reticleR * 0.5f, line)
        canvas.drawLine(cx - reticleR - 4 * d, cy, cx - reticleR * 0.2f, cy, line)
        canvas.drawLine(cx + reticleR * 0.2f, cy, cx + reticleR + 4 * d, cy, line)
        canvas.drawLine(cx, cy - reticleR - 4 * d, cx, cy - reticleR * 0.2f, line)
        canvas.drawLine(cx, cy + reticleR * 0.2f, cx, cy + reticleR + 4 * d, line)
        val a = ((now - start) % 4000) / 4000.0 * 2 * Math.PI
        line.color = c; line.alpha = 170
        canvas.drawLine(cx, cy, cx + (reticleR * cos(a)).toFloat(), cy + (reticleR * sin(a)).toFloat(), line)
        line.alpha = 255
        if ((now - start) / 700 % 2 == 0L) { fill.color = c; canvas.drawCircle(cx, cy, 2.5f * d, fill) }
        return 60
    }

    // ---- BLUEPRINT ----------------------------------------------------------------------

    private fun blueprint(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ink = primary
        // Drafting grid: fine every 8 dp, bold every 40 dp.
        line.color = ink; line.strokeWidth = 0.5f * d
        var x = 0f
        var n = 0
        while (x <= w) { line.alpha = if (n % 5 == 0) 60 else 22; canvas.drawLine(x, 0f, x, h, line); x += 8 * d; n++ }
        var y = 0f; n = 0
        while (y <= h) { line.alpha = if (n % 5 == 0) 60 else 22; canvas.drawLine(0f, y, w, y, line); y += 8 * d; n++ }
        line.alpha = 255
        // Title block box (bottom right).
        val boxW = min(130 * d, w * 0.38f); val boxH = 30 * d
        val box = RectF(w - boxW - 2 * d, h - boxH - 2 * d, w - 2 * d, h - 2 * d)
        line.strokeWidth = 1.2f * d
        canvas.drawRect(box, line)
        canvas.drawLine(box.left, box.centerY(), box.right, box.centerY(), line)
        sub.color = ink
        canvas.drawText("DWG RF-001  REV C", box.left + 4 * d, box.centerY() - 4 * d, sub)
        canvas.drawText("SCALE 1:1  RX ONLY", box.left + 4 * d, box.bottom - 4 * d, sub)
        // Title and its dimension line.
        val text = "RF SENTINEL"
        fit(text, w - boxW - 30 * d, 28 * d)
        title.color = ink
        val tx = 10 * d; val base = 8 * d + title.textSize
        canvas.drawText(text, tx, base, title)
        val tw = title.measureText(text)
        val dy = base + 14 * d
        line.strokeWidth = 1 * d
        canvas.drawLine(tx, base + 4 * d, tx, dy + 4 * d, line)
        canvas.drawLine(tx + tw, base + 4 * d, tx + tw, dy + 4 * d, line)
        canvas.drawLine(tx, dy, tx + tw, dy, line)
        fill.color = ink
        arrow(canvas, tx, dy, 1f); arrow(canvas, tx + tw, dy, -1f)
        val label = "2.4 - 5 GHz"
        val lw = sub.measureText(label)
        fill.color = bg
        canvas.drawRect(tx + tw / 2 - lw / 2 - 3 * d, dy - sub.textSize / 2 - 1 * d, tx + tw / 2 + lw / 2 + 3 * d, dy + sub.textSize / 2 + 1 * d, fill)
        sub.color = secondary
        canvas.drawText(label, tx + tw / 2 - lw / 2, dy + sub.textSize / 3, sub)
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, dir: Float) {
        path.reset()
        path.moveTo(x, y); path.lineTo(x + dir * 6 * d, y - 2.5f * d); path.lineTo(x + dir * 6 * d, y + 2.5f * d); path.close()
        canvas.drawPath(path, fill)
    }

    // ---- MASTHEAD (Paper) ----------------------------------------------------------------

    private fun masthead(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ink = primary
        line.color = ink
        // Thick-thin rule on top.
        line.strokeWidth = 2.5f * d; canvas.drawLine(0f, 2 * d, w, 2 * d, line)
        line.strokeWidth = 0.8f * d; canvas.drawLine(0f, 6 * d, w, 6 * d, line)
        val text = "The RF Sentinel"
        fit(text, w - 12 * d, 34 * d)
        title.color = ink
        val base = 10 * d + title.textSize
        canvas.drawText(text, w / 2, base, title)
        // Dateline between thin-thick rules.
        val ruleY = base + 8 * d
        line.strokeWidth = 0.8f * d; canvas.drawLine(0f, ruleY, w, ruleY, line)
        val lineY = ruleY + sub.textSize + 5 * d
        val cal = Calendar.getInstance()
        sub.color = ink
        sub.textAlign = Paint.Align.LEFT
        canvas.drawText("VOL. II · NO. ${cal.get(Calendar.DAY_OF_YEAR)}", 2 * d, lineY, sub)
        sub.textAlign = Paint.Align.CENTER
        canvas.drawText(SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(cal.time).uppercase(Locale.US), w / 2, lineY, sub)
        sub.textAlign = Paint.Align.RIGHT
        sub.color = secondary
        canvas.drawText("PASSIVE EDITION", w - 2 * d, lineY, sub)
        sub.textAlign = Paint.Align.LEFT
        line.strokeWidth = 2.5f * d; canvas.drawLine(0f, min(h - 2 * d, lineY + 6 * d), w, min(h - 2 * d, lineY + 6 * d), line)
    }

    private fun scanlines(canvas: Canvas, color: Int) {
        fill.color = color
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + d, fill)
            y += 3 * d
        }
    }
}
