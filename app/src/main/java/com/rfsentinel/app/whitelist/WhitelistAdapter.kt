package com.rfsentinel.app.whitelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.databinding.ItemWhitelistBinding

class WhitelistAdapter(
    private val items: List<WhitelistEntity>,
    private val onRemove: (WhitelistEntity) -> Unit
) : RecyclerView.Adapter<WhitelistAdapter.VH>() {

    inner class VH(val binding: ItemWhitelistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWhitelistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.macText.text = item.mac
        holder.binding.noteText.text = item.note
        holder.binding.removeButton.setOnClickListener { onRemove(item) }
    }

    override fun getItemCount() = items.size
}
