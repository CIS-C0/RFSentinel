package com.rfsentinel.app.whitelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ItemWhitelistBinding

/** One whitelist section's rows: a device ([title] = its address) or a network name rule; [onClick] edits it. */
class WhitelistAdapter<T>(
    private val items: List<T>,
    private val title: (T) -> String,
    private val subtitle: (T) -> String,
    private val onClick: ((T) -> Unit)? = null,
    private val onRemove: (T) -> Unit
) : RecyclerView.Adapter<WhitelistAdapter<T>.VH>() {

    inner class VH(val binding: ItemWhitelistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWhitelistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.macText.text = title(item)
        holder.binding.noteText.text = subtitle(item)
        holder.binding.removeButton.setOnClickListener { onRemove(item) }
        val click = onClick
        holder.binding.root.isClickable = click != null
        if (click != null) holder.binding.root.setOnClickListener { click(item) } else holder.binding.root.setOnClickListener(null)
    }

    override fun getItemCount() = items.size
}
