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
import com.rfsentinel.app.data.TrustRules
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
    private val rules = mutableListOf<TrustRules.Rule>()
    private lateinit var ruleAdapter: WhitelistAdapter<TrustRules.Rule>
    /** The Rule dropdown's choice. */
    private var ruleKind = TrustRules.Kind.LABEL

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

        setupRules(savedInstanceState)
        setupNetworks(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::ruleAdapter.isInitialized) showRules() // devices trusted now change while away
    }

    private fun setupRules(savedInstanceState: Bundle?) {
        val choices = TrustRules.Kind.entries
        binding.ruleKind.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, choices.map { it.label }))
        binding.ruleKind.setOnItemClickListener { _, _, position, _ -> setRuleKind(choices[position]) }
        setRuleKind(savedInstanceState?.getInt(STATE_RULE)?.let { choices.getOrNull(it) } ?: TrustRules.Kind.LABEL)

        ruleAdapter = WhitelistAdapter(rules, { it.describe }, { r ->
            val n = TrustRules.count(r)
            listOfNotNull(r.noteText.takeIf { it.isNotEmpty() && !r.describe.contains(it) },
                if (n == 0) "None in range now" else "$n in range now").joinToString(" · ")
        }) { rule ->
            TrustRules.remove(this, rule)
            showRules()
            com.rfsentinel.app.util.Undo.show(this, "Removed ${rule.describe}") { TrustRules.add(this, rule); showRules() }
        }
        binding.ruleList.layoutManager = LinearLayoutManager(this)
        binding.ruleList.adapter = ruleAdapter

        binding.addRuleButton.setOnClickListener {
            var text = binding.ruleInput.text.toString().trim()
            if (text.isEmpty()) { binding.ruleInput.error = "Type what to match"; return@setOnClickListener }
            if (ruleKind == TrustRules.Kind.PREFIX) {
                text = MacUtil.normalize(text)
                if (!MacUtil.isValidPrefix(text)) { binding.ruleInput.error = "Format: AA:BB:CC, 6 to 11 digits"; return@setOnClickListener }
            }
            TrustRules.add(this, TrustRules.Rule(ruleKind, text))
            binding.ruleInput.text?.clear()
            showRules()
        }
        showRules()
    }

    private fun setRuleKind(k: TrustRules.Kind) {
        ruleKind = k
        binding.ruleKind.setText(k.label, false) // false: keep every choice in the list
        binding.ruleInput.hint = when (k) {
            TrustRules.Kind.LABEL -> "What it is matched as, e.g. Zebra printer"
            TrustRules.Kind.PREFIX -> "Start of the address, e.g. AC:23:3F"
            TrustRules.Kind.STARTS -> "Start of the device name"
            TrustRules.Kind.CONTAINS -> "Part of the device name"
            TrustRules.Kind.VENDOR -> "Part of the maker's name, e.g. Zebra"
            TrustRules.Kind.ADVERT -> "Advert pattern, from a device's details"
            TrustRules.Kind.FINGERPRINT -> "Probe fingerprint, from a device's details"
        }
    }

    private fun showRules() {
        rules.clear()
        rules.addAll(TrustRules.rules)
        ruleAdapter.notifyDataSetChanged()
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
        outState.putInt(STATE_RULE, ruleKind.ordinal)
    }

    private companion object {
        const val STATE_MATCH = "networkMatch"
        const val STATE_RULE = "ruleKind"
    }
}
