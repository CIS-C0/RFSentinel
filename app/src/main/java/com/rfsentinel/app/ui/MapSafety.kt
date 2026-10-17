package com.rfsentinel.app.ui

import android.content.Context
import android.content.pm.PackageManager
import com.rfsentinel.app.util.Prefs
import org.maplibre.android.RenderingEngine

/**
 * A map that closes the app while it starts must not do it again at every launch. The map's own start-up
 * is marked on disk before it begins ([arm]) and cleared a few seconds after the map says it is ready (or the screen
 * pauses normally, [disarm]). If the next launch finds the mark still there the app died in it, and [recover]
 * steps down once at a time: the first time it makes the OpenFreeMap map use the OpenGL renderer instead of
 * Vulkan (a phone that claims Vulkan but cannot run it), the next time it takes the map off the main screen so
 * the rest of the app opens. The screens tell the user either way.
 *
 * (A phone whose graphics or native map libraries cannot run the map would otherwise crash again each time
 * the saved view, which includes the map, is restored - until the app's data is cleared.)
 */
internal object MapSafety {
    /** What [recover] did. */
    enum class Recovery { NONE, OPENGL, MAP_OFF }

    private const val FILE = "map_safety"
    private const val ARMED = "armed_at"
    private const val PROVIDER = "armed_provider"
    private const val CRASHES = "crashes"
    private const val OPENGL = "opengl"

    /** How long after the map says it is ready its start-up counts as survived. */
    const val SETTLE_MS = 3_000L

    /** A map is starting in this very process (so the mark on disk is ours, not a crash's). */
    @Volatile internal var live = false

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Marks the map's start-up (synchronously: it must be on disk before a native crash can end the process). */
    fun arm(context: Context, provider: MapEngine.Provider, now: Long = System.currentTimeMillis()) {
        live = true
        sp(context).edit().putLong(ARMED, now).putString(PROVIDER, provider.name).commit()
    }

    fun disarm(context: Context) {
        live = false
        if (sp(context).contains(ARMED)) sp(context).edit().remove(ARMED).remove(PROVIDER).apply()
    }

    /** True when the last map start-up never finished. */
    fun crashedLastTime(context: Context): Boolean = !live && sp(context).contains(ARMED)

    /** How many times in a row the map's start-up has been found unfinished (reset when it works). */
    fun crashes(context: Context): Int = sp(context).getInt(CRASHES, 0)

    /** The OpenFreeMap map has been told to use OpenGL, not Vulkan. */
    fun openGl(context: Context): Boolean = sp(context).getBoolean(OPENGL, false)

    /**
     * The renderer MapLibre must use, or null to let it choose (Vulkan). A phone without Vulkan gets OpenGL from the
     * start: MapLibre does not fall back by itself, it throws "No Vulkan compatible GPU found" and the app closes once.
     */
    fun renderer(context: Context): RenderingEngine.Type? =
        if (openGl(context) || !hasVulkan(context)) RenderingEngine.Type.OPENGL else null

    private fun hasVulkan(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)

    /**
     * If the last start-up never finished: forgets the mark and counts it. The first time for the OpenFreeMap map
     * it switches it to OpenGL and leaves the map where it is ([Recovery.OPENGL]); otherwise it takes the map out of
     * the saved main screen view (the list is the way back when it was the only one, [Recovery.MAP_OFF]).
     */
    fun recover(context: Context): Recovery {
        if (!crashedLastTime(context)) return Recovery.NONE
        val provider = sp(context).getString(PROVIDER, null)
        val edit = sp(context).edit().remove(ARMED).remove(PROVIDER).putInt(CRASHES, crashes(context) + 1)
        if (provider != MapEngine.Provider.MAPBOX.name && !openGl(context)) {
            edit.putBoolean(OPENGL, true).commit()
            return Recovery.OPENGL
        }
        edit.commit()
        val panes = Prefs.viewPanes(context)
        if ("map" in panes) Prefs.setViewPanes(context, panes - "map")
        return Recovery.MAP_OFF
    }

    /** The map has been up for a few seconds: whatever came before was not a crash in it. */
    fun worked(context: Context) {
        disarm(context)
        if (crashes(context) != 0) sp(context).edit().putInt(CRASHES, 0).apply()
    }
}
