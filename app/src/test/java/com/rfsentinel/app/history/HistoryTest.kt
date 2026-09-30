package com.rfsentinel.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** A UTC timestamp for 2026-09-[day] at [hour]:[minute] (Sept 28 2026 is a Monday). */
    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    private fun ev(time: Long, mac: String, lat: Double? = null, label: String = "Flock Safety camera") =
        HistoryEvent(time, lat, lat?.let { -20.5 }, "ALPR", label, mac)

    @Test
    fun repeatedRowsOfOneDeviceBecomeOneEncounter() {
        val rows = listOf(
            ev(at(28, 8, 0), "AA"), ev(at(28, 8, 1), "AA", lat = 10.5), ev(at(28, 8, 5), "AA"), // one stay
            ev(at(28, 17, 0), "AA"),                                                            // came back later
            ev(at(28, 8, 2), "BB", label = "Axon body camera")
        )
        val enc = History.encounters(rows)
        assertEquals(3, enc.size)
        // The morning encounter picked up the position from its second row.
        assertEquals(10.5, enc.first { it.mac == "AA" && it.time == at(28, 8, 0) }.lat!!, 0.0)
    }

    @Test
    fun hourAndWeekdayPatterns() {
        val enc = listOf(ev(at(28, 8), "A"), ev(at(28, 8, 30), "B"), ev(at(2, 17), "C")) // Mon 8h x2, Fri Oct 2? no: Sep 2
        val hours = History.byHour(enc, utc)
        assertEquals(2, hours[8]); assertEquals(1, hours[17])
        val days = History.byWeekday(enc, utc)
        assertEquals(2, days[0]) // Monday
        assertEquals(3, days.sum())
        assertTrue(History.busiest(enc, utc)!!.startsWith("Busiest: Mon, 08:00-09:00"))
        assertEquals("Flock Safety camera" to 3, History.topLabels(enc).first())
    }
}
