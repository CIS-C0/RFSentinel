package com.rfsentinel.app.ui

import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.sdr.P25Log
import com.rfsentinel.app.sdr.P25Record
import com.rfsentinel.app.sdr.P25Transcribe
import com.rfsentinel.app.sdr.RadioReference
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Tools › Voice history (and P25 talkgroups › Voice history): the saved voice ([P25Record.speechFiles]), P25 calls and the
 * transmissions the radio sweep played or recorded, newest first, with their text once transcribed ([P25Transcribe]).
 * Tap one to hear it; Delete then removes it and its text.
 */
object VoiceHistory {
    fun show(a: AppCompatActivity): kotlinx.coroutines.Job = a.lifecycleScope.launch {
        class Loaded(val calls: List<P25Record.Saved>, val texts: List<String?>, val watched: P25Log.Watched, val rr: P25Log.Watched)
        val l = withContext(Dispatchers.IO) {
            val c = P25Record.speechFiles(a).mapNotNull { P25Record.saved(it) }
            Loaded(c, c.map { P25Transcribe.textOf(it.file) }, P25Log.watched(Prefs.p25Talkgroups(a)), RadioReference.names(a))
        }
        if (l.calls.isEmpty()) {
            android.widget.Toast.makeText(a, "Nothing saved yet: turn on Save played calls or Record voice transmissions " +
                "in Settings › RTL-SDR radio", android.widget.Toast.LENGTH_LONG).show()
            return@launch
        }
        val labels = l.calls.mapIndexed { i, c ->
            val who = c.tg?.let { tg -> "Talkgroup $tg" + ((l.watched[tg]?.ifEmpty { null } ?: l.rr[tg])?.let { " ($it)" } ?: "") }
                ?: c.hz?.let { String.format(Locale.US, "Radio %.4f MHz", it / 1e6) } ?: "Private call to radio ${c.target}"
            DateUtils.formatDateTime(a, c.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_MONTH) + " · $who" + (c.who?.let { " · $it" } ?: "") + " · " + String.format(Locale.US, "%.1f s", c.seconds) +
                (l.texts[i]?.let { "\n“$it”" } ?: "")
        }.toTypedArray()
        var player: android.media.MediaPlayer? = null
        var picked = -1
        fun stop() { player?.release(); player = null }
        val stopOnLeave = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) stop() }
        a.lifecycle.addObserver(stopOnLeave)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setTitle("Voice history")
            .setSingleChoiceItems(labels, -1) { _, i ->
                picked = i
                stop()
                player = runCatching {
                    android.media.MediaPlayer().apply {
                        setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(l.calls[i].file.path); prepare(); start()
                    }
                }.onFailure { android.widget.Toast.makeText(a, "This one can't be played", android.widget.Toast.LENGTH_SHORT).show() }.getOrNull()
            }
            .setNeutralButton("Share") { _, _ ->
                com.rfsentinel.app.util.Exporter.shareFiles(a, l.calls.map { it.file }, "audio/wav",
                    "RF Sentinel voice (${l.calls.size})", "Share voice history")
            }
            .setNegativeButton("Delete") { _, _ ->
                val c = l.calls.getOrNull(picked)
                if (c == null) {
                    // A short toast here was easy to miss: the list just came back and Delete looked broken (issue #23).
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                        .setTitle("Which recording?")
                        .setMessage("Tap a recording in the list first (it plays and gets a dot), then Delete.")
                        .setPositiveButton("OK") { _, _ -> show(a) }
                        .setNegativeButton("Delete all ${l.calls.size}") { _, _ ->
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                                .setTitle("Delete all ${l.calls.size} recordings?")
                                .setMessage("Their text goes too. This can't be undone.")
                                .setPositiveButton("Delete all") { _, _ ->
                                    a.lifecycleScope.launch {
                                        val left = withContext(Dispatchers.IO) { l.calls.count { !P25Record.delete(it.file) } }
                                        com.rfsentinel.app.util.HwLogs.sdr.log("Voice history: all deleted, $left still on the phone")
                                        android.widget.Toast.makeText(a, if (left > 0) "Couldn't delete $left recordings" else "Voice history cleared",
                                            android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNegativeButton("Cancel") { _, _ -> show(a) }
                                .show()
                        }
                        .show()
                    return@setNegativeButton
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                    .setTitle("Delete this recording?")
                    .setMessage(labels[picked])
                    .setPositiveButton("Delete") { _, _ ->
                        a.lifecycleScope.launch {
                            val gone = withContext(Dispatchers.IO) { P25Record.delete(c.file) }
                            com.rfsentinel.app.util.HwLogs.sdr.log("Voice history: recording " + if (gone) "deleted" else "not deleted, still on the phone")
                            if (!gone) android.widget.Toast.makeText(a, "Couldn't delete this recording", android.widget.Toast.LENGTH_LONG).show()
                            show(a)
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ -> show(a) }
                    .show()
            }
            .setPositiveButton("Close", null)
            .setOnDismissListener { stop(); a.lifecycle.removeObserver(stopOnLeave) }
            .show()
    }
}
