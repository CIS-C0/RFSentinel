package com.rfsentinel.app.ouilist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.data.AlertKind
import com.rfsentinel.app.databinding.ItemOuiEntryBinding
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.ui.ChipStyle
import com.rfsentinel.app.ui.FitChip

class OuiAdapter(
    private val items: MutableList<OuiEntry>,
    private val onRemove: (OuiEntry) -> Unit,
    /** One of your entries' alert chips was toggled: the ways it now doesn't alert you. */
    private val onAlertsOff: (OuiEntry, Set<AlertKind>) -> Unit
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
            item.isStartsRule -> "Name starts with \"${item.ruleText}\""
            item.isVendorRule -> "Maker contains \"${item.ruleText}\""
            item.isProbeRule -> "Asks for network \"${item.ruleText}\""
            item.isFingerprintRule -> "Probe fingerprint ${item.ruleText}"
            item.isAdvertRule -> "Bluetooth advert pattern ${item.ruleText}"
            else -> item.prefix
        }
        holder.binding.labelText.text = item.label
        holder.binding.confidenceText.text = buildString {
            append(if (item.isCustom) "Your entry" else "Preset")
            append(" · ${item.effectiveCategory.shortTag} · ${Tier.of(item.effectiveScore).label} ${item.effectiveScore}%")
            if ((!item.isCustom || item.source == com.rfsentinel.app.oui.OuiEntry.LEARNED) && item.source.isNotEmpty()) append("\n${item.source}")
        }
        // Only user-added entries can be removed; bundled preset entries are read-only here
        // (deselect the whole preset in Settings instead).
        holder.binding.removeButton.visibility =
            if (item.confidence == "custom") android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.removeButton.setOnClickListener { onRemove(item) }
        // Your entries: a chip per way it alerts you, on unless you turned it off.
        val chips = holder.binding.alertChips
        val vis = if (item.isCustom) android.view.View.VISIBLE else android.view.View.GONE
        chips.visibility = vis
        holder.binding.alertsLabel.visibility = vis
        chips.removeAllViews()
        if (item.isCustom) {
            val off = item.alertsOffSet
            AlertKind.entries.forEach { kind ->
                chips.addView(FitChip(chips.context).apply {
                    text = kind.label; isCheckable = true
                    ChipStyle.apply(this)
                    isChecked = kind !in off
                    contentDescription = "${kind.label} alerts for ${item.label}"
                    setOnClickListener { onAlertsOff(item, if (isChecked) off - kind else off + kind) }
                })
            }
        }
    }

    override fun getItemCount() = items.size
}
