package com.rfsentinel.app.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * The hardware logs and the traces' SDR / USB WiFi activity over adb, release builds included:
 *
 *     adb exec-out content read --uri content://com.rfsentinel.app.logs/sdr
 *
 * Paths: "" lists what there is; a log by its file name without ".log" (sdr, usbwifi, phone...), the same report as
 * Settings › Export a hardware log; "trace/latest" or "trace/<id>" for a trace's or snapshot's activity CSV.
 * Readable only with android.permission.DUMP, which adb's shell has and no installed app can get.
 */
class LogProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        val text = when {
            seg.isEmpty() -> index()
            seg[0] == "trace" -> seg.getOrNull(1)?.let { if (it == "latest") com.rfsentinel.app.service.SdrTrack.latest() else it.toLongOrNull() }
                ?.let { com.rfsentinel.app.service.SdrTrack.read(it) }
            else -> HwLogs.all.firstOrNull { it.file == seg[0] + ".log" }?.let { HwLogs.report(ctx, it) }
        } ?: throw FileNotFoundException("nothing at $uri - read content://${uri.authority}/ for the list")
        // Handed over as an open file, then removed: nothing stays behind in the cache.
        val f = File(ctx.cacheDir, "adb_log_${System.nanoTime()}.txt")
        f.writeText(text)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).also { f.delete() }
    }

    private fun index(): String = buildString {
        append("RF Sentinel logs over adb: content read --uri content://${context?.packageName}.logs/<path>\n\nHardware logs:\n")
        HwLogs.all.forEach { append("  ${it.file.removeSuffix(".log")}  - ${it.title}\n") }
        append("\nTraces and snapshots with SDR / USB WiFi activity, newest first:\n  trace/latest\n")
        com.rfsentinel.app.service.SdrTrack.ids().forEach { (id, bytes) -> append("  trace/$id  (${bytes / 1024} KB)\n") }
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
