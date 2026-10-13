package com.rfsentinel.app.ui

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** The parts of a custom theme that import can replace one at a time. */
enum class ThemePart(val label: String) {
    COLORS("Colors (accent, palette, background)"),
    FONT("Font"),
    BANNER("Banner image"),
    HEADER("Header style and title"),
    MARKER("Position marker")
}

/**
 * A theme made in the theme maker: colours grown from one accent, a font, a banner image, the header on the home screen and
 * the position marker. Only the choices are kept here (the banner and a font file live in the theme's own folder).
 */
data class CustomTheme(
    val id: String,
    val name: String,
    val accent: Int = 0xFF1E6FE0.toInt(),
    /** [CustomColors.Scheme.key]: how the palette is grown from the accent. */
    val scheme: String = "tonal",
    /** [CustomColors.Mode.key]: follow the phone, light, dark or pure black. */
    val mode: String = "system",
    /** A [FontApplier] family key, or "file" for the theme's own font file. */
    val font: String = "default",
    val fontFile: String? = null,
    val banner: String? = null,
    /** A [ThemeHeaderView.Style] name, or null for no animated header. */
    val header: String? = null,
    val title: String? = null,
    val markerStyle: String? = null,
    val markerColor: String? = null,
    val markerSize: Float? = null
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("accent", accent).put("scheme", scheme).put("mode", mode)
        .put("font", font).put("fontFile", fontFile ?: JSONObject.NULL).put("banner", banner ?: JSONObject.NULL)
        .put("header", header ?: JSONObject.NULL).put("title", title ?: JSONObject.NULL)
        .put("markerStyle", markerStyle ?: JSONObject.NULL).put("markerColor", markerColor ?: JSONObject.NULL)
        .put("markerSize", markerSize?.toDouble() ?: JSONObject.NULL)

    /** Which of the five parts of this theme differ from the defaults (what a shared file carries). */
    val parts: Set<ThemePart> get() = buildSet {
        add(ThemePart.COLORS)
        if (font != "default") add(ThemePart.FONT)
        if (banner != null) add(ThemePart.BANNER)
        if (header != null || !title.isNullOrBlank()) add(ThemePart.HEADER)
        if (markerStyle != null) add(ThemePart.MARKER)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

        /** Reads one theme, cleaning every value (unknown words become the defaults) so a hand-edited file can't break the app. */
        fun fromJson(o: JSONObject, id: String = o.optString("id")): CustomTheme {
            val styles = ThemeHeaderView.Style.entries.map { it.name }
            val markerStyles = MarkerArt.Style.entries.map { it.key }
            val colors = MarkerArt.colors.map { it.key }
            val font = o.str("font")?.takeIf { it == "file" || it in FontApplier.keys } ?: "default"
            val markerStyle = o.str("markerStyle")?.takeIf { it in markerStyles }
            return CustomTheme(
                id = id,
                name = (o.str("name") ?: "My theme").trim().take(40),
                accent = (o.optLong("accent", 0xFF1E6FE0L).toInt() or 0xFF000000.toInt()),
                scheme = o.str("scheme")?.takeIf { s -> CustomColors.Scheme.entries.any { it.key == s } } ?: "tonal",
                mode = o.str("mode")?.takeIf { m -> CustomColors.Mode.entries.any { it.key == m } } ?: "system",
                font = font,
                fontFile = o.str("fontFile")?.let { safeName(it) }.takeIf { font == "file" },
                banner = o.str("banner")?.let { safeName(it) },
                header = o.str("header")?.takeIf { it in styles },
                title = o.str("title")?.trim()?.take(40),
                markerStyle = markerStyle,
                markerColor = if (markerStyle == null) null else o.str("markerColor")?.takeIf { it in colors } ?: "blue",
                markerSize = if (markerStyle == null) null else if (o.isNull("markerSize")) 1.0f else o.optDouble("markerSize", 1.0).toFloat().coerceIn(0.6f, 2.0f)
            )
        }

        /** A file name that can't point out of the theme's folder. */
        fun safeName(s: String) = s.substringAfterLast('/').substringAfterLast('\\').filter { it.isLetterOrDigit() || it in "._-" }.take(60)
    }
}

/** The custom themes on this phone, and sharing them as files (a theme with its banner and font in one JSON file). */
object CustomThemes {
    private const val PREFS = "custom_themes"
    private const val KEY = "list"
    const val MAX_THEMES = 30
    const val MAX_BANNER_BYTES = 4 * 1024 * 1024
    const val MAX_FONT_BYTES = 6 * 1024 * 1024
    const val FORMAT = 1

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<CustomTheme> = runCatching {
        val a = JSONArray(sp(c).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.takeIf { it.optString("id").isNotBlank() }?.let { CustomTheme.fromJson(it) } }
    }.getOrDefault(emptyList())

