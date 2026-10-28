package com.rfsentinel.app.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FOSS build: asks before downloading a USB WiFi or Bluetooth chip's non-free firmware ([Firmware]). A notification
 * when the adapter is plugged in; tapping it (or the adapter line in Settings) opens Settings with the question.
 */
object FirmwarePrompt {

    const val EXTRA = "com.rfsentinel.app.FIRMWARE_PROMPT"
    private const val CHANNEL = "usb_firmware"
    private const val NOTIFICATION_ID = 4130

    /** [chip]: what the adapter is called ("MT7921AU", "RTL8761BU Bluetooth"). */
    fun notify(context: Context, chip: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB adapter firmware", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, com.rfsentinel.app.settings.SettingsActivity::class.java)
                .putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_scan)
                .setContentTitle("$chip adapter needs its firmware")
                .setContentText("Tap to download it once from kernel.org")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** What waits for firmware: its name, the files still missing, and how to start it once they're here. */
    private class Waiting(val name: String, val blobs: List<Firmware.Blob>, val retry: (Context) -> Unit)

    /** The WiFi adapter first, else the Bluetooth one; null when neither waits (or its files just arrived: started). */
    private fun waiting(context: Context): Waiting? {
        UsbWifi.firmwareNeeded?.let { chip ->
            val blobs = Firmware.missing(context, chip.driver)
            if (blobs.isNotEmpty()) return Waiting(chip.name, blobs, UsbWifi::retry)
            UsbWifi.retry(context)
        }
        UsbBluetooth.firmwareNeeded?.let { (chip, files) ->
            val blobs = Firmware.missingFiles(context, files)
            if (blobs.isNotEmpty()) return Waiting("$chip Bluetooth", blobs, UsbBluetooth::retry)
            UsbBluetooth.retry(context)
        }
        return null
    }

    /** The question, for the adapter waiting for its firmware; false when none is waiting. */
    fun show(activity: AppCompatActivity, onDone: () -> Unit = {}): Boolean {
        val chip = waiting(activity) ?: return false
        val blobs = chip.blobs
        val kb = blobs.sumOf { it.size } / 1024
        val files = blobs.joinToString("\n") { "• ${it.name}" }
        MaterialAlertDialogBuilder(activity)
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

    private fun download(activity: AppCompatActivity, chip: Waiting, blobs: List<Firmware.Blob>, onDone: () -> Unit) {
        val app = activity.applicationContext
        Toast.makeText(activity, "Downloading the ${chip.name} firmware…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { Firmware.download(app, blobs) }.exceptionOrNull() }
            if (error == null) {
                UsbWifi.log("Firmware for ${chip.name} downloaded and checked")
                cancel(app)
                chip.retry(app)
                Toast.makeText(app, "Firmware ready: starting the ${chip.name} adapter", Toast.LENGTH_SHORT).show()
            } else {
                UsbWifi.log("Firmware download failed: ${UsbWifi.describe(error)}")
                Toast.makeText(app, "Firmware download failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
            onDone()
        }
    }
}
