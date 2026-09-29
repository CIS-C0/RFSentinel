package com.rfsentinel.app.util

import com.google.gson.JsonParser
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceExportTest {

    private val flags = DeviceExport.Flags(whitelisted = { false }, favorite = { it.endsWith("02") })

    @Before
    fun seed() {
        DeviceRegistry.startSession()
        // A matched device and an ordinary one - "export all" must include both.
        DeviceRegistry.report(
            Advert("00:25:DF:00:00:01", Advert.Source.BLE, -60, "Cam, \"Axon\"",
                manufacturerData = mapOf(0x034D to byteArrayOf(1, 2)),
                rawBytes = byteArrayOf(0x02, 0x01, 0x06)),
            listOf(Hit(Category.BODY_CAM, "Axon body camera", 90, "tag, with comma", "test")),
            DeviceIntel.Identity("Body camera", listOf("Appearance" to "Unknown")), "Axon Enterprise, Inc.", null, null
        )
        DeviceRegistry.report(
            Advert("C0:00:00:00:00:02", Advert.Source.WIFI, -80, "Home WiFi",
                wifi = Advert.WifiInfo(2437, "[WPA2]", null, emptyList())),
            emptyList(), DeviceIntel.Identity("WiFi access point", emptyList()), null, null,
            DeviceRegistry.GeoSample(1L, 10.5, -20.5)
        )
    }

    @After
    fun clear() = DeviceRegistry.clear()

    private fun full() = DeviceRegistry.snapshot().mapNotNull { DeviceRegistry.get(it.mac) }

    /** Minimal RFC 4180 field counter (handles quotes and doubled quotes). */
    private fun fields(line: String): Int {
        var n = 1; var inQ = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') { if (inQ && i + 1 < line.length && line[i + 1] == '"') i++ else inQ = !inQ }
            else if (c == ',' && !inQ) n++
            i++
        }
        return n
    }

    @Test
    fun csvIncludesUnmatchedDevicesWithConsistentColumns() {
        val lines = DeviceExport.sessionCsv(full(), flags).trimEnd().lines()
        assertEquals(3, lines.size) // header + 2 devices
        val cols = fields(lines[0])
        lines.forEach { assertEquals("column count in: $it", cols, fields(it)) }
        assertTrue(lines.any { it.startsWith("C0:00:00:00:00:02") && it.contains(",false,") }) // unmatched device present
        assertTrue(lines.any { it.contains("\"Cam, \"\"Axon\"\"\"") })                        // quotes escaped
    }

    @Test
    fun jsonCarriesMatchesRawAdvertAndPositions() {
        val arr = JsonParser.parseString(DeviceExport.pretty(DeviceExport.sessionJson(full(), flags))).asJsonArray
        assertEquals(2, arr.size())
        val axon = arr.map { it.asJsonObject }.first { it["mac"].asString == "00:25:DF:00:00:01" }
        assertEquals("BODY_CAM", axon["matches"].asJsonArray[0].asJsonObject["category"].asString)
        assertEquals("020106", axon["advertisement"].asJsonObject["raw"].asString)
        val wifi = arr.map { it.asJsonObject }.first { it["mac"].asString == "C0:00:00:00:00:02" }
        assertEquals(0, wifi["matches"].asJsonArray.size())
        assertTrue(wifi["favorite"].asBoolean)
        assertEquals(10.5, wifi["positions"].asJsonArray[0].asJsonObject["lat"].asDouble, 1e-9)
    }

    @Test
    fun kmlOnlyHasDevicesWithPositions() {
        val kml = DeviceExport.sessionKml(full())
        assertTrue(kml.contains("Home WiFi"))
        assertFalse(kml.contains("Axon body camera"))
    }

    @Test
    fun historyAndEverything() {
        val hist = listOf(KnownDeviceEntity("AA:BB:CC:DD:EE:FF", 1000, 2000, 3, 7, "Tag, \"x\"", null, null))
        val csv = DeviceExport.historyCsv(hist).trimEnd().lines()
        assertEquals(fields(csv[0]), fields(csv[1]))
        val all = JsonParser.parseString(DeviceExport.everythingJson("test", full(), flags, hist, emptyList(), emptyList())).asJsonObject
        assertEquals(2, all["sessionDevices"].asJsonArray.size())
        assertEquals(1, all["deviceHistory"].asJsonArray.size())
    }
}
