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

    fun signalLine(s: DeviceRegistry.Snapshot, now: Long = System.currentTimeMillis()): String {
        val age = (now - s.lastSeen) / 1000
        return "${s.rssi} dBm ${ProximityUtil.band(s.rssi)} · ${DeviceIntel.formatDistance(s.distanceM)} · " +
            if (age < 2) "now" else "${age}s ago"
    }

    /** Headline for the home screen, mirroring the phone's threat banner. */
    fun threat(devices: List<DeviceRegistry.Snapshot>, threshold: Int): Pair<Int, String> {
        val flagged = devices.filter { isFlagged(it) }
        val following = flagged.firstOrNull { it.following }
        val top = flagged.maxByOrNull { it.best!!.confidence }
        return when {
            following != null -> DANGER_COLOR to "${following.best!!.label} may be following you"
            top == null -> CLEAR_COLOR to "All clear - no flagged equipment"
            top.best!!.tier == Tier.STRONG -> DANGER_COLOR to "Strong match: ${top.best!!.label}"
            top.best!!.confidence >= threshold -> Category.BODY_CAM.colorArgb to "Probable: ${top.best!!.label}"
            else -> WEAK_COLOR to "Weak match (verify): ${top.best!!.label}"
        }
    }
}
