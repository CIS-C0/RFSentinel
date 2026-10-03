package com.rfsentinel.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
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
    private val onClick: (DetectionEntity) -> Unit
) : RecyclerView.Adapter<DetectionAdapter.VH>() {

    private var items: List<DetectionEntity> = emptyList()
    /** "Oct 3, 2026, 10:21:05 AM": a numeric date like 10/3/26 is read as 10 March in much of the world. */
    private val fmt get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)

    fun submitList(newItems: List<DetectionEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition].id == newItems[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(val binding: ItemDetectionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
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
        holder.binding.macText.text = item.mac + (item.vendor?.let { "  ·  $it" } ?: "")
        val parts = mutableListOf<String>()
        category?.let { parts += it.shortTag }
        if (item.confidence > 0) parts += "${Tier.of(item.confidence).label} ${item.confidence}%"
        parts += "${item.source} ${ProximityUtil.band(item.rssi)} ${item.rssi} dBm"
        parts += fmt.format(Date(item.timestamp))
        if (item.latitude != null) parts += String.format(Locale.US, "📍 %.4f, %.4f", item.latitude, item.longitude)
        holder.binding.metaText.text = parts.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
