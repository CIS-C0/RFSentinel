package com.rfsentinel.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Exports and shared logs older than a day are removed when the next one is written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExportsDirTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun filesOlderThanADayGo() {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val old = File(dir, "old.json").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 25 * 3_600_000L) }
        val recent = File(dir, "recent.txt").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 23 * 3_600_000L) }
        Exporter.exportsDir(ctx)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
