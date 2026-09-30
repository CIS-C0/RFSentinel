package com.rfsentinel.app.oui

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every bundled preset entry must be loadable: an invalid key is silently
 * dropped at runtime, so catch it here instead.
 */
class PresetFilesTest {

    private val type = object : TypeToken<List<OuiEntry>>() {}.type

    @Test
    fun allPresetEntriesAreValid() {
        for (name in OuiWatchlist.availablePresets) {
            val file = File("src/main/assets/oui_presets/$name.json")
            val entries: List<OuiEntry> = Gson().fromJson(file.readText(), type)
            assertTrue("$name is empty", entries.isNotEmpty())
            val keys = mutableSetOf<String>()
            for (e in entries) {
                assertTrue("$name: invalid key '${e.prefix}'", OuiWatchlist.isValidKey(e.prefix))
                assertTrue("$name: duplicate key '${e.prefix}'", keys.add(e.prefix.uppercase()))
                assertTrue("$name: '${e.prefix}' has no label", e.label.isNotBlank())
                assertTrue("$name: '${e.prefix}' has no source", e.source.isNotBlank())
                assertNotNull("$name: '${e.prefix}' has no score", e.score)
                assertTrue("$name: '${e.prefix}' score out of range", e.score!! in 0..100)
                assertNotNull("$name: '${e.prefix}' bad category ${e.category}", Category.parse(e.category))
            }
        }
    }

    /**
     * Every address block in the presets must belong, per the bundled IEEE table,
     * to the company its label names - so a typo can't silently flag the wrong vendor.
     */
    @Test
    fun presetBlocksMatchTheirIeeeRegistrant() {
        val ieee = File("src/main/assets/vendors/oui.tsv").readLines()
            .filter { '	' in it && !it.startsWith("#") }
            .associate { it.substringBefore('	') to it.substringAfter('	') }
        // Registrant word expected in the IEEE name, for entries whose label brand differs.
        val alias = mapOf("Sensys Gatso" to "Sensys", "ShotSpotter" to "ShotSpotter", "Grayshift" to "Grayshift",
            "Airbus" to "Airbus", "Axon equipment" to "Private")
        for (name in OuiWatchlist.availablePresets) {
            val entries: List<OuiEntry> = Gson().fromJson(File("src/main/assets/oui_presets/$name.json").readText(), type)
            for (e in entries.filter { !it.isNameRule && !it.isVendorRule && it.prefix.length < 17 }) {
                val hex = e.prefix.replace(":", "").uppercase()
                val registrant = ieee[hex]
                if (e.prefix == "D8:1F:65") continue // IEEE "Private"; field-attributed to Axon (documented)
                if (e.prefix == "F4:60:77") continue // Texas Instruments chip block, labelled by what uses it
                assertNotNull("$name: ${e.prefix} is not an IEEE block", registrant)
                val brand = alias.entries.firstOrNull { e.label.startsWith(it.key) }?.value
                    ?: e.label.substringBefore(" (").substringBefore(" -").substringBefore(" /").split(" ").first()
                assertTrue("$name: ${e.prefix} label '${e.label}' vs IEEE '$registrant'",
                    registrant!!.contains(brand, ignoreCase = true))
            }
        }
    }

    @Test
    fun globalPresetFlagsZepcamBodyCams() {
        val entries: List<OuiEntry> = Gson().fromJson(File("src/main/assets/oui_presets/global.json").readText(), type)
        val zepcam = entries.single { it.prefix == "48:46:8D" }
        assertEquals(Category.BODY_CAM.name, zepcam.category)
        assertEquals(75, zepcam.score)
        // The IEEE table must agree that this block really is Zepcam's.
        val oui = File("src/main/assets/vendors/oui.tsv").readLines().first { it.startsWith("48468D	") }
        assertTrue(oui.contains("Zepcam", ignoreCase = true))
    }

    @Test
    fun canadaPresetHasResearchedEntries() {
        val entries: List<OuiEntry> = Gson().fromJson(File("src/main/assets/oui_presets/canada.json").readText(), type)
        val byKey = entries.associateBy { it.prefix }
        assertEquals(65, byKey["8C:1F:64:DF:0"]?.score)            // Cyberkar MA-S block
        assertEquals(Category.ALPR.name, byKey["00:BF:15"]?.category) // Genetec
        assertTrue(byKey.containsKey("name:BC-03"))                // Getac body cam (police pilot)
    }
}
