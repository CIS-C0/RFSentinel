package com.rfsentinel.app.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.rfsentinel.app.BuildConfig

/** Version, scope, and where every signature and lookup table comes from. */
object AboutDialog {
    fun show(activity: AppCompatActivity) {
        val html = """
            <b>RF Sentinel ${BuildConfig.VERSION_NAME}</b><br/>
            Copyright &copy; 2026 CIS-C0. Free software under the GNU General Public
            License v3.0: you may share and modify it under its terms. It comes with
            ABSOLUTELY NO WARRANTY. Source code: github.com/CIS-C0/RFSentinel<br/><br/>
            Passive, receive-only Bluetooth LE and WiFi scanner. It never transmits to,
            connects to, jams or spoofs any device. Scans, detections, history and traces stay
            on this phone. The only internet use is downloading OpenStreetMap tiles while the
            map is open (the tile server sees your IP and the area viewed, never detections).
            Map data &copy; OpenStreetMap contributors.<br/><br/>

            <b>What a match means</b><br/>
            A device with that vendor or payload signature is broadcasting nearby. It is not
            proof of who is there. "Weak" matches (under 50%) are shared identifiers - verify
            before acting on them. Signal strength is not a reliable distance.<br/><br/>

            <b>Signature sources</b><br/>
            &bull; IEEE Registration Authority (MA-L/MA-M/MA-S) - MAC prefixes and vendor names<br/>
            &bull; Bluetooth SIG assigned numbers - company IDs, UUIDs, appearance values<br/>
            &bull; all-cameras-are-beacons signature reference (Apache-2.0) - Axon BWCDEVICE tag,
              Flock / Raven signatures, tracker and smart-glasses rules, drone maker prefixes<br/>
            &bull; ASTM F3411 Remote ID, decoded per opendroneid-core-c (Apache-2.0)<br/>
            &bull; Google Find Hub Network accessory spec; arXiv 2501.17452 (trackers)<br/>
            &bull; Research by ryanohoro and GainSec (Flock); Alan Meekins' DEF CON 31 talk (Axon)<br/><br/>

            DedSec and Night Vision theme font: Share Tech Mono &copy; Carrois Type Design, SIL Open Font
            License 1.1 (assets/licenses). "DedSec" is a name from Ubisoft's Watch Dogs; this
            theme is an unofficial, original homage and includes no Ubisoft artwork.<br/><br/>

            Feature ideas from SØPHIA, BLE Radar (MetaRadar) and RF Party; no code from
            those projects is included.<br/><br/>

            <b>Limits</b><br/>
            Phone radios hear less than dedicated hardware; Android throttles WiFi scans;
            WiFi only sees access points, not client devices; randomized addresses defeat
            prefix matching; most license-plate cameras other than Flock use cellular only
            and cannot be detected this way.<br/><br/>

            Check your local laws before use. This is not legal advice.
        """.trimIndent()
        val view = TextView(activity).apply {
            text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            textSize = 13f
        }
        AlertDialog.Builder(activity)
            .setTitle("About & sources")
            .setView(android.widget.ScrollView(activity).apply { addView(view) })
            .setPositiveButton("Close", null)
            .show()
    }
}
