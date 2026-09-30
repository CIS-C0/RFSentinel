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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.Favorites
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

    fun openDetails(activity: AppCompatActivity, mac: String) {
        activity.startActivity(
            Intent(activity, DeviceDetailActivity::class.java).putExtra(DeviceDetailActivity.EXTRA_MAC, mac)
        )
    }

    /** Long-press menu. */
    fun showQuickActions(activity: AppCompatActivity, mac: String) {
        val snap = DeviceRegistry.get(mac)
        val custom = OuiWatchlist.customEntry(mac) ?: OuiWatchlist.customEntry(MacUtil.oui(mac))
        val whitelisted = WhitelistCache.contains(mac)
        val favorite = Favorites.contains(mac)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Show details" to { openDetails(activity, mac) }
        if (custom != null) {
            items += "Remove from watchlist (${custom.prefix})" to {
                OuiWatchlist.removeCustomEntry(activity, custom.prefix)
                toast(activity, "Removed from watchlist")
            }
        } else {
            items += "Add to watchlist..." to { addToWatchlist(activity, mac, snap?.name) }
        }
        items += (if (whitelisted) "Remove from whitelist" else "Whitelist (trust & ignore)") to {
            if (whitelisted) unwhitelist(activity, mac) else whitelist(activity, mac, snap?.best?.label ?: snap?.name ?: "")
        }
        items += (if (favorite) "Remove from favorites" else "Add to favorites ★") to {
            activity.lifecycleScope.launch {
                Favorites.set(activity, mac, !favorite, snap?.name, snap?.vendor, snap?.deviceType)
                toast(activity, if (favorite) "Removed from favorites" else "Added to favorites")
            }
        }
        if (snap?.advert != null && snap.best == null) {
            items += "Report unknown device..." to { reportUnknown(activity, mac) }
        }
        items += "Copy address" to { copy(activity, mac) }

        AlertDialog.Builder(activity)
            .setTitle(snap?.best?.label ?: snap?.name ?: mac)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /**
     * Adds a device to the watchlist, either as this exact device (full MAC, the
     * default) or as its whole vendor prefix (disabled for randomized addresses).
     */
    fun addToWatchlist(activity: AppCompatActivity, mac: String, name: String?) {
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
        b.labelInput.setText(name ?: "")
        AlertDialog.Builder(activity)
            .setTitle("Add to watchlist")
            .setView(b.root)
            .setPositiveButton("Add") { _, _ ->
                val wholePrefix = b.scopePrefix.isChecked
                val key = if (wholePrefix) oui else mac
                val label = b.labelInput.text.toString().trim()
                    .ifEmpty { if (wholePrefix) "Custom prefix $oui" else "Custom device $mac" }
                OuiWatchlist.addCustomEntry(activity, OuiEntry(key, label, "user-added from live list", "custom"))
                toast(activity, "Added $key to the watchlist")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
            textSize = 11f
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
        AlertDialog.Builder(activity)
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

    fun whitelist(activity: AppCompatActivity, mac: String, label: String) {
        AlertDialog.Builder(activity)
            .setTitle("Whitelist this device?")
            .setMessage("$mac\n$label\n\nIt will be marked trusted and never logged, flashed or alerted on.")
            .setPositiveButton("Whitelist") { _, _ ->
                activity.lifecycleScope.launch {
                    AppDatabase.getInstance(activity).whitelistDao().add(WhitelistEntity(mac, label))
                    toast(activity, "Whitelisted $mac")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun unwhitelist(activity: AppCompatActivity, mac: String) {
        activity.lifecycleScope.launch {
            AppDatabase.getInstance(activity).whitelistDao().remove(WhitelistEntity(mac))
            toast(activity, "Removed $mac from the whitelist")
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
