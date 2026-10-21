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
    /** The Match dropdown's choice. */
    private var match = TrustedNetworks.Match.STARTS

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
        val choices = TrustedNetworks.Match.entries
        binding.networkMatch.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, choices.map { it.label }))
        binding.networkMatch.setOnItemClickListener { _, _, position, _ -> setMatch(choices[position]) }
        setMatch(savedInstanceState?.getInt(STATE_MATCH)?.let { choices.getOrNull(it) } ?: TrustedNetworks.Match.STARTS)

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
            TrustedNetworks.save(this, TrustedNetworks.Rule(text, match))
            binding.networkInput.text?.clear()
            showNetworks()
        }
        showNetworks()
    }

    private fun setMatch(m: TrustedNetworks.Match) {
        match = m
        binding.networkMatch.setText(m.label, false) // false: keep every choice in the list
        binding.networkInput.hint = when (m) {
            TrustedNetworks.Match.STARTS -> "Start of the network name, e.g. SchoolBus-"
            TrustedNetworks.Match.CONTAINS -> "Part of the network name, e.g. SchoolBus"
            TrustedNetworks.Match.EXACT -> "The whole network name"
        }
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
        val buttons = TrustedNetworks.Match.entries.associateWith { m ->
            RadioButton(this).apply { id = android.view.View.generateViewId(); text = m.label }
        }
        val group = RadioGroup(this).apply { buttons.values.forEach { addView(it) }; check(buttons.getValue(rule.how).id) }
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
                val how = buttons.entries.firstOrNull { it.value.id == group.checkedRadioButtonId }?.key ?: rule.how
                if (text.isNotEmpty()) TrustedNetworks.save(this, TrustedNetworks.Rule(text, how), replacing = rule)
                showNetworks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MATCH, match.ordinal)
    }

    private companion object {
        const val STATE_MATCH = "networkMatch"
    }
}
