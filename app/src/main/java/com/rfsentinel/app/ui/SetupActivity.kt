package com.rfsentinel.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rfsentinel.app.databinding.ActivitySetupBinding
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets

/**
 * First-launch setup wizard: welcome, theme, region presets, alerts, location
 * features, permissions. Every choice is saved as soon as it's made, so leaving
 * early keeps what was picked. Re-runnable from Settings.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val STEPS = 6
        private const val KEY_STEP = "step"
        /**
         * Step to resume after a theme change. Picking a theme can trigger two
         * back-to-back recreations (ours + AppCompat's night-mode switch), and
         * the saved-instance state doesn't reliably survive both.
         */
        private var resumeStep: Int? = null
    }

    private lateinit var binding: ActivitySetupBinding
    private var step = 0
    private var startAfter = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.hide()
        step = resumeStep ?: savedInstanceState?.getInt(KEY_STEP) ?: 0
        resumeStep = null

        binding.backButton.setOnClickListener { if (step > 0) { step--; render() } }
        binding.nextButton.setOnClickListener {
            if (step < STEPS - 1) { step++; render() } else finishSetup()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 0) { step--; render() } else finishSetup()
            }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    private fun render() {
        binding.stepText.text = "STEP ${step + 1} OF $STEPS"
        binding.progress.setProgressCompat(step + 1, true)
        binding.backButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        binding.nextButton.text = when (step) {
            0 -> "Get started"
            STEPS - 1 -> "Finish"
            else -> "Next"
        }
        binding.page.removeAllViews()
        when (step) {
            0 -> welcome()
            1 -> theme()
            2 -> region()
            3 -> alerts()
            4 -> location()
            5 -> permissions()
        }
    }

    // ---- Pages ----------------------------------------------------------------------

    private fun welcome() {
        header("Welcome to RF Sentinel", "A quick setup - about a minute. You can change everything later in Settings.")
        para("RF Sentinel listens - passively - to the Bluetooth and WiFi signals devices around you already broadcast, and flags equipment such as body cameras, license-plate cameras, drones, trackers following you and camera glasses.")
        bullet("It never transmits to, connects to or interferes with any device.")
        bullet("Scans, detections and history stay on this phone. Internet is only used for OpenStreetMap maps (including known plate, speed and red-light camera locations for the area you look at - can be turned off in Settings).")
        bullet("A match means a device with that signature is nearby - not proof of who is there.")
        para("Check your local laws before use. This is not legal advice.", small = true)
    }

    private fun theme() {
        header("Pick a look", "Tap a theme to preview it right away.")
        val current = ThemeManager.current(this)
        ThemeManager.AppTheme.entries.forEach { t ->
            val note = if (t == ThemeManager.AppTheme.MATERIAL_YOU && !ThemeManager.isDynamicColorAvailable)
                " (this phone: standard Material 3 colours)" else ""
            card(t.title, t.description + note, selected = t == current) {
                if (t != ThemeManager.current(this)) {
                    resumeStep = step
                    ThemeManager.set(this, t)
                    recreate() // restyles this screen; the step is kept
                }
            }
        }
    }

    private fun region() {
        header("Where do you use it?", "Adds watchlists of equipment known to be used by police in that region. The built-in detection (body cams, Flock, drones, trackers, glasses) works everywhere.")
        val enabled = OuiWatchlist.getEnabledPresets(this).toMutableSet()
        listOf(
            "global" to "Global - body cams (Axon, Zepcam, WatchGuard...), Flock, ShotSpotter, traffic cameras, radios",
            "canada" to "Canada - adds Cyberkar in-car systems, Getac body cams, Genetec plate readers",
            "us" to "United States"
        ).forEach { (key, label) ->
            check(label, key in enabled) { on ->
                if (on) enabled += key else enabled -= key
                OuiWatchlist.setEnabledPresets(this, enabled)
            }
        }
    }

    private fun alerts() {
        header("Alerts", "How loud should RF Sentinel be when it spots something?")
        label("Sound an alert for matches that are at least...")
        val group = RadioGroup(this)
        val threshold = Prefs.alertThreshold(this)
        listOf(
            0 to "Weak - every match (more false alarms)",
            50 to "Probable - recommended",
            80 to "Strong only - fewest alerts"
        ).forEach { (value, text) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                isChecked = when (value) {
                    0 -> threshold < 50
                    50 -> threshold in 50..79
                    else -> threshold >= 80
                }
                setOnCheckedChangeListener { _, c -> if (c) Prefs.setAlertThreshold(this@SetupActivity, value) }
            })
        }
        binding.page.addView(group)
        switch("Alert sound", Prefs.soundEnabled(this)) { Prefs.setSoundEnabled(this, it) }
        switch("Vibration (1 pulse weak, 2 probable, 3 strong)", Prefs.vibrateEnabled(this)) { Prefs.setVibrateEnabled(this, it) }
        switch("Spoken alerts (\"Axon body camera nearby\")", Prefs.voiceEnabled(this)) { Prefs.setVoiceEnabled(this, it) }
        switch("Discreet mode - hide details on the lock screen and in notifications", Prefs.discreetMode(this)) {
            Prefs.setDiscreetMode(this, it)
        }
    }

    private fun location() {
        header("Location features", "These use GPS while scanning. Everything stays on this phone.")
        switch("Warn me when a tracker or flagged device keeps moving with me", Prefs.followerAlerts(this)) {
            Prefs.setFollowerAlerts(this, it)
        }
        switch("Record a GPS trace automatically whenever scanning starts (view it on the map later)", Prefs.autoRecordTrace(this)) {
            Prefs.setAutoRecordTrace(this, it)
        }
        switch("Save my GPS position with logged matches (privacy-sensitive)", Prefs.gpsTaggingEnabled(this)) {
            Prefs.setGpsTaggingEnabled(this, it)
        }
        switch("Start scanning automatically when the phone boots", Prefs.autoStartOnBoot(this)) {
            Prefs.setAutoStartOnBoot(this, it)
        }
    }

    private fun permissions() {
        header("Permissions", "Android needs your OK before any app can hear nearby Bluetooth and WiFi devices.")
        val missing = Permissions.missingRequired(this)
        val optionalMissing = Permissions.optional().filterNot { Permissions.granted(this, it) }
        if (missing.isEmpty()) {
            para("✓ Location and nearby-devices access granted.")
        } else {
            para("• Location - Android requires it to read WiFi scan results.\n• Nearby devices - to hear Bluetooth advertisements.")
            button("Grant permissions") {
                permissionLauncher.launch((Permissions.required() + Permissions.optional()).toTypedArray())
            }
        }
        if (missing.isEmpty() && optionalMissing.isNotEmpty()) {
            para("Notifications are off - alerts will still sound, but won't appear in the notification shade.", small = true)
            button("Allow notifications") { permissionLauncher.launch(optionalMissing.toTypedArray()) }
        }
        para("Tip: in Android's battery settings, set RF Sentinel to \"Unrestricted\" so scanning keeps running in your pocket.", small = true)
        check("Start scanning when I tap Finish", startAfter) { startAfter = it }
    }

    private fun finishSetup() {
        Prefs.setOnboardingDone(this)
        if (step == STEPS - 1 && startAfter && Permissions.missingRequired(this).isEmpty() && !ScanForegroundService.isRunning) {
            runCatching { ScanForegroundService.start(this) }
                .onFailure { Toast.makeText(this, "Couldn't start scanning - tap Start on the main screen", Toast.LENGTH_LONG).show() }
        }
        finish()
    }

    // ---- Small view helpers ------------------------------------------------------------

    private val dp get() = resources.displayMetrics.density

    private fun header(title: String, subtitle: String) {
        binding.titleText.text = title
        binding.subtitleText.text = subtitle
    }

    private fun para(text: String, small: Boolean = false) {
        binding.page.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 15f)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            if (small) alpha = 0.75f
        })
    }

    private fun bullet(text: String) = para("• $text")

    private fun label(text: String) {
        binding.page.addView(TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        binding.page.addView(SwitchMaterial(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        binding.page.addView(CheckBox(this).apply {
            this.text = text
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun button(text: String, onClick: () -> Unit) {
        binding.page.addView(com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        })
    }

    private fun card(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (10 * dp).toInt() }
            radius = 14 * dp
            strokeWidth = ((if (selected) 3 else 1) * dp).toInt()
            strokeColor = com.google.android.material.color.MaterialColors.getColor(
                this@SetupActivity,
                if (selected) com.google.android.material.R.attr.colorSecondary else com.google.android.material.R.attr.colorOnSurface,
                0xFF888888.toInt()
            ).let { if (selected) it else (it and 0x00FFFFFF) or 0x33000000 }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            addView(TextView(context).apply {
                text = (if (selected) "◉  " else "○  ") + title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.8f
            })
        }
        card.addView(inner)
        binding.page.addView(card)
    }
}
