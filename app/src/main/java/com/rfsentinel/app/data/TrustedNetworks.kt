package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.service.DeviceRegistry

/**
 * WiFi networks trusted by name (Whitelist > Network names): every access point whose SSID matches a rule
 * is treated like a whitelisted address. A router that is on every bus of a fleet ("SchoolBus-123") is
 * trusted once, by its name, rather than address by address.
 *
 * Pure matching in [matches] (unit-tested); this object keeps the rules and the addresses they caught.
 */
object TrustedNetworks {

    /** How a rule's text is matched against an SSID; case never matters. */
    enum class Match(val label: String) {
        STARTS("Name starts with"), CONTAINS("Name contains"), EXACT("Exact name")
    }

    data class Rule(val text: String, private val match: Match?) {
        /** Rules saved without one (none were released) match by containing. */
        val how: Match get() = match ?: Match.CONTAINS
        val label get() = how.label

        fun matches(name: String): Boolean = when (how) {
            Match.STARTS -> name.startsWith(text, ignoreCase = true)
            Match.CONTAINS -> name.contains(text, ignoreCase = true)
            Match.EXACT -> name.equals(text, ignoreCase = true)
        }
    }

    private const val PREFS = "trusted_networks"
    private const val KEY = "rules"
    private val gson = Gson()
    private val type = object : TypeToken<List<Rule>>() {}.type

    @Volatile var rules: List<Rule> = emptyList()
        private set
    /** Access points heard with a matching SSID (an address that later broadcasts another name drops out). */
    private val trusted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun matches(rules: List<Rule>, ssid: String?): Boolean {
        val name = ssid?.trim().orEmpty()
        if (name.isEmpty()) return false
        return rules.any { it.matches(name) }
    }

    fun contains(mac: String): Boolean = mac in trusted

    /** The rule that trusts this network name, if any. */
    fun ruleFor(ssid: String?): Rule? = rules.firstOrNull { matches(listOf(it), ssid) }

    /** A WiFi access point was heard: trusted while its SSID matches (a hidden SSID keeps what it had). */
    fun onSeen(mac: String, ssid: String?) {
        if (ssid.isNullOrBlank()) return
        if (matches(rules, ssid)) trusted.add(mac) else trusted.remove(mac)
    }

    fun load(context: Context) {
        rules = runCatching {
            gson.fromJson<List<Rule>>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null), type)
        }.getOrNull().orEmpty()
        recheck()
    }

    /** Adds [rule], or puts it in place of [replacing] (an edit). */
    fun save(context: Context, rule: Rule, replacing: Rule? = null) {
        val clean = rule.copy(text = rule.text.trim())
        if (clean.text.isEmpty()) return
        val others = rules.filterNot { it == replacing || it.text.equals(clean.text, ignoreCase = true) && it.how == clean.how }
        write(context, others + clean)
    }

    fun remove(context: Context, rule: Rule) = write(context, rules.filterNot { it == rule })

    private fun write(context: Context, list: List<Rule>) {
        rules = list.sortedBy { it.text.lowercase() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(rules)).apply()
        recheck()
    }

    /** The rules changed: the networks in range are trusted (or not) at once, not at their next beacon. */
    private fun recheck() {
        val now = rules
        val caught = DeviceRegistry.snapshot().filter { it.source == com.rfsentinel.app.detect.Advert.Source.WIFI && matches(now, it.name) }.map { it.mac }
        trusted.retainAll(caught.toSet())
        trusted.addAll(caught)
    }
}
