package com.rfsentinel.app.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.receiver.CarMessageReceiver

/**
 * Alerts as an Android Auto messaging conversation while the phone is on Android Auto.
 *
 * A real car only runs Car App Library screens installed from a trusted store (Google
 * Play, ONE store), but Android Auto's "Unknown sources" setting does cover messaging
 * notifications. So the GitHub APK still gets every alert onto the car screen this way,
 * and RF Sentinel shows in the car's launcher as a messaging app.
 * developer.android.com/training/cars/communication/notification-messaging
 */
object CarMessages {
    const val NOTIFICATION_ID = 3
    private const val MAX_MESSAGES = 8

    private data class Message(val text: String, val time: Long)

    private val unread = ArrayDeque<Message>()

    /** The device behind the latest alert, for a spoken "ignore" reply. */
    @Volatile var lastMac: String? = null
        private set

    /** What a spoken reply asks for. */
    enum class Command { MUTE, IGNORE, NONE }

    internal fun command(reply: String?): Command {
        val words = reply.orEmpty().lowercase().split(Regex("[^a-z]+")).toSet()
        return when {
            words.any { it in setOf("mute", "silence", "quiet", "snooze", "stop", "shut") } -> Command.MUTE
            "ignore" in words -> Command.IGNORE
            else -> Command.NONE
        }
    }

    /** Adds an alert to the conversation and shows it on the car screen. */
    @Synchronized
    fun post(context: Context, text: String, mac: String? = null, now: Long = System.currentTimeMillis()) {
        unread.addLast(Message(text, now))
        while (unread.size > MAX_MESSAGES) unread.removeFirst()
        if (mac != null) lastMac = mac
        show(context.applicationContext)
    }

    /** Read on the car (or replied to): the conversation starts over. */
    @Synchronized
    fun clear(context: Context) {
        unread.clear()
        lastMac = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun show(context: Context) {
        val user = Person.Builder().setName("You").setKey("rfsentinel-user").build()
        val sender = Person.Builder()
            .setName("RF Sentinel")
            .setKey("rfsentinel")
            .setIcon(runCatching {
                IconCompat.createWithBitmap(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(128, 128))
            }.getOrNull())
            .build()
        val style = NotificationCompat.MessagingStyle(user)
            .setConversationTitle("RF Sentinel")
            .setGroupConversation(false)
        unread.forEach { style.addMessage(it.text, it.time, sender) }

        // Android Auto's rules: one RemoteInput, semantic actions, no UI, a mutable reply intent.
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_car_volume_off, "Reply",
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, CarMessageReceiver::class.java).setAction(CarMessageReceiver.ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(RemoteInput.Builder(CarMessageReceiver.KEY_REPLY).setLabel("Say mute or ignore").build())
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_tile_scan, "Mark as read",
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, CarMessageReceiver::class.java).setAction(CarMessageReceiver.ACTION_MARK_READ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // The alerts channel has no sound or vibration: AlertPlayer plays the tone and voice.
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(reply)
            .addInvisibleAction(markRead)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied - sound and voice still alert.
        }
    }
}
