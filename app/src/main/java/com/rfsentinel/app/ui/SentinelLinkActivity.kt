package com.rfsentinel.app.ui

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rfsentinel.app.link.LinkStore
import com.rfsentinel.app.link.PhoneLink
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sentinel Link: this phone's name, sharing on / off, linking a new phone ("Link up a Sentinel" on one,
 * "Join a Sentinel" on the other, same 6 digits on both), the Sentinels of the group, and leaving it.
 */
class SentinelLinkActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var share: SwitchMaterial
    private lateinit var list: LinearLayout
    private lateinit var leave: MaterialButton
    private var pairDialog: AlertDialog? = null
    private var pendingHost: Boolean? = null
    private val onLinkChanged: () -> Unit = { refresh() }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val host = pendingHost ?: return@registerForActivityResult
        pendingHost = null
        if (PhoneLink.canUse(this)) startPairing(host)
        else Toast.makeText(this, "Sentinel Link needs \"Nearby devices\"", Toast.LENGTH_LONG).show()
    }

    private val dp get() = resources.displayMetrics.density
    private fun pad(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sentinel Link"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val intro = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            text = "Link RF Sentinel phones near each other over Bluetooth: each one shows the same devices on its list, " +
                "radar and map - with what any phone's external hardware hears - whichever phone heard them first. " +
                "Linked phones share while they scan."
        }
        status = TextView(this).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad(12), 0, 0) }

        val nameLabel = TextView(this).apply { textSize = 13f; text = "This phone's name"; setPadding(0, pad(16), 0, 0) }
        val name = EditText(this).apply {
            setSingleLine(true)
            setText(LinkStore.myName(this@SentinelLinkActivity))
            setOnFocusChangeListener { _, focused -> if (!focused) saveName(text.toString()) }
            setOnEditorActionListener { v, _, _ -> saveName(v.text.toString()); v.clearFocus(); false }
        }
        share = SwitchMaterial(this).apply {
            text = "Share with linked Sentinels"
            isChecked = LinkStore.enabled(this@SentinelLinkActivity)
            setOnCheckedChangeListener { _, on ->
                LinkStore.setEnabled(this@SentinelLinkActivity, on)
                ScanForegroundService.refreshLink(this@SentinelLinkActivity)
                refresh()
            }
            setPadding(0, pad(8), 0, pad(8))
        }

        fun button(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad(8) }
        }
        val hostButton = button("Link up a Sentinel") { pair(host = true) }
        val joinButton = button("Join a Sentinel") { pair(host = false) }
        val listHeader = TextView(this).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); text = "Sentinels"; setPadding(0, pad(20), 0, pad(4))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leave = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Leave the group"
            setOnClickListener { confirmLeave() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(16), pad(16), pad(16), pad(16))
            addView(intro); addView(status); addView(nameLabel); addView(name); addView(share)
            addView(hostButton); addView(joinButton); addView(listHeader); addView(list); addView(leave)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.applySystemBarInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(5_000) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneLink.addListener(onLinkChanged)
    }

    override fun onStop() {
        PhoneLink.removeListener(onLinkChanged)
        super.onStop()
    }

    override fun onDestroy() {
        if (pairDialog?.isShowing == true) PhoneLink.cancelPairing()
        pairDialog = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun saveName(text: String) {
        if (text.trim() == LinkStore.myName(this)) return
        LinkStore.setMyName(this, text)
        PhoneLink.meChanged()
    }

    private fun refresh() {
        if (isFinishing) return
        val grouped = LinkStore.hasGroup(this)
        status.text = when {
            !grouped -> "Not linked yet"
            !LinkStore.enabled(this) -> "Sharing off"
            !ScanForegroundService.isRunning -> "Links while scanning - start a scan to share"
            else -> PhoneLink.status.ifBlank { "Starting…" }
        }
        share.visibility = if (grouped) View.VISIBLE else View.GONE
        leave.visibility = if (grouped) View.VISIBLE else View.GONE

        list.removeAllViews()
        val members = LinkStore.members(this)
        if (members.isEmpty()) {
            list.addView(TextView(this).apply {
                textSize = 14f
                text = "None yet. On two phones near each other, tap \"Link up a Sentinel\" on one and \"Join a Sentinel\" on the other."
            })
            return
        }
        val linked = PhoneLink.linked.associateBy { it.id }
        val now = System.currentTimeMillis()
        for (m in members) {
            val heard = PhoneLink.lastHeard[m.id]
            val detail = when {
                linked.containsKey(m.id) -> "Linked now"
                heard != null -> "Last heard " + DateUtils.getRelativeTimeSpanString(heard, now, DateUtils.MINUTE_IN_MILLIS)
                else -> "Not in reach"
            }
            list.addView(TextView(this).apply {
                textSize = 15f
                setPadding(0, pad(6), 0, pad(6))
                text = "${m.name}\n$detail"
            })
        }
    }

    private fun confirmLeave() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Leave the group?")
            .setMessage("This phone stops sharing with the other Sentinels and forgets the group. The others keep it on their " +
                "list; to link again, join one of them.")
            .setPositiveButton("Leave") { _, _ ->
                LinkStore.leave(this)
                PhoneLink.groupChanged()
                ScanForegroundService.refreshLink(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(host: Boolean) {
        val others = LinkStore.members(this).size
        if (!host && others > 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Join another group?")
                .setMessage("This phone is linked with $others Sentinel${if (others == 1) "" else "s"}. Joining leaves that group for the " +
                    "other phone's. To add a phone to this group instead, tap \"Link up a Sentinel\" here and \"Join a Sentinel\" on the new phone.")
                .setPositiveButton("Join") { _, _ -> withPermission(false) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        withPermission(host)
    }

    private fun withPermission(host: Boolean) {
        if (PhoneLink.canUse(this)) { startPairing(host); return }
        pendingHost = host
        permissions.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN) else emptyArray())
    }

    private fun startPairing(host: Boolean) {
        val messageView = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER }
        val codeView = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(0, pad(16), 0, pad(8))
            visibility = View.GONE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(16), pad(24), 0)
            addView(codeView); addView(messageView)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (host) "Link up a Sentinel" else "Join a Sentinel")
            .setView(body)
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel") { _, _ -> PhoneLink.cancelPairing() }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    isEnabled = false
                    PhoneLink.confirmCode()
                }
            }
        }
        dialog.show()
        pairDialog = dialog

        val listener = object : PhoneLink.PairListener {
            override fun onStatus(text: String) { messageView.text = text }
            override fun onCode(code: String) {
                codeView.text = code.substring(0, 3) + " " + code.substring(3)
                codeView.visibility = View.VISIBLE
                messageView.text = "Check that the other phone shows the same code, then tap Confirm on both. Different code? Cancel."
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
            override fun onLinked(name: String) {
                if (pairDialog === dialog) pairDialog = null
                dialog.dismiss()
                ScanForegroundService.refreshLink(this@SentinelLinkActivity)
                refresh()
                MaterialAlertDialogBuilder(this@SentinelLinkActivity)
                    .setTitle("Linked")
                    .setMessage("Linked with $name. " + if (ScanForegroundService.isRunning) "Sharing starts now."
                        else "Sharing starts when both phones scan.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            override fun onEnded(message: String) {
                if (pairDialog === dialog) pairDialog = null
                dialog.dismiss()
                Toast.makeText(this@SentinelLinkActivity, message, Toast.LENGTH_LONG).show()
            }
        }
        if (host) PhoneLink.host(this, listener) else PhoneLink.join(this, listener)
    }
}
