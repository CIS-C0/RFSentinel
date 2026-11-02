package com.rfsentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Starts the chosen music app playing when the phone connects to the car's Bluetooth, even if the app isn't open:
 * connects to its media service the way Android Auto does and presses Play. Apps that refuse other callers get a
 * Play button press sent straight to them instead. Android doesn't let an app open another app's screen from the
 * background, so nothing is opened.
 */
object CarMusic {

    private const val TAG = "CarMusic"
    /** The car's audio link comes up a moment after the Bluetooth connection: play then, not on the phone speaker. */
    private const val AUDIO_DELAY_MS = 3_000L
    private const val TIMEOUT_MS = 8_000L

    /** Music apps on the phone: (package, name), sorted by name. */
    fun apps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).map { it.serviceInfo.packageName }
        val buttons = pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0).map { it.activityInfo.packageName }
        return (services + buttons).distinct().filter { it != context.packageName }
            .map { p -> p to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p) }
            .sortedBy { it.second.lowercase() }
    }

    fun label(context: Context, pkg: String): String =
        runCatching { context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrNull() ?: pkg

    /** Plays [pkg]; [pending] (from the Bluetooth broadcast) is finished once done or given up. */
    fun play(context: Context, pkg: String, pending: BroadcastReceiver.PendingResult?) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var finished = false
        fun finish(why: String) {
            if (finished) return
            finished = true
            Log.i(TAG, why)
            runCatching { browser?.disconnect() }
            runCatching { pending?.finish() }
        }
        main.postDelayed({
            val service = app.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
                .firstOrNull()?.serviceInfo
            if (service == null) { pressPlay(app, pkg); finish("Play pressed (no media service)"); return@postDelayed }
            browser = MediaBrowser(app, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    runCatching { MediaController(app, browser!!.sessionToken).transportControls.play() }
                        .onFailure { pressPlay(app, pkg) }
                    // Kept connected a moment so the app has started its playback before we let go.
                    main.postDelayed({ finish("Playing") }, 1_500)
                }
                override fun onConnectionFailed() { pressPlay(app, pkg); finish("Play pressed (media service refused)") }
                override fun onConnectionSuspended() = finish("Media service stopped")
            }, null)
            runCatching { browser!!.connect() }.onFailure { pressPlay(app, pkg); finish("Play pressed (could not connect)") }
        }, AUDIO_DELAY_MS)
        main.postDelayed({ finish("Gave up waiting for the music app") }, AUDIO_DELAY_MS + TIMEOUT_MS)
    }

    /** A Play button press (down, up) sent only to [pkg]'s media button receiver: it starts the app if needed. */
    private fun pressPlay(context: Context, pkg: String) {
        val receiver = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0)
            .firstOrNull()?.activityInfo ?: return
        val t = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching {
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(receiver.packageName, receiver.name))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        }.onFailure { Log.w(TAG, "Play press refused", it) }
    }
}
