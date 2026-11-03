package com.rfsentinel.app.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.service.BleScanEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The extended Bluetooth scan falls back to the ordinary one after the phone's Bluetooth stops twice, until Android updates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BleFallbackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun twiceOnOneBuildThenFreshAfterAnUpdate() {
        assertFalse(Prefs.bleLegacyOnly(ctx, "build-a"))
        assertEquals(1, Prefs.addBleExtendedStrike(ctx, "build-a"))
        assertFalse(Prefs.bleLegacyOnly(ctx, "build-a"))
        assertEquals(2, Prefs.addBleExtendedStrike(ctx, "build-a"))
        assertTrue(Prefs.bleLegacyOnly(ctx, "build-a"))
        // Android updated: the extended scan gets another chance.
        assertFalse(Prefs.bleLegacyOnly(ctx, "build-b"))
        assertEquals(1, Prefs.addBleExtendedStrike(ctx, "build-b"))
    }

    @Test
    fun secondStrikeIsNotedInTheCrashLogWithoutThePrompt() {
        BleScanEngine.strike(ctx, "Bluetooth restarted")
        assertFalse(CrashLog.hasCrashes(ctx))
        BleScanEngine.strike(ctx, "Bluetooth restarted")
        assertTrue(CrashLog.file(ctx).readText().contains("using the ordinary scan"))
        assertFalse(ctx.getSharedPreferences("crash_log", Context.MODE_PRIVATE).getBoolean("unseen", false))
    }
}
