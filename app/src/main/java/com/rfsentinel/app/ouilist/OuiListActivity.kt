package com.rfsentinel.app.ouilist

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.rfsentinel.app.R
import com.rfsentinel.app.databinding.ActivityOuiListBinding
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets

/** View the active watchlist and add address / name / manufacturer rules. */
class OuiListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOuiListBinding
    private lateinit var adapter: OuiAdapter

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
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.ruleType.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.typeName -> {
                    binding.prefixInput.hint = "Text in the device name / WiFi SSID (e.g. Axon)"
                    binding.prefixInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                R.id.typeVendor -> {
                    binding.prefixInput.hint = "Text in the manufacturer name (e.g. Motorola)"
                    binding.prefixInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                else -> {
                    binding.prefixInput.hint = "Prefix AA:BB:CC or device AA:BB:CC:DD:EE:FF"
                    binding.prefixInput.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
            }
        }

        binding.addButton.setOnClickListener {
            val text = binding.prefixInput.text.toString().trim()
            val label = binding.labelInput.text.toString().trim()
            val key = when (binding.ruleType.checkedRadioButtonId) {
                R.id.typeName -> OuiEntry.NAME + text
                R.id.typeVendor -> OuiEntry.VENDOR + text
                else -> text
            }
            if (text.isEmpty() || !OuiWatchlist.isValidKey(key)) {
                binding.prefixInput.error = when (binding.ruleType.checkedRadioButtonId) {
                    R.id.typeAddress -> "Format: AA:BB:CC (vendor) or AA:BB:CC:DD:EE:FF (one device)"
                    else -> "Enter some text to match"
                }
            } else if (label.isEmpty()) {
                binding.labelInput.error = "Label required"
            } else {
                OuiWatchlist.addCustomEntry(this, OuiEntry(key, label, "user-added", "custom"))
                binding.prefixInput.text?.clear()
                binding.labelInput.text?.clear()
                refresh()
            }
        }

        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export my entries (JSON)")
        menu.add(0, 2, 1, "Import entries (JSON)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { Exporter.showExportMenu(this); true }
        2 -> { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        OuiWatchlist.load(this)
        // Your own entries first, then presets by confidence.
        adapter.update(OuiWatchlist.allEntries().sortedWith(
            compareByDescending<OuiEntry> { it.isCustom }.thenByDescending { it.effectiveScore }
        ))
    }
}
