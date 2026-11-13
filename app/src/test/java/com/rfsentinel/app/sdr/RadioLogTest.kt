package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioLogTest {

    private val band = RadioWatch.Band("UHF", 450_000_000L, 470_000_000L, RadioWatch.Kind.SHARED)

    @Test
    fun rowsSayWhatTheyDecodedAs() {
        RadioLog.clear()
        RadioLog.observe(RadioWatch.Event(468_312_500L, 60, band, 50), null, 0, 1_000L)
        assertNull(RadioLog.current(1_000L).single().digital)
        RadioLog.digital(468_312_500L, "digital radio (NXDN) · clear")
        RadioLog.observe(RadioWatch.Event(468_312_500L, 55, band, 50, repeat = true), null, 0, 2_000L)
        assertEquals("digital radio (NXDN) · clear", RadioLog.current(2_000L).single().digital)
        RadioLog.digital(462_562_500L, "digital radio (NXDN)") // not listed (a walkie-talkie): nothing added
        RadioLog.observe(RadioWatch.Event(806_000_000L, 40, band, 50, p25Nac = 0x293), null, 0, 3_000L)
        assertEquals("P25 digital radio", RadioLog.current(3_000L).first().digital)
        assertEquals(2, RadioLog.current(3_000L).size)
        RadioLog.observe(RadioWatch.Event(381_500_000L, 30, RadioWatch.TETRA_BAND, 65, tdma = true), null, 0, 4_000L)
        assertEquals("TETRA radio", RadioLog.current(4_000L).first().digital)
        RadioLog.clear()
    }
}
