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
            CarConnection(context.applicationContext).type.observeForever { type ->
                connected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                    type == CarConnection.CONNECTION_TYPE_NATIVE
            }
        }
    }
}
