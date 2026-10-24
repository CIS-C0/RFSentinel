package com.rfsentinel.app.ui

import android.content.Context
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.util.ProximityUtil
import java.text.DateFormat
import java.util.Date

/** The device list's row look, shared by the main list and saved snapshots so both read the same. */
object DeviceRows {
    const val BLUETOOTH_TAG_COLOR = 0xFF1565C0.toInt()
    const val WIFI_TAG_COLOR = 0xFF00838F.toInt()
    const val WEAK_COLOR = 0xFFB26A00.toInt()
    const val WHITELIST_COLOR = 0xFF6B7B80.toInt()

    /** An ordinary (not flagged) row's tag: the radios it was heard on. */
    fun radioTag(sources: Set<Advert.Source>): String = when {
        Advert.Source.BLE in sources && Advert.Source.WIFI in sources -> "BLUETOOTH + WIFI"
        Advert.Source.WIFI in sources -> "WIFI"
        else -> "BLUETOOTH"
    }

    fun radioColor(sources: Set<Advert.Source>): Int = if (Advert.Source.BLE in sources) BLUETOOTH_TAG_COLOR else WIFI_TAG_COLOR

    /** A match's colour: weak matches amber, others their category's. */
    fun matchColor(context: Context, category: Category, tier: Tier): Int =
        ThemeManager.ink(context, if (tier == Tier.WEAK) WEAK_COLOR else category.colorArgb)

    private val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

    /** A device saved in a snapshot, as the main list shows a live one (heard from-to instead of "now"). */
    fun saved(context: Context, d: TripDeviceEntity): DeviceRow {
        val whitelisted = WhitelistCache.contains(d.mac)
        val category = Category.parse(d.category)?.takeIf { d.confidence > 0 }
        val tier = Tier.of(d.confidence)
        val alert = category != null && d.flagged && !whitelisted
        val sources = setOfNotNull(Advert.Source.entries.firstOrNull { it.name == d.source })
        val type = DeviceIntel.savedType(d.deviceType, d.vendor, d.mac) ?: d.deviceType
        val label = DeviceIntel.savedLabel(d.label, d.deviceType, d.vendor, d.mac)
        val alias = DeviceNames.of(d.mac)
        val tag = when {
            whitelisted -> "WHITELISTED"
            category != null -> "${category.shortTag} · ${tier.label} ${d.confidence}%"
            else -> radioTag(sources)
        }
        val badges = buildList { if (Favorites.contains(d.mac)) add("★") }
        return DeviceRow(
            mac = d.mac,
            title = alias ?: label,
            subtitle = d.mac + (d.vendor?.let { "  ·  $it" } ?: ""),
            meta = listOfNotNull(
                if (alias != null) label
                else if (category != null && d.name != null && d.name != label) "\"${d.name}\"" else type?.takeIf { it != label },
                if (category != null) sources.joinToString("+") { if (it == Advert.Source.BLE) "BLE" else "WiFi" }.ifEmpty { null } else null,
                "${ProximityUtil.band(d.bestRssi)} ${d.bestRssi} dBm",
                timeFmt.format(Date(d.firstSeen)) + "–" + timeFmt.format(Date(d.lastSeen))
            ).joinToString(" · ") + if (badges.isNotEmpty()) "  " + badges.joinToString(" ") else "",
            tag = tag,
            tagColor = when {
                whitelisted -> ThemeManager.ink(context, WHITELIST_COLOR)
                category == null -> ThemeManager.ink(context, radioColor(sources))
                else -> matchColor(context, category, tier)
            },
            highlight = if (alert) matchColor(context, category!!, tier) else null,
            flashing = false,
            bold = alert,
            rssi = d.bestRssi,
            confidence = d.confidence.takeIf { category != null && !whitelisted }
        )
    }
}
