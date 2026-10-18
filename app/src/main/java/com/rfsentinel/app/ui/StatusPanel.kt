package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The main screen's status in the visual style: a warning chip while scanning when there is one, and the USB / ESP32
 * hardware. The hardware starts folded into one thin strip of chips (a coloured dot for its state and,
 * for the WiFi adapter and the RTL-SDR, its packets per second); tap the strip to unfold a card per
 * device with a line of its rate. ✕ hides the hardware until one of them changes state.
 * The device counts are on the filter chips below.
 */
class StatusPanel(private val context: Context) {

    /** One line of hardware status ("USB WiFi (MT7612U) · live · …") with an optional per-second counter. */
    class Hardware(val status: String, val counter: (() -> Long)? = null, val unit: String = "/s")

    private val dp = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("rf_sentinel_prefs", Context.MODE_PRIVATE)
    private var folded: Boolean
        get() = prefs.getBoolean("status_hw_folded", true)
        set(v) { prefs.edit().putBoolean("status_hw_folded", v).apply() }
    /** The hardware states when ✕ was tapped; the strip comes back when they change. */
    private var hiddenAt: String?
        get() = prefs.getString("status_hw_hidden", null)
        set(v) { prefs.edit().putString("status_hw_hidden", v).apply() }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val note = TextView(context).apply { textSize = 12f; visibility = View.GONE }
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val title = TextView(context).apply {
        text = "External hardware"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); alpha = 0.8f
        setPadding((4 * dp).toInt(), 0, 0, 0)
    }
    private val foldButton = iconButton("▾", "Show the hardware details")
    private val hideButton = iconButton("✕", "Hide the hardware status until something changes")
    private val cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Per hardware name: the counter's last reading and its rate over the last minute. */
    private class Rate(var last: Long = -1, var at: Long = 0, val history: ArrayDeque<Float> = ArrayDeque())
    private val rates = HashMap<String, Rate>()
    private var cardKey = ""
    private var last: List<Hardware> = emptyList()

    init {
        chipScroll.addView(chips)
        strip.addView(chipScroll, LinearLayout.LayoutParams(0, -2, 1f))
        strip.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        strip.addView(foldButton)
        strip.addView(hideButton)
        // The whole strip folds and unfolds; the chips scroll sideways when there are many.
        val toggle = View.OnClickListener { folded = !folded; showHardware(last) }
        strip.setOnClickListener(toggle)
        chips.setOnClickListener(toggle)
        title.setOnClickListener(toggle)
        foldButton.setOnClickListener(toggle)
        hideButton.setOnClickListener { hiddenAt = signature(last); showHardware(last) }
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (4 * dp).toInt() })
        root.addView(strip, LinearLayout.LayoutParams(-1, -2))
        root.addView(cards)
        root.setPadding(0, 0, 0, (4 * dp).toInt())
    }

    private fun iconButton(text: String, description: String) = TextView(context).apply {
        this.text = text
        contentDescription = description
        textSize = 14f
        gravity = Gravity.CENTER
        alpha = 0.8f
        minWidth = (36 * dp).toInt()
        minHeight = (32 * dp).toInt()
        isClickable = true
        isFocusable = true
    }

    /** [running]: scanning; [warning]: a fake-cell or restarted-scan note; [hardware]: the non-blank hardware status lines. */
    fun update(running: Boolean, warning: String?, hardware: List<Hardware>) {
        val grey = (ChipStyle.onSurface(context) and 0x00FFFFFF) or 0x99000000.toInt()
        // Idle needs no note here: the Start button says it.
        val shown = if (!running) null else warning?.let { "⚠ $it" }
        note.visibility = if (shown != null) View.VISIBLE else View.GONE
        if (shown != null) {
            VisualMode.text(note, shown)
            VisualMode.chip(note, if (running) 0xFFE0A33A.toInt() else grey)
        }
        showHardware(hardware)
    }

    private fun showHardware(list: List<Hardware>) {
        last = list
        val now = System.currentTimeMillis()
        val sig = signature(list)
        hiddenAt?.let { if (it != sig && list.isNotEmpty()) hiddenAt = null }
        val hidden = list.isEmpty() || hiddenAt == sig
        strip.visibility = if (hidden) View.GONE else View.VISIBLE
        val rateText = list.associate { h -> name(h.status) to sample(h, now) }
        val unfolded = !hidden && !folded
        cards.visibility = if (unfolded) View.VISIBLE else View.GONE
        chipScroll.visibility = if (unfolded) View.GONE else View.VISIBLE
        title.visibility = if (unfolded) View.VISIBLE else View.GONE
        VisualMode.text(foldButton, if (unfolded) "▴" else "▾")
        foldButton.contentDescription = if (unfolded) "Fold the hardware details" else "Show the hardware details"
        if (hidden) return

        val key = list.joinToString("|") { name(it.status) }
        if (key != cardKey) {
            cardKey = key
            cards.removeAllViews()
            chips.removeAllViews()
            list.forEach { _ ->
                cards.addView(card(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
                chips.addView(TextView(context).apply { textSize = 12f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
                    setOnClickListener { folded = !folded; showHardware(last) } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
            }
            rates.keys.retainAll(list.map { name(it.status) }.toSet())
        }
        list.forEachIndexed { i, h ->
            val color = colorOf(h.status)
            val n = name(h.status)
            val chip = chips.getChildAt(i) as TextView
            VisualMode.text(chip, "● $n" + (rateText[n]?.let { " · $it" } ?: ""))
            VisualMode.chip(chip, color)
            chip.setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            if (unfolded) bind(cards.getChildAt(i) as LinearLayout, h, color, rateText[n])
        }
    }

    /** The names and states, to tell when something changed after ✕. */
    private fun signature(list: List<Hardware>) = list.joinToString("|") { name(it.status) + ":" + stateOf(it.status) }

    /** Takes a reading of [h]'s counter about once a second; the latest rate as text, or null without a counter. */
    private fun sample(h: Hardware, now: Long): String? {
        val counter = h.counter ?: return null
        val r = rates.getOrPut(name(h.status)) { Rate() }
        val n = counter()
        if (r.last >= 0 && now - r.at >= 900) {
            r.history.addLast((n - r.last).coerceAtLeast(0) * 1000f / (now - r.at))
            while (r.history.size > 60) r.history.removeFirst()
        }
        if (r.last < 0 || now - r.at >= 900) { r.last = n; r.at = now }
        return r.history.lastOrNull()?.let { "%.0f%s".format(it, h.unit) } ?: "–"
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (6 * dp).toInt(); setPadding((10 * dp).toInt(), p, (10 * dp).toInt(), p)
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

    private fun bind(card: LinearLayout, h: Hardware, color: Int, rate: String?) {
        if (card.getTag(com.rfsentinel.app.R.id.visual_chip_color) != color) {
            card.setTag(com.rfsentinel.app.R.id.visual_chip_color, color)
            card.background = VisualMode.card(context, color, 0x16, 0x66, 12f)
            card.getChildAt(0).background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
        val texts = card.getChildAt(1) as LinearLayout
        VisualMode.text(texts.getChildAt(0) as TextView, name(h.status))
        VisualMode.text(texts.getChildAt(1) as TextView, detail(h.status))
        val side = card.getChildAt(2) as LinearLayout
        side.visibility = if (rate != null) View.VISIBLE else View.GONE
        if (rate == null) return
        (side.getChildAt(0) as SparklineView).set(rates[name(h.status)]?.history?.toFloatArray() ?: FloatArray(0), color, 5f)
        VisualMode.text(side.getChildAt(1) as TextView, rate)
    }

    private enum class State { LIVE, WAIT, ERROR }

    private fun colorOf(s: String) = when (stateOf(s)) {
        State.ERROR -> 0xFFD0503C.toInt()
        State.WAIT -> 0xFFE0A33A.toInt()
        State.LIVE -> 0xFF2E9E5B.toInt()
    }

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
