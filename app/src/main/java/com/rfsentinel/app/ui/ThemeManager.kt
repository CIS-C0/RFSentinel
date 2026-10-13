package com.rfsentinel.app.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.google.android.material.color.DynamicColors
import com.rfsentinel.app.R
import java.util.WeakHashMap

/**
 * App-wide appearance. The theme is applied to every activity before its views
 * are inflated (via activity lifecycle callbacks), and activities left in the
 * back stack with an older theme recreate themselves when resumed.
 */
object ThemeManager {

    /**
     * @param header animated wordmark drawn above the main screen (null = none)
     * @param appTitle action-bar title on the main screen
     * @param night true for dark themes, false for light, null to follow the phone
     * @param headerImage bundled header art shown instead of the animated wordmark
     */
    enum class AppTheme(
        val title: String,
        val description: String,
        val style: Int = R.style.Theme_RFSentinel,
        val night: Boolean? = null,
        val header: ThemeHeaderView.Style? = null,
        val appTitle: String? = null,
        val headerImage: Int? = null
    ) {
        // Featured fan themes first in the pickers.
        DEDSEC("DedSec", "Watch Dogs 2 hacktivist homage: black & electric blue, hooded-skull poster, glitching wordmark, ctOS-busting terminal",
            R.style.Theme_RFSentinel_DedSec, true, ThemeHeaderView.Style.GLITCH, "marcus@ctOS-2.0:~$", R.drawable.dedsec_header),
        FSOCIETY("fsociety", "Mr. Robot homage: black & blood red, fsociety poster, a terminal full of quotes from the show",
            R.style.Theme_RFSentinel_Fsociety, true, ThemeHeaderView.Style.FSOCIETY, "root@fsociety:~#", R.drawable.fsociety_header),
        SYSTEM("System default", "Teal - follows your phone's light / dark setting"),
        LIGHT("Light", "Always light", night = false),
        DARK("Dark", "Always dark", night = true),
        MATERIAL_YOU("Material You", "Material 3, coloured from your wallpaper (Android 12+)", R.style.Theme_RFSentinel_MaterialYou),
        NIGHT_DRIVE("Night Drive", "Red-only cockpit lighting that keeps your night vision on dark roads",
            R.style.Theme_RFSentinel_NightDrive, true, ThemeHeaderView.Style.HUD, "RF SENTINEL"),
        NIGHT_VISION("Night Vision", "Green phosphor image intensifier with grain and a scope vignette",
            R.style.Theme_RFSentinel_NightVision, true, ThemeHeaderView.Style.NIGHT_VISION, "RF SENTINEL // NV"),
        AMBER("Amber CRT", "1980s monochrome terminal with a blinking cursor",
            R.style.Theme_RFSentinel_Amber, true, ThemeHeaderView.Style.CRT, "RFSENTINEL.EXE"),
        SYNTHWAVE("Synthwave", "Neon pink and cyan, a striped sunset over a wireframe grid",
            R.style.Theme_RFSentinel_Synthwave, true, ThemeHeaderView.Style.SUNSET, "RF Sentinel"),
        TACTICAL("Tactical", "Olive drab and coyote tan, stencil callsign, low glare",
            R.style.Theme_RFSentinel_Tactical, true, ThemeHeaderView.Style.STENCIL, "RF-SENTINEL"),
        BLUEPRINT("Blueprint", "White technical drawing on drafting blue, with dimension lines",
            R.style.Theme_RFSentinel_Blueprint, true, ThemeHeaderView.Style.BLUEPRINT, "RF SENTINEL // DWG-001"),
        PAPER("Paper", "Newspaper ink on newsprint - the most readable theme in bright sunlight",
            R.style.Theme_RFSentinel_Paper, false, ThemeHeaderView.Style.MASTHEAD, "Front Page"),
        /** A theme made in the theme maker (which one: [custom]); not in the built-in lists. */
        CUSTOM("Custom theme", "Made in the theme maker", R.style.Theme_RFSentinel_Custom)
    }

    /** The built-in themes, for the pickers (the custom ones are listed from [CustomThemes]). */
    val builtIn: List<AppTheme> get() = AppTheme.entries.filter { it != AppTheme.CUSTOM }

    private const val PREFS = "rf_sentinel_prefs"
    private const val KEY = "app_theme"
    private const val CUSTOM_KEY = "custom_theme_id"

    /** What each live activity was created with (the theme, and for a custom one its choices), to detect stale ones. */
    private val applied = WeakHashMap<Activity, String>()

    fun current(context: Context): AppTheme {
        val t = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SYSTEM
        // A custom theme that is gone (deleted, or a backup restored without it) is the default look again.
        return if (t == AppTheme.CUSTOM && custom(context) == null) AppTheme.SYSTEM else t
    }

