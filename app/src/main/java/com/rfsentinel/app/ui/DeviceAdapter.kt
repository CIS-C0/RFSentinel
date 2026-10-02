package com.rfsentinel.app.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    /** Pulse the tint (flagged, above the alert threshold, heard within the last minute). */
    val flashing: Boolean,
    val bold: Boolean,
    /** Heard by an ESP32 board (blue badge) and/or the phone's own radios (green badge). */
    val heardByEsp: Boolean = false,
    val heardByPhone: Boolean = false
)

private const val ESP_BADGE = 0xFF1E88E5.toInt()
private const val PHONE_BADGE = 0xFF2E7D32.toInt()

/** The meta line, followed by small coloured "ESP32" / "INTERNAL" badges. */
private fun metaWithBadges(row: DeviceRow): CharSequence {
    if (!row.heardByEsp && !row.heardByPhone) return row.meta
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
    if (row.heardByPhone) badge("INTERNAL", PHONE_BADGE)
    return sb
}

class DeviceAdapter(
    private val onClick: (DeviceRow) -> Unit,
    private val onLongPress: (DeviceRow) -> Unit
) : ListAdapter<DeviceRow, DeviceAdapter.VH>(Diff) {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
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
        holder.setHighlight(row.highlight, row.flashing)
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener { onLongPress(row); true }
    }

    override fun onViewRecycled(holder: VH) {
        holder.stopFlash()
        super.onViewRecycled(holder)
    }
}
