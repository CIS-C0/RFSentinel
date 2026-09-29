package com.rfsentinel.app.ouilist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ItemOuiEntryBinding
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.oui.OuiEntry

class OuiAdapter(
    private val items: MutableList<OuiEntry>,
    private val onRemove: (OuiEntry) -> Unit
) : RecyclerView.Adapter<OuiAdapter.VH>() {

    fun update(newItems: List<OuiEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemOuiEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOuiEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.prefixText.text = when {
            item.isNameRule -> "Name contains \"${item.ruleText}\""
            item.isVendorRule -> "Maker contains \"${item.ruleText}\""
            else -> item.prefix
        }
        holder.binding.labelText.text = item.label
        holder.binding.confidenceText.text = buildString {
            append(if (item.isCustom) "Your entry" else "Preset")
            append(" · ${item.effectiveCategory.shortTag} · ${Tier.of(item.effectiveScore).label} ${item.effectiveScore}%")
            if (!item.isCustom && item.source.isNotEmpty()) append("\n${item.source}")
        }
        // Only user-added entries can be removed; bundled preset entries are read-only here
        // (deselect the whole preset in Settings instead).
        holder.binding.removeButton.visibility =
            if (item.confidence == "custom") android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.removeButton.setOnClickListener { onRemove(item) }
    }

    override fun getItemCount() = items.size
}
