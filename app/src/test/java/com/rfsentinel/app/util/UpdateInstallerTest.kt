package com.rfsentinel.app.util

import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** One-button updates: reading the release, what Android's answers mean, and refusing a wrong file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UpdateInstallerTest {

    private val release = """
        {"tag_name":"v2.21.0","html_url":"https://github.com/CIS-C0/RFSentinel/releases/tag/v2.21.0","assets":[
          {"name":"notes.txt","browser_download_url":"https://example.invalid/notes.txt","size":10},
          {"name":"RFSentinel-v2.21.0-release.apk","browser_download_url":"https://example.invalid/RFSentinel-v2.21.0-release.apk",
           "size":33755804,"digest":"sha256:11bda3ddc0c407d46383ad780d7678129ac1078c0a651dd45ab1dce24bd24d70"}]}
    """.trimIndent()

    @Test
    fun theReleaseGivesTheApkItsSizeAndChecksum() {
        val l = UpdateChecker.parseLatest(release)
        assertEquals("2.21.0", l.version)
        assertTrue(l.apkUrl!!.endsWith("-release.apk"))
        assertEquals(33755804L, l.apkSize)
        assertEquals("11bda3ddc0c407d46383ad780d7678129ac1078c0a651dd45ab1dce24bd24d70", l.apkSha256)
        assertTrue(l.notesUrl.contains("v2.21.0"))
    }

    @Test
    fun aReleaseWithoutAChecksumOrApkStillReads() {
        val l = UpdateChecker.parseLatest("""{"tag_name":"v2.21.0","assets":[{"name":"RFSentinel-v2.21.0-release.apk","browser_download_url":"u","size":5}]}""")
        assertNull(l.apkSha256)
        assertEquals(5L, l.apkSize)
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v2.21.0"}""").apkUrl)
    }

    @Test
    fun androidsAnswersAreTurnedIntoWhatTheScreenSays() {
        fun of(status: Int, msg: String? = null, card: Boolean = true) = UpdateInstaller.outcomeOf(status, msg, card)
        assertNull("waiting for the card", of(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertTrue(of(PackageInstaller.STATUS_SUCCESS) is UpdateInstaller.Outcome.Installed)
        assertTrue("card dismissed", of(PackageInstaller.STATUS_FAILURE_ABORTED, card = true) is UpdateInstaller.Outcome.Cancelled)
        assertTrue("no card shown: the phone refused a silent update", of(PackageInstaller.STATUS_FAILURE_ABORTED, card = false) is UpdateInstaller.Outcome.Refused)
        val conflict = of(PackageInstaller.STATUS_FAILURE_CONFLICT) as UpdateInstaller.Outcome.Failed
        assertTrue(conflict.message, conflict.message.contains("signing key"))
        val storage = of(PackageInstaller.STATUS_FAILURE_STORAGE) as UpdateInstaller.Outcome.Failed
        assertTrue(storage.message, storage.message.contains("room"))
        // Android's own words are shown when they are words (not an INSTALL_FAILED_ code).
        assertEquals("Not enough space", (of(PackageInstaller.STATUS_FAILURE, "Not enough space") as UpdateInstaller.Outcome.Failed).message)
        assertEquals("The install failed", (of(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_INTERNAL_ERROR") as UpdateInstaller.Outcome.Failed).message)
    }

    @Test
    fun aFileThatIsNotAnAppIsRefusedBeforeAndroidSeesIt() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val junk = File.createTempFile("not-an-app", ".apk").apply { writeText("this is not an apk"); deleteOnExit() }
        assertEquals("The downloaded file isn't a valid app", UpdateInstaller.checkArchive(ctx, junk))
    }

    @Test
    fun cleaningUpRemovesLeftoverDownloads() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        File(dir, "RFSentinel-v1.apk").writeText("x"); File(dir, "RFSentinel-v2.apk.part").writeText("y")
        UpdateInstaller.cleanUp(ctx)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun theAutomaticCheckIsDueAfterSixHoursOrSoonerAfterAFailure() {
        val h = 3_600_000L; val now = 100 * h
        assertTrue("never checked", UpdateChecker.due(0L, now))
        assertTrue("six hours later", UpdateChecker.due(now - 6 * h, now))
        assertTrue("not yet", !UpdateChecker.due(now - 5 * h, now))
        // A check that couldn't reach GitHub at time T is recorded as T - 6 h + 30 min: due again 30 minutes after T.
        val t = now; val recorded = t - 6 * h + h / 2
        assertTrue("10 minutes after a failure", !UpdateChecker.due(recorded, t + h / 6))
        assertTrue("30 minutes after a failure", UpdateChecker.due(recorded, t + h / 2))
        assertTrue("a clock set back never blocks it", UpdateChecker.due(now + h, now))
    }
}
