package com.rfsentinel.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The main screen's hardware strip: folded by default, unfolds on a tap, ✕ hides it until a state changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatusPanelTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun shownTexts(v: View): List<String> {
        if (v.visibility != View.VISIBLE) return emptyList()
        val own = (v as? TextView)?.let { listOf(it.text.toString()) }.orEmpty()
        return own + if (v is ViewGroup) (0 until v.childCount).flatMap { shownTexts(v.getChildAt(it)) } else emptyList()
    }

    private fun button(v: View, desc: String): View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) button(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    private val live = listOf(
        StatusPanel.Hardware("OUI-SPY: connecting..."),
        StatusPanel.Hardware("RTL8812BU · live · 32 access points", { 0L }, " frames/s")
    )

    @Test
    fun foldsUnfoldsAndHides() {
        val p = StatusPanel(ctx).also { it.root.visibility = View.VISIBLE }
        p.update(running = true, warning = null, hardware = live)
        var t = shownTexts(p.root)
        assertTrue(t.toString(), t.any { it.startsWith("● OUI-SPY") })
        assertTrue(t.toString(), t.any { it.startsWith("● RTL8812BU") })
        assertFalse("folded: no detail lines", t.any { it.contains("32 access points") })

        button(p.root, "Show the hardware details")!!.performClick()
        t = shownTexts(p.root)
        assertTrue(t.toString(), t.contains("External hardware"))
        assertTrue(t.toString(), t.any { it.contains("32 access points") })

        button(p.root, "Fold the hardware details")!!.performClick()
        assertFalse(shownTexts(p.root).any { it.contains("32 access points") })

        button(p.root, "Hide the hardware status until something changes")!!.performClick()
        p.update(running = true, warning = null, hardware = live)
        assertTrue(shownTexts(p.root).none { it.contains("OUI-SPY") })

        // OUI-SPY goes from connecting to live: the strip is back.
        p.update(running = true, warning = null, hardware = listOf(StatusPanel.Hardware("OUI-SPY: live"), live[1]))
        assertTrue(shownTexts(p.root).any { it.startsWith("● OUI-SPY") })
    }

    @Test
    fun warningOnlyWhileScanning() {
        val p = StatusPanel(ctx).also { it.root.visibility = View.VISIBLE }
        p.update(running = false, warning = null, hardware = emptyList())
        assertEquals(emptyList<String>(), shownTexts(p.root))
        p.update(running = true, warning = "Bluetooth scan restarted", hardware = emptyList())
        assertEquals(listOf("⚠ Bluetooth scan restarted"), shownTexts(p.root))
    }
}
