package com.rfsentinel.app.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 3D car models for Mapbox: valid glTF binaries, car-sized, standing on the ground, front towards +z. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Car3dTest {

    private val cars = MarkerArt.Style.entries.filter { Car3d.supports(it) }

    private class Glb(val json: JSONObject, val bin: ByteBuffer)

    private fun parse(bytes: ByteArray): Glb {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("glTF magic", 0x46546C67, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals("length", bytes.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals("JSON chunk", 0x4E4F534A, b.getInt(16))
        assertEquals(0, jsonLen % 4)
        val json = JSONObject(String(bytes, 20, jsonLen, Charsets.UTF_8))
        val binLen = b.getInt(20 + jsonLen)
        assertEquals("BIN chunk", 0x004E4942, b.getInt(24 + jsonLen))
        val bin = ByteBuffer.wrap(bytes, 28 + jsonLen, binLen).slice().order(ByteOrder.LITTLE_ENDIAN)
        return Glb(json, bin)
    }

    /** Every position of every triangle, in glTF axes. */
    private fun positions(g: Glb): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val prims = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        for (i in 0 until prims.length()) {
            val acc = g.json.getJSONArray("accessors").getJSONObject(prims.getJSONObject(i).getJSONObject("attributes").getInt("POSITION"))
            val view = g.json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
            for (k in 0 until acc.getInt("count")) {
                val o = view.getInt("byteOffset") + k * 12
                out += floatArrayOf(g.bin.getFloat(o), g.bin.getFloat(o + 4), g.bin.getFloat(o + 8))
            }
        }
        return out
    }

    @Test
    fun theArrowsAndDotsHaveNoModel() {
        for (s in listOf(MarkerArt.Style.NAVIGATION, MarkerArt.Style.DOT, MarkerArt.Style.ARROW, MarkerArt.Style.CHEVRON)) assertFalse(Car3d.supports(s))
        assertEquals(13, cars.size)
    }

    @Test
    fun everyCarIsAValidModelOfACar() {
        for (s in cars) {
            val g = parse(Car3d.glb(s, MarkerArt.colorOf("red").argb))
            val prims = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
            assertTrue("${s.key}: materials for its parts", prims.length() in 2..3)
            for (i in 0 until prims.length()) {
                val attrs = prims.getJSONObject(i).getJSONObject("attributes")
                val n = g.json.getJSONArray("accessors").getJSONObject(attrs.getInt("POSITION")).getInt("count")
                assertEquals("${s.key}: whole triangles", 0, n % 3)
                for (a in listOf("NORMAL", "COLOR_0")) assertEquals(n, g.json.getJSONArray("accessors").getJSONObject(attrs.getInt(a)).getInt("count"))
            }
            val p = positions(g)
            val xs = p.map { it[0] }; val ys = p.map { it[1] }; val zs = p.map { it[2] }
            val length = zs.max() - zs.min(); val width = xs.max() - xs.min(); val height = ys.max() - ys.min()
            assertTrue("${s.key}: car length in metres ($length)", length in 3.0f..5.0f)
            assertTrue("${s.key}: longer than wide", length > width * 1.6f)
            assertTrue("${s.key}: on the ground", ys.min() in -0.01f..0.05f)
            assertTrue("${s.key}: a car's height ($height)", height in 0.8f..2.6f)
            assertTrue("${s.key}: centred", kotlin.math.abs(xs.max() + xs.min()) < 0.05f)
        }
    }

    @Test
    fun theHeadlightsAreAtThePlusZEnd() {
        val g = parse(Car3d.glb(MarkerArt.Style.SEDAN, MarkerArt.colorOf("blue").argb))
        // The pale yellow headlights' vertices: all towards +z (glTF's front).
        val prims = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        var front = 0; var back = 0
        for (i in 0 until prims.length()) {
            val attrs = prims.getJSONObject(i).getJSONObject("attributes")
            val pa = g.json.getJSONArray("accessors").getJSONObject(attrs.getInt("POSITION"))
            val ca = g.json.getJSONArray("accessors").getJSONObject(attrs.getInt("COLOR_0"))
            val pv = g.json.getJSONArray("bufferViews").getJSONObject(pa.getInt("bufferView")).getInt("byteOffset")
            val cv = g.json.getJSONArray("bufferViews").getJSONObject(ca.getInt("bufferView")).getInt("byteOffset")
            for (k in 0 until pa.getInt("count")) {
                val r = g.bin.get(cv + k * 4).toInt() and 255; val gg = g.bin.get(cv + k * 4 + 1).toInt() and 255; val b = g.bin.get(cv + k * 4 + 2).toInt() and 255
                if (r > 240 && gg > 200 && b in 60..200) { if (g.bin.getFloat(pv + k * 12 + 8) > 0) front++ else back++ }
            }
        }
        assertTrue("headlights found at the front ($front)", front > 0)
        assertEquals("none at the back", 0, back)
    }

    @Test
    fun theModelFileIsWrittenOnceAndReused() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = Car3d.modelFile(ctx, MarkerArt.Style.SUV, MarkerArt.colorOf("green").argb)
        assertTrue(f.length() > 1000)
        val stamp = f.lastModified()
        assertEquals(f, Car3d.modelFile(ctx, MarkerArt.Style.SUV, MarkerArt.colorOf("green").argb))
        assertEquals(stamp, f.lastModified())
        assertTrue("another colour, another file", f != Car3d.modelFile(ctx, MarkerArt.Style.SUV, MarkerArt.colorOf("red").argb))
    }
}
