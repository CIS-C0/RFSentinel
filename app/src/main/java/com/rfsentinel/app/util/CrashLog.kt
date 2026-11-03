package com.rfsentinel.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes written to a private file (never sent anywhere) so testers without adb can send them:
 * Settings > Lists, tools & about > Export crash log, or the prompt after the app closed
 * unexpectedly. Keeps the newest ~200 KB. On Android 11+ other unexpected ends (native crash,
 * not responding, killed by the system) are added at the next start ([recordExits]).
 */
object CrashLog {
    private const val FILE = "crash_log.txt"
    private const val MAX_BYTES = 200_000
    private const val PREFS = "crash_log"

    fun file(context: Context) = File(context.filesDir, FILE)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("==== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append(" ====\n")
            append("RF Sentinel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Thread: ${thread.name}\n")
            append(trace).append("\n")
        }
        append(context, entry)
    }

    private fun append(context: Context, entry: String) {
        val f = file(context)
        val old = if (f.exists()) f.readText() else ""
        f.writeText((old + entry).takeLast(MAX_BYTES))
        // commit(): the process is about to die.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("unseen", true).commit()
    }

    /**
     * Android 11+: the ways earlier runs ended that [install] can't catch (native crash, not responding, killed with
     * Bluetooth or another service it used, low memory while in use), added to the log once. Looks back at most a week.
     * Android 8-10 keep no such record. Slow (reads traces): call it off the main thread.
     */
    fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("exits_seen", 0L)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 0)
        }.getOrNull().orEmpty()
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        val since = maxOf(seen, System.currentTimeMillis() - 7 * 24 * 3_600_000L)
        val noted = exits.filter { it.timestamp > since && noteworthy(it.reason, it.importance) }.sortedBy { it.timestamp }
        prefs.edit().putLong("exits_seen", maxOf(newest, seen)).apply()
        if (noted.isNotEmpty()) append(context, noted.joinToString("") { exitEntry(it) })
    }

    /** Worth logging: not a normal end (closed, force-stopped, updated) nor a crash [install] already logged. */
    internal fun noteworthy(reason: Int, importance: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> false
        // Low memory, signalled, other: normal for an app in the background, worth a look while on screen or scanning.
        else -> importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
    }

    private fun reasonLabel(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "killed because something it used died (Bluetooth or another system service?)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much CPU or memory"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        else -> "other (reason $reason)"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun exitEntry(e: ApplicationExitInfo): String = buildString {
        append("==== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(e.timestamp))).append(" ====\n")
        append("RF Sentinel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) found at its next start: ${reasonLabel(e.reason)}\n")
        append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val where = when {
            e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
            e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "scanning in the background"
            e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "visible"
            else -> "in the background"
        }
        append("Process ${e.processName}, $where, status ${e.status}, memory ${e.pss / 1024} MB\n")
        e.description?.let { append("Description: ").append(it).append("\n") }
        val trace = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull()
        when {
            trace == null -> {}
            // A tombstone (protobuf) on Android 12+: its text parts hold the signal, backtrace and library names.
            e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> append(printableRuns(trace).take(16_000)).append("\n")
            else -> append(String(trace, Charsets.UTF_8).take(32_000)).append("\n")
        }
        append("\n")
    }

    /** The runs of [min]+ readable ASCII characters in [bytes], one per line. */
    internal fun printableRuns(bytes: ByteArray, min: Int = 6): String {
        val out = StringBuilder()
        var start = -1
        for (i in 0..bytes.size) {
            val readable = i < bytes.size && bytes[i] in 0x20..0x7e
            if (readable && start < 0) start = i
            if (!readable && start >= 0) {
                if (i - start >= min) out.append(String(bytes, start, i - start, Charsets.US_ASCII)).append('\n')
                start = -1
            }
        }
        return out.toString()
    }

    fun hasCrashes(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    /** After a crash, once: offers to send the log. */
    fun offerAfterCrash(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("unseen", false)) return
        prefs.edit().putBoolean("unseen", false).apply()
        if (!hasCrashes(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("RF Sentinel closed unexpectedly")
            .setMessage("A crash log was saved on this phone (nothing is sent automatically). " +
                "Sending it to the developer helps fix it.\n\nLater: Settings > Lists, tools & about > Export crash log.")
            .setPositiveButton("Send log") { _, _ -> share(activity) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** The crash log as a .txt file to send (email, messages, Drive...). */
    fun share(activity: AppCompatActivity) {
        if (!hasCrashes(activity)) {
            Toast.makeText(activity, "No crashes recorded", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
            val out = File(dir, "rfsentinel-crash-log-${Exporter.stamp()}.txt")
            file(activity).copyTo(out, overwrite = true)
            androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", out)
        }.getOrNull()
        if (uri == null) { Toast.makeText(activity, "Couldn't create the log file", Toast.LENGTH_SHORT).show(); return }
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "RF Sentinel crash log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export crash log"))
    }

    fun clear(context: Context) { file(context).delete() }
}
