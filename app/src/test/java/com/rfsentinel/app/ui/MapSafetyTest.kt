package com.rfsentinel.app.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.RenderingEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A map that took the app down while it started (BV9100, Android 9: MapLibre's Vulkan renderer threw "No Vulkan compatible
 * GPU found" on its render thread, and the saved view asked for the map again at every launch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapSafetyTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("map_safety", Context.MODE_PRIVATE).edit().clear().commit()
        MapSafety.live = false
        Prefs.setViewPanes(ctx, setOf("map", "list"))
        vulkan(true)
    }

    private fun vulkan(has: Boolean) = shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, has)

    @Test
    fun aPhoneWithoutVulkanStartsOnOpenGl() {
        vulkan(false)
        assertEquals("no crash first: MapLibre would not fall back by itself", RenderingEngine.Type.OPENGL, MapSafety.renderer(ctx))
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
    }

    /** The app starts again after dying: nothing is live in the new process. */
    private fun newProcess() { MapSafety.live = false }

    @Test
    fun aCleanStartChangesNothing() {
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
        assertNull("MapLibre chooses (Vulkan where it exists)", MapSafety.renderer(ctx))
        assertEquals(setOf("map", "list"), Prefs.viewPanes(ctx))
    }

    @Test
    fun aMapThatSettledOrWasLeftIsNotACrash() {
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP)
        MapSafety.worked(ctx)
        newProcess()
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP)
        MapSafety.disarm(ctx) // the screen paused normally
        newProcess()
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
        assertEquals(setOf("map", "list"), Prefs.viewPanes(ctx))
    }

    @Test
    fun theFirstCrashTriesOpenGlAndKeepsTheMap() {
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP)
        newProcess()
        assertTrue(MapSafety.crashedLastTime(ctx))
        assertEquals(MapSafety.Recovery.OPENGL, MapSafety.recover(ctx))
        assertEquals(RenderingEngine.Type.OPENGL, MapSafety.renderer(ctx))
        assertEquals("the map stays on the main screen for the retry", setOf("map", "list"), Prefs.viewPanes(ctx))
        assertFalse("the mark is used up", MapSafety.crashedLastTime(ctx))
        assertEquals(1, MapSafety.crashes(ctx))
    }

    @Test
    fun aSecondCrashTakesTheMapOffTheMainScreen() {
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP); newProcess()
        assertEquals(MapSafety.Recovery.OPENGL, MapSafety.recover(ctx))
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP); newProcess()
        assertEquals(MapSafety.Recovery.MAP_OFF, MapSafety.recover(ctx))
        assertEquals(setOf("list"), Prefs.viewPanes(ctx))
        assertEquals(2, MapSafety.crashes(ctx))
        assertEquals("and the next launch is quiet", MapSafety.Recovery.NONE, MapSafety.recover(ctx))
    }

    @Test
    fun theMapOnlyViewFallsBackToTheList() {
        Prefs.setViewPanes(ctx, setOf("map"))
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP); newProcess(); MapSafety.recover(ctx)
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP); newProcess(); MapSafety.recover(ctx)
        assertEquals(setOf("list"), Prefs.viewPanes(ctx))
    }

    @Test
    fun aMapboxCrashCannotBeHelpedByOpenGl() {
        MapSafety.arm(ctx, MapEngine.Provider.MAPBOX); newProcess()
        assertEquals(MapSafety.Recovery.MAP_OFF, MapSafety.recover(ctx))
        assertNull("MapLibre's renderer was not to blame", MapSafety.renderer(ctx))
        assertEquals(setOf("list"), Prefs.viewPanes(ctx))
    }

    @Test
    fun aMapStillStartingInThisProcessIsNotACrash() {
        // The main screen opening (a notification tap) while the full screen map is still drawing its first frame.
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP)
        assertFalse(MapSafety.crashedLastTime(ctx))
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
        assertEquals(setOf("map", "list"), Prefs.viewPanes(ctx))
        assertNull(MapSafety.renderer(ctx))
    }

    @Test
    fun aMapThatWorksAfterTheRetryResetsTheCountAndStaysOnOpenGl() {
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP); newProcess(); MapSafety.recover(ctx)
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP)
        MapSafety.worked(ctx)
        assertEquals(0, MapSafety.crashes(ctx))
        assertEquals("OpenGL worked, so it stays", RenderingEngine.Type.OPENGL, MapSafety.renderer(ctx))
        newProcess()
        assertEquals(MapSafety.Recovery.NONE, MapSafety.recover(ctx))
    }

    @Test
    fun theMarkIsOnDiskBeforeTheMapStarts() {
        MapSafety.arm(ctx, MapEngine.Provider.OPENFREEMAP, now = 1234L)
        val sp = ctx.getSharedPreferences("map_safety", Context.MODE_PRIVATE)
        assertEquals(1234L, sp.getLong("armed_at", 0L))
        assertEquals("OPENFREEMAP", sp.getString("armed_provider", null))
    }
}