    fun get(c: Context, id: String?): CustomTheme? = id?.let { all(c).firstOrNull { t -> t.id == it } }

    private fun write(c: Context, list: List<CustomTheme>) {
        sp(c).edit(commit = true) { putString(KEY, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()) }
    }

    fun save(c: Context, t: CustomTheme) {
        val list = all(c).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list.add(t)
        write(c, list.takeLast(MAX_THEMES))
    }

    fun delete(c: Context, id: String) {
        write(c, all(c).filter { it.id != id })
        dir(c, id).deleteRecursively()
    }

    fun newId(): String = "t" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

    /** A name not used by another theme ("My theme", "My theme 2"...). */
    fun uniqueName(c: Context, wanted: String, exceptId: String? = null): String {
        val taken = all(c).filter { it.id != exceptId }.map { it.name.lowercase(Locale.US) }.toSet()
        val base = wanted.trim().ifEmpty { "My theme" }.take(36)
        if (base.lowercase(Locale.US) !in taken) return base
        var n = 2
        while ("$base $n".lowercase(Locale.US) in taken) n++
        return "$base $n"
    }

    fun dir(c: Context, id: String) = File(c.filesDir, "custom_themes/${CustomTheme.safeName(id)}")
    fun bannerFile(c: Context, t: CustomTheme): File? = t.banner?.let { File(dir(c, t.id), it) }?.takeIf { it.isFile && it.length() > 0 }
    fun fontFile(c: Context, t: CustomTheme): File? = t.fontFile?.let { File(dir(c, t.id), it) }?.takeIf { it.isFile && it.length() > 0 }

    /** Changes whenever the theme's choices or its files do: what tells an open screen it is out of date. */
    fun stamp(c: Context, t: CustomTheme): String =
        t.hashCode().toString() + ":" + (bannerFile(c, t)?.lastModified() ?: 0L) + ":" + (fontFile(c, t)?.lastModified() ?: 0L)

