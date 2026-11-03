package com.rfsentinel.app.util

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which earlier ends of the app go into the crash log, and the readable text of a native tombstone. */
class CrashLogExitTest {
    @Test
    fun crashesAndKillsWhileInUseAreLogged() {
        assertTrue(CrashLog.noteworthy(ApplicationExitInfo.REASON_CRASH_NATIVE, IMPORTANCE_CACHED))
        assertTrue(CrashLog.noteworthy(ApplicationExitInfo.REASON_ANR, IMPORTANCE_CACHED))
        assertTrue(CrashLog.noteworthy(ApplicationExitInfo.REASON_DEPENDENCY_DIED, IMPORTANCE_CACHED))
        assertTrue(CrashLog.noteworthy(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_FOREGROUND_SERVICE))
        // Normal: in the background, closed by the user, updated, or a crash the handler already logged.
        assertFalse(CrashLog.noteworthy(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_CACHED))
        assertFalse(CrashLog.noteworthy(ApplicationExitInfo.REASON_USER_REQUESTED, IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(CrashLog.noteworthy(ApplicationExitInfo.REASON_PACKAGE_UPDATED, IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(CrashLog.noteworthy(ApplicationExitInfo.REASON_CRASH, IMPORTANCE_FOREGROUND_SERVICE))
    }

    @Test
    fun tombstoneKeepsItsTextParts() {
        val bytes = byteArrayOf(0x0a, 0x07) + "SIGSEGV".toByteArray() + byteArrayOf(0x12, 0x02) + "ab".toByteArray() +
            byteArrayOf(0x1a, 0x0c) + "libmaplibre.so".toByteArray()
        assertEquals("SIGSEGV\nlibmaplibre.so\n", CrashLog.printableRuns(bytes))
    }
}
