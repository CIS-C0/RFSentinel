package com.rfsentinel.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.ui.MapFragment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings backup and restore, and when the live map takes you back to your position. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsBackupTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun sp(file: String) = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)

    @Test
    fun aBackupRestoresEveryKindOfSettingAndLeavesTheRest() {
        val main = sp("rf_sentinel_prefs")
        main.edit().putBoolean("map_3d", true).putInt("retention_days", 45).putFloat("map_default_zoom", 16.5f)
            .putString("map_marker_style", "taxi").putLong("scan_interval_ms", 4000L)
            .putStringSet("cat_x", setOf("a", "b"))
            .putString("map_center", "1.0,2.0").putBoolean("waze_accepted", true).putLong("alerts_snoozed_until", 99L).commit()
        sp("nav_places").edit().putString("nav_home", "HOME-JSON").putString("recent_destinations", "RECENT").commit()
        sp("oui_watchlist_prefs").edit().putString("entries", "[rule]").commit()
        val file = SettingsBackup.create(ctx, now = 1_000L)

        // Another state of the phone: everything changed, plus things that must survive.
        main.edit().clear().putBoolean("map_3d", false).putInt("retention_days", 7).putString("map_marker_style", "arrow")
            .putBoolean("waze_accepted", false).putString("map_center", "9.0,9.0").putBoolean("stale_setting", true).commit()
        sp("nav_places").edit().putString("nav_home", "OTHER").putString("recent_destinations", "OTHER-RECENT").commit()

        val r = SettingsBackup.restore(ctx, file)
        assertTrue(r is SettingsBackup.Result.Restored)
        assertTrue(main.getBoolean("map_3d", false)); assertEquals(45, main.getInt("retention_days", 0))
        assertEquals(16.5f, main.getFloat("map_default_zoom", 0f), 0f); assertEquals("taxi", main.getString("map_marker_style", null))
        assertEquals(4000L, main.getLong("scan_interval_ms", 0L)); assertEquals(setOf("a", "b"), main.getStringSet("cat_x", null))
        assertFalse("a setting the backup does not have goes back to its default", main.contains("stale_setting"))
        assertEquals("[rule]", sp("oui_watchlist_prefs").getString("entries", null))
        assertEquals("HOME-JSON", sp("nav_places").getString("nav_home", null))
        // Never part of it: where the map was, acknowledgements, snoozes, recent places.
        assertEquals("9.0,9.0", main.getString("map_center", null))
        assertFalse("the risk acknowledgement stays as this phone has it", main.getBoolean("waze_accepted", true))
        assertEquals("OTHER-RECENT", sp("nav_places").getString("recent_destinations", null))
    }

    @Test
    fun theFileNeverCarriesSecretsOrState() {
        sp("rf_sentinel_prefs").edit().putString("map_center", "1.0,2.0").putBoolean("waze_direct_accepted", true).putString("live_export_tree", "content://x")
            .putString("tracker_follow_paused_until", "5").putBoolean("sound_enabled", true).commit()
        sp("secure_store").edit().putString("owninja_key", "SECRET-VALUE").commit()
        sp("nav_places").edit().putString("recent_destinations", "RECENT").commit()
        sp("tracker_mutes").edit().putString("mutes", "AA:BB").commit()
        val file = SettingsBackup.create(ctx)
        for (bad in listOf("SECRET-VALUE", "map_center", "waze_direct_accepted", "live_export_tree", "RECENT", "tracker_follow_paused_until", "AA:BB"))
            assertFalse("$bad is not in the backup", file.contains(bad))
        assertTrue(file.contains("sound_enabled"))
        val root = JSONObject(file)
        assertEquals("RF Sentinel", root.getString("app")); assertEquals(SettingsBackup.FORMAT, root.getInt("format"))
    }

    @Test
    fun aFileThatIsNotABackupChangesNothing() {
        val main = sp("rf_sentinel_prefs"); main.edit().clear().putInt("retention_days", 12).commit()
        fun refused(text: String): String = (SettingsBackup.restore(ctx, text) as SettingsBackup.Result.Refused).why
        assertTrue(refused("not json at all").contains("not valid JSON"))
        assertTrue(refused("""{"app":"Something else","format":1,"files":{}}""").contains("isn't an RF Sentinel backup"))
        assertTrue(refused("""{"app":"RF Sentinel","format":99,"files":{}}""").contains("newer version"))
        assertTrue(refused("""{"app":"RF Sentinel","format":1}""").contains("no settings"))
        // One damaged entry stops the whole restore: nothing is half applied.
        refused("""{"app":"RF Sentinel","format":1,"files":{"rf_sentinel_prefs":{"retention_days":{"t":"i","v":3},"broken":{"t":"zzz","v":1}}}}""")
        assertEquals(12, main.getInt("retention_days", 0))
        assertTrue(refused("x".repeat(SettingsBackup.MAX_BYTES + 1)).contains("too big"))
        assertNull(main.getString("broken", null))
    }

    @Test
    fun aRestoreCanTakeJustSomeSections() {
        val main = sp("rf_sentinel_prefs")
        main.edit().clear().putInt("retention_days", 45).commit()
        sp("nav_places").edit().putString("nav_home", "HOME").commit()
        sp("oui_watchlist_prefs").edit().putString("entries", "[rule]").commit()
        val file = SettingsBackup.create(ctx)
        val found = SettingsBackup.inspect(file) as SettingsBackup.Inspect.Ok
        assertEquals(listOf(SettingsBackup.Section.SETTINGS, SettingsBackup.Section.WATCHLIST, SettingsBackup.Section.PLACES, SettingsBackup.Section.FLOATING), found.sections)
        main.edit().putInt("retention_days", 7).commit()
        sp("nav_places").edit().putString("nav_home", "ELSEWHERE").commit()
        sp("oui_watchlist_prefs").edit().putString("entries", "[mine]").commit()

        SettingsBackup.restore(ctx, file, setOf(SettingsBackup.Section.WATCHLIST, SettingsBackup.Section.PLACES))
        assertEquals("settings not ticked: untouched", 7, main.getInt("retention_days", 0))
        assertEquals("[rule]", sp("oui_watchlist_prefs").getString("entries", null))
        assertEquals("HOME", sp("nav_places").getString("nav_home", null))
    }

    @Test
    fun customThemesTravelInTheBackupAndRestoreOnTheirOwn() {
        val pic = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val t = com.rfsentinel.app.ui.CustomThemes.setBanner(ctx, com.rfsentinel.app.ui.CustomTheme(id = "tabc", name = "Mine", font = "mono", markerStyle = "suv", markerColor = "teal", markerSize = 1.2f), pic)!!
        com.rfsentinel.app.ui.CustomThemes.save(ctx, t)
        sp("rf_sentinel_prefs").edit().clear().putInt("retention_days", 45).commit()
        val file = SettingsBackup.create(ctx)
        val found = SettingsBackup.inspect(file) as SettingsBackup.Inspect.Ok
        assertTrue(SettingsBackup.Section.THEMES in found.sections); assertEquals(1, found.themes)

        com.rfsentinel.app.ui.CustomThemes.delete(ctx, "tabc")
        sp("rf_sentinel_prefs").edit().putInt("retention_days", 7).commit()
        val r = SettingsBackup.restore(ctx, file, setOf(SettingsBackup.Section.THEMES)) as SettingsBackup.Result.Restored
        assertEquals(1, r.count)
        assertEquals(t, com.rfsentinel.app.ui.CustomThemes.get(ctx, "tabc"))
        assertNotNull(com.rfsentinel.app.ui.CustomThemes.bannerFile(ctx, t))
        assertEquals("only themes were ticked", 7, sp("rf_sentinel_prefs").getInt("retention_days", 0))

        // Everything but the themes: they stay as they are.
        com.rfsentinel.app.ui.CustomThemes.delete(ctx, "tabc")
        SettingsBackup.restore(ctx, file, SettingsBackup.Section.entries.toSet() - SettingsBackup.Section.THEMES)
        assertNull(com.rfsentinel.app.ui.CustomThemes.get(ctx, "tabc"))
        assertEquals(45, sp("rf_sentinel_prefs").getInt("retention_days", 0))
    }

    @Test
    fun aDamagedThemeStopsTheWholeRestore() {
        val main = sp("rf_sentinel_prefs"); main.edit().clear().putInt("retention_days", 12).commit()
        val bad = """{"app":"RF Sentinel","format":1,"files":{"rf_sentinel_prefs":{"retention_days":{"t":"i","v":3}}},"customThemes":[{"id":"x","app":"Other"}]}"""
        assertTrue(SettingsBackup.restore(ctx, bad) is SettingsBackup.Result.Refused)
        assertEquals(12, main.getInt("retention_days", 0))
        assertTrue(SettingsBackup.inspect("nope") is SettingsBackup.Inspect.Bad)
    }

    @Test
    fun readingStopsAtTheLimit() {
        assertEquals("hello", SettingsBackup.read("hello".byteInputStream()))
        val big = object : java.io.InputStream() { override fun read() = 'x'.code }
        assertNull(SettingsBackup.read(big))
    }

    @Test
    fun theFileNameCarriesTheDate() {
        assertTrue(SettingsBackup.fileName(0L).startsWith("rf-sentinel-settings-19"))
        assertTrue(SettingsBackup.fileName().endsWith(".json"))
    }

    @Test
    fun theMapTakesYouBackOnlyWhileDrivingAndAfterTenQuietSeconds() {
        fun go(enabled: Boolean = true, following: Boolean = false, live: Boolean = true, guiding: Boolean = false, speed: Float = 10f,
               card: Boolean = false, idle: Long = 11_000L) = MapFragment.shouldRecenter(enabled, following, live, guiding, speed, card, idle)
        assertTrue("moving, left alone for 11 s", go())
        assertTrue("guided, even when stopped at a light", go(guiding = true, speed = 0f))
        assertFalse("not yet", go(idle = 9_000L))
        assertFalse("parked and browsing", go(speed = 0f))
        assertFalse("switched off", go(enabled = false))
        assertFalse("already following", go(following = true))
        assertFalse("a saved trace", go(live = false))
        assertFalse("looking at a place", go(card = true))
    }
}
