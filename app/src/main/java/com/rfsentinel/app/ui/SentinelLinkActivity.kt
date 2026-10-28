package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
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
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rfsentinel.app.link.LinkStore
import com.rfsentinel.app.link.MeshNode
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

    companion object {
        /** Opened from the "Pair your Meshtastic node" notification: pair now. */
        const val EXTRA_PAIR = "pair_mesh_node"
    }

    private lateinit var status: TextView
    private lateinit var share: SwitchMaterial
    private lateinit var list: LinearLayout
    private lateinit var leave: MaterialButton
    private lateinit var meshSection: LinearLayout
    private lateinit var meshSwitch: SwitchMaterial
    private lateinit var meshNodeText: TextView
    private lateinit var meshStatus: TextView
    private var pairDialog: AlertDialog? = null
    private var pending: (() -> Unit)? = null
    private val onLinkChanged: () -> Unit = { refresh() }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending ?: return@registerForActivityResult
        pending = null
        if (PhoneLink.canUse(this)) action()
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

        meshSection = meshSection(::button)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(16), pad(16), pad(16), pad(16))
            addView(intro); addView(status); addView(nameLabel); addView(name); addView(share)
            addView(hostButton); addView(joinButton); addView(listHeader); addView(list); addView(meshSection); addView(leave)
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
        MeshNode.addListener(onLinkChanged)
    }

    override fun onStop() {
        PhoneLink.removeListener(onLinkChanged)
        MeshNode.removeListener(onLinkChanged)
        super.onStop()
    }

    /** Meshtastic node: on / off, which node, how it's doing, how often it sends. */
    private fun meshSection(button: (String, () -> Unit) -> MaterialButton): LinearLayout {
        val header = TextView(this).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); text = "Meshtastic node"; setPadding(0, pad(20), 0, pad(4))
        }
        val about = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            text = "Reach Sentinels kilometres away through a Meshtastic node: a LoRa board with the official firmware, " +
                "paired with this phone over Bluetooth. RF Sentinel adds a private channel named ${com.rfsentinel.app.link.MeshCodec.CHANNEL_NAME} " +
                "to the node, made from the group's key, so only your group reads it. It goes in an empty slot (1 to 7) and " +
                "RF Sentinel only sends on it: never on the primary channel 0 or a channel you set up, and never to MQTT. " +
                "Others only see encrypted packets. LoRa is slow: watchlist matches go " +
                "first, then flagged devices, then every other device, a few per packet. While RF Sentinel uses the node, " +
                "the Meshtastic app on this phone can't."
        }
        meshSwitch = SwitchMaterial(this).apply {
            text = "Use a Meshtastic node"
            isChecked = LinkStore.meshOn(this@SentinelLinkActivity)
            setOnCheckedChangeListener { _, on ->
                LinkStore.setMeshOn(this@SentinelLinkActivity, on)
                ScanForegroundService.refreshLink(this@SentinelLinkActivity)
                if (on && LinkStore.meshNode(this@SentinelLinkActivity) == null) chooseNode()
                refresh()
            }
            setPadding(0, pad(8), 0, pad(8))
        }
        meshNodeText = TextView(this).apply { textSize = 14f }
        meshStatus = TextView(this).apply {
            textSize = 14f; setPadding(0, pad(4), 0, 0)
            // "not paired - tap here to pair"
            setOnClickListener { LinkStore.meshNode(this@SentinelLinkActivity)?.let { pairMeshNode(it) } }
        }
        val choose = button("Choose a node") { chooseNode() }
        val intervalLabel = TextView(this).apply { textSize = 14f; setPadding(0, pad(12), 0, 0) }
        fun showInterval(s: Int) { intervalLabel.text = "Send a packet every $s s - watchlist matches sooner" }
        val step = 15
        val interval = Slider(this).apply {
            valueFrom = LinkStore.MESH_INTERVAL_MIN.toFloat()
            valueTo = LinkStore.MESH_INTERVAL_MAX.toFloat()
            stepSize = step.toFloat()
            value = (LinkStore.meshIntervalSec(this@SentinelLinkActivity) / step * step).toFloat()
                .coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, v, fromUser ->
                showInterval(v.toInt())
                if (fromUser) LinkStore.setMeshIntervalSec(this@SentinelLinkActivity, v.toInt())
            }
        }
        showInterval(interval.value.toInt())
        fun slotLabel(s: Int) = if (s == 0) "Channel slot: automatic" else "Channel slot: $s"
        lateinit var slotButton: MaterialButton
        slotButton = button(slotLabel(LinkStore.meshSlot(this))) {
            val names = arrayOf("Automatic - first empty slot") + (1..7).map { "Slot $it" }
            MaterialAlertDialogBuilder(this)
                .setTitle("Channel slot on the node")
                .setMessage("Slot 0 is the primary channel and is never used. A slot that already holds another channel isn't changed.")
                .setSingleChoiceItems(names, LinkStore.meshSlot(this)) { d, which ->
                    LinkStore.setMeshSlot(this, which)
                    slotButton.text = slotLabel(which)
                    MeshNode.slotChanged()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val hopsLabel = TextView(this).apply { textSize = 14f; setPadding(0, pad(12), 0, 0) }
        fun showHops(n: Int) {
            hopsLabel.text = if (n == 1) "Hops: 1 - only nodes in range of yours pass it on"
                else "Hops: $n - relayed up to $n times (at most your node's own hop limit)"
        }
        val hops = Slider(this).apply {
            valueFrom = LinkStore.MESH_HOPS_MIN.toFloat()
            valueTo = LinkStore.MESH_HOPS_MAX.toFloat()
            stepSize = 1f
            value = LinkStore.meshHops(this@SentinelLinkActivity).toFloat()
            addOnChangeListener { _, v, fromUser ->
                showHops(v.toInt())
                if (fromUser) LinkStore.setMeshHops(this@SentinelLinkActivity, v.toInt())
            }
        }
        showHops(hops.value.toInt())
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(about); addView(meshSwitch); addView(meshNodeText); addView(meshStatus)
            addView(choose); addView(slotButton); addView(intervalLabel); addView(interval); addView(hopsLabel); addView(hops)
            setPadding(0, 0, 0, pad(12))
        }
    }

    /** Lists the Meshtastic nodes nearby; the one tapped is paired (Android asks for its PIN) and used. */
    private fun chooseNode() = withPermission {
        val names = ArrayList<String>()
        val addresses = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val hint = TextView(this).apply {
            textSize = 14f
            text = "Looking for Meshtastic nodes nearby… Turn the node on, keep it close, and close the Meshtastic app if it's connected to it."
            setPadding(pad(24), pad(12), pad(24), pad(8))
        }
        val listView = ListView(this).apply { this.adapter = adapter }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(hint); addView(listView) }
        var stop: (() -> Unit)? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Choose a Meshtastic node")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .create()
        listView.setOnItemClickListener { _, _, i, _ ->
            val address = addresses.getOrNull(i) ?: return@setOnItemClickListener
            stop?.invoke(); stop = null
            LinkStore.setMeshNode(this, address, names[i].substringBefore("  ·  "))
            LinkStore.setMeshOn(this, true)
            meshSwitch.isChecked = true
            pairMeshNode(address)
            ScanForegroundService.refreshLink(this)
            dialog.dismiss()
            refresh()
        }
        dialog.setOnDismissListener { stop?.invoke(); stop = null }
        stop = MeshNode.find(this) { name, address, rssi ->
            val label = "$name  ·  $rssi dBm"
            val i = addresses.indexOf(address)
            if (i >= 0) names[i] = label else { addresses += address; names += label }
            adapter.notifyDataSetChanged()
        }
        if (stop == null) { Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show(); return@withPermission }
        dialog.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // From the "Pair your Meshtastic node" notification: asked here, in front, Android shows its PIN dialog.
        if (intent.getBooleanExtra(EXTRA_PAIR, false)) {
            intent.removeExtra(EXTRA_PAIR)
            LinkStore.meshNode(this)?.let { pairMeshNode(it) }
        }
    }

    /** Asks Android to pair with the node, from this screen so its PIN dialog shows (not a notification). */
    private fun pairMeshNode(address: String) {
        com.rfsentinel.app.util.NotificationHelper.cancelMeshPairing(this)
        if (MeshNode.pair(this, address))
            Toast.makeText(this, "Android asks for a PIN: it's on the node's screen, or 123456", Toast.LENGTH_LONG).show()
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
        meshSection.visibility = if (grouped) View.VISIBLE else View.GONE
        meshNodeText.text = LinkStore.meshNodeName(this)?.let { "Node: $it" } ?: "No node chosen yet"
        meshStatus.text = when {
            !LinkStore.meshOn(this) -> "Off"
            LinkStore.meshNode(this) == null -> "Choose a node"
            !ScanForegroundService.isRunning || !LinkStore.enabled(this) -> "Starts with sharing, while scanning"
            else -> MeshNode.status.ifBlank { "Starting…" } +
                (MeshNode.lastSent.takeIf { it > 0 }?.let { " · sent " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS) } ?: "")
        }

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
            val overMesh = PhoneLink.lastHeardMesh[m.id]
            val detail = when {
                linked.containsKey(m.id) -> "Linked now"
                overMesh != null && overMesh == heard ->
                    "Over the mesh · last heard " + DateUtils.getRelativeTimeSpanString(overMesh, now, DateUtils.MINUTE_IN_MILLIS)
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
                .setPositiveButton("Join") { _, _ -> withPermission { startPairing(false) } }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        withPermission { startPairing(host) }
    }

    private fun withPermission(action: () -> Unit) {
        if (PhoneLink.canUse(this) && (Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            action(); return
        }
        pending = action
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