    /** Whether these bytes are a picture of at most 4 MB. */
    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_BANNER_BYTES) return false
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /** Replaces the theme's banner with these image bytes (a picture of at most 4 MB); returns the updated theme, or null when it isn't an image. */
    fun setBanner(c: Context, t: CustomTheme, bytes: ByteArray): CustomTheme? {
        if (!isImage(bytes)) return null
        val d = dir(c, t.id).apply { mkdirs() }
        t.banner?.let { File(d, it).delete() }
        val name = "banner"
        File(d, name).writeBytes(bytes)
        return t.copy(banner = name)
    }

    fun clearBanner(c: Context, t: CustomTheme): CustomTheme { t.banner?.let { File(dir(c, t.id), it).delete() }; return t.copy(banner = null) }

    /** Gives the theme its own font file (a .ttf or .otf of at most 6 MB that loads as a font); null when it isn't one. */
    fun setFont(c: Context, t: CustomTheme, bytes: ByteArray): CustomTheme? {
        if (bytes.isEmpty() || bytes.size > MAX_FONT_BYTES) return null
        val d = dir(c, t.id).apply { mkdirs() }
        val tmp = File(d, "font.tmp"); tmp.writeBytes(bytes)
        if (loadFont(tmp) == null) { tmp.delete(); return null }
        t.fontFile?.let { File(d, it).delete() }
        tmp.renameTo(File(d, "font"))
        return t.copy(font = "file", fontFile = "font")
    }

    /** The font in this file, or null when it isn't a TrueType / OpenType font that loads. */
    fun loadFont(f: File): android.graphics.Typeface? = runCatching {
        val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        val magic = head[0] == 0.toByte() && head[1] == 1.toByte() || head[0] == 'O'.code.toByte() || head[0] == 't'.code.toByte()
        if (magic) android.graphics.Typeface.createFromFile(f) else null
    }.getOrNull()

    /** Takes the theme's own font file away (it goes back to the standard font). */
    fun clearFont(c: Context, t: CustomTheme): CustomTheme { t.fontFile?.let { File(dir(c, t.id), it).delete() }; return t.copy(font = "default", fontFile = null) }

    /** A copy of the theme with its own files; saved and returned. */
    fun duplicate(c: Context, t: CustomTheme): CustomTheme {
        val shared = parseShared(toShareJson(c, t))
        return import(c, shared, null, ThemePart.entries.toSet(), t.name + " copy")
    }

    // ---- sharing: one JSON file with the choices and the theme's own files ---------------------------------

    class Shared(val theme: CustomTheme, val banner: ByteArray?, val font: ByteArray?)
    class BadThemeFile(message: String) : Exception(message)

    private fun shareObject(c: Context, t: CustomTheme): JSONObject {
        val assets = JSONObject()
        bannerFile(c, t)?.let { assets.put("banner", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        fontFile(c, t)?.let { assets.put("font", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        return JSONObject().put("app", "RF Sentinel").put("type", "theme").put("format", FORMAT).put("theme", t.toJson()).put("assets", assets)
    }

    fun toShareJson(c: Context, t: CustomTheme): String = shareObject(c, t).toString(2)

    fun parseShared(text: String): Shared {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw BadThemeFile("That isn't a theme file (not valid JSON)")
        return parseShared(root)
    }

    fun parseShared(root: JSONObject): Shared {
        if (root.optString("app") != "RF Sentinel" || root.optString("type") != "theme") throw BadThemeFile("That isn't an RF Sentinel theme file")
        if (root.optInt("format", 0) !in 1..FORMAT) throw BadThemeFile("That theme is from a newer version of RF Sentinel - update the app first")
        val t = root.optJSONObject("theme") ?: throw BadThemeFile("The theme file has no theme in it")
        val a = root.optJSONObject("assets")
        fun asset(k: String, max: Int) = a?.optString(k)?.takeIf { it.isNotEmpty() }?.let { s ->
            runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()?.takeIf { it.size in 1..max }
        }
        return Shared(CustomTheme.fromJson(t, id = ""), asset("banner", MAX_BANNER_BYTES)?.takeIf { isImage(it) }, asset("font", MAX_FONT_BYTES))
    }

    /** Which of the five parts the file really carries (one without a banner offers no banner to take). */
    fun partsIn(s: Shared): Set<ThemePart> = buildSet {
        add(ThemePart.COLORS)
        if (s.theme.font != "default" && (s.theme.font != "file" || s.font != null)) add(ThemePart.FONT)
        if (s.banner != null) add(ThemePart.BANNER)
        if (s.theme.header != null || !s.theme.title.isNullOrBlank()) add(ThemePart.HEADER)
        if (s.theme.markerStyle != null) add(ThemePart.MARKER)
    }

    /**
     * Takes the chosen [parts] of the shared theme: into [target] (those parts of it are replaced, the rest stays) or, with no
     * target, into a new theme (the parts not chosen stay at the defaults). Saves and returns the result.
     */
    fun import(c: Context, shared: Shared, target: CustomTheme?, parts: Set<ThemePart>, newName: String? = null): CustomTheme {
        val base = target ?: CustomTheme(id = newId(), name = uniqueName(c, newName ?: shared.theme.name))
        val s = shared.theme
        var t = base
        if (ThemePart.COLORS in parts) t = t.copy(accent = s.accent, scheme = s.scheme, mode = s.mode)
        if (ThemePart.HEADER in parts) t = t.copy(header = s.header, title = s.title)
        if (ThemePart.MARKER in parts) t = t.copy(markerStyle = s.markerStyle, markerColor = s.markerColor, markerSize = s.markerSize)
        if (ThemePart.FONT in parts) {
            t = if (s.font == "file" && shared.font != null) (setFont(c, t.copy(font = "default"), shared.font) ?: t.copy(font = "default", fontFile = null))
                else { t.fontFile?.let { File(dir(c, t.id), it).delete() }; t.copy(font = if (s.font == "file") "default" else s.font, fontFile = null) }
        }
        if (ThemePart.BANNER in parts) {
            t = if (shared.banner != null) (setBanner(c, t, shared.banner) ?: clearBanner(c, t)) else clearBanner(c, t)
        }
        save(c, t)
        return t
    }

    // ---- the settings backup carries every theme with its files ---------------------------------------------

    fun backup(c: Context): JSONArray = JSONArray().also { a ->
        for (t in all(c)) a.put(shareObject(c, t).put("id", t.id))
    }

    /** Reads the backup's themes (throws [BadThemeFile] for a damaged one) without changing anything. */
    fun parseBackup(a: JSONArray): List<Pair<String, Shared>> {
        val incoming = ArrayList<Pair<String, Shared>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = CustomTheme.safeName(o.optString("id")).ifEmpty { newId() }
            incoming += id to parseShared(o)
        }
        return incoming
    }

    /** Puts the backup's themes in place of this phone's; returns how many. */
    fun restoreBackup(c: Context, a: JSONArray): Int = applyBackup(c, parseBackup(a))

    fun applyBackup(c: Context, incoming: List<Pair<String, Shared>>): Int {
        for (t in all(c)) dir(c, t.id).deleteRecursively()
        write(c, emptyList())
        for ((id, s) in incoming.take(MAX_THEMES)) {
            val base = s.theme.copy(id = id, banner = null, font = if (s.theme.font == "file") "default" else s.theme.font, fontFile = null)
            var t = base
            if (s.banner != null) t = setBanner(c, t, s.banner) ?: t
            if (s.theme.font == "file" && s.font != null) t = setFont(c, t, s.font) ?: t
            save(c, t)
        }
        return minOf(incoming.size, MAX_THEMES)
    }
}
