package com.rfsentinel.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.rfsentinel.app.R
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.IgnoredCameras
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.esp.EspBoards
import com.rfsentinel.app.esp.OuiSpyBle
import com.rfsentinel.app.receiver.AlertActionReceiver
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.usb.UsbWifi
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

/**
 * RF Sentinel as an Android Auto media app: four tabs (Threats, Cameras, Alerts, Status)
 * and a "now playing" card with the threat headline plus Scan and Mute buttons.
 *
 * A real car only runs the Car App Library screens ([RFSentinelCarAppService]) when the
 * app comes from a trusted store, but Android Auto's "Unknown sources" setting covers
 * media apps, so the GitHub APK gets this screen in the car. Nothing is ever played:
 * the session takes no audio focus and stays inactive, so music keeps playing and keeps
 * the steering-wheel buttons.
 */
class RFSentinelMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())

    /** Latest children per tab, and what they showed (to notify Android Auto only on changes). */
    private val children = HashMap<String, List<MediaItem>>()
    private val childKeys = HashMap<String, Any>()
    private var metaKey: Any? = null
    private var stateKey: Any? = null

    /** What the "now playing" card shows instead of the headline, and since when. */
    private var selected: String? = null
    private var selectedAt = 0L

    private val dots = HashMap<Int, Bitmap>()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "RFSentinel").apply {
            setCallback(callback)
            // Inactive on purpose: Android routes media buttons only to active sessions.
            isActive = false
        }
        sessionToken = session.sessionToken
        refresh()
        handler.postDelayed(tick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // No "resume playback" card in the phone's quick settings.
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) return null
        // Detections are only shown to Android Auto (and the car's own media app).
        if (clientUid != Process.myUid() && clientPackageName !in ALLOWED_CALLERS) return null
        val extras = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val items = if (parentId == ROOT) tabs() else children[parentId] ?: build(parentId).also { children[parentId] = it }
        result.sendResult(items.toMutableList())
    }

    // ---- tabs ------------------------------------------------------------------------

    private fun tabs(): List<MediaItem> = listOf(
        browsable(TAB_THREATS, "Threats", R.drawable.ic_car_warning),
        browsable(TAB_CAMERAS, "Cameras", R.drawable.ic_car_navigate),
        browsable(TAB_ALERTS, "Alerts", R.drawable.ic_car_history),
        browsable(TAB_STATUS, "Status", R.drawable.ic_car_more)
    )

    private fun build(tab: String): List<MediaItem> = when (tab) {
        TAB_THREATS -> threats()
        TAB_CAMERAS -> cameras()
        TAB_ALERTS -> alerts()
        TAB_STATUS -> status()
        else -> emptyList()
    }

    private fun threats(): List<MediaItem> {
        if (!ScanForegroundService.isRunning) {
            return listOf(playable(ACTION_SCAN, "Not scanning", "Tap to start scanning", dot(CarUi.WEAK_COLOR)))
        }
        val flagged = CarUi.sorted(DeviceRegistry.snapshot()).filter { CarUi.isFlagged(it) }.take(MAX_ITEMS)
        if (flagged.isEmpty()) {
            return listOf(playable(INFO_CLEAR, "All clear", "No flagged equipment nearby", dot(CarUi.CLEAR_COLOR)))
        }
        return flagged.map { s ->
            playable(DEVICE + s.mac, CarUi.title(s),
                CarUi.tagLine(s) + " · " + com.rfsentinel.app.detect.DeviceIntel.formatDistance(s.distanceM),
                dot(CarUi.colorFor(s)))
        }
    }

    private fun cameras(): List<MediaItem> {
        val me = CarUi.currentLocation(this)
        val cams = AlprStore.cameras
        if (me == null || cams.isEmpty()) {
            return listOf(playable(INFO_NO_CAMERAS,
                if (cams.isEmpty()) "No cameras downloaded" else "Waiting for a GPS fix",
                if (cams.isEmpty()) "Download them on the phone: Settings > Known cameras" else "Cameras appear once the phone has a position",
                dot(CarUi.WEAK_COLOR)))
        }
        val ahead = CarUi.nextCamera(this)?.first
        val near = cams.asSequence()
            .map { it to DeviceRegistry.metersBetween(me.latitude, me.longitude, it.lat, it.lon) }
            .filter { it.second <= CAMERA_RADIUS_M && !IgnoredCameras.contains(this, it.first.osmId) }
            .sortedBy { it.second }
            .take(MAX_ITEMS)
            .toList()
        if (near.isEmpty()) {
            return listOf(playable(INFO_NO_CAMERAS, "No known cameras within 5 km", null, dot(CarUi.CLEAR_COLOR)))
        }
        val ordered = near.sortedByDescending { it.first.osmId == ahead?.osmId }
        return ordered.map { (cam, d) ->
            playable(CAMERA + cam.osmId, cam.label,
                (if (cam.osmId == ahead?.osmId) "Ahead · " else "") + NearbyMapScreen.distanceText(d) +
                    (cam.maxspeed?.let { " · $it km/h" } ?: "") + (cam.operator?.let { " · $it" } ?: ""),
                dot(NearbyMapScreen.cameraColor(cam.type)))
        }
    }

    private fun alerts(): List<MediaItem> {
        val now = System.currentTimeMillis()
        val list = AlertLog.recent(this).take(MAX_ITEMS)
        if (list.isEmpty()) return listOf(playable(INFO_NO_ALERTS, "No alerts yet", null, dot(CarUi.CLEAR_COLOR)))
        return list.map { e ->
            playable(ALERT + e.key + "|" + e.time, (if (e.following) "FOLLOWING · " else "") + e.label,
                "${e.categoryEnum?.shortTag ?: "Alert"} · ${CarUi.ageText(now - e.time)}", dot(alertColor(e)))
        }
    }

    private fun status(): List<MediaItem> {
        val running = ScanForegroundService.isRunning
        val devices = if (running) DeviceRegistry.snapshot() else emptyList()
        val silenced = CarUi.silencedText(this)
        return listOfNotNull(
            playable(ACTION_SCAN, if (running) "Stop scanning" else "Start scanning",
                if (running) "Scanning · ${devices.size} nearby · ${devices.count { CarUi.isFlagged(it) }} flagged" else "Not scanning",
                dot(if (running) CarUi.CLEAR_COLOR else CarUi.WEAK_COLOR)),
            playable(ACTION_MUTE, if (silenced.isEmpty()) "Mute alerts for 30 minutes" else "Turn alert sound back on",
                silenced.ifEmpty { "Alert sound and voice are on" }, dot(if (silenced.isEmpty()) CarUi.CLEAR_COLOR else CarUi.WEAK_COLOR)),
            CellsScreen.summary()?.let { playable(INFO_CELLS, "Cell towers", it, dot(0xFF7B1FA2.toInt())) },
            playable(INFO_HARDWARE, "Hardware", hardwareSummary(), dot(0xFF0B5C63.toInt()))
        )
    }

    private fun hardwareSummary(): String = listOfNotNull(
        EspBoards.status.takeIf { it.isNotBlank() },
        UsbWifi.status.takeIf { it.isNotBlank() },
        SdrRadio.status.takeIf { it.isNotBlank() },
        OuiSpyBle.status.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifEmpty { "Phone radios only" }

    // ---- now playing -------------------------------------------------------------------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when (mediaId) {
                ACTION_SCAN -> toggleScan()
                ACTION_MUTE -> toggleMute()
                null -> Unit
                else -> select(mediaId)
            }
            refresh()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_SCAN -> toggleScan()
                ACTION_MUTE -> toggleMute()
            }
            refresh()
        }

        override fun onSkipToNext() = step(1)
        override fun onSkipToPrevious() = step(-1)
    }

    private fun select(id: String) {
        selected = id
        selectedAt = System.currentTimeMillis()
    }

    /** Next / previous flagged device on the card. */
    private fun step(by: Int) {
        val ids = CarUi.sorted(DeviceRegistry.snapshot()).filter { CarUi.isFlagged(it) }.map { DEVICE + it.mac }
        if (ids.isEmpty()) return
        // Nothing picked yet: next starts at the top, previous at the bottom.
        val from = ids.indexOf(selected).takeIf { it >= 0 } ?: if (by > 0) -1 else 0
        select(ids[(from + by).mod(ids.size)])
        refresh()
    }

    private fun toggleScan() {
        if (ScanForegroundService.isRunning) {
            ScanForegroundService.stop(this)
            message("Scanning stopped")
            return
        }
        if (Permissions.missingRequired(this).isNotEmpty()) {
            message("Open RF Sentinel on the phone", "Location and Nearby devices are needed to scan")
            return
        }
        try {
            ScanForegroundService.start(this)
            message("Scanning started",
                if (HomeScreen.phoneScreenOn(this)) null else "Phone screen off: Android limits Bluetooth scanning")
        } catch (e: Exception) {
            message("Couldn't start scanning", "Open RF Sentinel on the phone")
        }
    }

    private fun toggleMute() {
        if (CarUi.silencedText(this).isEmpty()) {
            AlertActionReceiver.snooze(this)
            message("Alerts muted for 30 minutes")
        } else {
            Prefs.setAlertsMuted(this, false)
            Prefs.setAlertsSnoozedUntil(this, 0L)
            message("Alert sound on")
        }
    }

    private var messageText: Pair<String, String?>? = null

    private fun message(title: String, detail: String? = null) {
        messageText = title to detail
        select(MESSAGE)
    }

    private data class Card(val id: String, val title: String, val subtitle: String?, val detail: String?,
                            val color: Int, val big: String, val small: String?)

    private fun card(now: Long): Card {
        val pick = selected?.takeIf { now - selectedAt < (if (it == MESSAGE) MESSAGE_MS else SELECTION_MS) }
        if (pick == null) selected = null
        return pick?.let { cardFor(it, now) } ?: headline()
    }

    private fun headline(): Card {
        val running = ScanForegroundService.isRunning
        if (!running) return Card(HEADLINE, "Not scanning", "Tap Start scanning below", null, CarUi.WEAK_COLOR, "OFF", "RF Sentinel")
        val devices = DeviceRegistry.snapshot()
        val (color, text) = CarUi.threat(devices, Prefs.alertThreshold(this))
        val top = devices.filter { CarUi.isFlagged(it) }.maxByOrNull { it.best!!.confidence }
        val line = listOf("${devices.size} nearby · ${devices.count { CarUi.isFlagged(it) }} flagged", CarUi.silencedText(this))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        return Card(HEADLINE, text, line, top?.let { CarUi.signalLine(it) }, color,
            top?.best?.category?.shortTag ?: "CLEAR", top?.best?.let { "${it.tier.label.substringBefore(" -")} ${it.confidence}%" })
    }

    private fun cardFor(id: String, now: Long): Card? = when {
        id == MESSAGE -> messageText?.let { (t, d) -> Card(id, t, d, null, 0xFF0B5C63.toInt(), "RF SENTINEL", null) }
        id.startsWith(DEVICE) -> DeviceRegistry.get(id.removePrefix(DEVICE))?.let { s ->
            Card(id, CarUi.title(s), CarUi.tagLine(s), CarUi.signalLine(s, now), CarUi.colorFor(s),
                s.best?.category?.shortTag ?: "DEVICE", com.rfsentinel.app.detect.DeviceIntel.formatDistance(s.distanceM))
        }
        id.startsWith(CAMERA) -> AlprStore.cameras.firstOrNull { it.osmId == id.removePrefix(CAMERA) }?.let { cam ->
            val me = CarUi.currentLocation(this)
            val d = me?.let { DeviceRegistry.metersBetween(it.latitude, it.longitude, cam.lat, cam.lon) }
            Card(id, cam.label, listOfNotNull(d?.let { NearbyMapScreen.distanceText(it) + " away" }, cam.operator).joinToString(" · "),
                cam.maxspeed?.let { "Limit $it km/h" }, NearbyMapScreen.cameraColor(cam.type),
                NearbyMapScreen.cameraLabel(cam.type).let { when (it) { "S" -> "SPEED"; "R" -> "RED LIGHT"; else -> "PLATES" } },
                d?.let { NearbyMapScreen.distanceText(it) })
        }
        id.startsWith(ALERT) -> AlertLog.recent(this).firstOrNull { ALERT + it.key + "|" + it.time == id }?.let { e ->
            Card(id, (if (e.following) "FOLLOWING · " else "") + e.label,
                "${e.categoryEnum?.shortTag ?: "Alert"} · ${CarUi.ageText(now - e.time)}", e.evidence.take(160),
                alertColor(e), e.categoryEnum?.shortTag ?: "ALERT", e.tierEnum?.label?.substringBefore(" -"))
        }
        id == INFO_CELLS -> Card(id, "Cell towers", CellsScreen.summary() ?: "No cell information", null,
            0xFF7B1FA2.toInt(), "CELL", null)
        id == INFO_HARDWARE -> Card(id, "Hardware", hardwareSummary(), null, 0xFF0B5C63.toInt(), "HARDWARE", null)
        else -> null
    }

    private fun alertColor(e: AlertLog.Entry): Int =
        if (e.tierEnum == Tier.WEAK) CarUi.WEAK_COLOR else e.categoryEnum?.colorArgb ?: CarUi.DANGER_COLOR

    // ---- refresh -------------------------------------------------------------------------

    private fun refresh() {
        val now = System.currentTimeMillis()
        for (tab in TABS) {
            val items = build(tab)
            val key = items.map { listOf(it.mediaId, it.description.title, it.description.subtitle) }
            children[tab] = items
            if (childKeys[tab] != key) {
                childKeys[tab] = key
                notifyChildrenChanged(tab)
            }
        }

        val c = card(now)
        if (c != metaKey) {
            metaKey = c
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, c.id)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, c.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, c.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, c.subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, c.subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, c.detail)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art(c.color, c.big, c.small))
                    .build()
            )
        }

        val running = ScanForegroundService.isRunning
        val muted = CarUi.silencedText(this).isNotEmpty()
        val state = running to muted
        if (state != stateKey) {
            stateKey = state
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    // No play/pause: there's nothing to play, and the steering-wheel buttons stay with the music.
                    .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .addCustomAction(PlaybackStateCompat.CustomAction.Builder(
                        ACTION_SCAN, if (running) "Stop scanning" else "Start scanning",
                        if (running) R.drawable.ic_car_stop else R.drawable.ic_car_play).build())
                    .addCustomAction(PlaybackStateCompat.CustomAction.Builder(
                        ACTION_MUTE, if (muted) "Sound on" else "Mute 30 min",
                        if (muted) R.drawable.ic_car_volume else R.drawable.ic_car_snooze).build())
                    .build()
            )
        }
    }

    // ---- images ----------------------------------------------------------------------------

    /** The "now playing" picture: the category colour with its tag, e.g. "BODY CAM" / "strong 85%". */
    private fun art(color: Int, big: String, small: String?): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 46f
        }
        while (p.measureText(big) > size - 28 && p.textSize > 16f) p.textSize -= 2f
        canvas.drawText(big, size / 2f, size * 0.52f, p)
        if (!small.isNullOrBlank()) {
            p.typeface = Typeface.DEFAULT
            p.textSize = 26f
            while (p.measureText(small) > size - 28 && p.textSize > 12f) p.textSize -= 1f
            canvas.drawText(small, size / 2f, size * 0.74f, p)
        }
        return bmp
    }

    /** A small coloured dot for list rows (cached per colour). */
    private fun dot(color: Int): Bitmap = dots.getOrPut(color) {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size * 0.42f, p)
        bmp
    }

    private fun icon(@DrawableRes res: Int): Bitmap? = runCatching {
        ContextCompat.getDrawable(this, res)!!.mutate().apply { setTint(Color.WHITE) }.toBitmap(96, 96)
    }.getOrNull()

    private fun browsable(id: String, title: String, @DrawableRes res: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setIconBitmap(icon(res)).build(),
        MediaItem.FLAG_BROWSABLE
    )

    private fun playable(id: String, title: String, subtitle: String?, icon: Bitmap?) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).setIconBitmap(icon).build(),
        MediaItem.FLAG_PLAYABLE
    )

    companion object {
        private const val ROOT = "root"
        private const val TAB_THREATS = "threats"
        private const val TAB_CAMERAS = "cameras"
        private const val TAB_ALERTS = "alerts"
        private const val TAB_STATUS = "status"
        private val TABS = listOf(TAB_THREATS, TAB_CAMERAS, TAB_ALERTS, TAB_STATUS)

        private const val DEVICE = "dev:"
        private const val CAMERA = "cam:"
        private const val ALERT = "alert:"
        private const val ACTION_SCAN = "act:scan"
        private const val ACTION_MUTE = "act:mute"
        private const val INFO_CLEAR = "info:clear"
        private const val INFO_NO_CAMERAS = "info:nocams"
        private const val INFO_NO_ALERTS = "info:noalerts"
        private const val INFO_CELLS = "info:cells"
        private const val INFO_HARDWARE = "info:hw"
        private const val HEADLINE = "headline"
        private const val MESSAGE = "message"

        private const val MAX_ITEMS = 15
        private const val CAMERA_RADIUS_M = 5_000.0
        private const val REFRESH_MS = 3_000L
        /** A picked item stays on the card this long, then the headline returns. */
        private const val SELECTION_MS = 60_000L
        private const val MESSAGE_MS = 6_000L

        /** Android Auto (phone projection) and the car's own media app on Android Automotive. */
        private val ALLOWED_CALLERS = setOf(
            "com.google.android.projection.gearhead",
            "com.android.car.media"
        )
    }
}
