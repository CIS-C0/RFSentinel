package com.rfsentinel.app.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Color

/**
 * The position marker on the map (your arrow): the navigation arrow on a disc, a dot with a heading beam, an arrow, or a car seen
 * from above, in a colour of your choice.
 * Everything is drawn here in code on a 100 x 200 unit sheet with the front at the top, so any size is sharp and
 * any colour works; the map turns it with your direction of travel. Pure drawing: the same function makes the
 * map's marker and the pictures in Settings.
 */
object MarkerArt {

    /** [scale]: how much bigger than the others it is drawn at the same size setting (the round ones fill only the sheet's width). */
    enum class Style(val key: String, val label: String, val scale: Float = 1f) {
        NAVIGATION("navigation", "Navigation", 1.7f), DOT("dot", "Classic dot", 1.4f), ARROW("arrow", "Arrow"), CHEVRON("chevron", "Chevron"),
        SEDAN("sedan", "Sedan"), HATCHBACK("hatchback", "Hatchback"), WAGON("wagon", "Wagon"), SUV("suv", "SUV"),
        PICKUP("pickup", "Pickup"), SPORTS("sports", "Sports car"), RACER("racer", "Racer"), CONVERTIBLE("convertible", "Convertible"),
        MINI("mini", "Mini"), VAN("van", "Van"), TRUCK("truck", "Truck"), TAXI("taxi", "Taxi"), MOTORCYCLE("motorcycle", "Motorcycle");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ARROW
        }
    }

    class Swatch(val key: String, val label: String, val argb: Int) {
        override fun toString() = label
    }

    val colors: List<Swatch> = listOf(
        Swatch("red", "Red", 0xFFE53935.toInt()),
        Swatch("orange", "Orange", 0xFFFB8C00.toInt()),
        Swatch("yellow", "Yellow", 0xFFFDD835.toInt()),
        Swatch("gold", "Gold", 0xFFC9A227.toInt()),
        Swatch("lime", "Lime", 0xFF8BC34A.toInt()),
        Swatch("green", "Green", 0xFF2E9E4F.toInt()),
        Swatch("teal", "Teal", 0xFF0AA39C.toInt()),
        Swatch("cyan", "Sky", 0xFF29B6F6.toInt()),
        Swatch("blue", "Blue", 0xFF1E6FE0.toInt()),
        Swatch("navy", "Navy", 0xFF1F3A93.toInt()),
        Swatch("purple", "Purple", 0xFF8E44D6.toInt()),
        Swatch("pink", "Pink", 0xFFEC5C9B.toInt()),
        Swatch("white", "White", 0xFFF3F4F6.toInt()),
        Swatch("silver", "Silver", 0xFFB8C0C8.toInt()),
        Swatch("grey", "Grey", 0xFF6B7480.toInt()),
        Swatch("black", "Black", 0xFF23272D.toInt())
    )

    fun colorOf(key: String?): Swatch = colors.firstOrNull { it.key == key } ?: colors.first { it.key == "blue" }

    /** The marker, [heightPx] tall and half as wide, front up. */
    fun render(style: Style, color: Int, heightPx: Int): Bitmap {
        val h = heightPx.coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(h / 2, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(h / 200f, h / 200f)
        when (style) {
            Style.NAVIGATION -> navigation(c, color)
            Style.DOT -> dot(c, color)
            Style.ARROW -> arrow(c, color, ARROW)
            Style.CHEVRON -> arrow(c, color, CHEVRON)
            Style.SEDAN -> car(c, color, SEDAN)
            Style.HATCHBACK -> car(c, color, HATCHBACK)
            Style.WAGON -> car(c, color, WAGON)
            Style.SUV -> car(c, color, SUV)
            Style.PICKUP -> car(c, color, PICKUP)
            Style.SPORTS -> car(c, color, SPORTS)
            Style.RACER -> car(c, color, RACER)
            Style.CONVERTIBLE -> car(c, color, CONVERTIBLE)
            Style.MINI -> car(c, color, MINI)
            Style.VAN -> car(c, color, VAN)
            Style.TAXI -> car(c, color, TAXI)
            Style.TRUCK -> truck(c, color)
            Style.MOTORCYCLE -> motorcycle(c, color)
        }
        return bmp
    }

    // ---- colours ---------------------------------------------------------------------------------------

    internal fun shade(c: Int, f: Float) = Color.argb(Color.alpha(c),
        (Color.red(c) * f).toInt().coerceIn(0, 255), (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))

    internal fun lighten(c: Int, t: Float) = Color.argb(Color.alpha(c),
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(), (Color.green(c) + (255 - Color.green(c)) * t).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * t).toInt())

    internal const val GLASS = 0xFF1E2B38.toInt()
    private const val GLASS_HI = 0xFF3E5C78.toInt()
    internal const val DARK = 0xFF14171B.toInt()
    internal const val HEADLIGHT = 0xFFFFF3B0.toInt()
    internal const val TAILLIGHT = 0xFFD32F2F.toInt()
    private const val CX = 50f

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun line(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = width; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /** The paint for a body: the colour, a little darker towards both sides so it reads as round. */
    private fun bodyPaint(color: Int, left: Float, right: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(left, 0f, right, 0f,
            intArrayOf(shade(color, 0.74f), color, lighten(color, 0.14f), color, shade(color, 0.74f)),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) = c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    private fun softShadow(c: Canvas, path: Path) {
        val p = fill(0x66000000).apply { maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL) }
        c.save(); c.translate(2f, 4f); c.drawPath(path, p); c.restore()
    }

    // ---- arrows ------------------------------------------------------------------------------------------

    private val ARROW = floatArrayOf(50f, 8f, 92f, 184f, 50f, 148f, 8f, 184f)
    private val CHEVRON = floatArrayOf(50f, 10f, 92f, 182f, 70f, 182f, 50f, 92f, 30f, 182f, 8f, 182f)

    private fun poly(pts: FloatArray) = Path().apply {
        moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }; close()
    }

    private fun arrow(c: Canvas, color: Int, pts: FloatArray) {
        val path = poly(pts)
        softShadow(c, path)
        c.drawPath(path, line(0xFFFFFFFF.toInt(), 9f))
        c.drawPath(path, bodyPaint(color, 8f, 92f))
        // The left half a little lighter, as a ridge down the middle.
        val half = Path().apply { moveTo(50f, pts[1]); lineTo(50f, if (pts.size == 8) 150f else 92f); lineTo(pts[pts.size - 2], pts[pts.size - 1]); close() }
        c.drawPath(half, fill(0x2EFFFFFF))
        c.drawPath(path, line(shade(color, 0.6f), 1.5f))
    }

    /**
     * The classic map dot: a dot in a white ring on its shadow, with a soft beam towards where you're heading. Its
     * middle is the middle of the sheet (the point the map puts on your position and turns around).
     */
    private fun dot(c: Canvas, color: Int) {
        val cy = 100f
        val beamR = 90f
        val beam = Path().apply {
            moveTo(CX, cy); arcTo(RectF(CX - beamR, cy - beamR, CX + beamR, cy + beamR), -90f - 34f, 68f); close()
        }
        c.drawPath(beam, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(CX, cy, beamR,
                intArrayOf(Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawCircle(CX + 1f, cy + 3f, 31f, fill(0x55000000).apply { maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(CX, cy, 31f, fill(0xFFFFFFFF.toInt()))
        c.drawCircle(CX, cy, 31f, line(0x26000000, 1.2f))
        c.drawCircle(CX, cy, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(CX - 7f, cy - 7f, 30f, lighten(color, 0.22f), shade(color, 0.86f), Shader.TileMode.CLAMP)
        })
    }

    /**
     * The map's classic navigation marker (as the map drew it before markers could be chosen): an arrow on a white
     * disc in a faint halo, the arrow in your colour.
     */
    private fun navigation(c: Canvas, color: Int) {
        c.save()
        // Drawn on a 75-unit square, centred on the sheet.
        c.translate(0f, 50f); c.scale(100f / 75f, 100f / 75f)
        c.drawCircle(37.5f, 37.5f, 37.5f, fill(0x29263D57))
        c.drawCircle(37.5f, 38.5f, 28.5f, fill(0x40000000).apply { maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(37.5f, 37.5f, 28.5f, fill(0xFFFFFFFF.toInt()))
        c.drawPath(NAV_ARROW, fill(color))
        c.restore()
    }

    private val NAV_ARROW: Path by lazy {
        androidx.core.graphics.PathParser.createPathFromPathData(
            "M39.2,28.46C39.01,27.99 38.54,27.68 38.02,27.69C37.5,27.7 37.02,28.01 36.81,28.49L27.05,45.83C26.83,46.32 26.92,46.89 27.28,47.26" +
            "C27.65,47.64 28.21,47.75 28.71,47.54L37.07,44.03C37.39,43.89 37.75,43.89 38.06,44.02L46.27,47.34C46.75,47.54 47.33,47.42 47.71,47.03" +
            "C48.09,46.64 48.21,46.07 48,45.59L39.2,28.46Z")
    }

    // ---- cars --------------------------------------------------------------------------------------------

    internal enum class Extra { RAILS, BED, SPOILER, STRIPES, ROOF_SIGN, OPEN_TOP, RIBS, LONG_ROOF }

    /** A car from above, in 100 x 200 units: its width, front and rear, how round those are, and where the glass runs. */
    internal class Car(
        val w: Float, val top: Float, val bottom: Float, val rf: Float, val rr: Float,
        /** Where the windshield starts, ends (the roof starts), the roof ends (the rear window starts) and the rear window ends. */
        val hoodEnd: Float, val wsEnd: Float, val roofEnd: Float, val rearEnd: Float,
        val wheelsY: List<Float>, val extras: Set<Extra> = emptySet()
    )

    /** The measurements of a car style (null: not a car, or the truck / motorcycle, drawn their own way). */
    internal fun specOf(style: Style): Car? = when (style) {
        Style.SEDAN -> SEDAN; Style.HATCHBACK -> HATCHBACK; Style.WAGON -> WAGON; Style.SUV -> SUV; Style.PICKUP -> PICKUP
        Style.SPORTS -> SPORTS; Style.RACER -> RACER; Style.CONVERTIBLE -> CONVERTIBLE; Style.MINI -> MINI; Style.VAN -> VAN
        Style.TAXI -> TAXI
        else -> null
    }

    internal val SEDAN = Car(64f, 14f, 186f, 20f, 14f, 62f, 86f, 112f, 134f, listOf(42f, 152f))
    internal val HATCHBACK = Car(62f, 20f, 180f, 20f, 12f, 62f, 86f, 126f, 144f, listOf(44f, 148f))
    internal val WAGON = Car(62f, 12f, 188f, 18f, 8f, 58f, 82f, 146f, 162f, listOf(40f, 158f))
    internal val SUV = Car(72f, 14f, 186f, 14f, 10f, 58f, 84f, 146f, 160f, listOf(40f, 158f), setOf(Extra.RAILS))
    internal val PICKUP = Car(70f, 10f, 190f, 14f, 6f, 56f, 78f, 100f, 110f, listOf(38f, 156f), setOf(Extra.BED))
    internal val SPORTS = Car(70f, 8f, 184f, 26f, 16f, 78f, 98f, 118f, 134f, listOf(34f, 150f), setOf(Extra.SPOILER))
    internal val RACER = Car(70f, 8f, 184f, 26f, 16f, 78f, 98f, 118f, 134f, listOf(34f, 150f), setOf(Extra.SPOILER, Extra.STRIPES))
    internal val CONVERTIBLE = Car(66f, 14f, 184f, 22f, 14f, 70f, 84f, 128f, 128f, listOf(40f, 152f), setOf(Extra.OPEN_TOP))
    internal val MINI = Car(60f, 40f, 168f, 22f, 16f, 72f, 90f, 118f, 134f, listOf(62f, 146f))
    internal val VAN = Car(66f, 16f, 188f, 12f, 8f, 44f, 70f, 176f, 186f, listOf(38f, 162f), setOf(Extra.RIBS, Extra.LONG_ROOF))
    internal val TAXI = Car(64f, 14f, 186f, 20f, 14f, 62f, 86f, 112f, 134f, listOf(42f, 152f), setOf(Extra.ROOF_SIGN))

    private fun car(c: Canvas, color: Int, k: Car) {
        val l = CX - k.w / 2; val r = CX + k.w / 2
        val body = Path().apply {
            addRoundRect(RectF(l, k.top, r, k.bottom), floatArrayOf(k.rf, k.rf, k.rf, k.rf, k.rr, k.rr, k.rr, k.rr), Path.Direction.CW)
        }
        softShadow(c, body)
        // Wheels, a little out of the body at both sides.
        val wheel = fill(DARK)
        for (y in k.wheelsY) {
            rr(c, l - 6.5f, y - 13f, l + 7f, y + 13f, 5f, wheel)
            rr(c, r - 7f, y - 13f, r + 6.5f, y + 13f, 5f, wheel)
        }
        c.drawPath(body, line(0xFFFFFFFF.toInt(), 6f))
        c.drawPath(body, bodyPaint(color, l, r))

        if (Extra.STRIPES in k.extras) {
            val s = fill(0xE6FFFFFF.toInt())
            rr(c, CX - 11f, k.top + 5f, CX - 4f, k.bottom - 5f, 2f, s)
            rr(c, CX + 4f, k.top + 5f, CX + 11f, k.bottom - 5f, 2f, s)
        }
        if (Extra.BED in k.extras) {
            // The load bed: a dark tub with a lighter rim.
            val tub = RectF(l + 5f, k.rearEnd + 2f, r - 5f, k.bottom - 5f)
            c.drawRoundRect(tub, 4f, 4f, fill(shade(color, 0.38f)))
            c.drawRoundRect(tub, 4f, 4f, line(lighten(color, 0.25f), 1.6f))
            for (y in listOf(0.33f, 0.66f)) c.drawLine(tub.left + 3f, tub.top + tub.height() * y, tub.right - 3f, tub.top + tub.height() * y, line(shade(color, 0.3f), 1.2f))
        }

        // The windshield and the cabin.
        val bw = k.w * 0.40f; val tw = k.w * 0.33f
        if (Extra.OPEN_TOP in k.extras) {
            val cockpit = RectF(CX - k.w * 0.37f, k.wsEnd, CX + k.w * 0.37f, k.roofEnd)
            c.drawRoundRect(cockpit, 8f, 8f, fill(0xFF262B32.toInt()))
            val seat = fill(0xFF55616E.toInt())
            rr(c, CX - k.w * 0.31f, k.wsEnd + 10f, CX - 3f, k.wsEnd + 34f, 6f, seat)
            rr(c, CX + 3f, k.wsEnd + 10f, CX + k.w * 0.31f, k.wsEnd + 34f, 6f, seat)
            windshield(c, bw, tw, k.hoodEnd, k.wsEnd)
        } else {
            windshield(c, bw, tw, k.hoodEnd, k.wsEnd)
            // The roof, a touch lighter than the rest, with its edge.
            val roof = RectF(CX - tw, k.wsEnd, CX + tw, k.roofEnd)
            val long = Extra.LONG_ROOF in k.extras
            c.drawRoundRect(roof, if (long) 5f else 8f, if (long) 5f else 8f, fill(lighten(color, 0.18f)))
            c.drawRoundRect(roof, if (long) 5f else 8f, if (long) 5f else 8f, line(shade(color, 0.62f), 1.4f))
            if (Extra.RAILS in k.extras) {
                val rail = line(0xFF30363D.toInt(), 2.2f)
                c.drawLine(CX - tw * 0.72f, k.wsEnd + 5f, CX - tw * 0.72f, k.roofEnd - 5f, rail)
                c.drawLine(CX + tw * 0.72f, k.wsEnd + 5f, CX + tw * 0.72f, k.roofEnd - 5f, rail)
            }
            if (Extra.RIBS in k.extras) {
                val rib = line(shade(color, 0.7f), 1.4f)
                var y = k.wsEnd + 14f
                while (y < k.roofEnd - 8f) { c.drawLine(CX - tw + 4f, y, CX + tw - 4f, y, rib); y += 18f }
            }
            if (Extra.ROOF_SIGN in k.extras) {
                val s = RectF(CX - 10f, (k.wsEnd + k.roofEnd) / 2 - 6f, CX + 10f, (k.wsEnd + k.roofEnd) / 2 + 6f)
                c.drawRoundRect(s, 3f, 3f, fill(0xFFFFE082.toInt()))
                c.drawRoundRect(s, 3f, 3f, line(0xFF3A3A3A.toInt(), 1.4f))
            }
            if (k.rearEnd > k.roofEnd + 2f) {
                val rear = Path().apply {
                    moveTo(CX - tw, k.roofEnd); lineTo(CX + tw, k.roofEnd)
                    lineTo(CX + k.w * 0.37f, k.rearEnd); lineTo(CX - k.w * 0.37f, k.rearEnd); close()
                }
                c.drawPath(rear, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, k.roofEnd, 0f, k.rearEnd, GLASS, GLASS_HI, Shader.TileMode.CLAMP)
                })
            }
        }
        if (Extra.SPOILER in k.extras) {
            rr(c, l + 1f, k.bottom - 16f, r - 1f, k.bottom - 9f, 3f, fill(0xFF1A1D21.toInt()))
        }
        // Mirrors, lights.
        val mirror = fill(shade(color, 0.72f))
        c.drawOval(RectF(l - 5f, k.hoodEnd - 8f, l + 2f, k.hoodEnd + 2f), mirror)
        c.drawOval(RectF(r - 2f, k.hoodEnd - 8f, r + 5f, k.hoodEnd + 2f), mirror)
        val head = fill(HEADLIGHT); val tail = fill(TAILLIGHT)
        rr(c, l + 5f, k.top + 3f, l + 17f, k.top + 9f, 3f, head)
        rr(c, r - 17f, k.top + 3f, r - 5f, k.top + 9f, 3f, head)
        rr(c, l + 4f, k.bottom - 9f, l + 15f, k.bottom - 3f, 3f, tail)
        rr(c, r - 15f, k.bottom - 9f, r - 4f, k.bottom - 3f, 3f, tail)
    }

    private fun windshield(c: Canvas, bw: Float, tw: Float, from: Float, to: Float) {
        val p = Path().apply { moveTo(CX - bw, from); lineTo(CX + bw, from); lineTo(CX + tw, to); lineTo(CX - tw, to); close() }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, from, 0f, to, GLASS_HI, GLASS, Shader.TileMode.CLAMP)
        })
    }

    private fun truck(c: Canvas, color: Int) {
        val cab = RectF(CX - 31f, 12f, CX + 31f, 72f)
        val box = RectF(CX - 37f, 78f, CX + 37f, 192f)
        val all = Path().apply {
            addRoundRect(cab, floatArrayOf(18f, 18f, 18f, 18f, 4f, 4f, 4f, 4f), Path.Direction.CW)
            addRoundRect(box, 6f, 6f, Path.Direction.CW)
        }
        softShadow(c, all)
        val wheel = fill(DARK)
        for (y in listOf(34f, 134f, 168f)) {
            val cabSide = y < 78f
            val l = if (cabSide) cab.left else box.left; val r = if (cabSide) cab.right else box.right
            rr(c, l - 6.5f, y - 13f, l + 7f, y + 13f, 5f, wheel)
            rr(c, r - 7f, y - 13f, r + 6.5f, y + 13f, 5f, wheel)
        }
        // The hitch between the cab and the box.
        rr(c, CX - 14f, 68f, CX + 14f, 82f, 2f, fill(DARK))
        val cabPath = Path().apply { addRoundRect(cab, floatArrayOf(18f, 18f, 18f, 18f, 4f, 4f, 4f, 4f), Path.Direction.CW) }
        c.drawPath(cabPath, line(0xFFFFFFFF.toInt(), 6f))
        c.drawRoundRect(box, 6f, 6f, line(0xFFFFFFFF.toInt(), 6f))
        c.drawPath(cabPath, bodyPaint(color, cab.left, cab.right))
        // The box: pale, with the colour showing in its ribs.
        val boxColor = lighten(color, 0.82f)
        c.drawRoundRect(box, 6f, 6f, bodyPaint(boxColor, box.left, box.right))
        c.drawRoundRect(box, 6f, 6f, line(shade(boxColor, 0.7f), 1.4f))
        val rib = line(shade(color, 0.9f), 2.2f)
        var y = 94f
        while (y < 186f) { c.drawLine(box.left + 6f, y, box.right - 6f, y, rib); y += 18f }
        windshield(c, 26f, 21f, 36f, 52f)
        rr(c, CX - 22f, 52f, CX + 22f, 66f, 6f, fill(lighten(color, 0.16f)))
        val head = fill(HEADLIGHT); val tail = fill(TAILLIGHT)
        rr(c, cab.left + 5f, 15f, cab.left + 17f, 21f, 3f, head)
        rr(c, cab.right - 17f, 15f, cab.right - 5f, 21f, 3f, head)
        rr(c, box.left + 4f, box.bottom - 9f, box.left + 15f, box.bottom - 3f, 3f, tail)
        rr(c, box.right - 15f, box.bottom - 9f, box.right - 4f, box.bottom - 3f, 3f, tail)
    }

    private fun motorcycle(c: Canvas, color: Int) {
        val frontWheel = RectF(43f, 14f, 57f, 62f)
        val rearWheel = RectF(42f, 138f, 58f, 190f)
        val tank = RectF(36f, 46f, 64f, 150f)
        val shape = Path().apply { addRoundRect(tank, 14f, 14f, Path.Direction.CW) }
        softShadow(c, shape)
        val tyre = fill(DARK)
        c.drawRoundRect(frontWheel, 6f, 6f, tyre); c.drawRoundRect(rearWheel, 7f, 7f, tyre)
        c.drawPath(shape, line(0xFFFFFFFF.toInt(), 6f))
        c.drawPath(shape, bodyPaint(color, tank.left, tank.right))
        // Seat and the rider: shoulders, helmet, arms to the bars.
        rr(c, 41f, 104f, 59f, 146f, 8f, fill(0xFF2A2E34.toInt()))
        val bars = line(DARK, 5f)
        c.drawLine(22f, 58f, 78f, 58f, bars)
        c.drawLine(24f, 56f, 36f, 80f, line(0xFF3A3F47.toInt(), 6f)); c.drawLine(76f, 56f, 64f, 80f, line(0xFF3A3F47.toInt(), 6f))
        c.drawOval(RectF(33f, 78f, 67f, 116f), fill(0xFF3A3F47.toInt()))
        c.drawCircle(CX, 96f, 10f, fill(lighten(color, 0.55f)))
        c.drawCircle(CX, 96f, 10f, line(shade(color, 0.6f), 1.5f))
        c.drawOval(RectF(18f, 52f, 26f, 62f), fill(shade(color, 0.7f))); c.drawOval(RectF(74f, 52f, 82f, 62f), fill(shade(color, 0.7f)))
        rr(c, 45f, 16f, 55f, 24f, 4f, fill(HEADLIGHT))
        rr(c, 45f, 178f, 55f, 186f, 3f, fill(TAILLIGHT))
    }
}
