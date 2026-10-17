package com.rfsentinel.app.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FOSS build: asks before downloading a USB WiFi chip's non-free firmware ([Firmware]). A notification when the
 * adapter is plugged in; tapping it (or the adapter line in Settings) opens Settings with the question.
 */
object FirmwarePrompt {

    const val EXTRA = "com.rfsentinel.app.FIRMWARE_PROMPT"
    private const val CHANNEL = "usb_firmware"
    private const val NOTIFICATION_ID = 4130

    fun notify(context: Context, chip: UsbWifi.Chip) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB adapter firmware", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, com.rfsentinel.app.settings.SettingsActivity::class.java)
                .putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_scan)
                .setContentTitle("${chip.name} adapter needs its firmware")
                .setContentText("Tap to download it once from kernel.org")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** The question, for the adapter waiting for its firmware; false when none is waiting. */
    fun show(activity: AppCompatActivity, onDone: () -> Unit = {}): Boolean {
        val chip = UsbWifi.firmwareNeeded ?: return false
        val blobs = Firmware.missing(activity, chip.driver).ifEmpty { UsbWifi.retry(activity); return false }
        val kb = blobs.sumOf { it.size } / 1024
        val files = blobs.joinToString("\n") { "• ${it.name}" }
        AlertDialog.Builder(activity)
            .setTitle("Download the ${chip.name} firmware?")
            .setMessage("This adapter needs its maker's firmware to run. That firmware isn't free software, so this " +
                "build of RF Sentinel doesn't include it.\n\nRF Sentinel can download it once (about $kb KB) from the " +
                "Linux firmware repository at kernel.org, check it against a fixed fingerprint, and keep it on this " +
                "phone:\n$files\n\nkernel.org sees your IP address, nothing else. The files stay under their maker's " +
                "redistribution licence.")
            .setPositiveButton("Download") { _, _ -> download(activity, chip, blobs, onDone) }
            .setNeutralButton("Licence") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Firmware.licenceUrl(blobs.first()))))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
        return true
    }

    private fun download(activity: AppCompatActivity, chip: UsbWifi.Chip, blobs: List<Firmware.Blob>, onDone: () -> Unit) {
        val app = activity.applicationContext
        Toast.makeText(activity, "Downloading the ${chip.name} firmware…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { Firmware.download(app, blobs) }.exceptionOrNull() }
            if (error == null) {
                UsbWifi.log("Firmware for ${chip.name} downloaded and checked")
                cancel(app)
                UsbWifi.retry(app)
                Toast.makeText(app, "Firmware ready: starting the ${chip.name} adapter", Toast.LENGTH_SHORT).show()
            } else {
                UsbWifi.log("Firmware download failed: ${UsbWifi.describe(error)}")
                Toast.makeText(app, "Firmware download failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
            onDone()
        }
    }
}
