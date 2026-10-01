package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Trackers the user chose to ignore. AirTags and other Find My / Find Hub tags
 * change their Bluetooth address (about once a day away from their owner), so
 * the ordinary MAC whitelist can't hold them.
 *
 * - "Just today": ignored until the address changes (at most 24 h).
 * - "It's mine": followed through its address changes. When an ignored tag
 *   falls silent and, within a few minutes, a tag of the same kind appears at a
 *   similar signal strength, the new address inherits the mute. This is a best
 *   effort, so the setting is shown with a clear warning.
 *
 * Pure logic in [Store] (unit-tested); this object persists it.
 */
object TrackerMutes {

    data class Mute(
        val mac: String,
        /** The match label, e.g. "Apple Find My tracker away from its owner (AirTag or compatible)". */
        val kind: String,
        /** Epoch ms, or Long.MAX_VALUE for "it's mine". */
        val until: Long,
        val follow: Boolean,
        val lastSeen: Long,
        val lastRssi: Int
    )

    /** In-memory rules; no Android types. */
    class Store(var mutes: List<Mute> = emptyList()) {

        fun isMuted(mac: String, now: Long): Boolean = mutes.any { it.mac == mac && it.until > now }

        fun mute(mac: String, kind: String, rssi: Int, now: Long, follow: Boolean) {
            val until = if (follow) Long.MAX_VALUE else now + DAY_MS
            mutes = mutes.filterNot { it.mac == mac } + Mute(mac, kind, until, follow, now, rssi)
        }

        fun unmute(mac: String) { mutes = mutes.filterNot { it.mac == mac } }

        /**
         * A tracker of [kind] was heard. Returns true when it's muted - possibly
         * because it just inherited the mute of a "mine" tag whose address changed.
         * Returns whether the stored list changed through [changed].
         */
        fun onSeen(mac: String, kind: String, rssi: Int, now: Long, changed: (Boolean) -> Unit = {}): Boolean {
            mutes = mutes.filter { it.until > now }
            val own = mutes.firstOrNull { it.mac == mac }
            if (own != null) {
                // Keep "last seen" fresh, but don't rewrite storage on every packet.
                if (now - own.lastSeen > 30_000) {
                    mutes = mutes.map { if (it.mac == mac) it.copy(lastSeen = now, lastRssi = rssi) else it }
                    changed(true)
                }
                return true
            }
            val candidates = mutes.filter {
                it.follow && it.kind == kind &&
                    now - it.lastSeen in SILENT_MS..CARRY_WINDOW_MS &&
                    kotlin.math.abs(it.lastRssi - rssi) <= RSSI_TOLERANCE_DB
            }
            val prev = candidates.singleOrNull() ?: return false
            mutes = mutes.map { if (it === prev) it.copy(mac = mac, lastSeen = now, lastRssi = rssi) else it }
            changed(true)
            return true
        }
    }

    const val DAY_MS = 24 * 3600_000L
    /** The old address must have been silent at least this long... */
    const val SILENT_MS = 10_000L
    /** ...and the new one must show up within this window. */
    const val CARRY_WINDOW_MS = 5 * 60_000L
    const val RSSI_TOLERANCE_DB = 12

    private const val PREFS = "tracker_mutes"
    private const val KEY = "mutes"
    private val gson = Gson()
    private val type = object : TypeToken<List<Mute>>() {}.type
    private val store = Store()
    @Volatile private var loaded = false

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        store.mutes = runCatching {
            gson.fromJson<List<Mute>>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null), type)
        }.getOrNull().orEmpty()
        loaded = true
    }

    @Synchronized
    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(store.mutes)).apply()
    }

    /** Fast check for the UI (no disk access once loaded). */
    fun isMuted(mac: String): Boolean = loaded && synchronized(this) { store.isMuted(mac, System.currentTimeMillis()) }

    fun load(context: Context) = ensure(context)

    @Synchronized
    fun onSeen(context: Context, mac: String, kind: String, rssi: Int, now: Long = System.currentTimeMillis()): Boolean {
        ensure(context)
        var dirty = false
        val muted = store.onSeen(mac, kind, rssi, now) { dirty = it }
        if (dirty) save(context)
        return muted
    }

    @Synchronized
    fun mute(context: Context, mac: String, kind: String, rssi: Int, follow: Boolean) {
        ensure(context)
        store.mute(mac, kind, rssi, System.currentTimeMillis(), follow)
        save(context)
    }

    @Synchronized
    fun unmute(context: Context, mac: String) {
        ensure(context)
        store.unmute(mac)
        save(context)
    }

    @Synchronized
    fun count(context: Context): Int {
        ensure(context)
        return store.mutes.count { it.until > System.currentTimeMillis() }
    }

    @Synchronized
    fun clear(context: Context) {
        ensure(context)
        store.mutes = emptyList()
        save(context)
    }
}
