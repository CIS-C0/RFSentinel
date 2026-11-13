package com.rfsentinel.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogProviderTest {

    @Test
    fun adbReadsOnlyInsideTheAppsStorage() {
        val base = Files.createTempDirectory("lp").toFile()
        val root = File(base, "app").apply { mkdirs() }
        File(base, "app2").mkdirs()
        val roots = listOf(root)
        assertTrue(LogProvider.inside(File(root, "databases/rfsentinel.db"), roots))
        assertTrue(LogProvider.inside(root, roots))
        assertFalse(LogProvider.inside(File(root, "../app2/x"), roots)) // ".." out of it
        assertFalse(LogProvider.inside(File(base, "app2/x"), roots)) // same prefix, another folder
        assertFalse(LogProvider.inside(File("/etc/hosts"), roots))
        base.deleteRecursively()
    }
}