    /** The custom theme in use, null when a built-in one is. */
    fun custom(context: Context): CustomTheme? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getString(KEY, null) != AppTheme.CUSTOM.name) return null
        return CustomThemes.get(context, sp.getString(CUSTOM_KEY, null))
    }

    /** Uses the custom theme [id]. */
    fun setCustom(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, AppTheme.CUSTOM.name); putString(CUSTOM_KEY, id) }
        refresh(context)
    }

    /** After a custom theme was changed: the night mode follows its mode (screens restyle themselves when next shown). */
    fun refresh(context: Context) = applyNightMode(current(context), custom(context))

    /** What an open screen compares to know whether it must restyle itself. */
    private fun signature(context: Context): String {
        val c = custom(context)
        return current(context).name + (c?.let { ":" + it.id + ":" + CustomThemes.stamp(context, it) } ?: "")
    }

    // The animated header, title and banner: a custom theme chooses its own; built-in ones have theirs.
    fun headerStyle(context: Context): ThemeHeaderView.Style? {
        val c = custom(context)
        return if (c != null) c.header?.let { h -> runCatching { ThemeHeaderView.Style.valueOf(h) }.getOrNull() } else current(context).header
    }

    fun appTitle(context: Context): String? {
        val c = custom(context)
        return if (c != null) c.title?.takeIf { it.isNotBlank() } else current(context).appTitle
    }

    /** True once the user has picked a theme (the setup wizard requires one). */
    fun isChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun set(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, theme.name) }
        applyNightMode(theme, custom(context))
    }

    /**
     * Status colours (category tags, radar blips) adjusted for the theme. Night
     * Drive maps every hue onto red with the same brightness, so nothing on
     * screen emits the blue/green light that ruins night vision.
     */
    fun ink(context: Context, color: Int): Int {
        if (current(context) != AppTheme.NIGHT_DRIVE) return color
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
        val lum = (0.3 * r + 0.59 * g + 0.11 * b).toInt()
        val red = (90 + lum * 165 / 255).coerceIn(90, 255)
        return (color and 0xFF000000.toInt()) or (red shl 16) or ((red / 10) shl 8) or (red / 14)
    }

    val isDynamicColorAvailable: Boolean get() = DynamicColors.isDynamicColorAvailable()

    /**
     * Optional user-chosen banner image for the styled themes (e.g. a logo the user
     * owns). Copied into private app storage; never bundled or shared.
     */
    private fun globalBannerFile(context: Context) = java.io.File(context.filesDir, "theme_banner")

    /** The banner in use: a custom theme's own, else the one picked in Settings. */
    fun bannerFile(context: Context): java.io.File =
        custom(context)?.let { CustomThemes.bannerFile(context, it) } ?: globalBannerFile(context)

    fun hasBanner(context: Context) = bannerFile(context).let { it.exists() && it.length() > 0 }

    /** The banner picked in Settings (for the built-in styled themes). */
    fun hasGlobalBanner(context: Context) = globalBannerFile(context).let { it.exists() && it.length() > 0 }

    /** Copies the picked image into app storage (max 8 MB). Returns false if unreadable. */
    fun setBanner(context: Context, uri: android.net.Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = input.readBytes()
            require(bytes.size in 1..8 * 1024 * 1024)
            // Verify it decodes as an image before accepting it.
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            require(opts.outWidth > 0 && opts.outHeight > 0)
            globalBannerFile(context).writeBytes(bytes)
        }
        true
    }.getOrDefault(false)

    fun clearBanner(context: Context) {
        globalBannerFile(context).delete()
    }

    fun install(app: Application) {
        applyNightMode(current(app), custom(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = style(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // API < 29 has no pre-created callback; this still runs before setContentView.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) style(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                val was = applied[activity]
                if (was != null && was != signature(activity)) activity.recreate()
            }
            // A custom theme's font, set on the screen's texts once it is up.
            override fun onActivityStarted(activity: Activity) {
                val c = custom(activity)
                FontApplier.attach(activity, c?.let { FontApplier.typeface(activity, it) })
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { applied.remove(activity); FontApplier.detach(activity) }
        })
    }

    private fun style(activity: Activity) {
        val theme = current(activity)
        activity.setTheme(theme.style)
        if (theme == AppTheme.MATERIAL_YOU) DynamicColors.applyToActivityIfAvailable(activity)
        custom(activity)?.let { runCatching { CustomColors.apply(activity, it) } }
        applied[activity] = signature(activity)
    }

    private fun applyNightMode(theme: AppTheme, custom: CustomTheme? = null) {
        val night = if (theme == AppTheme.CUSTOM && custom != null) when (CustomColors.Mode.of(custom.mode)) {
            CustomColors.Mode.LIGHT -> false
            CustomColors.Mode.DARK, CustomColors.Mode.BLACK -> true
            CustomColors.Mode.SYSTEM -> null
        } else theme.night
        AppCompatDelegate.setDefaultNightMode(
            when (night) {
                false -> AppCompatDelegate.MODE_NIGHT_NO
                true -> AppCompatDelegate.MODE_NIGHT_YES
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
