package com.rfsentinel.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.util.ProximityUtil
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class DetectionAdapter(
    private val onClick: (DetectionEntity) -> Unit,
    private val onLongClick: ((DetectionEntity) -> Unit)? = null
) : ListAdapter<DetectionEntity, DetectionAdapter.VH>(object : DiffUtil.ItemCallback<DetectionEntity>() {
    override fun areItemsTheSame(a: DetectionEntity, b: DetectionEntity) =
        if (a.id == 0L && b.id == 0L) a.mac == b.mac else a.id == b.id
    override fun areContentsTheSame(a: DetectionEntity, b: DetectionEntity) = a == b
}) {

    /** "Oct 3, 2026, 10:21:05 AM": a numeric date like 10/3/26 is read as 10 March in much of the world. */
    private val fmt get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)

    /** Settings › Appearance › List size. */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    var size = com.rfsentinel.app.util.Prefs.ListSize.REGULAR
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    inner class VH(val binding: ItemDetectionBinding) : RecyclerView.ViewHolder(binding.root) {
        private var shownSize: com.rfsentinel.app.util.Prefs.ListSize? = null

        /** Sets the row up for [s]; only when it changes, as it re-lays the row out. */
        fun applySize(s: com.rfsentinel.app.util.Prefs.ListSize) {
            if (shownSize == s) return
            shownSize = s
            val small = s != com.rfsentinel.app.util.Prefs.ListSize.REGULAR
            val dense = s == com.rfsentinel.app.util.Prefs.ListSize.DENSE
            val res = binding.root.resources
            val dp = res.displayMetrics.density
            // Regular: the layout's 12 dp padding and 8 dp above the divider.
            val pad = ((if (dense) 3 else if (small) 6 else 12) * dp).toInt()
            binding.root.setPaddingRelative(binding.root.paddingStart, pad, binding.root.paddingEnd, pad)
            (binding.divider.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin =
                ((if (dense) 2 else if (small) 4 else 8) * dp).toInt()
            // A row stays a comfortable touch target at any size.
            binding.root.minimumHeight = (48 * dp).toInt()
            fun android.widget.TextView.size(regular: Int, smallSp: Float, lines: Int) {
                if (small) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, smallSp)
                else setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, res.getDimension(regular))
                includeFontPadding = !small
                maxLines = if (small) lines else Int.MAX_VALUE
                ellipsize = if (small) android.text.TextUtils.TruncateAt.END else null
            }
            binding.labelText.size(com.rfsentinel.app.R.dimen.text_subtitle, 14f, 1)
            binding.macText.size(com.rfsentinel.app.R.dimen.text_body, 13f, 1)
            binding.metaText.size(com.rfsentinel.app.R.dimen.text_caption, 12f, if (dense) 1 else 2)
            binding.macText.visibility = if (dense) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.requestLayout()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.applySize(size)
        val category = Category.parse(item.category)
        holder.binding.labelText.text = item.label
        holder.binding.labelText.setTextColor(
            ThemeManager.ink(
                holder.itemView.context,
                // confidence 0 = logged by v1.x, before confidence existed: show the category colour.
                if (category != null && (item.confidence == 0 || item.confidence >= Tier.MEDIUM.min)) category.colorArgb
                else 0xFFB26A00.toInt()
            )
        )
        // Its name or network (SSID) first when it had one: easier to recognise than the address.
        val who = listOfNotNull(item.deviceName?.takeIf { it.isNotBlank() }, item.mac, item.vendor).joinToString("  ·  ")
        holder.binding.macText.text = who
        val parts = mutableListOf<String>()
        category?.let { parts += it.shortTag }
        if (item.confidence > 0) parts += "${Tier.of(item.confidence).label} ${item.confidence}%"
        parts += "${item.heardBy ?: item.source} ${ProximityUtil.band(item.rssi)} ${item.rssi} dBm"
        item.radio?.let { parts += it }
        parts += fmt.format(Date(item.timestamp))
        if (item.latitude != null) parts += String.format(Locale.US, "📍 %.4f, %.4f", item.latitude, item.longitude)
        // Dense: the address and the details share one line.
        holder.binding.metaText.text = if (size == com.rfsentinel.app.util.Prefs.ListSize.DENSE) "$who  ·  " + parts.joinToString(" · ")
            else parts.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener(onLongClick?.let { f -> android.view.View.OnLongClickListener { f(item); true } })
    }
}
