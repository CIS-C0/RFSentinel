package com.rfsentinel.app.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ItemDeviceBinding

/** One row of the live "all nearby devices" list, fully pre-formatted. */
data class DeviceRow(
    val mac: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    /** e.g. "BODY CAM · strong 90%", or "WHITELISTED"; null for ordinary devices. */
    val tag: String?,
    val tagColor: Int,
    /** Row tint colour for flagged devices, else null. */
    val highlight: Int?,
    /** Pulse the tint (flagged, above the alert threshold, still in range). */
    val flashing: Boolean,
    val bold: Boolean,
    /** Heard by an ESP32 board (blue badge) and/or the phone's own radios (green badge). */
    val heardByEsp: Boolean = false,
    val heardByUsb: Boolean = false,
    /** Heard by a USB Bluetooth adapter. */
    val heardByUsbBt: Boolean = false,
    val heardByPhone: Boolean = false,
    /** The linked Sentinel that heard it (Sentinel Link), or null. */
    val heardByPeer: String? = null,
    /** For the visual style: the signal for the bars (null = none), and the match's confidence (null = not a match). */
    val rssi: Int? = null,
    val confidence: Int? = null,
    /** Added to [rssi] for the bars only: cell signals run about 10 dB lower than WiFi and Bluetooth. */
    val barBoost: Int = 0,
    /** A group's header ("Trackers · 3", Group by in the ⋮ menu): [title] only, not a device. */
    val header: Boolean = false,
    /** A flagged device: its row has the mute button ([com.rfsentinel.app.data.AlertMutes]). */
    val canMute: Boolean = false,
    val muted: Boolean = false,
    /** More boxes after the heard-by ones, label and colour ("NEAR 38 dB" on a talkgroup row). */
    val badges: List<Pair<String, Int>> = emptyList()
) {
    companion object {
        /** Keyed by the group ([key]), not its label: the count changing must not swap the row (it flickered). */
        fun header(key: String, label: String) = DeviceRow("header:$key", label, "", "", null, 0, null, false, true, header = true)
    }
}

private const val ESP_BADGE = 0xFF1E88E5.toInt()
private const val PHONE_BADGE = 0xFF2E7D32.toInt()
private const val USB_BADGE = 0xFF6A1B9A.toInt()
internal const val PEER_BADGE = 0xFF00838F.toInt()
internal const val NEAR_BADGE = 0xFFD0503C.toInt()

/** The meta line, followed by small coloured "ESP32" / "INTERNAL" / "VIA <Sentinel>" badges. */
private fun metaWithBadges(row: DeviceRow): CharSequence {
    if (!row.heardByEsp && !row.heardByUsb && !row.heardByUsbBt && !row.heardByPhone && row.heardByPeer == null && row.badges.isEmpty()) return row.meta
    val sb = android.text.SpannableStringBuilder(row.meta)
    fun badge(label: String, color: Int) {
        sb.append("  ")
        appendBadge(sb, label, color)
    }
    if (row.heardByEsp) badge("ESP32", ESP_BADGE)
    if (row.heardByUsb) badge("USB WIFI", USB_BADGE)
    if (row.heardByUsbBt) badge("USB BT", USB_BADGE)
    if (row.heardByPhone) badge("INTERNAL", PHONE_BADGE)
    row.heardByPeer?.let { badge("VIA " + it.uppercase(), PEER_BADGE) }
    row.badges.forEach { (label, color) -> badge(label, color) }
    return sb
}

