package com.rfsentinel.app.util

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    /** The newest release: its version, the APK's link, size and SHA-256 (as GitHub lists them) and the notes page. */
    data class Latest(val version: String, val apkUrl: String?, val notesUrl: String, val apkSize: Long = 0L, val apkSha256: String? = null)

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

    /**
     * The newest release from GitHub's answer: version, this build's APK asset with its size and SHA-256, the notes
     * page. The full build takes the "-release.apk" asset, the FOSS build the "-foss.apk" one.
     */
    internal fun parseLatest(text: String, foss: Boolean = BuildConfig.FLAVOR == "foss"): Latest {
        val suffix = if (foss) "-foss.apk" else "-release.apk"
        val json = JSONObject(text)
        val assets = json.optJSONArray("assets")
        var apk: String? = null; var size = 0L; var sha: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(suffix)) {
                apk = a.optString("browser_download_url"); size = a.optLong("size", 0L)
                sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            }
        }
        return Latest(json.getString("tag_name").trimStart('v'), apk, json.optString("html_url", RELEASES), size, sha)
    }

    private fun fetch(): Latest {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            return parseLatest(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    /** After a check that couldn't reach GitHub (no signal...), the next one is this soon, not a whole interval later. */
    private const val RETRY_MS = 30 * 60 * 1000L
    @Volatile private var checking = false

    /**
     * The startup check (Settings / setup wizard toggle), for every time the app comes to the front (the main
     * screen often stays alive for days, so "opening" it is not a fresh start): at most every 6 hours - a
     * check that couldn't reach GitHub is tried again after 30 minutes - and silent unless a newer version
     * is out: no "checking", "up to date" or connection-error messages.
     */
    fun checkOnStartup(activity: AppCompatActivity) {
        if (!Prefs.autoUpdateCheck(activity) || checking) return
        if (!due(Prefs.lastUpdateCheck(activity), System.currentTimeMillis())) return
        check(activity, silent = true)
    }

    /** Whether the automatic check is due: [last] is when the last one ended (0 = never). */
    internal fun due(last: Long, now: Long) = now - last >= AUTO_INTERVAL_MS || now < last

    /** SHA-256 of the certificate RF Sentinel's GitHub releases are signed with (public: any release APK shows it). */
    private const val RELEASE_CERT_SHA256 = "1775783cf3cef638332796435f2125ecbbccd5b0021a336218eeb218904da8ab"

    /** Whether this copy is signed with the GitHub releases' key (not F-Droid's), so a GitHub APK can update it. */
    @Suppress("DEPRECATION")
    private fun signedByUs(context: android.content.Context): Boolean = runCatching {
        val pm = context.packageManager
        val certs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        else pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        certs.orEmpty().any { c ->
            java.security.MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } == RELEASE_CERT_SHA256
        }
    }.getOrDefault(true)

    fun check(activity: AppCompatActivity, silent: Boolean = false) {
        if (!silent) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        if (checking) return
        checking = true
        activity.lifecycleScope.launch {
            val result = try { withContext(Dispatchers.IO) { runCatching { fetch() } } } finally { checking = false }
            // Reached GitHub: the next automatic check is an interval away; not reached: soon.
            val now = System.currentTimeMillis()
            Prefs.setLastUpdateCheck(activity, if (result.isSuccess) now else now - AUTO_INTERVAL_MS + RETRY_MS)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (silent && result.isFailure) return@launch
            val latest = result.getOrElse {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Couldn't check for updates")
                    .setMessage("GitHub couldn't be reached (${it.message ?: "no connection"}). You can look at the releases page instead.")
                    .setPositiveButton("Open releases") { _, _ -> open(activity, RELEASES) }
                    .setNegativeButton("Close", null)
                    .show()
                return@launch
            }
            val current = BuildConfig.VERSION_NAME.substringBefore('-')
            if (isNewer(latest.version, current) && BuildConfig.FLAVOR == "foss" && !signedByUs(activity)) {
                // Built and signed by F-Droid: Android would refuse our APK over it, so F-Droid updates it.
                MaterialAlertDialogBuilder(activity)
                    .setTitle("RF Sentinel ${latest.version} is available")
                    .setMessage("You have $current. This copy was built and signed by F-Droid, so it updates through F-Droid " +
                        "once F-Droid has built the new version.")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("What's new") { _, _ -> open(activity, latest.notesUrl) }
                    .show()
            } else if (isNewer(latest.version, current)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("RF Sentinel ${latest.version} is available")
                    .setMessage("You have $current. It downloads here and installs over this one - your history and settings are kept. " +
                        "Scanning stops while it installs.")
                    .setPositiveButton("Install now") { _, _ -> installNow(activity, latest) }
                    .setNeutralButton("What's new") { _, _ -> open(activity, latest.notesUrl) }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (!silent) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("You're up to date")
                    .setMessage("RF Sentinel $current is the latest version.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * One button: allow installs from this app if Android hasn't been told to yet (it opens that switch and carries
     * on when you are back), download the release with a progress bar, check it, and hand it to Android's installer
     * (its "Update?" card, which is the one tap left). The ordinary download in the browser stays as the fallback.
     */
    fun installNow(activity: AppCompatActivity, latest: Latest) {
        val url = latest.apkUrl ?: return open(activity, latest.notesUrl)
        if (!UpdateInstaller.canInstall(activity)) { askToAllowInstalls(activity, latest); return }

        val d = activity.resources.displayMetrics.density
        val text = android.widget.TextView(activity).apply { text = "Starting the download..." }
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            addView(text); addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
        }
        var job: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Updating to RF Sentinel ${latest.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        fun alive() = !activity.isFinishing && !activity.isDestroyed
        fun mb(n: Long) = "%.1f MB".format(java.util.Locale.US, n / 1_048_576.0)
        job = (activity.application as com.rfsentinel.app.RFSentinelApp).appScope.launch(Dispatchers.Main) {
            try {
                val file = UpdateInstaller.download(activity.applicationContext, url, latest.version, latest.apkSize, latest.apkSha256) { done, total ->
                    if (alive()) activity.runOnUiThread {
                        if (total > 0) {
                            bar.isIndeterminate = false; bar.max = 1000; bar.progress = (done * 1000 / total).toInt()
                            text.text = "Downloading ${mb(done)} of ${mb(total)}"
                        } else text.text = "Downloading ${mb(done)}"
                    }
                }
                if (alive()) { bar.isIndeterminate = true; text.text = "Installing... confirm Android's \"Update\" card" }
                val outcome = UpdateInstaller.install(activity.applicationContext, file)
                if (alive()) dialog.dismiss()
                when (outcome) {
                    is UpdateInstaller.Outcome.Cancelled -> if (alive()) Toast.makeText(activity,
                        "Update cancelled - tap Check for updates to try again", Toast.LENGTH_LONG).show()
                    is UpdateInstaller.Outcome.Failed -> if (alive()) failed(activity, latest, outcome.message)
                    is UpdateInstaller.Outcome.Refused -> if (alive()) failed(activity, latest, "Android didn't take the update")
                    else -> {} // installed: the app is replaced, or Refused twice (shown as a failure by the second try)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (alive()) { dialog.dismiss(); Toast.makeText(activity, "Update cancelled", Toast.LENGTH_SHORT).show() }
                UpdateInstaller.cleanUp(activity.applicationContext)
            } catch (e: Exception) {
                if (alive()) { dialog.dismiss(); failed(activity, latest, e.message ?: "no connection") }
            }
        }
    }

    private fun failed(activity: AppCompatActivity, latest: Latest, why: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Couldn't install the update")
            .setMessage("$why\n\nYou can download RF Sentinel ${latest.version} from GitHub and install it yourself.")
            .setPositiveButton("Open release page") { _, _ -> open(activity, latest.notesUrl) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Android asks each app to be allowed to install apps: one switch, then the update carries on from here. */
    private fun askToAllowInstalls(activity: AppCompatActivity, latest: Latest) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Allow RF Sentinel to install updates")
            .setMessage("Android needs you to allow this once: it opens a switch called \"Allow from this source\". " +
                "Turn it on, come back, and the update starts by itself. Nothing else is installed this way.")
            .setPositiveButton("Open the switch") { _, _ ->
                var left = false
                activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { left = true }
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        if (!left) return
                        activity.lifecycle.removeObserver(this)
                        if (UpdateInstaller.canInstall(activity)) installNow(activity, latest)
                        else Toast.makeText(activity, "Not allowed - the update wasn't started", Toast.LENGTH_LONG).show()
                    }
                })
                runCatching { activity.startActivity(UpdateInstaller.allowInstallsIntent(activity)) }
            }
            .setNeutralButton("Download in the browser") { _, _ -> open(activity, latest.apkUrl ?: latest.notesUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun open(activity: AppCompatActivity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
