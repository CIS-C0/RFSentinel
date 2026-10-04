package com.rfsentinel.app.util

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates": asks GitHub for the latest release, only when tapped.
 * GitHub sees the request (your IP address); nothing about your scans is sent.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/CIS-C0/RFSentinel/releases/latest"
    private const val RELEASES = "https://github.com/CIS-C0/RFSentinel/releases/latest"

    data class Latest(val version: String, val apkUrl: String?, val notesUrl: String)

    /** True when [a] is a newer dotted version than [b] ("2.11.1" > "2.11.0"). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun fetch(): Latest {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith("-release.apk")) apk = a.optString("browser_download_url")
            }
            return Latest(json.getString("tag_name").trimStart('v'), apk, json.optString("html_url", RELEASES))
        } finally {
            conn.disconnect()
        }
    }

    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /**
     * The startup check (Settings / setup wizard toggle): at most every 6 hours, and silent
     * unless a newer version is out - no "checking", "up to date" or connection-error messages.
     */
    fun checkOnStartup(activity: AppCompatActivity) {
        if (!Prefs.autoUpdateCheck(activity)) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck(activity) < AUTO_INTERVAL_MS) return
        Prefs.setLastUpdateCheck(activity, now)
        check(activity, silent = true)
    }

    fun check(activity: AppCompatActivity, silent: Boolean = false) {
        if (!silent) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (silent && result.isFailure) return@launch
            val latest = result.getOrElse {
                AlertDialog.Builder(activity)
                    .setTitle("Couldn't check for updates")
                    .setMessage("GitHub couldn't be reached (${it.message ?: "no connection"}). You can look at the releases page instead.")
                    .setPositiveButton("Open releases") { _, _ -> open(activity, RELEASES) }
                    .setNegativeButton("Close", null)
                    .show()
                return@launch
            }
            val current = BuildConfig.VERSION_NAME.substringBefore('-')
            if (isNewer(latest.version, current)) {
                AlertDialog.Builder(activity)
                    .setTitle("RF Sentinel ${latest.version} is available")
                    .setMessage("You have $current. Download the new APK and install it over this one - your history and settings are kept.")
                    .setPositiveButton("Download") { _, _ -> download(activity, latest) }
                    .setNeutralButton("What's new") { _, _ -> open(activity, latest.notesUrl) }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (!silent) {
                AlertDialog.Builder(activity)
                    .setTitle("You're up to date")
                    .setMessage("RF Sentinel $current is the latest version.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Downloads the APK with Android's download manager into Downloads. Handing the APK link
     * to the browser instead stalls at 100% in Chrome: a download started by another app
     * waits on Chrome's "this file can be harmful" check, whose prompt never shows. Tapping
     * the finished-download notification opens the installer. Falls back to the browser.
     */
    private fun download(activity: AppCompatActivity, latest: Latest) {
        val url = latest.apkUrl ?: return open(activity, latest.notesUrl)
        val queued = runCatching {
            val dm = activity.getSystemService(android.app.DownloadManager::class.java)!!
            dm.enqueue(android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("RF Sentinel ${latest.version}")
                .setDescription("Update - tap when done to install")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, url.substringAfterLast('/')))
        }.isSuccess
        if (queued) Toast.makeText(activity, "Downloading RF Sentinel ${latest.version}… when it's done, tap its notification (or the file in Downloads) to install", Toast.LENGTH_LONG).show()
        else open(activity, url)
    }

    private fun open(activity: AppCompatActivity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
