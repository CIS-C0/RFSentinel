package com.rfsentinel.app.ui

import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Importing a theme file: a box per part of the theme (colours, font, banner, header, marker) says what to take, and the
 * choice is either a new theme or an existing one of yours whose ticked parts get replaced (the rest of it stays).
 */
object ThemeImport {

    /** About 14 MB of text is a 4 MB picture and a 6 MB font; more than this is not a theme. */
    private const val MAX_FILE = 16 * 1024 * 1024

    /** Reads the picked file and opens the dialog; [done] is called after a theme was imported. */
    fun open(activity: AppCompatActivity, uri: Uri, done: () -> Unit) {
        val text = runCatching {
            activity.contentResolver.openInputStream(uri)!!.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_FILE) return@use null }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
        if (text == null) { Toast.makeText(activity, "Couldn't read that file (or it is too big to be a theme)", Toast.LENGTH_LONG).show(); return }
        val shared = try { CustomThemes.parseShared(text) } catch (e: CustomThemes.BadThemeFile) {
            MaterialAlertDialogBuilder(activity).setTitle("Not imported").setMessage(e.message).setPositiveButton("OK", null).show(); return
        }
        show(activity, shared, done)
    }

    fun show(activity: AppCompatActivity, shared: CustomThemes.Shared, done: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val mine = CustomThemes.all(activity)
        val have = CustomThemes.partsIn(shared)

        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), px(4f)) }
        box.addView(TextView(activity).apply { text = "\"${shared.theme.name}\""; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })

        // Where it goes
        val where = RadioGroup(activity)
        val asNew = RadioButton(activity).apply { id = View.generateViewId(); text = "Make it a new theme"; isChecked = true }
        val asOld = RadioButton(activity).apply { id = View.generateViewId(); text = "Change one of my themes"; isEnabled = mine.isNotEmpty() }
        where.addView(asNew); where.addView(asOld)
        box.addView(where, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        val name = EditText(activity).apply {
            hint = "Name of the new theme"; setText(CustomThemes.uniqueName(activity, shared.theme.name)); maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        box.addView(name)
        val which = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, mine.map { it.name })
            visibility = View.GONE
            // Start with the theme in use, when it is one of mine.
            ThemeManager.custom(activity)?.let { c -> mine.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let(::setSelection) }
        }
        box.addView(which)

        // What to take
        box.addView(TextView(activity).apply {
            text = "What to take from the file:"; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(14f), 0, px(2f))
        })
        val boxes = LinkedHashMap<ThemePart, CheckBox>()
        var positive: android.widget.Button? = null
        fun refreshOk() { positive?.isEnabled = boxes.values.any { it.isChecked && it.isEnabled } }
        for (p in ThemePart.entries) {
            val has = p in have
            boxes[p] = CheckBox(activity).apply {
                text = p.label + if (has) "" else " - not in this file"
                isEnabled = has; isChecked = has
                setOnCheckedChangeListener { _, _ -> refreshOk() }
            }
            box.addView(boxes.getValue(p))
        }
        val note = TextView(activity).apply { textSize = 12.5f; alpha = 0.75f; setPadding(0, px(6f), 0, px(6f)) }
        box.addView(note)
        val useNow = MaterialSwitch(activity).apply { showText = false; text = "Use it right away"; isChecked = true }
        box.addView(useNow)

        fun update() {
            val old = asOld.isChecked
            name.visibility = if (old) View.GONE else View.VISIBLE
            which.visibility = if (old) View.VISIBLE else View.GONE
            note.text = if (old) "Ticked parts replace the same parts of that theme; the others stay as they are."
                else "Parts you don't tick stay at the standard look in the new theme."
        }
        where.setOnCheckedChangeListener { _, _ -> update() }
        update()

        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Import theme")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive = ok
            refreshOk()
            ok.setOnClickListener {
                val parts = boxes.filter { it.value.isChecked && it.value.isEnabled }.keys
                val target = if (asOld.isChecked) mine.getOrNull(which.selectedItemPosition) else null
                if (target == null && CustomThemes.all(activity).size >= CustomThemes.MAX_THEMES) {
                    Toast.makeText(activity, "That's ${CustomThemes.MAX_THEMES} themes - delete one first", Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                val result = CustomThemes.import(activity, shared, target, parts, name.text.toString())
                dialog.dismiss()
                if (useNow.isChecked) ThemeManager.setCustom(activity, result.id)
                else if (ThemeManager.custom(activity)?.id == result.id) ThemeManager.refresh(activity)
                Toast.makeText(activity, "\"${result.name}\" imported (${parts.size} of ${ThemePart.entries.size} parts)", Toast.LENGTH_LONG).show()
                done()
            }
        }
        dialog.show()
    }
}
