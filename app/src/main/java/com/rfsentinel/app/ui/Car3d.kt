package com.rfsentinel.app.ui

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Your car as a 3D model (as in Google Maps), for the Mapbox map, which draws a 3D position marker itself: it turns
 * and tilts with the map and shows the car's sides, roof and glass as a real one would. The models are built here from
 * the same measurements as the flat drawings in [MarkerArt] and saved as glTF files ([modelFile]). The OpenFreeMap
 * map has no 3D models and keeps the flat drawings.
 *
 * Units: the flat drawings' 100 x 200 sheet; x to the right, y forward, z up, the car's middle on the ground at 0.
 */
internal object Car3d {

    fun supports(style: MarkerArt.Style) = when (style) {
        MarkerArt.Style.NAVIGATION, MarkerArt.Style.DOT, MarkerArt.Style.ARROW, MarkerArt.Style.CHEVRON -> false
        else -> true
    }

    // ---- the model --------------------------------------------------------------------------------------

    private class V(val x: Float, val y: Float, val z: Float)

    /** One face: its corners, colour, shine and a point inside its part (its normal points away from it). */
    private class Tri(val a: V, val b: V, val c: V, val color: Int, val shine: Float, val inside: V, val paint: ((Float, Float) -> Int)? = null)

    private class Mesh(val tris: List<Tri>)

