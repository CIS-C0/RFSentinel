package com.rfsentinel.app.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun AppCompatActivity.logScreen(name: String, adapter: RecyclerView.Adapter<*>) =
    ActivityLogBinding.inflate(layoutInflater).also {
        setContentView(it.root)
        it.root.applySystemBarInsets()
        title = name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        it.recyclerView.layoutManager = LinearLayoutManager(this)
        it.recyclerView.adapter = adapter
    }

internal fun AppCompatActivity.refreshWhileStarted(interval: Long, refresh: suspend () -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { refresh(); delay(interval) }
        }
    }
}

internal class DetectionRows<T>(
    private val items: () -> List<T>,
    private val id: ((T) -> Long)? = null,
    private val bind: ItemDetectionBinding.(T) -> Unit
) : RecyclerView.Adapter<DetectionRows.Holder>() {
    class Holder(val binding: ItemDetectionBinding) : RecyclerView.ViewHolder(binding.root)
    init { setHasStableIds(id != null) }
    override fun getItemCount() = items().size
    override fun getItemId(position: Int) = id?.invoke(items()[position]) ?: RecyclerView.NO_ID
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.binding.bind(items()[position])
}

internal fun AppCompatActivity.visualTitle(label: String) = TextView(this).apply {
    text = label
    textSize = 12f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(ChipStyle.accent(context).first)
    letterSpacing = 0.08f
}

internal fun AppCompatActivity.visualCard(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = VisualMode.card(context)
    val dp = resources.displayMetrics.density
    val padding = (12 * dp).toInt()
    setPadding(padding, padding, padding, padding)
    views.forEach { addView(it) }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
}
