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
 * Tap one to hear it.
 */
object VoiceHistory {
    fun show(a: AppCompatActivity) = a.lifecycleScope.launch {
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
        fun stop() { player?.release(); player = null }
        val stopOnLeave = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) stop() }
        a.lifecycle.addObserver(stopOnLeave)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setTitle("Voice history")
            .setSingleChoiceItems(labels, -1) { _, i ->
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
            .setPositiveButton("Close", null)
            .setOnDismissListener { stop(); a.lifecycle.removeObserver(stopOnLeave) }
            .show()
    }
}
