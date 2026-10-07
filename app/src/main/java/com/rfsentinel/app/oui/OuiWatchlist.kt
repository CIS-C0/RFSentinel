package com.rfsentinel.app.oui

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.util.MacUtil
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The editable watchlist: bundled regional preset JSON assets plus user-added
 * custom entries (SharedPreferences). Entries match by MAC prefix, exact MAC,
 * name substring or manufacturer substring (see [OuiEntry.prefix]).
 *
 * Matching runs on scanner threads while [load] may run on the UI thread, so
 * the lookup state is rebuilt off to the side and swapped in atomically.
 */
object OuiWatchlist {

    private const val PREFS = "oui_watchlist_prefs"
    private const val KEY_CUSTOM = "custom_entries"
    private const val KEY_ENABLED_PRESETS = "enabled_presets"
    private val DEFAULT_PRESETS = setOf("global", "canada")

    private val gson = Gson()
    private val listType = object : TypeToken<List<OuiEntry>>() {}.type

    private class State(
        /** Address entries keyed by hex digits: 12 = one device, 9/7/6 = IEEE block. */
        val byHex: Map<String, OuiEntry>,
        val nameRules: List<OuiEntry>,
        val vendorRules: List<OuiEntry>,
        /** Requested-network rules keyed by the lower-cased network name. */
        val probeRules: Map<String, OuiEntry>,
        val fingerprintRules: Map<String, OuiEntry>,
        val all: List<OuiEntry>
    )

    /**
     * False when only the France preset is on among the regional ones (no US / Canada): Flock
     * isn't deployed there, so its weak module-prefix clues are ignored (see FlockNoise).
     */
    @Volatile
    var flockRegion = true
        private set

    /** Bumped on every (re)load, so a running scan can re-check devices against the new list. */
    @Volatile
    var version = 0
        private set

    @Volatile
    private var state = State(emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList())

    /** Load order: regional presets after "global", so their labels win for shared prefixes. */
    val availablePresets = listOf("global", "canada", "us", "france")

    @Synchronized
    fun load(context: Context) {
        val byKey = linkedMapOf<String, OuiEntry>()
        val enabled = getEnabledPresets(context)
        flockRegion = "france" !in enabled || "us" in enabled || "canada" in enabled
        for (preset in availablePresets.filter { it in enabled }) {
            loadPresetAsset(context, preset).forEach { byKey[it.prefix] = it }
        }
        readCustom(context).forEach { byKey[it.prefix] = it }
        val all = byKey.values.toList()
        state = State(
            byHex = all.filter { !it.isNameRule && !it.isVendorRule && !it.isProbeRule && !it.isFingerprintRule }.associateBy { MacUtil.hex(it.prefix) },
            nameRules = all.filter { it.isNameRule && it.ruleText.isNotEmpty() },
            vendorRules = all.filter { it.isVendorRule && it.ruleText.isNotEmpty() },
            probeRules = all.filter { it.isProbeRule && it.ruleText.isNotEmpty() }.associateBy { it.ruleText.lowercase() },
            fingerprintRules = all.filter { it.isFingerprintRule && it.ruleText.isNotEmpty() }.associateBy { it.ruleText.lowercase() },
            all = all
        )
        version++
    }

    private fun loadPresetAsset(context: Context, preset: String): List<OuiEntry> {
        return try {
            val stream = context.assets.open("oui_presets/$preset.json")
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            sanitize(gson.fromJson(text, listType))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Gson bypasses Kotlin defaults/non-null types, so fill in anything a JSON file omitted. */
    private fun sanitize(list: List<OuiEntry>?): List<OuiEntry> =
        list.orEmpty().mapNotNull { e ->
            @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
            if (e.prefix == null || !isValidKey(e.prefix)) null
            else e.copy(
                prefix = normalizeKey(e.prefix),
                label = e.label ?: "",
                source = e.source ?: "",
                confidence = e.confidence ?: "confirmed"
            )
        }

    fun normalizeKey(key: String): String {
        val k = key.trim()
        return when {
            k.startsWith(OuiEntry.NAME, ignoreCase = true) -> OuiEntry.NAME + k.substringAfter(':').trim()
            k.startsWith(OuiEntry.VENDOR, ignoreCase = true) -> OuiEntry.VENDOR + k.substringAfter(':').trim()
            k.startsWith(OuiEntry.PROBE, ignoreCase = true) -> OuiEntry.PROBE + k.substringAfter(':').trim()
            k.startsWith(OuiEntry.FINGERPRINT, ignoreCase = true) -> OuiEntry.FINGERPRINT + k.substringAfter(':').trim().lowercase()
            else -> MacUtil.normalize(k)
        }
    }

    private fun readCustom(context: Context): List<OuiEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            sanitize(gson.fromJson(json, listType))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeCustom(context: Context, entries: List<OuiEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CUSTOM, gson.toJson(entries))
        }
    }

