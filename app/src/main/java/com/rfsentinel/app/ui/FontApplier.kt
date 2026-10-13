package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.rfsentinel.app.R
import java.util.WeakHashMap

/**
 * The font of a custom theme. Android themes can't choose a font from a file at run time, so every text on a screen is set to
 * it after the screen is laid out (and again when lists and dialogs inside it add new views). Bold and italic are kept.
 * System dialogs and the keyboard keep the phone's font.
 */
object FontApplier {

    /** The built-in choices: the phone's own font families, and the app's bundled Share Tech Mono. */
    class Family(val key: String, val label: String, val system: String?)

    val families = listOf(
        Family("default", "Standard", null),
        Family("medium", "Medium sans", "sans-serif-medium"),
        Family("light", "Light sans", "sans-serif-light"),
        Family("condensed", "Condensed", "sans-serif-condensed"),
        Family("serif", "Serif", "serif"),
        Family("mono", "Monospace", "monospace"),
        Family("serif-mono", "Typewriter", "serif-monospace"),
        Family("casual", "Casual", "casual"),
        Family("cursive", "Cursive", "cursive"),
        Family("tech", "Share Tech Mono (the DedSec look)", null)
    )

    val keys: Set<String> = families.map { it.key }.toSet()

    /** The typeface for a theme, or null for the standard font. */
    fun typeface(context: Context, t: CustomTheme): Typeface? = when {
        t.font == "default" -> null
        t.font == "file" -> CustomThemes.fontFile(context, t)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        t.font == "tech" -> runCatching { ResourcesCompat.getFont(context, R.font.share_tech_mono) }.getOrNull()
        else -> families.firstOrNull { it.key == t.font }?.system?.let { Typeface.create(it, Typeface.NORMAL) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    /** Starts keeping [activity]'s texts in [base]; a null [base] stops it (the standard font is back at the next screen). */
    fun attach(activity: Activity, base: Typeface?) {
        detach(activity)
        if (base == null) return
        val decor = activity.window?.decorView ?: return
        var pending = false
        val run = Runnable { pending = false; apply(decor, base) }
        val l = ViewTreeObserver.OnGlobalLayoutListener { if (!pending) { pending = true; main.postDelayed(run, 120) } }
        listeners[activity] = l
        decor.viewTreeObserver.addOnGlobalLayoutListener(l)
        apply(decor, base)
    }

    fun detach(activity: Activity) {
        listeners.remove(activity)?.let { l -> runCatching { activity.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(l) } }
    }

    /** Sets the font on every text under [root] (and on [root] itself). */
    fun apply(root: View, base: Typeface) {
        if (root is TextView) {
            val style = root.typeface?.style ?: Typeface.NORMAL
            val want = Typeface.create(base, style)
            if (root.typeface != want) root.typeface = want
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) apply(root.getChildAt(i), base)
    }
}
