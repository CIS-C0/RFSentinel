package com.rfsentinel.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Recorded scan traces. Tap to view on the map; long-press to export, rename or delete. */
class TripsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private val adapter = object : ListAdapter<TripEntity, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<TripEntity>() {
            override fun areItemsTheSame(a: TripEntity, b: TripEntity) = a.id == b.id
            override fun areContentsTheSame(a: TripEntity, b: TripEntity) = a == b
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val t = getItem(position)
            val b = ItemDetectionBinding.bind(holder.itemView)
            val live = t.id == TripRecorder.activeTripId
            b.labelText.text = (if (live) "● " else "") + t.name
            b.labelText.setTextColor(if (live) 0xFFB3261E.toInt() else if (t.flaggedCount > 0) 0xFFC8431A.toInt() else b.macText.currentTextColor)
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            b.macText.text = fmt.format(Date(t.startTime)) +
                String.format(Locale.US, "  ·  %d:%02d  ·  %.2f km", mins / 60, mins % 60, t.distanceM / 1000)
            b.metaText.text = (if (live) "Recording now · " else "") +
                "${devicesLabel(t.deviceCount)} · ${t.flaggedCount} flagged · ${t.pointCount} GPS points"
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@TripsActivity, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, t.id))
            }
            holder.itemView.setOnLongClickListener { actions(t); true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Recorded traces"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.emptyText.text = "No recorded traces yet.\nOpen the map and tap Record trace."

        lifecycleScope.launch {
            AppDatabase.getInstance(this@TripsActivity).tripDao().allFlow().collect { list ->
                adapter.submitList(list)
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun actions(t: TripEntity) {
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf("View on map", "Export (GPX, KML, GeoJSON, CSV)...", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, t.id))
                    1 -> Exporter.showTripExport(this, t.id)
                    2 -> rename(t)
                    3 -> delete(t)
                }
            }
            .show()
    }

    private fun rename(t: TripEntity) {
        val input = EditText(this).apply { setText(t.name); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Rename trace")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@TripsActivity).tripDao().rename(t.id, input.text.toString().trim().ifEmpty { "Trace" })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(t: TripEntity) {
        if (t.id == TripRecorder.activeTripId) {
            AlertDialog.Builder(this).setMessage("Stop recording on the map before deleting this trace.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete \"${t.name}\"?")
            .setMessage("The route and its device list are deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { AppDatabase.getInstance(this@TripsActivity).tripDao().delete(t.id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