    fun getEnabledPresets(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ENABLED_PRESETS, DEFAULT_PRESETS) ?: DEFAULT_PRESETS
        // Presets that no longer exist were merged into "canada".
        return set.map { if (it in availablePresets) it else "canada" }.toSet()
    }

    fun setEnabledPresets(context: Context, presets: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_ENABLED_PRESETS, presets)
        }
        load(context)
    }

    fun addCustomEntry(context: Context, entry: OuiEntry) {
        val key = normalizeKey(entry.prefix)
        val existing = readCustom(context).filterNot { it.prefix == key }
        writeCustom(context, existing + entry.copy(prefix = key))
        load(context)
    }

    fun removeCustomEntry(context: Context, prefix: String) {
        val target = normalizeKey(prefix)
        writeCustom(context, readCustom(context).filterNot { it.prefix == target })
        load(context)
    }

    /** User entries as pretty JSON, for sharing between devices. */
    fun exportCustomJson(context: Context): String =
        GsonBuilder().setPrettyPrinting().create().toJson(readCustom(context))

    /** Merges entries from exported JSON; returns how many were imported. */
    fun importCustomJson(context: Context, json: String): Int {
        val incoming = sanitize(gson.fromJson(json, listType)).map { it.copy(confidence = "custom") }
        val keys = incoming.map { it.prefix }.toSet()
        writeCustom(context, readCustom(context).filterNot { it.prefix in keys } + incoming)
        load(context)
        return incoming.size
    }

    fun allEntries(): List<OuiEntry> = state.all

    /** Valid keys: IEEE block prefix (24/28/36-bit), full MAC, or a non-empty name:/vendor: rule. */
    fun isValidKey(prefix: String): Boolean {
        val k = prefix.trim()
        if (k.startsWith(OuiEntry.NAME, true) || k.startsWith(OuiEntry.VENDOR, true) || k.startsWith(OuiEntry.PROBE, true) ||
            k.startsWith(OuiEntry.FINGERPRINT, true)) {
            return k.substringAfter(':').isNotBlank()
        }
        return MacUtil.isValidBlock(k) || MacUtil.isValidMac(k)
    }

    /**
     * Returns the address-keyed entry for a MAC, or null. The most specific
     * entry wins: exact device, then 36-bit, 28-bit, 24-bit block.
     */
    fun match(mac: String): OuiEntry? {
        val table = state.byHex
        val hex = MacUtil.hex(mac)
        for (len in intArrayOf(12, 9, 7, 6)) {
            if (hex.length >= len) table[hex.take(len)]?.let { return it }
        }
        return null
    }

    /** Every watchlist entry that matches, as signature hits. */
    fun hits(mac: String, name: String?, vendors: List<String>): List<Hit> {
        val s = state
        val out = mutableListOf<Hit>()
        match(mac)?.let {
            out += it.toHit(
                if (MacUtil.hex(it.prefix).length == 12) "Exact address $mac is on the watchlist"
                else "Address is in the watchlisted block ${it.prefix}"
            )
        }
        if (name != null) {
            s.nameRules.filter { name.contains(it.ruleText, ignoreCase = true) }
                .forEach { out += it.toHit("Name \"$name\" contains \"${it.ruleText}\"") }
        }
        if (vendors.isNotEmpty()) {
            s.vendorRules.forEach { rule ->
                vendors.firstOrNull { it.contains(rule.ruleText, ignoreCase = true) }
                    ?.let { out += rule.toHit("Manufacturer \"$it\" contains \"${rule.ruleText}\"") }
            }
        }
        return out
    }

    /** Hits for devices asking for watched network names ([probed]: names one device requested). */
    fun probeHits(probed: Collection<String>): List<Hit> = probeHits(state.probeRules, probed)

    /** Hits for a device whose probe requests have a watched fingerprint. */
    fun fingerprintHits(fp: String?): List<Hit> = fp?.let { state.fingerprintRules[it.lowercase()] }
        ?.let { listOf(it.toHit("Its probe requests match the watched fingerprint $fp")) }.orEmpty()

    fun isFingerprintWatched(fp: String): Boolean = fp.lowercase() in state.fingerprintRules

    /** Watchlist name / maker rules against what a device's WPS block names. */
    fun wpsHits(wps: com.rfsentinel.app.usb.ProbeIntel.Wps?): List<Hit> =
        if (wps == null) emptyList() else hits("", wps.text.ifEmpty { null }, listOfNotNull(wps.manufacturer))

    /** Whether requests for [ssid] raise an alert. */
    fun isProbeWatched(ssid: String): Boolean = ssid.trim().lowercase() in state.probeRules

    internal fun probeHits(rules: Map<String, OuiEntry>, probed: Collection<String>): List<Hit> {
        if (rules.isEmpty()) return emptyList()
        return probed.mapNotNull { ssid ->
            rules[ssid.trim().lowercase()]?.toHit("Asked for the watched network \"${ssid.trim()}\"")
        }.distinctBy { it.label }
    }

    /** The custom (user-added) entry keyed exactly by [prefix], if any. */
    fun customEntry(prefix: String): OuiEntry? =
        state.all.firstOrNull { it.prefix == normalizeKey(prefix) && it.isCustom }
}
