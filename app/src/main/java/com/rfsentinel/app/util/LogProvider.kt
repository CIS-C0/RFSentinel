package com.rfsentinel.app.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Everything the app keeps, over adb, release builds included:
 *
 *     adb exec-out content read --uri content://com.rfsentinel.app.logs/sdr
 *
 * Paths: "" lists what there is; a log by its file name without ".log" (sdr, usbwifi, phone...), the same report as
 * Settings › Export a hardware log; "trace/latest" or "trace/<id>" for a trace's or snapshot's activity CSV;
 * "files" lists the app's private storage; "file/<absolute path>" reads one of those files as it is (databases,
 * settings, crash log, caches). Read-only SQL on a database:
 *
 *     adb shell "content query --uri content://com.rfsentinel.app.logs/sql --where 'SELECT * FROM detections LIMIT 20'"
 *
 * ("sql/<database file>" for one other than rfsentinel.db). Readable only with android.permission.DUMP, which adb's
 * shell has and no installed app can get.
 */
class LogProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        if (seg.firstOrNull() == "file") {
            val f = File("/" + seg.drop(1).joinToString("/"))
            if (!inside(f, roots()) || !f.isFile) throw FileNotFoundException("no file ${f.path} - read content://${uri.authority}/files for the list")
            // Recent writes wait in rfsentinel.db-wal: Room folds them in first, so the .db alone is complete.
            // (Not through a connection of our own: one opened without WAL would switch the database out of it.)
            if (f.canonicalPath == ctx.getDatabasePath("rfsentinel.db").canonicalPath) runCatching {
                com.rfsentinel.app.data.AppDatabase.getInstance(ctx).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val text = when {
            seg.isEmpty() -> index()
            seg[0] == "files" -> roots().flatMap { root -> root.walkTopDown().filter { it.isFile }
                .map { "${it.length()}\t${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(it.lastModified())}\t${it.path}" } }
                .joinToString("\n", postfix = "\n")
            seg[0] == "trace" -> seg.getOrNull(1)?.let { if (it == "latest") com.rfsentinel.app.service.SdrTrack.latest() else it.toLongOrNull() }
                ?.let { com.rfsentinel.app.service.SdrTrack.read(it) }
            else -> HwLogs.all.firstOrNull { it.file == seg[0] + ".log" }?.let { HwLogs.report(ctx, it) }
        } ?: throw FileNotFoundException("nothing at $uri - read content://${uri.authority}/ for the list")
        // Handed over as an open file, then removed: nothing stays behind in the cache.
        val f = File(ctx.cacheDir, "adb_log_${System.nanoTime()}.txt")
        f.writeText(text)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).also { f.delete() }
    }

    /** Read-only SQL (the --where of `content query`) on rfsentinel.db, or "sql/<database file>". */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        if (uri.pathSegments.firstOrNull() != "sql" || selection.isNullOrBlank()) return null
        val db = ctx.getDatabasePath(uri.pathSegments.getOrNull(1) ?: "rfsentinel.db")
        if (!db.isFile) throw IllegalArgumentException("no database ${db.name}")
        // Its own read-only connection: a query can't change anything, whatever it says (nor its journal mode).
        return readOnly.getOrPut(db.path) { SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY) }
            .rawQuery(selection, selectionArgs?.let { arrayOf(*it) })
    }

    private val readOnly = java.util.concurrent.ConcurrentHashMap<String, SQLiteDatabase>()

    /** The app's private storage: its data folder, and the one kept readable before the phone is first unlocked. */
    private fun roots(): List<File> = context?.let { listOfNotNull(it.dataDir, it.createDeviceProtectedStorageContext().dataDir) }.orEmpty()

    private fun index(): String = buildString {
        val base = "content://${context?.packageName}.logs"
        append("RF Sentinel over adb: adb exec-out content read --uri $base/<path>\n\nHardware logs:\n")
        HwLogs.all.forEach { append("  ${it.file.removeSuffix(".log")}  - ${it.title}\n") }
        append("\nTraces and snapshots with SDR / USB WiFi activity, newest first:\n  trace/latest\n")
        com.rfsentinel.app.service.SdrTrack.ids().forEach { (id, bytes) -> append("  trace/$id  (${bytes / 1024} KB)\n") }
        append("\nPrivate storage:\n  files  - every file: size, last changed, path\n  file/<path>  - one file as it is, e.g. file")
        append(context?.getDatabasePath("rfsentinel.db")?.path.orEmpty())
        append("\n\nRead-only SQL (tables: SELECT name FROM sqlite_master):\n  adb shell \"content query --uri $base/sql --where 'SELECT * FROM detections ORDER BY timestamp DESC LIMIT 20'\"\n")
        append("  $base/sql/<database file> for another database\n")
    }

    override fun getType(uri: Uri) = if (uri.pathSegments.firstOrNull() == "file") "application/octet-stream" else "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        /** [f] is one of [roots] or below it, after resolving ".." and links. */
        fun inside(f: File, roots: List<File>): Boolean {
            val p = runCatching { f.canonicalPath }.getOrNull() ?: return false
            return roots.any { r -> runCatching { r.canonicalPath }.getOrNull()?.let { p == it || p.startsWith(it + File.separator) } == true }
        }
    }
}
