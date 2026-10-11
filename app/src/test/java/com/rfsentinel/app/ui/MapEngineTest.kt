package com.rfsentinel.app.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.expressions.Expression
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The map's layers are written once as style JSON and its data as GeoJSON, for both engines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MapEngineTest {

    @Test
    fun styleValuesBecomeWhatMapLibreTakes() {
        assertEquals(1.5f, MapLibreEngine.value("line-width", 1.5))
        assertEquals(true, MapLibreEngine.value("icon-allow-overlap", true))
        assertEquals("rf-tower", MapLibreEngine.value("icon-image", "rf-tower"))
        assertArrayEquals(arrayOf(0f, 1.1f), MapLibreEngine.value("text-offset", JSONArray("[0, 1.1]")) as Array<*>)
        assertArrayEquals(arrayOf("Noto Sans Regular"), MapLibreEngine.value("text-font", JSONArray("[\"Noto Sans Regular\"]")) as Array<*>)
        assertTrue("an expression", MapLibreEngine.value("circle-color", JSONArray("[\"to-color\", [\"get\", \"color\"]]")) is Expression)
    }

    @Test
    fun featuresKeepLongitudeFirstAndTheirProperties() {
        val f = GeoJson.feature(GeoJson.point(10.0, 20.0), "id" to "a", "flagged" to true, "skip" to null)
        val c = JSONObject(GeoJson.collection(listOf(f))).getJSONArray("features").getJSONObject(0)
        val xy = c.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(20.0, xy.getDouble(0), 1e-9); assertEquals(10.0, xy.getDouble(1), 1e-9)
        val p = c.getJSONObject("properties")
        assertEquals("a", p.getString("id")); assertEquals(true, p.getBoolean("flagged")); assertTrue(!p.has("skip"))
        assertEquals(0, JSONObject(GeoJson.EMPTY).getJSONArray("features").length())
    }

    @Test
    fun polygonsKeepLongitudeFirst() {
        val first = GeoJson.polygon(listOf(1.0 to 2.0, 3.0 to 4.0, 1.0 to 2.0)).getJSONArray("coordinates").getJSONArray(0).getJSONArray(0)
        assertEquals(2.0, first.getDouble(0), 1e-9); assertEquals(1.0, first.getDouble(1), 1e-9)
    }

    @Test
    fun theMapboxChoiceFallsBackWithoutAToken() {
        assertEquals(MapEngine.Provider.OPENFREEMAP, MapEngine.Provider.parse(null))
        assertEquals(MapEngine.Provider.MAPBOX, MapEngine.Provider.parse("mapbox"))
        assertEquals(MapEngine.Provider.OPENFREEMAP, MapEngine.Provider.parse("something else"))
    }

    @Test
    fun theNightMapsExtraLayersAreValid() {
        for (text in NightLabels.layers) {
            val l = JSONObject(text)
            assertTrue(l.getString("id"), l.getString("id").startsWith("night-") && l.has("source-layer") && l.has("layout"))
            // Every layout / paint value converts to what MapLibre takes (no exception).
            for (part in listOf("layout", "paint")) l.optJSONObject(part)?.let { o -> o.keys().forEach { k -> MapLibreEngine.value(k, o.get(k)) } }
        }
        assertEquals(9, NightLabels.layers.size)
    }
}
