package com.rfsentinel.app.util

import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.KnownDeviceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export › Report for an AI assistant: the last days as one Markdown file a local model (Ollama...) or any chat
 * assistant can read in one go. It opens with what the data means and how to answer, so the model suggests
 * settings changes for the user to make by hand. Tables are capped to fit a small model's context.
 */
object AiReport {
    const val DAYS = 7
    private const val MAX_MATCH_ROWS = 300
    private const val MAX_DEVICE_ROWS = 150

    fun markdown(
        now: Long, version: String, matches: List<DetectionEntity>, devices: List<KnownDeviceEntity>,
        detect: List<Pair<String, Boolean>>, whitelisted: (String) -> Boolean,
    ): String = buildString {
        val since = now - DAYS * 86_400_000L
        val recent = matches.filter { it.timestamp >= since }
        appendLine("# RF Sentinel report: the last $DAYS days")
        appendLine()
        appendLine("Made ${time(now)} by RF Sentinel $version.")
        appendLine()
        appendLine("## How to read this")
        appendLine()
        appendLine("RF Sentinel is a passive scanner: it only listens for Bluetooth, WiFi and radio signals around the phone and flags")
        appendLine("the ones that match police, surveillance and tracker signatures. Positions are where the phone was when it heard")
        appendLine("the device, not where the device is. RSSI is signal strength in dBm: about -40 is very close, -90 is far.")
        appendLine("Times are the phone's local time.")
        appendLine()
        appendLine("Please:")
        appendLine("1. Summarise what happened: where and when matches came up, and what repeats.")
        appendLine("2. Point out weak or rare matches that may still matter, and devices that keep turning up in different places.")
        appendLine("3. Suggest settings changes as a table: setting, now, suggested, why. The user makes them by hand.")
        appendLine("4. Use only what is in these tables. Say so when the data is too thin to tell.")
        appendLine()

        val groups = recent.groupBy { it.mac to it.label }.values.sortedByDescending { it.size }
        appendLine("## Matches (${recent.size} in ${groups.size} device and label pairs)")
        appendLine()
        if (groups.isEmpty()) appendLine("None in the last $DAYS days.")
        else {
            appendLine("| Label | Category | Device | Name | Times | First | Last | Best RSSI | Position at best | Heard by |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            groups.take(MAX_MATCH_ROWS).forEach { g ->
                val best = g.maxBy { it.rssi }
                val name = g.firstNotNullOfOrNull { it.deviceName } ?: g.firstNotNullOfOrNull { it.vendor }
                appendLine(row(best.label, best.category, best.mac + if (whitelisted(best.mac)) " (whitelisted)" else "", name,
                    g.size.toString(), time(g.minOf { it.timestamp }), time(g.maxOf { it.timestamp }), "${best.rssi}",
                    place(best.latitude, best.longitude), g.mapNotNull { it.heardBy }.distinct().joinToString(", ").ifEmpty { "this phone" }))
            }
            if (groups.size > MAX_MATCH_ROWS) appendLine("\n${groups.size - MAX_MATCH_ROWS} more pairs left out, the rarest.")
        }
        appendLine()

        val seen = devices.filter { it.lastSeen >= since }.sortedWith(compareByDescending<KnownDeviceEntity> { it.sessions }.thenByDescending { it.lastSeen })
        appendLine("## Devices heard in the last $DAYS days, most scans first (${seen.size})")
        appendLine()
        if (seen.isEmpty()) appendLine("None.")
        else {
            appendLine("| Device | Name | Vendor | Type | Scans heard in | First ever | Last | Matches ever | Note |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            seen.take(MAX_DEVICE_ROWS).forEach { d ->
                appendLine(row(d.mac + if (whitelisted(d.mac)) " (whitelisted)" else "", d.alias ?: d.name, d.vendor, d.deviceType,
                    d.sessions.toString(), time(d.firstSeen), time(d.lastSeen), d.detectCount.toString(), d.note))
            }
            if (seen.size > MAX_DEVICE_ROWS) appendLine("\n${seen.size - MAX_DEVICE_ROWS} more devices left out, heard in the fewest scans.")
        }
        appendLine()

        appendLine("## What to detect")
        appendLine()
        appendLine("| Category | On |")
        appendLine("|---|---|")
        detect.forEach { (title, on) -> appendLine(row(title, if (on) "yes" else "no")) }
    }

    private fun row(vararg cells: String?) = cells.joinToString(" | ", "| ", " |") { cell(it) }

    /** One table cell: no pipes or line breaks to break the table. */
    internal fun cell(s: String?) = s?.replace("|", "/")?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null } ?: "-"

    private fun place(lat: Double?, lon: Double?) =
        if (lat == null || lon == null) "-" else String.format(Locale.US, "%.5f, %.5f", lat, lon)

    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
