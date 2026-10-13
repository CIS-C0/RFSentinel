// Material's colour science (the palettes Material You makes) and its colour-resource override are marked internal to the library;
// they are what DynamicColors itself uses, pinned with the Material version and covered by CustomThemeTest.
@file:android.annotation.SuppressLint("RestrictedApi")

package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorInt
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.google.android.material.color.ThemeUtils
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeRainbow
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant

/**
 * The colours of a custom theme: Material 3's palette grown from the accent you pick (the same colour science Android's
 * own Material You uses), light or dark, or pure black for an OLED screen. Applied by overriding the colour resources the
 * Material 3 dynamic-colour theme overlay reads, which Android can do from version 11; older phones keep the standard
 * colours (the font, banner, header and marker still apply).
 */
object CustomColors {

    enum class Scheme(val key: String, val label: String) {
        TONAL("tonal", "Balanced"), VIBRANT("vibrant", "Vivid"), NEUTRAL("neutral", "Muted"), EXPRESSIVE("expressive", "Playful"),
        CONTENT("content", "Match my accent"), FIDELITY("fidelity", "Faithful"), MONOCHROME("monochrome", "Grey"),
        RAINBOW("rainbow", "Rainbow"), FRUIT("fruit", "Fruit salad");

        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: TONAL }
    }

    enum class Mode(val key: String, val label: String) {
        SYSTEM("system", "Follow the phone"), LIGHT("light", "Light"), DARK("dark", "Dark"), BLACK("black", "Pure black (OLED)");

        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM }
    }

    /** Whether this phone can recolour the app (Android 11 or newer). */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Dark or light, as this theme's mode says (the phone's own setting for "follow"). */
    fun isDark(context: Context, t: CustomTheme): Boolean = when (Mode.of(t.mode)) {
        Mode.LIGHT -> false
        Mode.DARK, Mode.BLACK -> true
        Mode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun scheme(t: CustomTheme, dark: Boolean): DynamicScheme {
        val hct = Hct.fromInt(t.accent)
        return when (Scheme.of(t.scheme)) {
            Scheme.TONAL -> SchemeTonalSpot(hct, dark, 0.0)
            Scheme.VIBRANT -> SchemeVibrant(hct, dark, 0.0)
            Scheme.NEUTRAL -> SchemeNeutral(hct, dark, 0.0)
            Scheme.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
            Scheme.CONTENT -> SchemeContent(hct, dark, 0.0)
            Scheme.FIDELITY -> SchemeFidelity(hct, dark, 0.0)
            Scheme.MONOCHROME -> SchemeMonochrome(hct, dark, 0.0)
            Scheme.RAINBOW -> SchemeRainbow(hct, dark, 0.0)
            Scheme.FRUIT -> SchemeFruitSalad(hct, dark, 0.0)
        }
    }

    /**
     * The colour resources to override and their new values. With the pure-black mode the dark backgrounds and surfaces
     * become black (the raised containers a step lighter, so cards still show).
     */
    fun overrides(context: Context, t: CustomTheme, dark: Boolean = isDark(context, t)): Map<Int, Int> {
        val map = HashMap(MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(scheme(t, dark)))
        if (dark && Mode.of(t.mode) == Mode.BLACK) {
            for (id in map.keys.toList()) {
                val name = runCatching { context.resources.getResourceEntryName(id) }.getOrNull() ?: continue
                blackFor(name)?.let { map[id] = it }
            }
        }
        return map
    }

    /**
     * The theme's colours by token name ("primary", "surface_container", "on_surface"...), read straight from the palette:
     * what the maker's preview paints with (Android caches resource colours, so a preview through resources would not change).
     */
    fun palette(context: Context, t: CustomTheme, dark: Boolean): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((id, c) in overrides(context, t, dark)) {
            val name = runCatching { context.resources.getResourceEntryName(id) }.getOrNull() ?: continue
            if (name.startsWith("material_personalized_color_")) out[name.removePrefix("material_personalized_color_")] = c
        }
        return out
    }

    /** The colour for a background / surface token in the pure-black mode (the overrides are of the dark palette then); null for any other token. */
    internal fun blackFor(entryName: String): Int? {
        if (!entryName.startsWith("material_personalized_color_")) return null
        return when (entryName.removePrefix("material_personalized_color_")) {
            "background", "surface", "surface_dim", "surface_container_lowest" -> 0xFF000000.toInt()
            "surface_container_low" -> 0xFF0A0A0A.toInt()
            "surface_container" -> 0xFF111111.toInt()
            "surface_container_high" -> 0xFF181818.toInt()
            "surface_container_highest", "surface_bright", "surface_variant" -> 0xFF202020.toInt()
            else -> null
        }
    }

    private val overlay get() = com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight

    /** Recolours an activity that is being created (before its views exist). False when this phone can't. */
    fun apply(activity: Activity, t: CustomTheme): Boolean {
        if (!available) return false
        ThemeUtils.applyThemeOverlay(activity, overlay)
        return ColorResourcesOverride.getInstance()?.applyIfPossible(activity, overrides(activity, t)) ?: false
    }

    /** A context in which views come out in this theme's colours (the maker's preview); [dark] says which way round. */
    fun preview(base: Context, t: CustomTheme, dark: Boolean): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        var ctx: Context = base.createConfigurationContext(cfg)
        if (available) ctx = ColorResourcesOverride.getInstance()?.wrapContextIfPossible(ctx, overrides(ctx, t, dark)) ?: ctx
        return androidx.appcompat.view.ContextThemeWrapper(ctx, com.rfsentinel.app.R.style.Theme_RFSentinel_Custom).also {
            if (available) it.theme.applyStyle(overlay, true)
        }
    }

    @ColorInt fun accentOf(t: CustomTheme): Int = t.accent
}
