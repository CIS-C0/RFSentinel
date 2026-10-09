package com.rfsentinel.app.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 * unexpectedly. Keeps the newest ~200 KB.
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
        val f = file(context)
        val old = if (f.exists()) f.readText() else ""
        f.writeText((old + entry).takeLast(MAX_BYTES))
        // commit(): the process is about to die.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("unseen", true).commit()
    }

    fun hasCrashes(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    /** After a crash, once: offers to send the log. */
    fun offerAfterCrash(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("unseen", false)) return
        prefs.edit().putBoolean("unseen", false).apply()
        if (!hasCrashes(activity)) return
        AlertDialog.Builder(activity)
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
