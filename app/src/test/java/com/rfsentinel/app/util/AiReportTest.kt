package com.rfsentinel.app.util

import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.KnownDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun groupsMatchesKeepsTheWeekAndKeepsTablesWhole() {
        val m = listOf(
            DetectionEntity(mac = "AA:BB:CC:00:00:01", label = "Axon body camera", source = "BLE", rssi = -80, timestamp = now - day,
                latitude = 1.0, longitude = 2.0, category = "BODY_CAM"),
            DetectionEntity(mac = "AA:BB:CC:00:00:01", label = "Axon body camera", source = "BLE", rssi = -55, timestamp = now - 2 * day,
                latitude = 1.5, longitude = 2.5, category = "BODY_CAM", deviceName = "Axon | Body 3"),
            DetectionEntity(mac = "AA:BB:CC:00:00:02", label = "Old one", source = "WIFI", rssi = -60, timestamp = now - 30 * day),
        )
        val d = listOf(
            KnownDeviceEntity(mac = "AA:BB:CC:00:00:01", firstSeen = now - 90 * day, lastSeen = now - day, sessions = 12, detectCount = 2),
            KnownDeviceEntity(mac = "AA:BB:CC:00:00:09", firstSeen = now - 90 * day, lastSeen = now - 20 * day, sessions = 50, detectCount = 0),
        )
        val md = AiReport.markdown(now, "9.9", m, d, listOf("Body cameras" to true, "TPMS" to false)) { it.endsWith("01") }
        assertTrue(md.contains("## Matches (2 in 1 device and label pairs)"))
        val row = md.lines().first { it.startsWith("| Axon body camera") }
        assertTrue(row.contains("| 2 |")); assertTrue(row.contains("| -55 |")); assertTrue(row.contains("1.50000, 2.50000"))
        assertTrue(row.contains("Axon / Body 3")) // a pipe in a name doesn't split the table
        assertTrue(row.contains("(whitelisted)"))
        assertFalse(md.contains("Old one"))
        assertFalse(md.contains("00:00:09")) // not heard this week
        assertTrue(md.contains("| TPMS | no |"))
        assertEquals("-", AiReport.cell("  "))
    }
}
