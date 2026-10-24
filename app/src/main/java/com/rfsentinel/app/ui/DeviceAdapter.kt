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
    val heardByPhone: Boolean = false,
    /** The linked Sentinel that heard it (Sentinel Link), or null. */
    val heardByPeer: String? = null,
    /** For the visual style: the signal for the bars (null = none), and the match's confidence (null = not a match). */
    val rssi: Int? = null,
    val confidence: Int? = null,
    /** Added to [rssi] for the bars only: cell signals run about 10 dB lower than WiFi and Bluetooth. */
    val barBoost: Int = 0,
    /** A group's header ("Trackers · 3", Group by in the ⋮ menu): [title] only, not a device. */
    val header: Boolean = false
) {
    companion object {
        fun header(label: String) = DeviceRow("header:$label", label, "", "", null, 0, null, false, true, header = true)
    }
}

private const val ESP_BADGE = 0xFF1E88E5.toInt()
private const val PHONE_BADGE = 0xFF2E7D32.toInt()
private const val USB_BADGE = 0xFF6A1B9A.toInt()
private const val PEER_BADGE = 0xFF00838F.toInt()

/** The meta line, followed by small coloured "ESP32" / "INTERNAL" / "VIA <Sentinel>" badges. */
private fun metaWithBadges(row: DeviceRow): CharSequence {
    if (!row.heardByEsp && !row.heardByUsb && !row.heardByPhone && row.heardByPeer == null) return row.meta
    val sb = android.text.SpannableStringBuilder(row.meta)
    fun badge(label: String, color: Int) {
        sb.append("  ")
        val start = sb.length
        sb.append(" ").append(label).append(" ")
        val end = sb.length
        sb.setSpan(android.text.style.BackgroundColorSpan(color), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (row.heardByEsp) badge("ESP32", ESP_BADGE)
    if (row.heardByUsb) badge("USB WIFI", USB_BADGE)
    if (row.heardByPhone) badge("INTERNAL", PHONE_BADGE)
    row.heardByPeer?.let { badge("VIA " + it.uppercase(), PEER_BADGE) }
    return sb
}

class DeviceAdapter(
    private val onClick: (DeviceRow) -> Unit,
    private val onLongPress: (DeviceRow) -> Unit
) : ListAdapter<DeviceRow, RecyclerView.ViewHolder>(Diff) {

    /** Visual style: signal bars and a confidence bar on each row (Settings > Appearance, or the list's own choice). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    var visual = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    object Diff : DiffUtil.ItemCallback<DeviceRow>() {
        override fun areItemsTheSame(a: DeviceRow, b: DeviceRow) = a.mac == b.mac
        override fun areContentsTheSame(a: DeviceRow, b: DeviceRow) = a == b
    }

    inner class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        private var animator: ValueAnimator? = null
        private var animColor = 0

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
            if (flashing) {
                if (animator?.isRunning == true && animColor == color) return
                stopFlash()
                animColor = color
                animator = ValueAnimator.ofArgb(low, high).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { binding.root.setBackgroundColor(it.animatedValue as Int) }
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
        if (row.tag != null) {
            b.tagText.visibility = View.VISIBLE
            b.tagText.text = row.tag
            b.tagText.setBackgroundColor(row.tagColor)
        } else {
            b.tagText.visibility = View.GONE
        }
        b.titleText.text = row.title
        b.titleText.setTypeface(null, if (row.bold) Typeface.BOLD else Typeface.NORMAL)
        b.macText.text = row.subtitle
        b.metaText.text = metaWithBadges(row)
        val conf = row.confidence
        b.confidenceBar.visibility = if (visual && conf != null) View.VISIBLE else View.GONE
        if (visual && conf != null) b.confidenceBar.set(conf / 100f, row.tagColor)
        b.signalBox.visibility = if (visual && row.rssi != null) View.VISIBLE else View.GONE
        if (visual && row.rssi != null) {
            b.signalBars.set(row.rssi + row.barBoost)
            b.signalText.text = "${row.rssi}"
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

        /** The header row of a group, shared with the snapshot list so both look the same. */
        fun groupHeader(parent: ViewGroup): TextView = TextView(parent.context).apply {
            val dp = resources.displayMetrics.density
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPaddingRelative((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            val tv = android.util.TypedValue()
            if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, tv, true)) setTextColor(tv.data)
            isClickable = false
        }
    }
}
