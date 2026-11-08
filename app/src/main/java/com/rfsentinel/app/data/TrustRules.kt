package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.MacUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Whitelist rules, the watchlist's opposite: every device a rule matches is trusted like a whitelisted address
 * (never logged, flashed or alerted on). "Ignore all like this" is a [Kind.LABEL] rule: every device matched as
 * "Zebra printer" is trusted once, not address by address. WiFi network names stay in [TrustedNetworks].
 *
 * Pure matching in [decide] (unit-tested); this object keeps the rules and the addresses they caught.
 */
object TrustRules {

    enum class Kind(val label: String) {
        LABEL("Matched as"), PREFIX("Address starts with"), STARTS("Name starts with"), CONTAINS("Name contains"),
        VENDOR("Maker contains"), ADVERT("Bluetooth advert pattern"), FINGERPRINT("Probe fingerprint")
    }

    data class Rule(val kind: Kind, val text: String, private val note: String? = null) {
        /** Gson leaves a missing note null. */
        val noteText: String get() = note.orEmpty()
        val describe: String get() = "${kind.label} " + if (kind == Kind.PREFIX) text else "\"$text\""
    }

    /** What a device shows that rules can match; the costlier facts are only worked out when a rule needs them. */
    class Facts(val mac: String, val name: String?, val vendor: String?, val labels: List<String>,
                advert: () -> String? = { null }, fingerprint: () -> String? = { null }) {
        val advert by lazy(LazyThreadSafetyMode.NONE, advert)
        val fingerprint by lazy(LazyThreadSafetyMode.NONE, fingerprint)
    }

    fun matches(r: Rule, f: Facts): Boolean = when (r.kind) {
        Kind.LABEL -> f.labels.any { it.equals(r.text, ignoreCase = true) }
        Kind.PREFIX -> MacUtil.hex(r.text).let { it.length >= 6 && MacUtil.hex(f.mac).startsWith(it) }
        Kind.STARTS -> f.name?.trimStart()?.startsWith(r.text, ignoreCase = true) == true
        Kind.CONTAINS -> f.name?.contains(r.text, ignoreCase = true) == true
        Kind.VENDOR -> f.vendor?.contains(r.text, ignoreCase = true) == true
        Kind.ADVERT -> f.advert.equals(r.text, ignoreCase = true)
        Kind.FINGERPRINT -> f.fingerprint.equals(r.text, ignoreCase = true)
    }

    /** Whether this sighting carries what [r] looks at: a scan response without its name doesn't untrust a name match. */
    private fun decidable(r: Rule, f: Facts): Boolean = when (r.kind) {
        Kind.LABEL -> f.labels.isNotEmpty()
        Kind.PREFIX -> true
        Kind.STARTS, Kind.CONTAINS -> f.name != null
        Kind.VENDOR -> f.vendor != null
        Kind.ADVERT -> f.advert != null
        Kind.FINGERPRINT -> f.fingerprint != null
    }

    /** The rule trusting a device after this sighting: the first that matches, else the [old] one while this sighting can't tell. */
    internal fun decide(rules: List<Rule>, old: Rule?, f: Facts): Rule? =
        rules.firstOrNull { matches(it, f) } ?: old?.takeIf { it in rules && !decidable(it, f) }

    private const val PREFS = "trust_rules"
    private const val KEY = "rules"
    private val gson = Gson()
    private val type = object : TypeToken<List<Rule>>() {}.type

    @Volatile var rules: List<Rule> = emptyList()
        private set
    private val trusted = ConcurrentHashMap<String, Rule>()

    fun contains(mac: String): Boolean = trusted.containsKey(mac)

    /** The rule [mac] is trusted by, if any. */
    fun ruleOf(mac: String): Rule? = trusted[mac]

    /** Devices in range a rule trusts now. */
    fun count(rule: Rule): Int = trusted.values.count { it == rule }

    /** A device was heard (on the scan pipeline): trusted while a rule matches it. */
    fun onSeen(f: Facts) {
        val now = rules
        if (now.isEmpty()) { if (trusted.isNotEmpty()) trusted.remove(f.mac); return }
        val r = decide(now, trusted[f.mac], f)
        if (r != null) trusted[f.mac] = r else trusted.remove(f.mac)
    }

    fun factsOf(mac: String, name: String?, vendor: String?, hits: List<Hit>, advert: com.rfsentinel.app.detect.Advert?) =
        Facts(mac, name, vendor, hits.map { it.label },
            { advert?.takeIf { it.isBle }?.let { com.rfsentinel.app.detect.AdvertFingerprint.key(it) } },
            { com.rfsentinel.app.usb.ProbeIntel.of(mac)?.fingerprint })

    fun load(context: Context) {
        rules = runCatching {
            gson.fromJson<List<Rule>>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null), type)
        }.getOrNull().orEmpty().filter { it.kind != null && it.text.isNotBlank() }
        recheck()
    }

    fun add(context: Context, rule: Rule) {
        val clean = rule.copy(text = rule.text.trim())
        if (clean.text.isEmpty()) return
        write(context, rules.filterNot { it.kind == clean.kind && it.text.equals(clean.text, ignoreCase = true) } + clean)
    }

    fun remove(context: Context, rule: Rule) = write(context, rules.filterNot { it == rule })

    private fun write(context: Context, list: List<Rule>) {
        rules = list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(list)).apply()
        recheck()
    }

    /** The rules changed: devices in range are trusted (or not) at once, not at their next advert. */
    private fun recheck() {
        val now = rules
        val caught = HashMap<String, Rule>()
        if (now.isNotEmpty()) for (s in DeviceRegistry.snapshot()) {
            now.firstOrNull { matches(it, factsOf(s.mac, s.name, s.vendor, s.hits, s.advert)) }?.let { caught[s.mac] = it }
        }
        trusted.keys.retainAll(caught.keys)
        trusted.putAll(caught)
    }
}
