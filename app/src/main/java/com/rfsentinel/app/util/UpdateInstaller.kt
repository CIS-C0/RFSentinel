package com.rfsentinel.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.R
import com.rfsentinel.app.RFSentinelApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads a new release and installs it over this app in one go, the way open-source app managers
 * (Obtainium, Morphe Manager...) do: the APK is fetched into the app's own cache, checked, written into an
 * Android PackageInstaller session and committed. Android then shows its usual "Update this app?" card
 * (or none, where it lets the app that installed it update itself), and replaces the app.
 *
 * Needs Android's "Install unknown apps" allowed for RF Sentinel ([canInstall], [allowInstallsIntent]).
 */
object UpdateInstaller {

    private const val ACTION_STATUS = "com.rfsentinel.app.UPDATE_INSTALL_STATUS"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4120

    /** How an install ended. */
    sealed class Outcome {
        object Installed : Outcome()
        /** The "Update?" card was dismissed. */
        object Cancelled : Outcome()
        /** Android refused an update without its card (some phones do): ask again with the card. */
        object Refused : Outcome()
        class Failed(val message: String) : Outcome()
    }

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Outcome>>()
    private val cardShown = ConcurrentHashMap.newKeySet<Int>()

    // ---- permission ----------------------------------------------------------------------------------

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" switch for this app. */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- the download -----------------------------------------------------------------------------------

    private fun dir(context: Context) = File(context.cacheDir, "updates")

    /** Drops downloaded updates left over (the app starts fresh after an install, or a download was cut off). */
    fun cleanUp(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * Fetches [url] into the app's cache and checks it: the size and SHA-256 GitHub lists for the file, and that
     * it is this app, newer than the one installed, and signed by the same key (so a wrong file stops here, with
     * a reason, instead of at Android's installer). [onProgress] gets bytes so far and the total (0 = unknown).
     */
    suspend fun download(context: Context, url: String, version: String, expectedSize: Long, sha256: String?,
                         onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = dir(context).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val part = File(folder, "RFSentinel-$version.apk.part")
        val file = File(folder, "RFSentinel-$version.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("The download server answered ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            val sum = MessageDigest.getInstance("SHA-256")
            var done = 0L; var lastReport = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sum.update(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) { lastReport = now; onProgress(done, total) }
                    }
                }
            }
            onProgress(done, total)
            if (expectedSize > 0 && done != expectedSize) throw IllegalStateException("The download was cut short ($done of $expectedSize bytes)")
            val hex = sum.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != null && !hex.equals(sha256, ignoreCase = true)) throw IllegalStateException("The download doesn't match the checksum GitHub lists for it")
            if (!part.renameTo(file)) throw IllegalStateException("Couldn't keep the downloaded file")
            checkArchive(context, file)?.let { file.delete(); throw IllegalStateException(it) }
            file
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Why this APK can't be installed over the app, in words; null when it can. */
    @Suppress("DEPRECATION")
    internal fun checkArchive(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file isn't a valid app"
        if (archive.packageName != context.packageName) return "The downloaded file is a different app (${archive.packageName})"
        val mine = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return null
        val newer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode > mine.longVersionCode else archive.versionCode > mine.versionCode
        if (!newer) return "The downloaded version isn't newer than the one installed"
        val a = signers(archive); val b = signers(mine)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) return "The downloaded file is signed with a different key than this app, so Android would refuse it"
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()

    // ---- the install ------------------------------------------------------------------------------------

    /**
     * Installs [apk] over this app. Where Android allows it (12+, an app that installed itself before) the update
     * goes through without a card; a phone that refuses that is asked again the ordinary way. Suspends until the
     * card is answered - when the install succeeds the app is replaced and this never returns.
     */
    suspend fun install(context: Context, apk: File): Outcome {
        val first = commit(context.applicationContext, apk, requireUserAction = false)
        return if (first is Outcome.Refused) commit(context.applicationContext, apk, requireUserAction = true) else first
    }

    private suspend fun commit(app: Context, apk: File, requireUserAction: Boolean): Outcome {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            setOriginatingUid(Process.myUid())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(
                if (requireUserAction) PackageInstaller.SessionParams.USER_ACTION_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val result = CompletableDeferred<Outcome>()
        waiting[id] = result
        if (requireUserAction) cardShown += id
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(app, UpdateStatusReceiver::class.java).setAction(ACTION_STATUS)
                    val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pending.intentSender)
                }
            }
            return result.await()
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        } finally {
            waiting.remove(id); cardShown.remove(id)
            runCatching { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        }
    }

    /** Android's answer for a session (from [UpdateStatusReceiver]). */
    internal fun onStatus(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            cardShown += id
            @Suppress("DEPRECATION")
            val card = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            showCard(context.applicationContext, card)
            return
        }
        outcomeOf(status, message, id in cardShown)?.let { waiting[id]?.complete(it) }
    }

    /** What an install status means; null while Android is still waiting for the card to be answered. */
    internal fun outcomeOf(status: Int, message: String?, cardWasShown: Boolean): Outcome? = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> null
        PackageInstaller.STATUS_SUCCESS -> Outcome.Installed
        PackageInstaller.STATUS_FAILURE_ABORTED -> if (cardWasShown) Outcome.Cancelled else Outcome.Refused
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            Outcome.Failed(friendly(message) ?: "Android says this file doesn't fit the installed app (a different signing key or an older version)")
        PackageInstaller.STATUS_FAILURE_BLOCKED, PackageInstaller.STATUS_FAILURE_STORAGE ->
            Outcome.Failed(friendly(message) ?: "Android blocked the install or has no room for it")
        else -> Outcome.Failed(friendly(message) ?: "The install failed")
    }

    private fun friendly(message: String?) = message?.takeIf { it.isNotBlank() && !it.startsWith("INSTALL_") }

    /** Android's "Update?" card: straight away while the app is on screen, else as a notification to tap. */
    private fun showCard(app: Context, card: Intent) {
        if (RFSentinelApp.inForeground) {
            runCatching { app.startActivity(card.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(app, 0, card.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setContentTitle("RF Sentinel update is ready")
            .setContentText("Tap to install it")
            .setContentIntent(tap).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
}

/** Receives Android's answers about the update install session. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = UpdateInstaller.onStatus(context, intent)
}
