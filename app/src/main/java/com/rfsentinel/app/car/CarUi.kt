package com.rfsentinel.app.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.ProximityUtil

/** Shared helpers for the Android Auto screens. */
object CarUi {

    const val WEAK_COLOR = 0xFFB26A00.toInt()
    const val CLEAR_COLOR = 0xFF2E7D32.toInt()
    const val DANGER_COLOR = 0xFFB3261E.toInt()

    fun icon(context: CarContext, @DrawableRes res: Int, color: Int? = null): CarIcon {
        val b = CarIcon.Builder(IconCompat.createWithResource(context, res))
        if (color != null) b.setTint(CarColor.createCustom(color, color))
        return b.build()
    }

    /** Where the driver is: the scanner's latest fix, else the phone's last known position. */
    @android.annotation.SuppressLint("MissingPermission") // checked via Permissions
    fun currentLocation(context: android.content.Context): android.location.Location? {
        // Debug demo only: a made-up position, so screenshots never show the real one.
        com.rfsentinel.app.ui.DemoData.fakeLocation?.let { return it }
        // Navigation keeps its own 1 s GPS fixes; use whichever fix is newer.
        val nav = com.rfsentinel.app.nav.Navigator.lastFix
        val scan = com.rfsentinel.app.service.ScanForegroundService.lastFix
        listOfNotNull(nav, scan).maxByOrNull { it.elapsedRealtimeNanos }?.let { return it }
        if (!com.rfsentinel.app.util.Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = context.getSystemService(android.location.LocationManager::class.java) ?: return null
        return listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Hands a spot to the car's navigation app (Google Maps, Waze...). */
    fun navigateTo(context: CarContext, lat: Double, lon: Double) {
        try {
            context.startCarApp(
                android.content.Intent(CarContext.ACTION_NAVIGATE,
                    android.net.Uri.parse(String.format(java.util.Locale.US, "geo:%.7f,%.7f", lat, lon)))
            )
        } catch (e: Exception) {
            androidx.car.app.CarToast.makeText(context, "No navigation app available", androidx.car.app.CarToast.LENGTH_SHORT).show()
        }
    }

    /** Max rows the car allows in a list (typically 6 while driving). */
    fun listLimit(context: CarContext): Int = runCatching {
        context.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceAtLeast(1)

    fun paneLimit(context: CarContext): Int = runCatching {
        context.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
    }.getOrDefault(4).coerceAtLeast(1)

    fun isFlagged(s: DeviceRegistry.Snapshot) = s.best != null && !WhitelistCache.contains(s.mac)

    fun colorFor(s: DeviceRegistry.Snapshot): Int {
        val best = s.best ?: return 0xFF0B5C63.toInt()
        if (WhitelistCache.contains(s.mac)) return 0xFF6B7B80.toInt()
        return if (best.tier == Tier.WEAK) WEAK_COLOR else best.category.colorArgb
    }

    /** Same order as the phone list: flagged & following first, then strongest evidence, then closest. */
    fun sorted(list: List<DeviceRegistry.Snapshot>): List<DeviceRegistry.Snapshot> =
        list.sortedWith(
            compareByDescending<DeviceRegistry.Snapshot> { isFlagged(it) }
                .thenByDescending { it.following }
                .thenByDescending { it.best?.confidence ?: 0 }
                .thenByDescending { it.rssi }
        )

    fun title(s: DeviceRegistry.Snapshot): String = s.best?.label ?: s.name ?: s.deviceType

    fun tagLine(s: DeviceRegistry.Snapshot): String {
        val best = s.best
        return when {
            WhitelistCache.contains(s.mac) -> "Whitelisted · ${s.deviceType}"
            best != null -> (if (s.following) "FOLLOWING · " else "") +
                "${best.category.shortTag} · ${best.tier.label} ${best.confidence}%"
            else -> s.deviceType + (s.vendor?.let { " · $it" } ?: "")
        }
    }

    /** Which radio heard it: an ESP32 board, a USB WiFi adapter, or the phone (Bluetooth / WiFi). */
    fun sourceTag(s: DeviceRegistry.Snapshot): String = when {
        com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac) -> "USB WiFi"
        com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac) -> "ESP32"
        s.source == com.rfsentinel.app.detect.Advert.Source.WIFI -> "WiFi"
        else -> "Bluetooth"
    }

    /** Which radio heard it first (the line above can be cut off), then signal, distance and age. */
    fun signalLine(s: DeviceRegistry.Snapshot, now: Long = System.currentTimeMillis()): String =
        "${sourceTag(s)} · ${s.rssi} dBm ${ProximityUtil.band(s.rssi)} · ${DeviceIntel.formatDistance(s.distanceM)} · " + ageText(now - s.lastSeen)

    /** Coarse on purpose: a screen only refreshes when what it shows changes. */
    fun ageText(ms: Long): String = when {
        ms < 5_000 -> "now"
        ms < 60_000 -> "under a minute ago"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        else -> "${ms / 3_600_000} h ago"
    }

    /** What a device row shows, for a screen's refresh key (3 dB signal steps). */
    fun rowKey(s: DeviceRegistry.Snapshot, now: Long): String =
        "${s.mac}|${title(s)}|${tagLine(s)}|${sourceTag(s)}|${s.rssi / 3}|${ageText(now - s.lastSeen)}|${s.following}"

    /**
     * The next known camera on your way (see KnownCameras.ahead): needs a GPS heading
     * while moving; parked, the nearest within 1 km. Silenced cameras are left out.
     */
    fun nextCamera(context: android.content.Context): Pair<com.rfsentinel.app.alpr.KnownCamera, Double>? {
        val cams = com.rfsentinel.app.alpr.AlprStore.cameras
        if (cams.isEmpty()) return null
        val me = currentLocation(context) ?: return null
        val moving = me.hasBearing() && me.hasSpeed() && me.speed > 2f
        return com.rfsentinel.app.alpr.KnownCameras.ahead(
            cams, me.latitude, me.longitude, if (moving) me.bearing else null
        ) { com.rfsentinel.app.alpr.IgnoredCameras.contains(context, it.osmId) }
    }

    /** Alert sound state for the home screen and the More screen ("" when sound is on). */
    fun silencedText(context: android.content.Context, now: Long = System.currentTimeMillis()): String {
        val until = com.rfsentinel.app.util.Prefs.alertsSnoozedUntil(context)
        return when {
            com.rfsentinel.app.util.Prefs.alertsMuted(context) -> "Alert sound muted"
            now < until -> "Alerts snoozed until " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
            else -> ""
        }
    }

    /** Headline for the home screen, mirroring the phone's threat banner. */
    fun threat(
        devices: List<DeviceRegistry.Snapshot>, threshold: Int,
        ambient: com.rfsentinel.app.online.AmbientThreats.Threat? = null
    ): Pair<Int, String> {
        val flagged = devices.filter { isFlagged(it) }
        val following = flagged.firstOrNull { it.following }
        val top = flagged.maxByOrNull { it.best!!.confidence }
        // A camera close by, a police aircraft or a Waze report, when it outranks the devices.
        if (ambient != null && following == null && ambient.score > (top?.best?.confidence ?: 0)) {
            return when {
                ambient.score >= 85 -> DANGER_COLOR
                ambient.score >= threshold -> Category.BODY_CAM.colorArgb
                else -> WEAK_COLOR
            } to ambient.label
        }
        return when {
            following != null -> DANGER_COLOR to "${following.best!!.label} may be following you"
            top == null -> CLEAR_COLOR to "All clear - no flagged equipment"
            top.best!!.tier == Tier.STRONG -> DANGER_COLOR to "Strong match: ${top.best!!.label}"
            top.best!!.confidence >= threshold -> Category.BODY_CAM.colorArgb to "Probable: ${top.best!!.label}"
            else -> WEAK_COLOR to "Weak match (verify): ${top.best!!.label}"
        }
    }
}
