package com.rfsentinel.app.whitelist

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TrustedNetworks
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.databinding.ActivityWhitelistBinding
import com.rfsentinel.app.util.MacUtil
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch

class WhitelistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWhitelistBinding
    private val entries = mutableListOf<WhitelistEntity>()
    private lateinit var adapter: WhitelistAdapter<WhitelistEntity>
    private val networks = mutableListOf<TrustedNetworks.Rule>()
    private lateinit var networkAdapter: WhitelistAdapter<TrustedNetworks.Rule>
    /** The Match dropdown: whole name (true) or part of it. */
    private var exact = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Whitelist"

        adapter = WhitelistAdapter(entries, { it.mac }, { it.note }) { entry ->
            lifecycleScope.launch {
                val dao = AppDatabase.getInstance(this@WhitelistActivity).whitelistDao()
                dao.remove(entry)
                com.rfsentinel.app.util.Undo.show(this@WhitelistActivity, "Removed ${entry.mac}") {
                    lifecycleScope.launch { dao.add(entry) }
                }
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

        setupNetworks(savedInstanceState)
    }

    private fun setupNetworks(savedInstanceState: Bundle?) {
        val choices = listOf("Name contains", "Exact name")
        binding.networkMatch.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, choices))
        binding.networkMatch.setOnItemClickListener { _, _, position, _ -> setExact(position == 1) }
        setExact(savedInstanceState?.getBoolean(STATE_EXACT) ?: false)

        networkAdapter = WhitelistAdapter(networks, { it.text }, { it.label }, { editNetwork(it) }) { rule ->
            TrustedNetworks.remove(this, rule)
            showNetworks()
            com.rfsentinel.app.util.Undo.show(this, "Removed ${rule.text}") {
                TrustedNetworks.save(this, rule)
                showNetworks()
            }
        }
        binding.networkList.layoutManager = LinearLayoutManager(this)
        binding.networkList.adapter = networkAdapter

        binding.addNetworkButton.setOnClickListener {
            val text = binding.networkInput.text.toString().trim()
            if (text.isEmpty()) {
                binding.networkInput.error = "Type a network name or part of one"
                return@setOnClickListener
            }
            TrustedNetworks.save(this, TrustedNetworks.Rule(text, exact))
            binding.networkInput.text?.clear()
            showNetworks()
        }
        showNetworks()
    }

    private fun setExact(on: Boolean) {
        exact = on
        binding.networkMatch.setText(if (on) "Exact name" else "Name contains", false) // false: keep every choice in the list
    }

    private fun showNetworks() {
        networks.clear()
        networks.addAll(TrustedNetworks.rules)
        networkAdapter.notifyDataSetChanged()
    }

    /** A tap on a network rule: change its text or how it matches. */
    private fun editNetwork(rule: TrustedNetworks.Rule) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply { setText(rule.text); setSelection(rule.text.length); hint = "Network name or keyword" }
        val contains = RadioButton(this).apply { id = android.view.View.generateViewId(); text = "Name contains" }
        val whole = RadioButton(this).apply { id = android.view.View.generateViewId(); text = "Exact name" }
        val group = RadioGroup(this).apply { addView(contains); addView(whole); check(if (rule.exact) whole.id else contains.id) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input); addView(group)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit network name")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) TrustedNetworks.save(this, TrustedNetworks.Rule(text, group.checkedRadioButtonId == whole.id), replacing = rule)
                showNetworks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EXACT, exact)
    }

    private companion object {
        const val STATE_EXACT = "networkExact"
    }
}
