package com.rfsentinel.app.probes

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.online.Wigle
import com.rfsentinel.app.online.WiglePins
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.MapActivity
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.SecureStore
import com.rfsentinel.app.util.Units
import kotlinx.coroutines.launch

/**
 * WiGLE screens: the account (API name and token), a search for a network name or an access point
 * address near you then worldwide, its answers, and pinning them on the map.
 */
object WigleUi {

    const val PRIVACY = "Searches WiGLE's database of networks mapped by wardrivers. Needs your own free WiGLE " +
        "account: the API name and token from wigle.net/account (not your login), stored encrypted on this phone.\n\n" +
        "What's sent to WiGLE: the name or address you search and, for a search near you, your position rounded " +
        "to about 10 km. New accounts get only a few searches a day; an answer is kept on this phone for a day."

    /** The WiGLE account fields; [then] runs after saving when both are filled in. */
    fun account(a: AppCompatActivity, then: (() -> Unit)? = null) {
        val dp = a.resources.displayMetrics.density
        fun field(hint: String, key: String, password: Boolean) = EditText(a).apply {
            this.hint = hint
            setText(SecureStore.get(a, key).orEmpty())
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val name = field("API name (starts with AID)", Wigle.NAME_KEY, false)
        val token = field("API token", Wigle.TOKEN_KEY, true)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(TextView(a).apply { text = PRIVACY; textSize = 13f; alpha = 0.8f })
            addView(name); addView(token)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("WiGLE account")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                SecureStore.put(a, Wigle.NAME_KEY, name.text.toString().trim())
                SecureStore.put(a, Wigle.TOKEN_KEY, token.text.toString().trim())
                if (Wigle.hasAccount(a)) then?.invoke()
            }
            .setNeutralButton("Remove") { _, _ ->
                SecureStore.put(a, Wigle.NAME_KEY, ""); SecureStore.put(a, Wigle.TOKEN_KEY, "")
                Toast.makeText(a, "WiGLE account removed from this phone", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for a name or an address to search. */
    fun ask(a: AppCompatActivity) {
        val input = EditText(a).apply {
            hint = "Network name or address"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            val pad = (20 * dp).toInt(); setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Search WiGLE")
            .setMessage("A WiFi network name (SSID), * as a wildcard, or an access point address (BSSID).")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { search(a, it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Searches [text] near you (worldwide when your position isn't known), offering worldwide when nothing is near. */
    fun search(a: AppCompatActivity, text: String, worldwide: Boolean = false) {
        if (!Wigle.hasAccount(a)) { account(a) { search(a, text, worldwide) }; return }
        val me = myPosition(a)
        val near = if (worldwide) null else me
        // Every step as it happens, and how long WiGLE has been at it.
        val dp = a.resources.displayMetrics.density
        val steps = mutableListOf<String>()
        val log = TextView(a).apply { textSize = 13f; setTextIsSelectable(true) }
        val clock = TextView(a).apply { textSize = 13f; alpha = 0.75f; setPadding(0, (8 * dp).toInt(), 0, 0) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt(); setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(log); addView(clock)
        }
        var cancelled = false
        val progress = MaterialAlertDialogBuilder(a)
            .setTitle(if (near != null) "Searching WiGLE near you…" else "Searching WiGLE worldwide…")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .show()
        fun step(s: String) = a.runOnUiThread { steps += "• $s"; log.text = steps.joinToString("\n") }
        val started = android.os.SystemClock.elapsedRealtime()
        val ticker = a.lifecycleScope.launch {
            while (true) {
                val s = (android.os.SystemClock.elapsedRealtime() - started) / 1000
                clock.text = "$s s of up to ${Wigle.ANSWER_TIMEOUT_MS / 1000} s"
                kotlinx.coroutines.delay(1000)
            }
        }
        a.lifecycleScope.launch {
            val result = runCatching { Wigle.search(a, text, near, ::step) }
            ticker.cancel()
            progress.dismiss()
            // A cancelled search still keeps a good answer for the day; it just isn't shown.
            if (cancelled || a.isFinishing || a.isDestroyed) return@launch
            result.onSuccess { answer ->
                if (answer.nets.isEmpty() && answer.near) {
                    MaterialAlertDialogBuilder(a)
                        .setTitle("Nothing near you")
                        .setMessage("WiGLE has no \"$text\" within ${Wigle.NEAR_KM.toInt()} km of you. Search worldwide?")
                        .setPositiveButton("Search worldwide") { _, _ -> search(a, text, worldwide = true) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    results(a, text, answer, me)
                }
            }.onFailure { e ->
                MaterialAlertDialogBuilder(a)
                    .setTitle("WiGLE search failed")
                    .setMessage((e.message ?: "Unknown error") + "\n\nWhat happened:\n" + steps.joinToString("\n"))
                    .apply { if (e.message?.contains("API name") == true) setPositiveButton("Account") { _, _ -> account(a) } }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun results(a: AppCompatActivity, text: String, answer: Wigle.Answer, me: Pair<Double, Double>?) {
        if (answer.nets.isEmpty()) {
            MaterialAlertDialogBuilder(a).setTitle("Not found")
                .setMessage("WiGLE has no network \"$text\" with a position.")
                .setNegativeButton("Close", null).show()
            return
        }
        val nets = if (me == null) answer.nets
        else answer.nets.sortedBy { DeviceRegistry.metersBetween(me.first, me.second, it.lat, it.lon) }
        val rows = nets.map { n -> line(n, me) }.toTypedArray()
        val where = if (answer.near) "within ${Wigle.NEAR_KM.toInt()} km" else "worldwide"
        val more = if (answer.total > nets.size) " (${nets.size} of ${answer.total} shown)" else ""
        MaterialAlertDialogBuilder(a)
            .setTitle("\"$text\" $where$more")
            .setItems(rows) { _, which -> pinAndShow(a, listOf(nets[which]), text, nets[which]) }
            .setPositiveButton("Pin all on map") { _, _ -> pinAndShow(a, nets, text, nets.first()) }
            .apply { if (answer.near) setNeutralButton("Worldwide") { _, _ -> search(a, text, worldwide = true) } }
            .setNegativeButton("Close", null)
            .show()
    }

    /** One answer as a list row: name and address, then where, when and how far. */
    internal fun line(n: Wigle.Net, me: Pair<Double, Double>?): String {
        val details = listOfNotNull(
            n.place,
            n.lastSeen?.take(10)?.let { "seen $it" },
            me?.let { Units.distance(DeviceRegistry.metersBetween(it.first, it.second, n.lat, n.lon)) + " away" }
        ).joinToString(" · ")
        return "${n.ssid ?: "Hidden network"}  ${n.bssid}" + if (details.isEmpty()) "" else "\n$details"
    }

    private fun pinAndShow(a: AppCompatActivity, nets: List<Wigle.Net>, searched: String, focus: Wigle.Net) {
        WiglePins.add(a, nets, searched)
        Toast.makeText(a, if (nets.size == 1) "Pinned on the map" else "${nets.size} networks pinned on the map", Toast.LENGTH_SHORT).show()
        a.startActivity(Intent(a, MapActivity::class.java)
            .putExtra(MapActivity.EXTRA_CENTER_LAT, focus.lat)
            .putExtra(MapActivity.EXTRA_CENTER_LON, focus.lon))
    }

    /** Where you are: the scan's last fix, else the phone's last known position. */
    @android.annotation.SuppressLint("MissingPermission") // checked just before
    private fun myPosition(context: Context): Pair<Double, Double>? {
        ScanForegroundService.lastFix?.let { return it.latitude to it.longitude }
        if (!Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best: Location? = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return best?.let { it.latitude to it.longitude }
    }
}
