package com.rfsentinel.app.whitelist

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.databinding.ActivityWhitelistBinding
import com.rfsentinel.app.util.MacUtil
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch

class WhitelistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWhitelistBinding
    private val entries = mutableListOf<WhitelistEntity>()
    private lateinit var adapter: WhitelistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Whitelist"

        adapter = WhitelistAdapter(entries) { entry ->
            lifecycleScope.launch {
                AppDatabase.getInstance(this@WhitelistActivity).whitelistDao().remove(entry)
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.addButton.setOnClickListener {
            // Scanner results are normalized to "AA:BB:CC:DD:EE:FF"; store the same form
            // so isWhitelisted() actually matches.
            val mac = MacUtil.normalize(binding.macInput.text.toString())
            val note = binding.noteInput.text.toString().trim()
            if (!MacUtil.isValidMac(mac)) {
                binding.macInput.error = "Format: AA:BB:CC:DD:EE:FF"
            } else {
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@WhitelistActivity).whitelistDao()
                        .add(WhitelistEntity(mac, note))
                    binding.macInput.text?.clear()
                    binding.noteInput.text?.clear()
                }
            }
        }

        lifecycleScope.launch {
            AppDatabase.getInstance(this@WhitelistActivity).whitelistDao().all()
                .collect { list ->
                    entries.clear()
                    entries.addAll(list)
                    adapter.notifyDataSetChanged()
                }
        }
    }
}
