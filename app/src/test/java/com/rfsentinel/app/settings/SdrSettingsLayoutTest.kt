package com.rfsentinel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings › RTL-SDR radio is split into folding groups. Moving rows between groups must not lose or
 * duplicate a setting: each SDR preference is still written from exactly one row, inside that section.
 */
class SdrSettingsLayoutTest {

    private val source = File("src/main/java/com/rfsentinel/app/settings/SettingsActivity.kt").readText()
    private val radio = source.substring(source.indexOf("private fun setupRadioSection()")).let { it.substring(0, it.indexOf("\n    }\n")) }

    private val sdrSetters = listOf(
        "setSdrSweep", "setSdrAdsb", "setSdrP25", "setSdrPager", "setSdrTpms", "setP25Bands", "setSdrTurnS",
        "setRadioMinSnr", "setRadioBandsOff", "setRadioCustomBands", "setRadioExcluded", "setRadioTargets",
        "setRadioSpeakFreq", "setRadioCellOn", "setRadioTetraOn", "setRadioDmrOn", "setRadioTrendDb", "setRadioMatchKhz",
        "setP25Talkgroups", "setP25AlertsOff", "setP25LogDays", "setP25Unusual", "setP25Inputs", "setP25Record", "setP25Listen",
        "setPagerFreqs", "setPagerSpeeds", "setPagerWatch", "setPagerIgnore", "setPagerAlertsOff", "setPagerAlertText",
        "setRadioReferenceOn", "setNetworkSdr", "setSdrLogAll", "setSdrBiasTee", "setSdrPpmAuto", "setSdrPpm",
    )

    private fun count(text: String, what: Regex) = what.findAll(text).count()

    @Test fun everySdrSettingIsOnScreenExactlyOnce() {
        for (s in sdrSetters) {
            val call = Regex("""Prefs\.$s\(""")
            assertEquals("$s in Settings", 1, count(source, call))
            assertEquals("$s in RTL-SDR radio", 1, count(radio, call))
        }
        for (key in listOf("USER_KEY", "PASS_KEY", "APPKEY_KEY"))
            assertEquals("RadioReference $key", 1, count(radio, Regex("""RadioReference\.$key\b""")))
    }

    @Test fun groupsInTheAgreedOrder() {
        val keys = Regex("""group\("(\w+)"""").findAll(radio).map { it.groupValues[1] }.toList()
        assertEquals(listOf("scan", "sweep", "p25", "pagers", "names", "radioreference", "connection"), keys)
        assertTrue("no bare subheadings left", !radio.contains("heading("))
    }
}
