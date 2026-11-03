package com.rfsentinel.app.ui

import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.oui.ComboRule
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist

/** Watchlist & rules › Custom rule, and the device page's "Custom rule from this device": several conditions that must all hold. */
object ComboRuleDialog {

    /** [from]: a device's own values, filled in (the usual ones ticked); null starts empty. */
    fun show(activity: AppCompatActivity, from: ComboRule.Facts? = null, label: String? = null, done: () -> Unit = {}) {
        val d = activity.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        box.addView(TextView(activity).apply { text = "Tick the conditions. A device matches only when every ticked one holds." })
        val name = EditText(activity).apply {
            hint = "Label: what it is"; setSingleLine(true); setText(label.orEmpty())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        box.addView(name)

        /** A condition with a text box, shown while it is ticked. */
        fun text(title: String, hint: String, value: String?, ticked: Boolean, number: Boolean = false): Pair<CheckBox, EditText> {
            val input = EditText(activity).apply {
                this.hint = hint; setSingleLine(true); setText(value.orEmpty())
                inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                visibility = if (ticked) View.VISIBLE else View.GONE
            }
            val check = CheckBox(activity).apply {
                text = title; isChecked = ticked
                setOnCheckedChangeListener { _, on -> input.visibility = if (on) View.VISIBLE else View.GONE }
            }
            box.addView(check); box.addView(input)
            return check to input
        }
        val unnamed = from != null && from.name.isNullOrBlank()
        val vendor = text("Maker contains", "e.g. Panasonic", from?.vendors?.firstOrNull(), from?.vendors?.isNotEmpty() == true)
        val starts = text("Name starts with", "Start of the name or SSID, e.g. Flock-", from?.name?.trim(), from != null && !unnamed)
        val has = text("Name contains", "Text in the name or SSID", null, false)
        val noName = CheckBox(activity).apply { text = "No name or hidden network"; isChecked = unnamed }.also { box.addView(it) }
        val bands = ComboRule.BANDS.entries.toList()
        val bandGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val bandButtons = bands.map { (key, words) ->
            RadioButton(activity).apply { text = words; id = View.generateViewId(); isChecked = key == (from?.band ?: "2.4") }
                .also { bandGroup.addView(it) }
        }
        // Scrolls sideways on a narrow screen instead of cutting a band off.
        val bandRow = android.widget.HorizontalScrollView(activity).apply { addView(bandGroup) }
        val band = CheckBox(activity).apply {
            text = "Band"; isChecked = from?.band != null
            setOnCheckedChangeListener { _, on -> bandRow.visibility = if (on) View.VISIBLE else View.GONE }
        }
        bandRow.visibility = if (band.isChecked) View.VISIBLE else View.GONE
        box.addView(band); box.addView(bandRow)
        val shape = from?.probeKeys?.firstOrNull { it.startsWith("shape-") }
        val probe = shape?.let { s ->
            CheckBox(activity).apply { text = "Probe shape ${s.removePrefix("shape-")}"; isChecked = false }.also { box.addView(it) }
        }
        val type = text("Device type contains", "e.g. Laptop", from?.type, from?.type != null)
        val rssi = text("Signal at least, dBm", "e.g. -70", from?.rssi?.let { (it / 5 * 5).toString() }, false, number = true)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Custom rule")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Add") { _, _ ->
                fun value(p: Pair<CheckBox, EditText>) = p.second.text.toString().trim().takeIf { p.first.isChecked }
                val conds = mutableListOf<ComboRule.Cond>()
                val texts = listOfNotNull(value(vendor), value(starts), value(has), value(type))
                if (texts.any { it.isEmpty() || '&' in it }) {
                    toast(activity, "Not added: fill in every ticked condition (no & in the text)"); return@setPositiveButton
                }
                value(vendor)?.let { conds += ComboRule.Cond.Vendor(it) }
                value(starts)?.let { conds += ComboRule.Cond.NameStarts(it) }
                value(has)?.let { conds += ComboRule.Cond.NameHas(it) }
                if (noName.isChecked) conds += ComboRule.Cond.NoName
                if (band.isChecked) conds += ComboRule.Cond.Band(bands[bandButtons.indexOfFirst { it.isChecked }.coerceAtLeast(0)].key)
                if (probe?.isChecked == true) conds += ComboRule.Cond.Probe(shape)
                value(type)?.let { conds += ComboRule.Cond.Type(it) }
                value(rssi)?.let { v ->
                    val dbm = v.toIntOrNull()?.let { if (it > 0) -it else it }?.takeIf { it in -120..0 }
                        ?: run { toast(activity, "Not added: the signal is a number of dBm, e.g. -70"); return@setPositiveButton }
                    conds += ComboRule.Cond.Rssi(dbm)
                }
                if (conds.isEmpty()) { toast(activity, "Not added: tick at least one condition"); return@setPositiveButton }
                val rule = ComboRule(conds)
                if (OuiWatchlist.customEntry(rule.key) != null) { toast(activity, "Already on your watchlist"); return@setPositiveButton }
                val entry = OuiEntry(rule.key, name.text.toString().trim().ifEmpty { rule.describe() }, "user-added custom rule", "custom")
                OuiWatchlist.addCustomEntry(activity, entry)
                done()
                com.rfsentinel.app.util.Undo.show(activity, "Watching ${entry.label}") {
                    OuiWatchlist.removeCustomEntry(activity, entry.prefix); done()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(activity: AppCompatActivity, msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
