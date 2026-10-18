package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The main screen's status in the visual style: counters for what's heard (Bluetooth, WiFi, cells,
 * flagged), a warning chip when there is one, and a card per piece of USB / ESP32 hardware with a
 * coloured dot for its state and, for the WiFi adapter and the RTL-SDR, a line of its packets per second.
 */
class StatusPanel(private val context: Context) {

    /** One line of hardware status ("USB WiFi (MT7612U) · live · …") with an optional per-second counter. */
    class Hardware(val status: String, val counter: (() -> Long)? = null, val unit: String = "/s")

    private val dp = context.resources.displayMetrics.density
    private val accent = ChipStyle.accent(context).first
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val counters = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val note = TextView(context).apply { textSize = 12f; visibility = View.GONE }
    private val cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val counterViews = List(4) { pill() }

    /** Per hardware name: the counter's last reading and its rate over the last minute. */
    private class Rate(var last: Long = -1, var at: Long = 0, val history: ArrayDeque<Float> = ArrayDeque())
    private val rates = HashMap<String, Rate>()
    private var cardKey = ""

    init {
        counterViews.forEachIndexed { i, v ->
            // A fixed height so the text can shrink to fit narrow screens ("Flagged 12" on a small phone).
            counters.addView(v, LinearLayout.LayoutParams(0, (26 * dp).toInt(), 1f).apply { if (i > 0) marginStart = (5 * dp).toInt() })
        }
        root.addView(counters)
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * dp).toInt() })
        root.addView(cards)
        root.setPadding(0, 0, 0, (6 * dp).toInt())
    }

    private fun pill() = TextView(context).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    private fun setPill(v: TextView, text: String, color: Int) {
        if (v.text != text) v.text = text
        VisualMode.chip(v, color)
        v.setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
    }

    /**
     * [running]: scanning; [ble], [wifi], [cells], [flagged]: what's heard now; [warning]: a fake-cell or
     * restarted-scan note; [hardware]: the non-blank hardware status lines.
     */
    fun update(running: Boolean, ble: Int, wifi: Int, cells: Int, flagged: Int, warning: String?, hardware: List<Hardware>) {
        val grey = (ChipStyle.onSurface(context) and 0x00FFFFFF) or 0x99000000.toInt()
        setPill(counterViews[0], "BLE $ble", if (running) accent else grey)
        setPill(counterViews[1], "WiFi $wifi", if (running) accent else grey)
        setPill(counterViews[2], "Cells $cells", if (running) accent else grey)
        setPill(counterViews[3], "Flagged $flagged", if (flagged > 0) 0xFFD0503C.toInt() else if (running) accent else grey)
        val shown = if (!running) "Idle - tap Start to listen for nearby devices" else warning?.let { "⚠ $it" }
        note.visibility = if (shown != null) View.VISIBLE else View.GONE
        if (shown != null) {
            VisualMode.text(note, shown)
            VisualMode.chip(note, if (running) 0xFFE0A33A.toInt() else grey)
        }
        showHardware(hardware)
    }

    private fun showHardware(list: List<Hardware>) {
        val now = System.currentTimeMillis()
        val key = list.joinToString("|") { name(it.status) }
        if (key != cardKey) {
            cardKey = key
            cards.removeAllViews()
            list.forEach { cards.addView(card(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * dp).toInt() }) }
            rates.keys.retainAll(list.map { name(it.status) }.toSet())
        }
        list.forEachIndexed { i, h -> bind(cards.getChildAt(i) as LinearLayout, h, now) }
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (8 * dp).toInt(); setPadding((10 * dp).toInt(), p, (10 * dp).toInt(), p)
        addView(View(context), LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(TextView(context).apply { textSize = 12f; alpha = 0.8f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(SparklineView(context), LinearLayout.LayoutParams((64 * dp).toInt(), (22 * dp).toInt()))
            addView(TextView(context).apply { textSize = 10f; alpha = 0.75f; gravity = Gravity.END })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
    }

    private fun bind(card: LinearLayout, h: Hardware, now: Long) {
        val color = when (stateOf(h.status)) {
            State.ERROR -> 0xFFD0503C.toInt()
            State.WAIT -> 0xFFE0A33A.toInt()
            State.LIVE -> 0xFF2E9E5B.toInt()
        }
        card.background = VisualMode.card(context, color, 0x16, 0x66, 12f)
        card.getChildAt(0).background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val texts = card.getChildAt(1) as LinearLayout
        val name = name(h.status)
        VisualMode.text(texts.getChildAt(0) as TextView, name)
        VisualMode.text(texts.getChildAt(1) as TextView, detail(h.status))
        val side = card.getChildAt(2) as LinearLayout
        val counter = h.counter
        side.visibility = if (counter != null) View.VISIBLE else View.GONE
        if (counter == null) return
        // Rate over each refresh, the last minute of it as a line.
        val r = rates.getOrPut(name) { Rate() }
        val n = counter()
        if (r.last >= 0 && now - r.at >= 900) {
            r.history.addLast((n - r.last).coerceAtLeast(0) * 1000f / (now - r.at))
            while (r.history.size > 60) r.history.removeFirst()
        }
        if (r.last < 0 || now - r.at >= 900) { r.last = n; r.at = now }
        (side.getChildAt(0) as SparklineView).set(r.history.toFloatArray(), color, 5f)
        VisualMode.text(side.getChildAt(1) as TextView, r.history.lastOrNull()?.let { "%.0f%s".format(it, h.unit) } ?: "–")
    }

    private enum class State { LIVE, WAIT, ERROR }

    private fun stateOf(s: String): State {
        val l = s.lowercase()
        return when {
            listOf("error", "couldn't", "isn't supported", "didn't lock", "failed", "isn't the", "is off", "disconnected").any { it in l } -> State.ERROR
            listOf("starting", "allow", "found", "needs", "learning", "connecting", "reconnecting", "setting up", "looking up", "tap ",
                "waiting", "listening on").any { it in l } -> State.WAIT
            else -> State.LIVE
        }
    }

    /** "USB WiFi (MT7612U)" from "USB WiFi (MT7612U) · live · …", "OUI-SPY" from "OUI-SPY: connecting…". */
    private fun name(s: String): String {
        val cut = listOf(" · ", ": ", " - ").mapNotNull { sep -> s.indexOf(sep).takeIf { it > 0 } }.minOrNull() ?: return s
        return s.substring(0, cut)
    }

    private fun detail(s: String): String {
        val n = name(s)
        return s.removePrefix(n).trimStart(' ', '·', ':', '-').replaceFirstChar { it.uppercase() }
    }
}
