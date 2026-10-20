package com.rfsentinel.app.ouilist

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.rfsentinel.app.databinding.ActivityOuiListBinding
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets

/** View the active watchlist and add address / name / manufacturer rules. */
class OuiListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOuiListBinding
    private lateinit var adapter: OuiAdapter

    /** The kinds of rule the form adds: the dropdown's label, the text box's hint and the key's prefix. */
    private enum class RuleType(val label: String, val hint: String, val keyPrefix: String) {
        ADDRESS("Address or vendor prefix", "Prefix AA:BB:CC or device AA:BB:CC:DD:EE:FF", ""),
        NAME("Name contains", "Text in the device name or WiFi SSID, e.g. Axon", OuiEntry.NAME),
        STARTS("Name starts with", "Start of the device name or WiFi SSID, e.g. Flock-", OuiEntry.STARTS),
        VENDOR("Maker contains", "Text in the manufacturer name, e.g. Motorola", OuiEntry.VENDOR),
        PROBE("Asks for network", "Exact WiFi network name a device asks for", OuiEntry.PROBE)
    }
    private var ruleType = RuleType.ADDRESS

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val n = OuiWatchlist.importCustomJson(this, json)
            Toast.makeText(this, "Imported $n entries", Toast.LENGTH_SHORT).show()
            refresh()
        } catch (e: Exception) {
            Toast.makeText(this, "Not a valid RF Sentinel watchlist file", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOuiListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Watchlist & rules"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = OuiAdapter(mutableListOf()) { entry ->
            OuiWatchlist.removeCustomEntry(this, entry.prefix)
            refresh()
            com.rfsentinel.app.util.Undo.show(this, "Removed ${entry.label}") {
                OuiWatchlist.addCustomEntry(this, entry)
                refresh()
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.searchInput.doAfterTextChanged { refresh() }

        val types = RuleType.entries
        binding.ruleType.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, types.map { it.label }))
        binding.ruleType.setOnItemClickListener { _, _, position, _ -> setRuleType(types[position]) }
        setRuleType(savedInstanceState?.getInt(STATE_RULE_TYPE)?.let { types.getOrNull(it) } ?: RuleType.ADDRESS)

        binding.addButton.setOnClickListener {
            val text = binding.prefixInput.text.toString().trim()
            val label = binding.labelInput.text.toString().trim()
            val key = ruleType.keyPrefix + text
            if (text.isEmpty() || !OuiWatchlist.isValidKey(key)) {
                binding.prefixInput.error = when (ruleType) {
                    RuleType.ADDRESS -> "Format: AA:BB:CC for a vendor, AA:BB:CC:DD:EE:FF for one device"
                    else -> "Enter some text to match"
                }
            } else if (label.isEmpty()) {
                binding.labelInput.error = "Label required"
            } else {
                val before = OuiWatchlist.customEntry(key)
                OuiWatchlist.addCustomEntry(this, OuiEntry(key, label, "user-added", "custom"))
                binding.prefixInput.text?.clear()
                binding.labelInput.text?.clear()
                refresh()
                com.rfsentinel.app.util.Undo.show(this, if (before != null) "Updated $label" else "Watching $label") {
                    if (before != null) OuiWatchlist.addCustomEntry(this, before) else OuiWatchlist.removeCustomEntry(this, key)
                    refresh()
                }
            }
        }

        refresh()
    }

    /** Shows [type] in the dropdown and sets the text box up for it. */
    private fun setRuleType(type: RuleType) {
        ruleType = type
        binding.ruleType.setText(type.label, false) // false: keep every choice in the list
        binding.prefixInput.hint = type.hint
        binding.prefixInput.error = null
        binding.prefixInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (type == RuleType.ADDRESS) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_RULE_TYPE, ruleType.ordinal)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export my entries as JSON")
        menu.add(0, 2, 1, "Import entries from JSON")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { Exporter.showWatchlistExport(this); true }
        2 -> { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        OuiWatchlist.load(this)
        val all = OuiWatchlist.allEntries()
        // The search box: the rule, its label or where it came from (also an address typed without colons).
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        val hex = q.replace(":", "").replace("-", "")
        val shown = if (q.isEmpty()) all else all.filter { e ->
            e.prefix.contains(q, true) || e.label.contains(q, true) || e.source.contains(q, true) ||
                (hex.length >= 2 && e.prefix.replace(":", "").contains(hex, true))
        }
        // Your own entries first, then presets by confidence.
        adapter.update(shown.sortedWith(
            compareByDescending<OuiEntry> { it.isCustom }.thenByDescending { it.effectiveScore }
        ))
        supportActionBar?.subtitle = if (q.isEmpty()) "${all.size} rules" else "${shown.size} of ${all.size} rules"
    }

    private companion object {
        const val STATE_RULE_TYPE = "ruleType"
    }
}