    private val meshes = object : LinkedHashMap<String, Mesh>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mesh>?) = size > 6
    }

    private fun mesh(style: MarkerArt.Style, color: Int): Mesh = synchronized(meshes) {
        meshes.getOrPut("${style.key}|$color") { build(style, color) }
    }

    private class Builder {
        val tris = ArrayList<Tri>()

        fun tri(a: V, b: V, c: V, color: Int, shine: Float, inside: V, paint: ((Float, Float) -> Int)? = null) {
            tris += Tri(a, b, c, color, shine, inside, paint)
        }

        fun quad(a: V, b: V, c: V, d: V, color: Int, shine: Float, inside: V, paint: ((Float, Float) -> Int)? = null) {
            tri(a, b, c, color, shine, inside, paint); tri(a, c, d, color, shine, inside, paint)
        }

        /** A box (no floor: never seen from above). */
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, color: Int, shine: Float = 0.25f,
                top: Int = color, paint: ((Float, Float) -> Int)? = null) {
            val i = V((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
            val a = V(x0, y0, z0); val b = V(x1, y0, z0); val c = V(x1, y1, z0); val d = V(x0, y1, z0)
            val e = V(x0, y0, z1); val f = V(x1, y0, z1); val g = V(x1, y1, z1); val h = V(x0, y1, z1)
            quad(e, f, g, h, top, shine, i, paint)
            quad(a, b, f, e, color, shine, i); quad(b, c, g, f, color, shine, i)
            quad(c, d, h, g, color, shine, i); quad(d, a, e, h, color, shine, i)
        }

        /** Rings of the same number of points, joined band by band; the last ring closed with a lid. */
        fun loft(rings: List<List<V>>, bands: List<Int>, shine: Float, lid: Int, lidPaint: ((Float, Float) -> Int)? = null) {
            val all = rings.flatten()
            val inside = V(all.map { it.x }.average().toFloat(), all.map { it.y }.average().toFloat(), all.map { it.z }.average().toFloat())
            for (r in 0 until rings.size - 1) {
                val lo = rings[r]; val hi = rings[r + 1]
                for (i in lo.indices) {
                    val j = (i + 1) % lo.size
                    quad(lo[i], lo[j], hi[j], hi[i], bands[r], shine, inside)
                }
            }
            val top = rings.last()
            val mid = V(top.map { it.x }.average().toFloat(), top.map { it.y }.average().toFloat(), top.map { it.z }.average().toFloat())
            val under = V(mid.x, mid.y, mid.z - 50f)
            for (i in top.indices) tri(mid, top[i], top[(i + 1) % top.size], lid, shine, under, lidPaint)
        }

        /** A wheel on the ground: a tyre with a grey rim on its outer face. */
        fun wheel(x: Float, y: Float, r: Float, width: Float, outward: Float) {
            val n = 14
            val xi = x - outward * width / 2; val xo = x + outward * width / 2
            val inside = V(x, y, r)
            val inner = (0 until n).map { k -> val a = 2 * PI * k / n; V(xi, y + r * cos(a).toFloat(), r + r * sin(a).toFloat()) }
            val outer = (0 until n).map { k -> val a = 2 * PI * k / n; V(xo, y + r * cos(a).toFloat(), r + r * sin(a).toFloat()) }
            for (k in 0 until n) {
                val j = (k + 1) % n
                quad(inner[k], inner[j], outer[j], outer[k], MarkerArt.DARK, 0.08f, inside)
            }
            val hub = V(xo, y, r)
            val face = V(x, y, r)
            for (k in 0 until n) tri(hub, outer[k], outer[(k + 1) % n], MarkerArt.DARK, 0.15f, face) { py, pz ->
                if (sqrt((py - y) * (py - y) + (pz - r) * (pz - r)) < r * 0.62f) 0xFF9AA1AA.toInt() else MarkerArt.DARK
            }
        }
    }

    /** A rounded rectangle in plan, counter-clockwise: [l]..[r] wide, [front]..[rear] long, corners [rf] / [rr]. */
    private fun outline(l: Float, r: Float, front: Float, rear: Float, rf: Float, rr: Float): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        fun arc(cx: Float, cy: Float, rad: Float, from: Double) {
            val segs = 5
            for (k in 0..segs) {
                val a = Math.toRadians(from + 90.0 * k / segs)
                pts += (cx + rad * cos(a).toFloat()) to (cy + rad * sin(a).toFloat())
            }
        }
        arc(r - rf, front - rf, rf, 0.0)
        arc(l + rf, front - rf, rf, 90.0)
        arc(l + rr, rear + rr, rr, 180.0)
        arc(r - rr, rear + rr, rr, 270.0)
        return pts
    }

    /** The front (or rear) edge of that outline at [x]. */
    private fun edgeY(x: Float, half: Float, end: Float, rad: Float, front: Boolean): Float {
        val dx = abs(x) - (half - rad)
        val dy = if (dx <= 0f) 0f else rad - sqrt(max(0f, rad * rad - dx * dx))
        return if (front) end - dy else end + dy
    }

    private class Heights(val body: Float, val cabin: Float)

    private fun heights(style: MarkerArt.Style, w: Float) = when (style) {
        MarkerArt.Style.HATCHBACK -> Heights(0.42f * w, 0.36f * w)
        MarkerArt.Style.WAGON -> Heights(0.42f * w, 0.34f * w)
        MarkerArt.Style.SUV -> Heights(0.50f * w, 0.36f * w)
        MarkerArt.Style.PICKUP -> Heights(0.50f * w, 0.34f * w)
        MarkerArt.Style.SPORTS, MarkerArt.Style.RACER -> Heights(0.32f * w, 0.26f * w)
        MarkerArt.Style.CONVERTIBLE -> Heights(0.38f * w, 0.30f * w)
        MarkerArt.Style.MINI -> Heights(0.44f * w, 0.38f * w)
        MarkerArt.Style.VAN -> Heights(0.52f * w, 0.50f * w)
        else -> Heights(0.40f * w, 0.33f * w)
    }

    private fun build(style: MarkerArt.Style, color: Int): Mesh {
        val b = Builder()
        when (style) {
            MarkerArt.Style.TRUCK -> truck(b, color)
            MarkerArt.Style.MOTORCYCLE -> motorcycle(b, color)
            else -> car(b, style, MarkerArt.specOf(style) ?: MarkerArt.SEDAN, color)
        }
        return Mesh(b.tris)
    }

    private const val CLEAR = 6f

    /** A car body with its cabin, wheels, lights and extras, from its flat drawing's measurements. */
    private fun car(b: Builder, style: MarkerArt.Style, k: MarkerArt.Car, color: Int, heightsOverride: Heights? = null) {
        val hs = heightsOverride ?: heights(style, k.w)
        val half = k.w / 2
        // Sheet (y down, 0..200) to model (y forward, middle at 0).
        fun my(sheetY: Float) = 100f - sheetY
        val front = my(k.top); val rear = my(k.bottom)
        val yHood = my(k.hoodEnd); val yWs = my(k.wsEnd); val yRoof = my(k.roofEnd)
        val yRearGlass = if (k.rearEnd > k.roofEnd + 2f) my(k.rearEnd) else yRoof
        val deckTop = CLEAR + hs.body
        // The hood slopes down to the nose, the boot a little to the tail.
        fun deck(y: Float): Float {
            val hood = if (y > yHood) ((y - yHood) / max(1f, front - yHood)).coerceIn(0f, 1f) else 0f
            val boot = if (y < yRearGlass) ((yRearGlass - y) / max(1f, yRearGlass - rear)).coerceIn(0f, 1f) else 0f
            return deckTop - hs.body * (0.22f * hood + 0.10f * boot)
        }
        fun ring(inset: Float, frac: Float): List<V> =
            outline(-half + inset, half - inset, front - inset, rear + inset, max(0f, k.rf - inset), max(0f, k.rr - inset))
                .map { (x, y) -> V(x, y, CLEAR + frac * (deck(y) - CLEAR)) }
        val stripes = MarkerArt.Extra.STRIPES in k.extras
        val bodyPaint: ((Float, Float) -> Int)? = if (stripes) { x, _ -> if (abs(x) in 4f..11f) 0xFFF2F2F2.toInt() else color } else null
        val lower = MarkerArt.shade(color, 0.82f)
        b.loft(listOf(ring(3f, 0f), ring(0f, 0.55f), ring(1.2f, 0.86f), ring(5f, 1f)),
            listOf(lower, color, color), 0.4f, color, bodyPaint)

        // Wheels, a little out of the body.
        val wheelR = min(13f, CLEAR + hs.body * 0.5f)
        for (wy in k.wheelsY) for (side in listOf(-1f, 1f)) b.wheel(side * (half - 3f), my(wy), wheelR, 11f, side)

        // The cabin: windshield, side windows, rear window and the roof.
        val roofZ = deck((yWs + yRoof) / 2) + hs.cabin
        val glassBase = k.w * 0.42f; val glassRear = k.w * 0.40f; val roofHalf = k.w * 0.33f
        if (MarkerArt.Extra.OPEN_TOP in k.extras) {
            val z0 = deck(yHood)
            b.quad(V(-glassBase, yHood, z0), V(glassBase, yHood, z0), V(roofHalf, yWs, z0 + hs.cabin * 0.7f), V(-roofHalf, yWs, z0 + hs.cabin * 0.7f),
                MarkerArt.GLASS, 0.7f, V(0f, yHood - 10f, z0))
            val cockpit = 0xFF262B32.toInt()
            b.box(-k.w * 0.37f, k.w * 0.37f, yRoof, yWs, deck(yWs) - 0.5f, deck(yWs) + 0.3f, cockpit, 0.05f)
            val seat = 0xFF55616E.toInt()
            b.box(-k.w * 0.31f, -3f, yWs - 34f, yWs - 10f, deck(yWs), deck(yWs) + hs.cabin * 0.45f, seat, 0.1f)
            b.box(3f, k.w * 0.31f, yWs - 34f, yWs - 10f, deck(yWs), deck(yWs) + hs.cabin * 0.45f, seat, 0.1f)
        } else {
            val long = MarkerArt.Extra.LONG_ROOF in k.extras
            val roofColor = MarkerArt.lighten(color, 0.08f)
            val ribs = MarkerArt.Extra.RIBS in k.extras
            val roofPaint: ((Float, Float) -> Int)? = when {
                stripes -> { x, _ -> if (abs(x) in 4f..11f) 0xFFF2F2F2.toInt() else roofColor }
                ribs -> { _, y -> if (((yWs - y) % 18f) in 12f..14f) MarkerArt.shade(color, 0.7f) else roofColor }
                else -> null
            }
            val base = listOf(V(-glassBase, yHood, deck(yHood)), V(glassBase, yHood, deck(yHood)),
                V(glassRear, yRearGlass, deck(yRearGlass)), V(-glassRear, yRearGlass, deck(yRearGlass)))
            val sill = listOf(V(-glassBase, yHood + (yWs - yHood) * 0.06f, deck(yHood) + 1.5f), V(glassBase, yHood + (yWs - yHood) * 0.06f, deck(yHood) + 1.5f),
                V(glassRear, yRearGlass, deck(yRearGlass) + 1.5f), V(-glassRear, yRearGlass, deck(yRearGlass) + 1.5f))
            val r = if (long) roofHalf * 1.05f else roofHalf
            val top = listOf(V(-r, yWs, roofZ), V(r, yWs, roofZ), V(r, yRoof, roofZ), V(-r, yRoof, roofZ))
            // The lowest strip in the body's colour, then glass all round, then the roof.
            b.loft(listOf(base, sill, top), listOf(color, MarkerArt.GLASS), 0.65f, roofColor, roofPaint)
            if (MarkerArt.Extra.RAILS in k.extras) for (side in listOf(-1f, 1f)) {
                val x = side * r * 0.72f
                b.box(x - 1.3f, x + 1.3f, yRoof + 4f, yWs - 4f, roofZ, roofZ + 2.6f, 0xFF30363D.toInt(), 0.2f)
            }
            if (MarkerArt.Extra.ROOF_SIGN in k.extras) {
                val m = (yWs + yRoof) / 2
                b.box(-10f, 10f, m - 6f, m + 6f, roofZ, roofZ + 7f, 0xFFFFE082.toInt(), 0.3f)
            }
        }
        if (MarkerArt.Extra.BED in k.extras) {
            val z = deck(rear + 10f)
            b.box(-half + 5f, half - 5f, rear + 5f, yRearGlass - 2f, z - 0.4f, z + 0.3f, MarkerArt.shade(color, 0.38f), 0.05f)
        }
        if (MarkerArt.Extra.SPOILER in k.extras) {
            val y0 = rear + 9f; val y1 = rear + 16f; val z = deck(rear + 12f)
            for (side in listOf(-1f, 1f)) b.box(side * half * 0.6f - 1.5f, side * half * 0.6f + 1.5f, y0 + 2f, y1 - 2f, z, z + 6f, 0xFF1A1D21.toInt(), 0.2f)
            b.box(-half + 1f, half - 1f, y0, y1, z + 6f, z + 9f, 0xFF1A1D21.toInt(), 0.3f)
        }
        // Mirrors.
        for (side in listOf(-1f, 1f)) {
            val x = side * half
            b.box(min(x, x + side * 5f), max(x, x + side * 5f), yHood - 4f, yHood + 2f, deck(yHood) + 1f, deck(yHood) + 5f, MarkerArt.shade(color, 0.72f), 0.3f)
        }
        // Lights on the nose and the tail, following their curve.
        for (side in listOf(-1f, 1f)) {
            val hx = side * (half - 11f)
            val fy = edgeY(hx, half, front, k.rf, true)
            val zf = CLEAR + 0.62f * (deck(front) - CLEAR)
            b.box(hx - 6f, hx + 6f, fy - 4f, fy + 0.6f, zf, zf + 4f, MarkerArt.HEADLIGHT, 0.5f)
            val ty = edgeY(hx, half, rear, k.rr, false)
            val zr = CLEAR + 0.66f * (deck(rear) - CLEAR)
            b.box(hx - 5.5f, hx + 5.5f, ty - 0.6f, ty + 4f, zr, zr + 4f, MarkerArt.TAILLIGHT, 0.4f)
        }
    }

    private fun truck(b: Builder, color: Int) {
        // The cab: a short, tall car.
        car(b, MarkerArt.Style.TRUCK, MarkerArt.Car(62f, 12f, 72f, 18f, 4f, 36f, 50f, 70f, 70f, listOf(34f)), color, Heights(36f, 24f))
        // The box behind it, pale, with ribs across its roof.
        val boxColor = MarkerArt.lighten(color, 0.82f)
        val rib = MarkerArt.shade(color, 0.9f)
        b.box(-37f, 37f, 100f - 192f, 100f - 78f, CLEAR + 2f, 78f, MarkerArt.shade(boxColor, 0.92f), 0.2f, boxColor) { _, y ->
            if (((100f - 94f - y) % 18f).let { it < 0f || it > 16f }) rib else boxColor
        }
        b.box(-14f, 14f, 100f - 82f, 100f - 68f, CLEAR, CLEAR + 8f, MarkerArt.DARK, 0.1f)
        for (wy in listOf(134f, 168f)) for (side in listOf(-1f, 1f)) b.wheel(side * 34f, 100f - wy, 13f, 12f, side)
        for (side in listOf(-1f, 1f)) b.box(side * 30f - 5.5f, side * 30f + 5.5f, 100f - 192f - 0.6f, 100f - 188f, 14f, 18f, MarkerArt.TAILLIGHT, 0.4f)
    }

    private fun motorcycle(b: Builder, color: Int) {
        b.wheel(0f, 100f - 38f, 15f, 7f, 1f)
        b.wheel(0f, 100f - 164f, 16f, 8f, 1f)
        // Tank and engine, the seat, then the rider leaning to the bars.
        b.loft(listOf(
            outline(-12f, 12f, 54f, -50f, 11f, 11f).map { (x, y) -> V(x, y, 14f) },
            outline(-14f, 14f, 54f, -50f, 13f, 13f).map { (x, y) -> V(x, y, 30f) },
            outline(-11f, 11f, 50f, -46f, 10f, 10f).map { (x, y) -> V(x, y, 40f) }),
            listOf(0xFF3A3F47.toInt(), color), 0.45f, color)
        b.box(-9f, 9f, -46f, -4f, 38f, 45f, 0xFF2A2E34.toInt(), 0.1f)
        val suit = 0xFF3A3F47.toInt()
        b.box(-13f, 13f, -28f, -6f, 45f, 76f, suit, 0.15f)
        for (side in listOf(-1f, 1f)) b.box(min(side * 10f, side * 26f), max(side * 10f, side * 26f), -8f, 42f, 62f, 67f, suit, 0.15f)
        b.box(-28f, 28f, 40f, 44f, 54f, 58f, MarkerArt.DARK, 0.3f)
        // The helmet: a low ball in a lighter shade.
        val helmet = MarkerArt.lighten(color, 0.45f)
        val c = V(0f, -14f, 86f); val r = 11f
        val lat = 5; val lon = 10
        fun p(i: Int, j: Int): V {
            val t = PI * i / lat; val f = 2 * PI * j / lon
            return V(c.x + r * (sin(t) * cos(f)).toFloat(), c.y + r * (sin(t) * sin(f)).toFloat(), c.z + r * cos(t).toFloat())
        }
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = p(i, j); val bb = p(i, j + 1); val cc = p(i + 1, j + 1); val d = p(i + 1, j)
            b.quad(a, bb, cc, d, helmet, 0.6f, c)
        }
        b.box(-5f, 5f, 100f - 16f, 100f - 12f, 26f, 32f, MarkerArt.HEADLIGHT, 0.5f)
        b.box(-5f, 5f, 100f - 190f, 100f - 186f, 28f, 32f, MarkerArt.TAILLIGHT, 0.4f)
    }

    // ---- glTF ------------------------------------------------------------------------------------------

    /** Metres per model unit: a sedan (172 units) is 4.3 m long. */
    private const val METRE = 1f / 40f
    /** Painted faces are cut into smaller triangles where their colour changes, this many times at most. */
    private const val PAINT_DEPTH = 4

    /**
     * The car as a glTF 2.0 binary (.glb), for a map that draws 3D models itself (Mapbox's 3D position marker):
     * flat-shaded triangles with their colours, in metres, y up and the front towards +z (glTF's own convention).
     * Glass, paint and the matte parts (tyres, trim) are separate materials, so the map's light plays on them
     * differently.
     */
    fun glb(style: MarkerArt.Style, color: Int): ByteArray {
        val mesh = mesh(style, color)
        // By material: matte, paint, glass.
        val groups = Array(3) { Prim() }
        for (t in mesh.tris) {
            val g = groups[if (t.shine >= 0.6f) 2 else if (t.shine >= 0.3f) 1 else 0]
            val n = normal(t) ?: continue
            if (t.paint == null) g.add(t.a, t.b, t.c, n, t.color) else paintInto(g, t, n, t.a, t.b, t.c, 0)
        }
        val materials = listOf(Material(0.85f, 0.0f), Material(0.35f, 0.25f), Material(0.12f, 0.0f))
        val used = groups.indices.filter { groups[it].count > 0 }
        return GlbWriter.write(used.map { groups[it] }, used.map { materials[it] })
    }

    /** The face's normal, away from the inside of its part (null for a degenerate face). */
    private fun normal(t: Tri): FloatArray? {
        val ux = t.b.x - t.a.x; val uy = t.b.y - t.a.y; val uz = t.b.z - t.a.z
        val vx = t.c.x - t.a.x; val vy = t.c.y - t.a.y; val vz = t.c.z - t.a.z
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return null
        val cx = (t.a.x + t.b.x + t.c.x) / 3 - t.inside.x
        val cy = (t.a.y + t.b.y + t.c.y) / 3 - t.inside.y
        val cz = (t.a.z + t.b.z + t.c.z) / 3 - t.inside.z
        if (nx * cx + ny * cy + nz * cz < 0) { nx = -nx; ny = -ny; nz = -nz }
        return floatArrayOf(nx / len, ny / len, nz / len)
    }

    /** A painted face (stripes, rims, ribs): cut into four while its corners and middle differ in colour. */
    private fun paintInto(g: Prim, t: Tri, n: FloatArray, a: V, b: V, c: V, depth: Int) {
        val paint = t.paint ?: return
        val wheel = abs(t.a.x - t.b.x) < 0.01f && abs(t.a.x - t.c.x) < 0.01f
        fun at(v: V) = if (wheel) paint(v.y, v.z) else paint(v.x, v.y)
        val m = at(V((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3, (a.z + b.z + c.z) / 3))
        if (depth >= PAINT_DEPTH || (at(a) == m && at(b) == m && at(c) == m)) { g.add(a, b, c, n, m); return }
        val ab = V((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
        val bc = V((b.x + c.x) / 2, (b.y + c.y) / 2, (b.z + c.z) / 2)
        val ca = V((c.x + a.x) / 2, (c.y + a.y) / 2, (c.z + a.z) / 2)
        paintInto(g, t, n, a, ab, ca, depth + 1); paintInto(g, t, n, ab, b, bc, depth + 1)
        paintInto(g, t, n, ca, bc, c, depth + 1); paintInto(g, t, n, ab, bc, ca, depth + 1)
    }

    private class Material(val roughness: Float, val metallic: Float)

    /** One material's triangles, ready for glTF: positions and normals in glTF's axes, colours in linear light. */
    private class Prim {
        val pos = ArrayList<Float>()
        val nrm = ArrayList<Float>()
        val col = ArrayList<Byte>()
        val count get() = pos.size / 3

        fun add(a: V, b: V, c: V, n: FloatArray, color: Int) {
            for (v in listOf(a, b, c)) {
                // Model x right, y forward, z up -> glTF x to the car's left, y up, z forward.
                pos += -v.x * METRE; pos += v.z * METRE; pos += v.y * METRE
                nrm += -n[0]; nrm += n[2]; nrm += n[1]
                for (ch in listOf(Color.red(color), Color.green(color), Color.blue(color))) col += linear(ch).toByte()
                col += 255.toByte()
            }
        }

        private fun linear(c: Int) = (255.0 * (c / 255.0).pow(2.2)).roundToInt().coerceIn(0, 255)
    }

    /** The glTF binary container: a JSON chunk describing the triangles, then their data. */
    private object GlbWriter {
        fun write(prims: List<Prim>, materials: List<Material>): ByteArray {
            val bin = java.io.ByteArrayOutputStream()
            val views = JSONArray()
            val accessors = JSONArray()
            val primitives = JSONArray()
            fun view(bytes: ByteArray): Int {
                while (bin.size() % 4 != 0) bin.write(0)
                views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", bytes.size))
                bin.write(bytes)
                return views.length() - 1
            }
            fun floats(list: List<Float>): ByteArray {
                val bb = ByteBuffer.allocate(list.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                list.forEach { bb.putFloat(it) }
                return bb.array()
            }
            prims.forEachIndexed { i, p ->
                val min = FloatArray(3) { Float.MAX_VALUE }
                val max = FloatArray(3) { -Float.MAX_VALUE }
                for (k in p.pos.indices) { min[k % 3] = minOf(min[k % 3], p.pos[k]); max[k % 3] = maxOf(max[k % 3], p.pos[k]) }
                accessors.put(JSONObject().put("bufferView", view(floats(p.pos))).put("componentType", FLOAT).put("count", p.count)
                    .put("type", "VEC3").put("min", JSONArray(min.map { it.toDouble() })).put("max", JSONArray(max.map { it.toDouble() })))
                accessors.put(JSONObject().put("bufferView", view(floats(p.nrm))).put("componentType", FLOAT).put("count", p.count).put("type", "VEC3"))
                accessors.put(JSONObject().put("bufferView", view(p.col.toByteArray())).put("componentType", UNSIGNED_BYTE)
                    .put("normalized", true).put("count", p.count).put("type", "VEC4"))
                // An index list too (0, 1, 2...): glTF doesn't need one, but some loaders count on it.
                val ib = ByteBuffer.allocate(p.count * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until p.count) ib.putInt(k)
                accessors.put(JSONObject().put("bufferView", view(ib.array())).put("componentType", UNSIGNED_INT).put("count", p.count).put("type", "SCALAR"))
                primitives.put(JSONObject()
                    .put("attributes", JSONObject().put("POSITION", i * 4).put("NORMAL", i * 4 + 1).put("COLOR_0", i * 4 + 2))
                    .put("indices", i * 4 + 3)
                    .put("material", i).put("mode", TRIANGLES))
            }
            while (bin.size() % 4 != 0) bin.write(0)
            val json = JSONObject()
                .put("asset", JSONObject().put("version", "2.0").put("generator", "RF Sentinel"))
                .put("scene", 0)
                .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
                .put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
                .put("meshes", JSONArray().put(JSONObject().put("primitives", primitives)))
                .put("materials", JSONArray(materials.map { m ->
                    JSONObject().put("doubleSided", true).put("pbrMetallicRoughness", JSONObject()
                        .put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
                        .put("metallicFactor", m.metallic.toDouble()).put("roughnessFactor", m.roughness.toDouble()))
                }))
                .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
                .put("bufferViews", views)
                .put("accessors", accessors)
            var text = json.toString().toByteArray(Charsets.UTF_8)
            // Chunks are padded to 4 bytes: the JSON with spaces, the data with zeros.
            if (text.size % 4 != 0) text += ByteArray(4 - text.size % 4) { 0x20 }
            val data = bin.toByteArray()
            val out = ByteBuffer.allocate(12 + 8 + text.size + 8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MAGIC).putInt(2).putInt(out.capacity())
            out.putInt(text.size).putInt(CHUNK_JSON).put(text)
            out.putInt(data.size).putInt(CHUNK_BIN).put(data)
            return out.array()
        }

        private const val MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val FLOAT = 5126
        private const val UNSIGNED_BYTE = 5121
        private const val UNSIGNED_INT = 5125
        private const val TRIANGLES = 4
    }

    /** The model file for this car, written once into the app's own files (the map loads it by its path). */
    fun modelFile(context: android.content.Context, style: MarkerArt.Style, color: Int): java.io.File {
        val dir = java.io.File(context.filesDir, "cars3d").apply { mkdirs() }
        val f = java.io.File(dir, "v2-${style.key}-${Integer.toHexString(color)}.glb")
        if (!f.exists() || f.length() == 0L) {
            val tmp = java.io.File(dir, f.name + ".tmp")
            tmp.writeBytes(glb(style, color))
            tmp.renameTo(f)
            // Only the car in use is kept (a few hundred KB each; older versions and other colours go).
            dir.listFiles()?.forEach { if (it != f) it.delete() }
        }
        return f
    }
}
