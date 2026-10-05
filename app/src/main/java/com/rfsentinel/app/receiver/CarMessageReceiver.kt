package com.rfsentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.rfsentinel.app.util.CarMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android Auto's actions on the alert conversation ([CarMessages]): "mark as read"
 * clears it; a spoken reply of "mute" silences alerts for 30 minutes and "ignore"
 * stops alerting about the latest device (same as the alert card's buttons).
 */
class CarMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_MARK_READ -> CarMessages.clear(app)
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                val mac = CarMessages.lastMac
                CarMessages.clear(app)
                when (CarMessages.command(reply)) {
                    CarMessages.Command.MUTE -> {
                        AlertActionReceiver.snooze(app)
                        CarMessages.post(app, "Alerts muted for 30 minutes.")
                    }
                    CarMessages.Command.IGNORE -> {
                        if (mac == null) return
                        val pending = goAsync()
                        scope.launch {
                            try {
                                val done = runCatching { AlertActionReceiver.ignore(app, mac) }.getOrNull()
                                if (done != null) CarMessages.post(app, "$done.")
                            } finally {
                                pending.finish()
                            }
                        }
                    }
                    CarMessages.Command.NONE -> Unit
                }
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.rfsentinel.app.action.CAR_MESSAGE_REPLY"
        const val ACTION_MARK_READ = "com.rfsentinel.app.action.CAR_MESSAGE_READ"
        const val KEY_REPLY = "reply"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
