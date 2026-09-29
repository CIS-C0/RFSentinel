package com.rfsentinel.app.util

import com.google.gson.JsonParser
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.TripPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class TripExportTest {

    private val trip = TripEntity(id = 1, name = "Drive <home> & back", startTime = 1_000_000L, endTime = 2_000_000L, distanceM = 1234.0)
    private val points = listOf(
        TripPointEntity(tripId = 1, time = 1_000_000L, lat = 10.50, lon = -20.57),
        TripPointEntity(tripId = 1, time = 1_030_000L, lat = 10.51, lon = -20.56),
        TripPointEntity(tripId = 1, time = 1_060_000L, lat = 10.52, lon = -20.55)
    )
    private val devices = listOf(
        TripDeviceEntity(1, "00:25:DF:00:00:01", "Axon body camera", null, "Axon Enterprise, Inc.", "Body camera", "BLE",
            "BODY_CAM", 90, "BWCDEVICE \"tag\"", 1_010_000L, 1_020_000L, -61, 10.505, -20.565),
        TripDeviceEntity(1, "C0:00:00:00:00:02", "Phone", "Pixel", null, "Phone", "BLE",
            null, 0, null, 1_040_000L, 1_050_000L, -80, 10.515, -20.555),
        TripDeviceEntity(1, "C0:00:00:00:00:03", "No fix", null, null, null, "WIFI",
            null, 0, null, 1_040_000L, 1_050_000L, -85, null, null)
    )

    private fun parseXml(s: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(s.byteInputStream())

    @Test
    fun gpxIsValidXmlWithTrackAndWaypoints() {
        val doc = parseXml(TripExport.gpx(trip, points, devices, onlyFlagged = false))
        assertEquals(3, doc.getElementsByTagName("trkpt").length)
        assertEquals(2, doc.getElementsByTagName("wpt").length) // device without a fix is skipped
        assertEquals("Drive <home> & back", doc.getElementsByTagName("name").item(0).textContent)

        val flaggedOnly = parseXml(TripExport.gpx(trip, points, devices, onlyFlagged = true))
        assertEquals(1, flaggedOnly.getElementsByTagName("wpt").length)
    }

    @Test
    fun kmlIsValidXmlWithLineAndPlacemarks() {
        val doc = parseXml(TripExport.kml(trip, points, devices, onlyFlagged = false))
        assertEquals(1, doc.getElementsByTagName("LineString").length)
        assertEquals(3, doc.getElementsByTagName("Placemark").length) // trace + 2 devices
        assertTrue(doc.getElementsByTagName("coordinates").item(0).textContent.contains("-20.5700000,10.5000000"))
    }

    @Test
    fun geoJsonHasLonLatOrder() {
        val root = JsonParser.parseString(TripExport.geoJson(trip, points, devices, onlyFlagged = false)).asJsonObject
        assertEquals("FeatureCollection", root["type"].asString)
        val features = root["features"].asJsonArray
        assertEquals(3, features.size())
        val line = features[0].asJsonObject["geometry"].asJsonObject
        assertEquals("LineString", line["type"].asString)
        assertEquals(-20.57, line["coordinates"].asJsonArray[0].asJsonArray[0].asDouble, 1e-9) // [lon, lat]
        val axon = features.map { it.asJsonObject }.first { it["properties"].asJsonObject["mac"]?.asString == "00:25:DF:00:00:01" }
        assertEquals("BODY_CAM", axon["properties"].asJsonObject["category"].asString)
    }

    @Test
    fun csvListsEveryDeviceIncludingUnpositioned() {
        val lines = TripExport.devicesCsv(devices).trimEnd().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains("\"BWCDEVICE \"\"tag\"\"\""))
        assertFalse(lines[3].contains("null"))
    }
}
