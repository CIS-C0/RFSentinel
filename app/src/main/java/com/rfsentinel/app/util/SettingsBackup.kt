package com.rfsentinel.app.util

import android.content.Context
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.ui.CustomThemes
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Settings > Data > Back up / Restore settings: the app's settings, your watchlist rules, Home / Work / starred places, the
 * floating windows' positions and your custom themes (with their banners and fonts), as one JSON file you keep. Left out on
 * purpose: API keys and other secrets (they never leave the phone's secure store), the "I accept the risk" acknowledgements
 * (asked again), recent places, snoozes and other short-lived state, where the map last looked, and detections, devices and
 * traces (those have their own export).
 *
 * Restoring asks which [Section]s to take, so a file can bring back just the themes, or everything but them.
 *
 * Pure Kotlin on top of SharedPreferences, so it is unit-tested.
 */
object SettingsBackup {

    const val FORMAT = 1
    /** Themes carry pictures and fonts, so the file can be big; a file over this is refused. */
    const val MAX_BYTES = 48_000_000

    /** What a restore can take, one at a time. */
    enum class Section(val label: String) {
        SETTINGS("Settings"),
        WATCHLIST("Watchlist rules"),
        PLACES("Home, Work and starred places"),
        FLOATING("Floating map and bubble positions"),
        THEMES("Custom themes (with their banners and fonts)")
    }

    /** The preference files that are backed up, and for each the keys it never includes (all of them when [only] is set). */
    private class Part(val file: String, val section: Section, val skip: Set<String> = emptySet(), val only: Set<String>? = null)

    private val parts = listOf(
        Part("rf_sentinel_prefs", Section.SETTINGS, skip = setOf(
            // Short-lived state and where you were.
            "alerts_snoozed_until", "tracker_follow_paused_until", "last_update_check", "map_center", "map_zoom", "deflock_updated", "started_by_car", "recording_by_car",
            // Acknowledgements that must be given again, and folder access that does not carry over.
            "waze_accepted", "waze_direct_accepted", "live_export", "live_export_tree")),
        Part("nav_places", Section.PLACES, only = setOf("nav_home", "nav_work", "nav_favorites")),
        Part("oui_watchlist_prefs", Section.WATCHLIST),
        Part("floating_map", Section.FLOATING),
        Part("threat_bubble", Section.FLOATING)
    )

    private const val THEMES = "customThemes"

    private fun wanted(p: Part, key: String) = (p.only?.contains(key) ?: true) && key !in p.skip

    /** The backup file's text. */
    fun create(context: Context, now: Long = System.currentTimeMillis()): String {
        val files = JSONObject()
        for (p in parts) {
            val out = JSONObject()
            for ((k, v) in context.getSharedPreferences(p.file, Context.MODE_PRIVATE).all) {
                if (!wanted(p, k) || v == null) continue
                out.put(k, when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                })
            }
            files.put(p.file, out)
        }
        val root = JSONObject().put("app", "RF Sentinel").put("format", FORMAT).put("version", BuildConfig.VERSION_NAME)
            .put("created", now).put("files", files)
        val themes = CustomThemes.backup(context)
        if (themes.length() > 0) root.put(THEMES, themes)
        return root.toString(2)
    }

    /** Reads a backup file's text; null when it is bigger than [MAX_BYTES] (or unreadable). */
    fun read(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    sealed class Result {
        class Restored(val count: Int) : Result()
        class Refused(val why: String) : Result()
    }

    /** What a backup file holds: the sections in it, or why it is not a backup. */
    sealed class Inspect {
        class Ok(val sections: List<Section>, val themes: Int) : Inspect()
        class Bad(val why: String) : Inspect()
    }

    private class Opened(val files: JSONObject?, val themes: JSONArray?)

    private fun open(text: String): Any {
        if (text.length > MAX_BYTES) return Result.Refused("That file is too big to be an RF Sentinel backup")
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Result.Refused("That isn't a backup file (not valid JSON)")
        if (root.optString("app") != "RF Sentinel") return Result.Refused("That isn't an RF Sentinel backup")
        val format = root.optInt("format", 0)
        if (format < 1 || format > FORMAT) return Result.Refused("That backup is from a newer version of RF Sentinel - update the app first")
        val files = root.optJSONObject("files")
        val themes = root.optJSONArray(THEMES)
        if (files == null && themes == null) return Result.Refused("The backup has no settings in it")
        return Opened(files, themes)
    }

    fun inspect(text: String): Inspect {
        val o = open(text)
        if (o is Result.Refused) return Inspect.Bad(o.why)
        o as Opened
        val found = LinkedHashSet<Section>()
        for (p in parts) if (o.files?.optJSONObject(p.file) != null) found += p.section
        val themes = o.themes?.length() ?: 0
        if (themes > 0) found += Section.THEMES
        return Inspect.Ok(Section.entries.filter { it in found }, themes)
    }

    /**
     * Puts the file's settings in place of this phone's: in each backed-up file of the chosen [only] sections (all by default)
     * every setting that is backed up is replaced (one the file has not got goes back to its default); the custom themes
     * replace this phone's. Nothing changes if the file is not a backup of this app, or is damaged anywhere.
     */
    fun restore(context: Context, text: String, only: Set<Section> = Section.entries.toSet()): Result {
        val o = open(text)
        if (o is Result.Refused) return o
        o as Opened
        // Read everything first: a bad entry anywhere means nothing is applied.
        val plan = HashMap<String, Map<String, Any>>()
        for (p in parts) {
            if (p.section !in only) continue
            val f = o.files?.optJSONObject(p.file) ?: continue
            val values = HashMap<String, Any>()
            for (k in f.keys()) {
                if (!wanted(p, k)) continue
                val e = f.optJSONObject(k) ?: return Result.Refused("The backup is damaged ($k)")
                values[k] = when (e.optString("t")) {
                    "b" -> e.optBoolean("v")
                    "i" -> e.optInt("v")
                    "l" -> e.optLong("v")
                    "f" -> e.optDouble("v").toFloat()
                    "s" -> e.optString("v")
                    "ss" -> (e.optJSONArray("v") ?: return Result.Refused("The backup is damaged ($k)")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> return Result.Refused("The backup is damaged ($k)")
                }
            }
            plan[p.file] = values
        }
        val themes = if (Section.THEMES in only && o.themes != null && o.themes.length() > 0) {
            try { CustomThemes.parseBackup(o.themes) } catch (e: CustomThemes.BadThemeFile) { return Result.Refused("The backup is damaged (a theme: ${e.message})") }
        } else null
        var count = 0
        for (p in parts) {
            val values = plan[p.file] ?: continue
            val sp = context.getSharedPreferences(p.file, Context.MODE_PRIVATE)
            val e = sp.edit()
            for (k in sp.all.keys) if (wanted(p, k)) e.remove(k)
            for ((k, v) in values) {
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                }
                count++
            }
            e.commit() // written before the app restarts
        }
        if (themes != null) count += CustomThemes.applyBackup(context, themes)
        return Result.Restored(count)
    }

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "rf-sentinel-settings-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now)) + ".json"
}