/** A small white-on-[color] box ("ESP32", or the tag in a smaller row's title). */
private fun appendBadge(sb: android.text.SpannableStringBuilder, label: String, color: Int) {
    val start = sb.length
    sb.append(" ").append(label).append(" ")
    val end = sb.length
    sb.setSpan(android.text.style.BackgroundColorSpan(color), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

class DeviceAdapter(
    private val onClick: (DeviceRow) -> Unit,
    private val onLongPress: (DeviceRow) -> Unit,
    /** The mute button on a flagged row; [forever] on a long press. Null: no mute buttons. */
    private val onMute: ((row: DeviceRow, forever: Boolean) -> Unit)? = null
) : ListAdapter<DeviceRow, RecyclerView.ViewHolder>(Diff) {

    /** Visual style: signal bars and a confidence bar on each row (Settings > Appearance, or the list's own choice). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    var visual = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    /** Settings › Appearance › List size. A row with an alert colour ([DeviceRow.highlight]) stays Regular. */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    var size = com.rfsentinel.app.util.Prefs.ListSize.REGULAR
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    object Diff : DiffUtil.ItemCallback<DeviceRow>() {
        override fun areItemsTheSame(a: DeviceRow, b: DeviceRow) = a.mac == b.mac
        override fun areContentsTheSame(a: DeviceRow, b: DeviceRow) = a == b
    }

    inner class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        private var animator: ValueAnimator? = null
        private var animColor = 0
        val metaColors = binding.metaText.textColors
        /** The size this row's views are set up for: changing it re-lays the row out, so only when it changes. */
        var shownSize: com.rfsentinel.app.util.Prefs.ListSize? = null

        fun applySize(s: com.rfsentinel.app.util.Prefs.ListSize) {
            if (shownSize == s) return
            shownSize = s
            val small = s != com.rfsentinel.app.util.Prefs.ListSize.REGULAR
            val dense = s == com.rfsentinel.app.util.Prefs.ListSize.DENSE
            val res = binding.root.resources
            val dp = res.displayMetrics.density
            val pad = ((if (dense) 3 else if (small) 5 else 10) * dp).toInt()
            binding.rowBox.setPaddingRelative(binding.rowBox.paddingStart, pad, binding.rowBox.paddingEnd, pad)
            // A row stays a comfortable touch target at any size.
            binding.rowBox.minimumHeight = (48 * dp).toInt()
            fun TextView.size(regular: Int, smallSp: Float, lines: Int) {
                if (small) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, smallSp)
                else setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, res.getDimension(regular))
                includeFontPadding = !small
                maxLines = if (small) lines else Int.MAX_VALUE
                ellipsize = if (small) android.text.TextUtils.TruncateAt.END else null
            }
            binding.titleText.size(com.rfsentinel.app.R.dimen.text_subtitle, 14f, 1)
            binding.macText.size(com.rfsentinel.app.R.dimen.text_body, 13f, 1)
            binding.metaText.size(com.rfsentinel.app.R.dimen.text_caption, 12f, if (dense) 1 else 2)
            binding.macText.visibility = if (dense) View.GONE else View.VISIBLE
            (binding.confidenceBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = ((if (small) 3 else 5) * dp).toInt()
            binding.signalBars.layoutParams = binding.signalBars.layoutParams.apply {
                width = ((if (small) 22 else 28) * dp).toInt(); height = ((if (small) 17 else 22) * dp).toInt()
            }
            binding.rowBox.requestLayout()
        }

        init {
            binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    currentList.getOrNull(bindingAdapterPosition)?.let { setHighlight(it.highlight, it.flashing) }
                }
                override fun onViewDetachedFromWindow(view: View) = stopFlash()
            })
        }

        /**
         * Pulses the row background while flashing. A running pulse of the same
         * colour is left alone on rebind (rows rebind every second as RSSI and
         * age change) so it doesn't visibly restart.
         */
        fun setHighlight(color: Int?, flashing: Boolean) {
            if (color == null) {
                stopFlash()
                binding.root.setBackgroundColor(Color.TRANSPARENT)
                return
            }
            val low = (color and 0x00FFFFFF) or 0x26000000
            val high = (color and 0x00FFFFFF) or 0xE6000000.toInt()
            if (flashing && binding.root.isAttachedToWindow) {
                if (animator?.isRunning == true && animColor == color) return
                stopFlash()
                animColor = color
                // The animator only ticks frames; the shade comes from the frame clock, so every row is on one beat
                // however late its first frame came (a seek before it was lost on a busy frame: aircraft rows lagged).
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2 * FLASH_MS
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { binding.root.setBackgroundColor(flashColor(low, high, android.view.animation.AnimationUtils.currentAnimationTimeMillis())) }
                    start()
                }
            } else {
                stopFlash()
                binding.root.setBackgroundColor(low)
            }
        }

        fun stopFlash() {
            animator?.cancel()
            animator = null
        }
    }

    /** A group's header: bold, in the accent colour, like the settings' section headers. */
    class HeaderVH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun getItemViewType(position: Int) = if (getItem(position).header) HEADER else DEVICE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == HEADER) HeaderVH(groupHeader(parent))
        else VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder is HeaderVH) { holder.text.text = row.title; return }
        holder as VH
        val b = holder.binding
        // Alerting rows stay full size, so they stand out among smaller ones.
        val rowSize = if (row.highlight != null) com.rfsentinel.app.util.Prefs.ListSize.REGULAR else size
        val small = rowSize != com.rfsentinel.app.util.Prefs.ListSize.REGULAR
        val dense = rowSize == com.rfsentinel.app.util.Prefs.ListSize.DENSE
        holder.applySize(rowSize)
        if (row.tag != null && !small) {
            b.tagText.visibility = View.VISIBLE
            b.tagText.text = row.tag
            b.tagText.background.mutate().setTint(row.tagColor)
        } else {
            b.tagText.visibility = View.GONE
        }
        // Smaller rows: the tag goes in front of the title instead of on a line of its own.
        b.titleText.text = if (row.tag != null && small)
            android.text.SpannableStringBuilder().also { appendBadge(it, row.tag, row.tagColor); it.append("  ").append(row.title) }
        else row.title
        b.titleText.setTypeface(b.titleText.typeface, if (row.bold) Typeface.BOLD else Typeface.NORMAL)
        // Only an address is monospaced: names and distances read as normal text.
        val subtitle: CharSequence = if (row.subtitle.startsWith(row.mac)) android.text.SpannableString(row.subtitle).apply {
            setSpan(android.text.style.TypefaceSpan("monospace"), 0, row.mac.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else row.subtitle
        b.macText.text = subtitle
        val meta = metaWithBadges(row)
        // Dense: the address and the details share one line.
        b.metaText.text = if (dense && subtitle.isNotBlank() && meta.isNotBlank())
            android.text.SpannableStringBuilder(subtitle).append("  ·  ").append(meta)
        else if (dense && subtitle.isNotBlank()) subtitle
        else meta
        // Grey is hard to read on an alert's colour.
        b.metaText.setTextColor(if (row.highlight != null) b.macText.textColors else holder.metaColors)
        b.signalText.setTextColor(if (row.highlight != null) b.macText.textColors else holder.metaColors)
        val conf = row.confidence
        // Dense: the tag already says the confidence, and the bars the signal without the number.
        b.confidenceBar.visibility = if (visual && conf != null && !dense) View.VISIBLE else View.GONE
        if (visual && conf != null && !dense) b.confidenceBar.set(conf / 100f, row.tagColor)
        b.signalBox.visibility = if (visual && row.rssi != null) View.VISIBLE else View.GONE
        b.signalText.visibility = if (dense) View.GONE else View.VISIBLE
        if (visual && row.rssi != null) {
            b.signalBars.set(row.rssi + row.barBoost)
            b.signalText.text = "${row.rssi}"
        }
        val mute = onMute?.takeIf { row.canMute }
        b.muteButton.visibility = if (mute != null) View.VISIBLE else View.GONE
        if (mute != null) {
            // Muted: in the highlight colour; tap again to unmute.
            b.muteButton.imageTintList = android.content.res.ColorStateList.valueOf(
                if (row.muted) ChipStyle.accent(b.root.context).first else ChipStyle.onSurface(b.root.context))
            b.muteButton.alpha = if (row.muted) 1f else 0.6f
            b.muteButton.contentDescription = if (row.muted) "Unmute ${row.title}"
                else "Mute ${row.title} for 30 minutes. Long-press to mute until turned back on."
            b.muteButton.setOnClickListener { mute(row, false) }
            b.muteButton.setOnLongClickListener { mute(row, true); true }
        }
        holder.setHighlight(row.highlight, row.flashing)
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener { onLongPress(row); true }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? VH)?.stopFlash()
        super.onViewRecycled(holder)
    }

    companion object {
        private const val DEVICE = 0
        private const val HEADER = 1
        /** Half a flash: [low] to high, then back. */
        internal const val FLASH_MS = 450L
        private val flashCurve = android.view.animation.AccelerateDecelerateInterpolator()

        /** A flashing row's shade at [timeMs]: the same for every row at the same moment. */
        internal fun flashColor(low: Int, high: Int, timeMs: Long): Int {
            val t = (timeMs % (2 * FLASH_MS)).toFloat()
            val f = if (t < FLASH_MS) t / FLASH_MS else (2 * FLASH_MS - t) / FLASH_MS
            return android.animation.ArgbEvaluator().evaluate(flashCurve.getInterpolation(f), low, high) as Int
        }

        /** The header row of a group, shared with the snapshot list so both look the same. */
        fun groupHeader(parent: ViewGroup): TextView = TextView(parent.context).apply {
            val dp = resources.displayMetrics.density
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPaddingRelative((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (1 * dp).toInt())
            includeFontPadding = false
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            val tv = android.util.TypedValue()
            if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, tv, true)) setTextColor(tv.data)
            isClickable = false
        }
    }
}
