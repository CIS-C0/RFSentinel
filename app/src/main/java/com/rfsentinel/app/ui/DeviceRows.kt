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

    private val STATUS = listOf("FAR" to 0xFF2E7D32.toInt(), "NEARBY" to 0xFFB8A000.toInt(),
        "CLOSE" to 0xFFE65100.toInt(), "VERY CLOSE" to 0xFFC62828.toInt())

    /**
     * Settings › Appearance › Status colours: a box on a flagged row, green far, yellow nearby, orange close, red very
     * close, from its last 3 signal readings; one step up, as CLOSING, when that is 6 dB or more over what it was
     * 15 s to 1 min before. Signal is a rough distance: a hint beside the flashing rows, which it never replaces.
     */
    fun status(history: List<com.rfsentinel.app.service.DeviceRegistry.Sample>): Pair<String, Int>? {
        if (history.isEmpty()) return null
        fun median(l: List<Int>) = l.sorted()[l.size / 2]
        val level = median(history.takeLast(3).map { it.rssi })
        val last = history.last().time
        val before = history.filter { last - it.time in 15_000L..60_000L }.map { it.rssi }
        val closing = before.isNotEmpty() && level - median(before) >= 6
        val step = (when { level >= -55 -> 3; level >= -67 -> 2; level >= -80 -> 1; else -> 0 } + if (closing) 1 else 0).coerceAtMost(3)
        return (if (closing) "CLOSING" else STATUS[step].first) to STATUS[step].second
    }

    /** A match's tag, the same on every kind of row: "BODY CAM · strong 90%". */
    fun matchTag(name: String, confidence: Int) = "$name · ${Tier.of(confidence).label} $confidence%"

    /** The network name a flagged router's row shows next to its address, or null for every other row. */
    fun routerSsid(flagged: Boolean, sources: Set<Advert.Source>, name: String?, type: String?): String? =
        name?.takeIf { flagged && Advert.Source.WIFI in sources && type != null && DeviceIntel.isRouter(type) }

    /** A row's second line: the address, then [ssid] when it has one, then [vendor]. */
    fun subtitle(mac: String, ssid: String?, vendor: String?) =
        listOfNotNull(mac, ssid?.let { "\"$it\"" }, vendor).joinToString(" · ")

    /** How long ago, the same on every row. */
    fun ago(sec: Long) = when {
        sec < 2 -> "now"
        sec < 60 -> "${sec}s ago"
        else -> "${sec / 60} min ago"
    }

    /** An ordinary (not flagged) row's tag: the radios it was heard on. */
    fun radioTag(sources: Set<Advert.Source>, lora: Boolean = false): String = when {
        lora -> "LORA"
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
            category != null -> matchTag(category.shortTag, d.confidence)
            else -> radioTag(sources)
        }
        val badges = buildList { if (Favorites.contains(d.mac)) add("★") }
        val ssid = routerSsid(category != null, sources, d.name, type)
        return DeviceRow(
            mac = d.mac,
            title = alias ?: label,
            subtitle = subtitle(d.mac, ssid, d.vendor),
            meta = (listOfNotNull(
                if (alias != null) label
                else if (ssid != null) null
                else if (category != null && d.name != null && d.name != label) "\"${d.name}\"" else type?.takeIf { it != label },
                if (category != null) sources.joinToString("+") { if (it == Advert.Source.BLE) "BLE" else "WiFi" }.ifEmpty { null } else null,
                "${ProximityUtil.band(d.bestRssi)} ${d.bestRssi} dBm",
                timeFmt.format(Date(d.firstSeen)) + "–" + timeFmt.format(Date(d.lastSeen))
            ) + badges).joinToString(" · "),
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
