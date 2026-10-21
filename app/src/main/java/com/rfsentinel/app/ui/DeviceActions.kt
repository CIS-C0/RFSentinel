package com.rfsentinel.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.TrustedNetworks
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.databinding.DialogAddWatchlistBinding
import com.rfsentinel.app.detect.SignatureReport
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.MacUtil
import kotlinx.coroutines.launch

/** Actions on one device, shared by the list, the radar and the detail screen. */
object DeviceActions {

    /**
     * A logged match: the device's details, or for a police aircraft or a Waze report the map on it -
     * where it is now while the feed still has it, else where it was logged.
     */
    fun openDetection(activity: AppCompatActivity, d: com.rfsentinel.app.data.DetectionEntity) {
        // Internet alerts are logged as "AIR <ICAO address>" / "WAZE <report id>" (their alert key).
        val key = d.mac.takeIf { d.source == "ONLINE" && ' ' in it }?.let { it.substringBefore(' ').lowercase() + ":" + it.substringAfter(' ') }
        val intent = key?.let { MapActivity.onlineIntent(activity, it, d.latitude, d.longitude) }
        if (key == null || intent == null) { openDetails(activity, d.mac); return }
        if (d.latitude == null && !MapActivity.onlineIsLive(key)) {
            android.widget.Toast.makeText(activity, "Its position wasn't saved (GPS tagging off) and it's no longer current",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        activity.startActivity(intent)
    }

    fun openDetails(activity: AppCompatActivity, mac: String) {
        activity.startActivity(
            Intent(activity, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, mac)
        )
    }

    /** Long-press menu. */
    fun showQuickActions(activity: AppCompatActivity, mac: String) {
        val snap = DeviceRegistry.get(mac)
        val custom = OuiWatchlist.customEntry(mac) ?: OuiWatchlist.customEntry(MacUtil.oui(mac))
        val whitelisted = WhitelistCache.inTable(mac)
        val ignoredTracker = com.rfsentinel.app.data.TrackerMutes.isMuted(mac)
        // Trackers that change their address can't be whitelisted: offer ignoring instead.
        val rotatingTracker = snap?.best?.category == com.rfsentinel.app.detect.Category.TRACKER &&
            snap.addressType != com.rfsentinel.app.detect.AddressType.PUBLIC
        val favorite = Favorites.contains(mac)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Show details" to { openDetails(activity, mac) }
        if (custom != null) {
            items += "Remove from watchlist (${custom.prefix})" to {
                OuiWatchlist.removeCustomEntry(activity, custom.prefix)
                com.rfsentinel.app.util.Undo.show(activity, "Removed from watchlist") {
                    OuiWatchlist.addCustomEntry(activity, custom)
                }
            }
        } else {
            items += "Add to watchlist..." to { addToWatchlist(activity, mac, snap?.name, snap?.advert?.isWifi == true) }
        }
        val trustedName = !whitelisted && TrustedNetworks.contains(mac)
        when {
            trustedName -> items += "Remove network name from whitelist" to { untrustNetwork(activity, ssidOf(snap)) }
            ignoredTracker -> items += "Stop ignoring this tracker" to {
                com.rfsentinel.app.data.TrackerMutes.unmute(activity, mac)
                toast(activity, "Tracker no longer ignored")
            }
            rotatingTracker && !whitelisted -> items += "Ignore this tracker..." to { ignoreTracker(activity, mac) }
            else -> items += (if (whitelisted) "Remove from whitelist" else "Whitelist (trust & ignore)") to {
                if (whitelisted) unwhitelist(activity, mac) else whitelist(activity, mac, snap?.best?.label ?: snap?.name ?: "", ssidOf(snap))
            }
        }
        items += (if (favorite) "Remove from favorites" else "Add to favorites ★") to {
            activity.lifecycleScope.launch {
                Favorites.set(activity, mac, !favorite, snap?.name, snap?.vendor, snap?.deviceType)
                toast(activity, if (favorite) "Removed from favorites" else "Added to favorites")
            }
        }
        items += "I saw it: learn this device..." to { learn(activity, mac) }
        if (snap?.advert != null && snap.best == null) {
            items += "Report unknown device..." to { reportUnknown(activity, mac) }
        }
        items += "Copy address" to { copy(activity, mac) }

        MaterialAlertDialogBuilder(activity)
            .setTitle(snap?.best?.label ?: snap?.name ?: mac)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /**
     * Adds a device to the watchlist, either as this exact device (full MAC, the
     * default), as its whole vendor prefix (disabled for randomized addresses), or
     * as every network / device whose name starts the same way (an SSID prefix).
     */
    fun addToWatchlist(activity: AppCompatActivity, mac: String, name: String?, wifi: Boolean = false) {
        val oui = MacUtil.oui(mac)
        val randomized = MacUtil.isRandomized(mac)
        val b = DialogAddWatchlistBinding.inflate(activity.layoutInflater)
        b.scopeDevice.text = "Only this device ($mac)"
        b.scopePrefix.text = "Every device with prefix $oui"
        if (randomized) {
            b.warningText.visibility = View.VISIBLE
            b.warningText.text = "This is a randomized (private) address: it may change later, and its prefix " +
                "is not a real vendor, so prefix matching is disabled. Tip: add a name rule under " +
                "Watchlist & rules instead."
            b.scopePrefix.isEnabled = false
        }
        // A probe fingerprint (USB adapter) marks the device model / OS, even across random addresses.
        val fp = com.rfsentinel.app.usb.ProbeIntel.of(mac)?.fingerprint
        // FlipDeFlock's coarser probe shape: also survives random addresses, but many phones share one.
        val shape = com.rfsentinel.app.usb.ProbeIntel.of(mac)?.skeleton?.let { com.rfsentinel.app.usb.ProbeIntel.shapeKey(it) }
        if (shape != null) {
            b.scopeShape.visibility = View.VISIBLE
            b.scopeShape.text = "Every device whose probe requests are built the same way (${shape.removePrefix("shape-")}; " +
                "coarse: many phones share a shape)"
        }
        if (fp != null) {
            b.scopeFingerprint.visibility = View.VISIBLE
            b.scopeFingerprint.text = "Every device of this type (probe fingerprint $fp)"
            if (randomized) b.warningText.text = "This is a randomized (private) address: it may change later. " +
                "The probe fingerprint below stays the same - watch that to catch this type of device."
        }
        // The start of its name / SSID: catches the whole family ("Flock-..."), whatever the address.
        val named = name?.trim().orEmpty()
        if (named.isNotEmpty()) {
            b.scopeStarts.visibility = View.VISIBLE
            b.scopeStarts.text = if (wifi) "Every network whose name (SSID) starts with:" else "Every device whose name starts with:"
            b.startsInput.setText(named)
            b.scopeGroup.setOnCheckedChangeListener { _, id ->
                b.startsInput.visibility = if (id == b.scopeStarts.id) View.VISIBLE else View.GONE
                if (id == b.scopeStarts.id) b.startsInput.requestFocus()
            }
            if (randomized && fp == null) b.warningText.text = "This is a randomized (private) address: it may change later, and its " +
                "prefix is not a real vendor, so prefix matching is disabled. Tip: watch the start of its name instead."
        }
        b.labelInput.setText(name ?: "")
        MaterialAlertDialogBuilder(activity)
            .setTitle("Add to watchlist")
            .setView(b.root)
            .setPositiveButton("Add") { _, _ ->
                val wholePrefix = b.scopePrefix.isChecked
                val byFingerprint = fp != null && b.scopeFingerprint.isChecked
                val byShape = shape != null && b.scopeShape.isChecked
                val start = b.startsInput.text.toString().trim()
                val byStart = named.isNotEmpty() && b.scopeStarts.isChecked
                if (byStart && start.isEmpty()) { toast(activity, "Not added: enter the start of the name"); return@setPositiveButton }
                val key = when {
                    byStart -> OuiEntry.STARTS + start
                    byShape -> OuiEntry.FINGERPRINT + shape; byFingerprint -> OuiEntry.FINGERPRINT + fp
                    wholePrefix -> oui; else -> mac
                }
                // The label box holds the device's own full name: for a name prefix, say what is watched instead.
                val typed = b.labelInput.text.toString().trim().takeUnless { byStart && it == named }.orEmpty()
                val label = typed.ifEmpty {
                    when {
                        byStart -> (if (wifi) "SSID starts with " else "Name starts with ") + start
                        byShape -> "Probe shape ${shape!!.removePrefix("shape-")}"; byFingerprint -> "Device type $fp"
                        wholePrefix -> "Custom prefix $oui"; else -> "Custom device $mac"
                    }
                }
                OuiWatchlist.addCustomEntry(activity, OuiEntry(key, label, "user-added from live list", "custom"))
                toast(activity, "Added $key to the watchlist")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * "I saw it": the user confirmed what this device is (a camera on a pole, a patrol car...), so RF
     * Sentinel learns every stable thing that identifies it, to know it again next time, even under a new
     * address: its address when that is fixed, its Bluetooth advert pattern, its WiFi probe fingerprint.
     * A pattern many devices nearby share (one phone model) is refused, and patterns learned this way
     * stay probable matches ([LEARNED_SCORE]), never strong ones: only the exact address is certain.
     */
    fun learn(activity: AppCompatActivity, mac: String) {
        val snap = DeviceRegistry.get(mac)
        val advert = snap?.advert
        val randomized = MacUtil.isRandomized(mac)
        val d = activity.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        box.addView(TextView(activity).apply {
            text = "Tell RF Sentinel what this is, so it knows it next time, even under a new address."
        })
        val label = EditText(activity).apply {
            hint = "What it is, e.g. pole camera on the bridge"
            setSingleLine(true)
            setText(snap?.best?.label ?: snap?.name ?: "")
        }
        box.addView(label)

        // Each stable identity: key, what it is, how sure a later match is, and why it can't be used.
        class Option(val key: String, val text: String, val score: Int?, val refused: String?)
        val options = mutableListOf<Option>()
        if (!randomized) options += Option(mac, "This exact address ($mac)", null, null)
        if (advert != null) com.rfsentinel.app.detect.AdvertFingerprint.of(advert)?.let { fp ->
            val key = com.rfsentinel.app.detect.AdvertFingerprint.key(advert)!!
            val shared = DeviceRegistry.sharingFingerprint(fp)
            options += Option(OuiEntry.ADVERT + key, "Its Bluetooth advert pattern ($key)", LEARNED_SCORE,
                if (shared >= MAX_SHARED) "$shared devices nearby send the same pattern" else null)
        }
        com.rfsentinel.app.usb.ProbeIntel.of(mac)?.fingerprint?.let { fp ->
            val shared = com.rfsentinel.app.usb.ProbeIntel.sharing(fp)
            options += Option(OuiEntry.FINGERPRINT + fp, "Its WiFi probe fingerprint ($fp)", LEARNED_SCORE,
                if (shared >= MAX_SHARED) "$shared addresses heard today share it" else null)
        }
        val boxes = options.map { o ->
            android.widget.CheckBox(activity).apply {
                val watched = OuiWatchlist.customEntry(o.key) != null
                text = o.text + when {
                    watched -> "\nAlready on your watchlist"
                    o.refused != null -> "\nNot usable: ${o.refused}, so it would match them too"
                    o.score != null -> "\nMatches as probable (${o.score}%): similar devices can share it"
                    else -> ""
                }
                isEnabled = o.refused == null && !watched
                isChecked = isEnabled
            }.also { box.addView(it) }
        }
        if (boxes.none { it.isEnabled }) box.addView(TextView(activity).apply {
            setPadding(0, (8 * d).toInt(), 0, 0)
            text = if (options.isEmpty()) "Nothing stable to learn: its address is private and changes, and it sends nothing " +
                "that marks it apart from similar devices. Tip: add a name rule under Watchlist & rules."
            else "Nothing new to learn from it."
        })
        val category = snap?.best?.category?.takeIf { it != com.rfsentinel.app.detect.Category.CUSTOM }
        MaterialAlertDialogBuilder(activity)
            .setTitle("I saw it")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Learn") { _, _ ->
                val chosen = options.filterIndexed { i, _ -> boxes[i].isEnabled && boxes[i].isChecked }
                if (chosen.isEmpty()) { toast(activity, "Nothing learned"); return@setPositiveButton }
                val name = label.text.toString().trim().ifEmpty { "Seen device" }
                val entries = chosen.map { OuiEntry(it.key, name, OuiEntry.LEARNED, "custom", it.score, category?.name) }
                entries.forEach { OuiWatchlist.addCustomEntry(activity, it) }
                com.rfsentinel.app.util.Undo.show(activity, "Learned $name (${entries.size} " +
                    (if (entries.size == 1) "rule" else "rules") + ")") {
                    entries.forEach { OuiWatchlist.removeCustomEntry(activity, it.prefix) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** How sure a later match on a learned pattern (not an exact address) is: probable, never strong. */
    const val LEARNED_SCORE = 60
    /** A pattern this many devices share is refused: it would match them all. */
    const val MAX_SHARED = 4

    /**
     * Shares a signature report (see [SignatureReport]) so a device RF Sentinel
     * doesn't know can be added. The user sees exactly what is shared first.
     */
    fun reportUnknown(activity: AppCompatActivity, mac: String) {
        val snap = DeviceRegistry.get(mac)
        val advert = snap?.advert ?: run { toast(activity, "Device no longer in range"); return }
        val d = activity.resources.displayMetrics.density
        val note = EditText(activity).apply { hint = "What do you think it is? (optional)"; setSingleLine(false) }
        val preview = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        fun report() = SignatureReport.build(advert, snap.vendor, snap.deviceType, MacUtil.isRandomized(mac),
            note.text.toString(), BuildConfig.VERSION_NAME)
        preview.text = report()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, (8 * d).toInt(), p, 0)
            addView(TextView(activity).apply {
                text = "Shares only what a signature needs: vendor prefix, name pattern (serial digits hidden), " +
                    "service and manufacturer IDs. No full address, location or times."
            })
            addView(note)
            addView(preview)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Report unknown device")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Share") { _, _ ->
                val text = "RF Sentinel signature report - please open an issue at\n" +
                    SignatureReport.ISSUE_URL + "\n\n" + report()
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "New device signature")
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Share signature report"))
            }
            .setNeutralButton("Copy") { _, _ -> copy(activity, report()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * AirTags and other Find My / Find Hub tags change their address (about daily
     * away from their owner), so a MAC whitelist can't hold them. Offers ignoring
     * it as your own tag (followed through address changes), for today, or
     * pausing tracker follow warnings.
     */
    fun ignoreTracker(activity: AppCompatActivity, mac: String) {
        val snap = DeviceRegistry.get(mac) ?: run { toast(activity, "Tracker no longer in range"); return }
        val kind = snap.best?.label ?: return
        val choices = arrayOf(
            "It's mine - keep ignoring it\n(follows it when its address changes; best effort)",
            "Ignore it today only\n(until its address changes, at most 24 h)",
            "Pause all tracker follow warnings for 24 h"
        )
        val title = android.widget.TextView(activity).apply {
            val d = resources.displayMetrics.density
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            textSize = 16f
            text = "Ignore this tracker?\n\nIts Bluetooth address changes about once a day, so it can't be " +
                "whitelisted like other devices. Only ignore a tag you know - a hidden tracker would stay silent too."
        }
        MaterialAlertDialogBuilder(activity)
            .setCustomTitle(title)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        com.rfsentinel.app.data.TrackerMutes.mute(activity, mac, kind, snap.rssi, follow = true)
                        toast(activity, "Ignored - RF Sentinel follows it when its address changes")
                    }
                    1 -> {
                        com.rfsentinel.app.data.TrackerMutes.mute(activity, mac, kind, snap.rssi, follow = false)
                        toast(activity, "Ignored until its address changes (at most 24 h)")
                    }
                    else -> {
                        com.rfsentinel.app.util.Prefs.setTrackerFollowPausedUntil(activity, System.currentTimeMillis() + 24 * 3600_000L)
                        toast(activity, "Tracker follow warnings paused for 24 hours")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** [ssid]: a WiFi network's name, offered as a choice too (every network of that name, whatever its address). */
    fun whitelist(activity: AppCompatActivity, mac: String, label: String, ssid: String? = null) {
        fun addDevice() = activity.lifecycleScope.launch {
            AppDatabase.getInstance(activity).whitelistDao().add(WhitelistEntity(mac, label))
            toast(activity, "Whitelisted $mac")
        }
        val name = ssid?.trim().orEmpty()
        if (name.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Whitelist this device?")
                .setMessage("$mac\n$label\n\nIt will be marked trusted and never logged, flashed or alerted on.")
                .setPositiveButton("Whitelist") { _, _ -> addDevice() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // A network on many vehicles (a bus fleet's routers) has a new address on each: its name catches them all.
        var byName = false
        MaterialAlertDialogBuilder(activity)
            .setTitle("Whitelist: trusted, never logged, flashed or alerted on")
            .setSingleChoiceItems(arrayOf("Only this device ($mac)", "Every network named \"$name\""), 0) { _, which -> byName = which == 1 }
            .setPositiveButton("Whitelist") { _, _ ->
                if (!byName) { addDevice(); return@setPositiveButton }
                TrustedNetworks.save(activity, TrustedNetworks.Rule(name, exact = true))
                toast(activity, "Every network named $name is whitelisted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The device is trusted by its network name: that name comes off the whitelist (with Undo). */
    fun untrustNetwork(activity: AppCompatActivity, ssid: String?) {
        val rule = TrustedNetworks.ruleFor(ssid)
        if (rule == null) { toast(activity, "Edit network names under Whitelist"); return }
        TrustedNetworks.remove(activity, rule)
        com.rfsentinel.app.util.Undo.show(activity, "Removed ${rule.text} from the whitelist") { TrustedNetworks.save(activity, rule) }
    }

    /** A WiFi network's name, for whitelisting it by name; null for Bluetooth and hidden networks. */
    fun ssidOf(s: DeviceRegistry.Snapshot?): String? =
        s?.takeIf { it.source == com.rfsentinel.app.detect.Advert.Source.WIFI }?.name?.trim()?.takeIf { it.isNotEmpty() }

    fun unwhitelist(activity: AppCompatActivity, mac: String) {
        activity.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(activity).whitelistDao()
            val was = dao.get(mac) ?: WhitelistEntity(mac)
            dao.remove(was)
            com.rfsentinel.app.util.Undo.show(activity, "Removed $mac from the whitelist") {
                activity.lifecycleScope.launch { dao.add(was) }
            }
        }
    }

    fun copy(activity: AppCompatActivity, text: String) {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("RF Sentinel", text))
        toast(activity, "Copied")
    }

    private fun toast(activity: AppCompatActivity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
