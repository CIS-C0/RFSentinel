package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch

/** History of logged matches (the rows the exports contain). Tap one for device details. */
class DetectionLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Match history"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = DetectionAdapter(onClick = { DeviceActions.openDetails(this, it.mac) })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        lifecycleScope.launch {
            AppDatabase.getInstance(this@DetectionLogActivity).detectionDao().recent()
                .collect { list ->
                    adapter.submitList(list)
                    binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    supportActionBar?.subtitle = "${list.size}${if (list.size >= 1000) "+" else ""} logged"
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export...")
        menu.add(0, 2, 1, "Clear match history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { Exporter.showExportMenu(this); true }
        2 -> {
            AlertDialog.Builder(this)
                .setTitle("Clear match history?")
                .setMessage("This deletes every logged match on this device. Export first if you need a copy.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch { AppDatabase.getInstance(this@DetectionLogActivity).detectionDao().clearAll() }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }
}
