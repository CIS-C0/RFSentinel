package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Custom themes: reading, saving, sharing as a file, importing part by part, the settings backup, and the colours they make. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class CustomThemeTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clean() {
        ctx.getSharedPreferences("custom_themes", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("rf_sentinel_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(ctx.filesDir, "custom_themes").deleteRecursively()
    }

    private fun png(color: Int = Color.RED): ByteArray {
        val b = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A theme with every part set. */
    private fun full(c: Context = ctx, name: String = "Neon"): CustomTheme {
        val base = CustomTheme(id = CustomThemes.newId(), name = name, accent = 0xFFE91E63.toInt(), scheme = "vibrant", mode = "black",
            font = "serif", header = "HUD", title = "Neon Sentinel", markerStyle = "taxi", markerColor = "red", markerSize = 1.4f)
        val withBanner = CustomThemes.setBanner(c, base, png())!!
        CustomThemes.save(c, withBanner)
        return withBanner
    }

    @Test
    fun aHandEditedFileCannotBreakTheApp() {
        val t = CustomTheme.fromJson(JSONObject("""{"id":"a","name":"  ${"x".repeat(80)}  ","accent":16711680,"scheme":"nope","mode":"???",
            "font":"comic-sans","fontFile":"../../etc/passwd","banner":"..\\..\\evil","header":"BOGUS","markerStyle":"spaceship","markerSize":99}"""))
        assertEquals(40, t.name.length)
        assertEquals("accent is made opaque", 0xFFFF0000.toInt(), t.accent)
        assertEquals("tonal", t.scheme); assertEquals("system", t.mode); assertEquals("default", t.font)
        assertNull("a font file only goes with the font choice 'file'", t.fontFile)
        assertEquals("evil", t.banner)
        assertNull(t.header); assertNull(t.markerStyle); assertNull("no marker, so no size", t.markerSize)
        val m = CustomTheme.fromJson(JSONObject("""{"id":"b","name":"m","markerStyle":"suv","markerColor":"mauve","markerSize":99}"""))
        assertEquals("blue", m.markerColor); assertEquals(2.0f, m.markerSize!!, 0f)
        assertEquals("a name is never empty", "My theme", CustomTheme.fromJson(JSONObject("""{"id":"c","name":"  "}""")).name)
        assertEquals("passwd", CustomTheme.safeName("../../etc/passwd")); assertEquals("a_b-c.ttf", CustomTheme.safeName("C:\\x\\a_b-c.ttf"))
    }

    @Test
    fun themesAreSavedFoundRenamedAndDeletedWithTheirFiles() {
        val a = full(name = "Neon")
        val b = CustomTheme(id = CustomThemes.newId(), name = CustomThemes.uniqueName(ctx, "Neon"))
        assertEquals("a taken name gets a number", "Neon 2", b.name)
        assertEquals("a theme keeps its own name when renaming it", "Neon", CustomThemes.uniqueName(ctx, "Neon", exceptId = a.id))
        CustomThemes.save(ctx, b)
        assertEquals(2, CustomThemes.all(ctx).size)
        assertEquals(a, CustomThemes.get(ctx, a.id))
        assertNotNull(CustomThemes.bannerFile(ctx, a))
        val stamp = CustomThemes.stamp(ctx, a)
        CustomThemes.save(ctx, a.copy(accent = 0xFF00FF00.toInt()))
        assertNotEquals("a changed choice changes the stamp open screens compare", stamp, CustomThemes.stamp(ctx, CustomThemes.get(ctx, a.id)!!))
        CustomThemes.delete(ctx, a.id)
        assertNull(CustomThemes.get(ctx, a.id)); assertFalse(CustomThemes.dir(ctx, a.id).exists())
        assertEquals(1, CustomThemes.all(ctx).size)
    }

    @Test
    fun onlyAPictureCanBeABannerAndOnlyAFontCanBeAFont() {
        val t = CustomTheme(id = CustomThemes.newId(), name = "x")
        assertNull(CustomThemes.setBanner(ctx, t, "not a picture".toByteArray()))
        assertNull(CustomThemes.setBanner(ctx, t, ByteArray(0)))
        assertNull(CustomThemes.setBanner(ctx, t, ByteArray(CustomThemes.MAX_BANNER_BYTES + 1)))
        assertEquals("banner", CustomThemes.setBanner(ctx, t, png())!!.banner)
        assertNull(CustomThemes.setFont(ctx, t, "not a font at all".toByteArray()))
        assertNull(CustomThemes.setFont(ctx, t, ByteArray(CustomThemes.MAX_FONT_BYTES + 1)))
        assertFalse("a rejected font leaves nothing behind", java.io.File(CustomThemes.dir(ctx, t.id), "font.tmp").exists())
        val cleared = CustomThemes.clearBanner(ctx, CustomThemes.setBanner(ctx, t, png())!!)
        assertNull(cleared.banner); assertFalse(java.io.File(CustomThemes.dir(ctx, t.id), "banner").exists())
    }

    @Test
    fun aSharedThemeComesBackWithItsPicture() {
        val a = full()
        val file = CustomThemes.toShareJson(ctx, a)
        val shared = CustomThemes.parseShared(file)
        assertEquals(a.copy(id = ""), shared.theme.copy(id = ""))
        assertArrayEquals(CustomThemes.bannerFile(ctx, a)!!.readBytes(), shared.banner)
        assertNull(shared.font)
        assertEquals(setOf(ThemePart.COLORS, ThemePart.FONT, ThemePart.BANNER, ThemePart.HEADER, ThemePart.MARKER), CustomThemes.partsIn(shared))
        assertEquals(CustomThemes.partsIn(shared), a.parts)
    }

    @Test
    fun aFileThatIsNotAThemeIsRefusedWithAReason() {
        fun why(text: String) = try { CustomThemes.parseShared(text); fail("accepted"); "" } catch (e: CustomThemes.BadThemeFile) { e.message!! }
        assertTrue(why("hello").contains("not valid JSON"))
        assertTrue(why("""{"app":"Other","type":"theme","format":1}""").contains("isn't an RF Sentinel theme"))
        assertTrue(why("""{"app":"RF Sentinel","type":"backup","format":1}""").contains("isn't an RF Sentinel theme"))
        assertTrue(why("""{"app":"RF Sentinel","type":"theme","format":9,"theme":{}}""").contains("newer version"))
        assertTrue(why("""{"app":"RF Sentinel","type":"theme","format":1}""").contains("no theme"))
        // A picture that is not a picture offers no banner to take.
        val s = CustomThemes.parseShared("""{"app":"RF Sentinel","type":"theme","format":1,"theme":{"name":"p","banner":"banner"},"assets":{"banner":"AAAA"}}""")
        assertNull(s.banner); assertFalse(ThemePart.BANNER in CustomThemes.partsIn(s))
    }

    @Test
    fun importTakesOnlyTheTickedPartsIntoANewTheme() {
        val src = CustomThemes.parseShared(CustomThemes.toShareJson(ctx, full()))
        val t = CustomThemes.import(ctx, src, null, setOf(ThemePart.COLORS, ThemePart.MARKER), "Mine")
        assertEquals("Mine", t.name)
        assertEquals(src.theme.accent, t.accent); assertEquals("vibrant", t.scheme); assertEquals("black", t.mode)
        assertEquals("taxi", t.markerStyle); assertEquals(1.4f, t.markerSize!!, 0f)
        assertEquals("default", t.font); assertNull(t.banner); assertNull(t.header); assertNull(t.title)
        assertEquals(t, CustomThemes.get(ctx, t.id))
        assertNull(CustomThemes.bannerFile(ctx, t))
    }

    @Test
    fun importIntoAnExistingThemeReplacesOnlyTheTickedParts() {
        val mine = CustomTheme(id = CustomThemes.newId(), name = "Old", accent = 0xFF112233.toInt(), font = "mono", header = "CRT", title = "Old title",
            markerStyle = "arrow", markerColor = "green", markerSize = 1.0f)
        val withBanner = CustomThemes.setBanner(ctx, mine, png(Color.BLUE))!!
        CustomThemes.save(ctx, withBanner)
        val src = CustomThemes.parseShared(CustomThemes.toShareJson(ctx, full()))

        val out = CustomThemes.import(ctx, src, withBanner, setOf(ThemePart.FONT, ThemePart.HEADER))
        assertEquals("Old", out.name); assertEquals("the same theme, not a new one", withBanner.id, out.id)
        assertEquals("serif", out.font); assertEquals("HUD", out.header); assertEquals("Neon Sentinel", out.title)
        assertEquals("colours stay", 0xFF112233.toInt(), out.accent)
        assertEquals("marker stays", "green", out.markerColor)
        assertNotNull("banner stays", CustomThemes.bannerFile(ctx, out))
        assertEquals(1, CustomThemes.all(ctx).filter { it.name.startsWith("Old") }.size)

        val withNewBanner = CustomThemes.import(ctx, src, out, setOf(ThemePart.BANNER))
        assertArrayEquals(src.banner, CustomThemes.bannerFile(ctx, withNewBanner)!!.readBytes())
        assertEquals("serif", withNewBanner.font)
    }

    @Test
    fun aFontThatCannotBeLoadedFallsBackToTheStandardFont() {
        val src = CustomThemes.Shared(CustomTheme("", "f", font = "file", fontFile = "font"), null, "garbage".toByteArray())
        val t = CustomThemes.import(ctx, src, null, setOf(ThemePart.FONT))
        assertEquals("default", t.font); assertNull(t.fontFile)
        // And a font choice without its file is not offered as a part to take.
        assertFalse(ThemePart.FONT in CustomThemes.partsIn(CustomThemes.Shared(CustomTheme("", "f", font = "file"), null, null)))
    }

    @Test
    fun duplicateCopiesThePictureToo() {
        val a = full()
        val copy = CustomThemes.duplicate(ctx, a)
        assertNotEquals(a.id, copy.id); assertEquals("Neon copy", copy.name)
        assertEquals(a.accent, copy.accent); assertEquals(a.markerStyle, copy.markerStyle)
        assertArrayEquals(CustomThemes.bannerFile(ctx, a)!!.readBytes(), CustomThemes.bannerFile(ctx, copy)!!.readBytes())
        CustomThemes.delete(ctx, a.id)
        assertNotNull("deleting one does not take the other's picture", CustomThemes.bannerFile(ctx, CustomThemes.get(ctx, copy.id)!!))
    }

    @Test
    fun theBackupBringsThemesBackWithTheirIdsAndPictures() {
        val a = full(); val b = full(name = "Other")
        val arr = CustomThemes.backup(ctx)
        CustomThemes.delete(ctx, a.id); CustomThemes.delete(ctx, b.id)
        CustomThemes.save(ctx, CustomTheme(id = "stray", name = "Stray"))
        assertEquals(2, CustomThemes.restoreBackup(ctx, arr))
        assertNull("a theme not in the backup is replaced", CustomThemes.get(ctx, "stray"))
        assertEquals(a, CustomThemes.get(ctx, a.id))
        assertNotNull(CustomThemes.bannerFile(ctx, CustomThemes.get(ctx, b.id)!!))
    }

    @Test
    fun aDamagedThemeInABackupChangesNothing() {
        val a = full()
        val bad = JSONArray().put(JSONObject().put("id", "x").put("app", "Other"))
        try { CustomThemes.restoreBackup(ctx, bad); fail("accepted") } catch (_: CustomThemes.BadThemeFile) {}
        assertEquals(a, CustomThemes.get(ctx, a.id))
    }

    // ---- colours -------------------------------------------------------------------------------

    @Test
    fun theAccentMakesAPaletteAndPureBlackMakesBlackBackgrounds() {
        assertTrue(CustomColors.available)
        val t = CustomTheme("p", "p", accent = 0xFFE53935.toInt())
        val normal = CustomColors.overrides(ctx, t, dark = true)
        assertTrue("a palette is made (${normal.size})", normal.size > 20)
        val black = CustomColors.overrides(ctx, t.copy(mode = "black"), dark = true)
        fun colorOf(map: Map<Int, Int>, name: String): Int? {
            val id = ctx.resources.getIdentifier(name, "color", ctx.packageName); assertNotEquals(name, 0, id); return map[id]
        }
        assertEquals(0xFF000000.toInt(), colorOf(black, "material_personalized_color_surface"))
        assertEquals(0xFF000000.toInt(), colorOf(black, "material_personalized_color_background"))
        assertNotEquals("the normal dark surface is not black", 0xFF000000.toInt(), colorOf(normal, "material_personalized_color_surface"))
        assertNotEquals("raised containers stay a step lighter so cards still show", 0xFF000000.toInt(), colorOf(black, "material_personalized_color_surface_container_high"))
        assertEquals("the accent colours are not touched by black", colorOf(normal, "material_personalized_color_primary"), colorOf(black, "material_personalized_color_primary"))
        val light = CustomColors.overrides(ctx, t.copy(mode = "black"), dark = false)
        assertNotEquals("black only changes the dark side", 0xFF000000.toInt(), colorOf(light, "material_personalized_color_surface"))
        assertNull(CustomColors.blackFor("m3_sys_color_dynamic_dark_surface")); assertNull(CustomColors.blackFor("material_personalized_color_primary"))
    }

    @Test
    fun everyPaletteStyleAndModeWorks() {
        for (s in CustomColors.Scheme.entries) for (dark in listOf(false, true)) {
            val sch = CustomColors.scheme(CustomTheme("p", "p", scheme = s.key), dark)
            assertNotEquals(s.key, sch.primary, sch.surface)
        }
        assertEquals(CustomColors.Mode.SYSTEM, CustomColors.Mode.of("whatever")); assertEquals(CustomColors.Scheme.TONAL, CustomColors.Scheme.of(null))
        assertTrue(CustomColors.isDark(ctx, CustomTheme("p", "p", mode = "dark"))); assertTrue(CustomColors.isDark(ctx, CustomTheme("p", "p", mode = "black")))
        assertFalse(CustomColors.isDark(ctx, CustomTheme("p", "p", mode = "light")))
    }

    // ---- the theme in use --------------------------------------------------------------------------

    @Test
    fun theThemeInUseDecidesTheHeaderTitleBannerAndMarker() {
        assertEquals(ThemeManager.AppTheme.SYSTEM, ThemeManager.current(ctx))
        val a = full()
        ThemeManager.setCustom(ctx, a.id)
        assertEquals(ThemeManager.AppTheme.CUSTOM, ThemeManager.current(ctx)); assertEquals(a.id, ThemeManager.custom(ctx)!!.id)
        assertEquals(ThemeHeaderView.Style.HUD, ThemeManager.headerStyle(ctx)); assertEquals("Neon Sentinel", ThemeManager.appTitle(ctx))
        assertTrue(ThemeManager.hasBanner(ctx)); assertFalse("the Settings banner is a different one", ThemeManager.hasGlobalBanner(ctx))
        val m = MarkerPicker.effective(ctx)
        assertEquals("taxi", m.style); assertEquals("red", m.color); assertEquals(1.4f, m.size, 0f)
        // A built-in theme is back: the marker is the one chosen in Settings.
        ThemeManager.set(ctx, ThemeManager.builtIn.first { it != ThemeManager.AppTheme.SYSTEM })
        assertNull(ThemeManager.custom(ctx)); assertEquals("arrow", MarkerPicker.effective(ctx).style)
        assertFalse(ThemeManager.AppTheme.CUSTOM in ThemeManager.builtIn)
    }

    @Test
    fun aDeletedThemeInUseIsTheDefaultLookAgain() {
        val a = full()
        ThemeManager.setCustom(ctx, a.id)
        CustomThemes.delete(ctx, a.id)
        assertEquals(ThemeManager.AppTheme.SYSTEM, ThemeManager.current(ctx)); assertNull(ThemeManager.custom(ctx))
    }

    @Test
    fun theFontsAreRealOnes() {
        assertNull(FontApplier.typeface(ctx, CustomTheme("p", "p", font = "default")))
        for (f in FontApplier.families.filter { it.system != null }) assertNotNull(f.key, FontApplier.typeface(ctx, CustomTheme("p", "p", font = f.key)))
        assertNull("a font file that is missing is the standard font", FontApplier.typeface(ctx, CustomTheme("p", "p", font = "file", fontFile = "font")))
        val tv = android.widget.TextView(ctx).apply { setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        val group = android.widget.LinearLayout(ctx).apply { addView(tv) }
        FontApplier.apply(group, android.graphics.Typeface.SERIF)
        assertTrue("bold is kept", tv.typeface.isBold)
        assertNotEquals(android.graphics.Typeface.DEFAULT_BOLD, tv.typeface)
    }
}
