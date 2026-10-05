package com.rfsentinel.app.car

import android.content.Context
import androidx.car.app.connection.CarConnection

/**
 * Whether the phone is currently connected to Android Auto (projection) or
 * running on an Android Automotive head unit. Used to route alert audio to the
 * car speakers the way navigation prompts are.
 */
object CarState {

    @Volatile var connected = false
        private set

    /** Call once from the main thread (Application.onCreate). */
    fun start(context: Context) {
        runCatching {
            val app = context.applicationContext
            CarConnection(app).type.observeForever { type ->
                val now = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                    type == CarConnection.CONNECTION_TYPE_NATIVE
                if (now && !connected) com.rfsentinel.app.receiver.CarAutoStart.onCarConnected(app, "Android Auto connected")
                if (!now && connected) {
                    com.rfsentinel.app.receiver.CarAutoStart.onCarDisconnected(app, "Android Auto disconnected")
                    // The next drive starts a fresh alert conversation.
                    com.rfsentinel.app.util.CarMessages.clear(app)
                }
                connected = now
            }
        }
    }
}
