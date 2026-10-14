package com.gng.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.gng.app.MainActivity
import com.gng.app.R
import com.gng.app.call.CallActivity
import java.util.concurrent.ConcurrentHashMap

/** Builds and posts every notification the app shows. */
class Notifier(private val context: Context) {
    data class Line(val sender: String, val text: String, val at: Long)

    private val manager = NotificationManagerCompat.from(context)
    private val history = ConcurrentHashMap<String, MutableList<Line>>()
    private val lastIds = ConcurrentHashMap<String, Long>()

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Chats in your gangs and DMs"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    vibrationPattern = longArrayOf(0, 800, 600, 800)
                    enableVibration(true)
                },
            )
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    // --- messages ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun showMessage(
        groupId: String,
        title: String,
        isDm: Boolean,
        sender: String,
        text: String,
        lastMessageId: Long,
        myName: String,
    ) {
        if (!canPost()) return
        if (lastMessageId > 0) lastIds[groupId] = lastMessageId
        val lines = history.getOrPut(groupId) { mutableListOf() }.apply {
            add(Line(sender, text, System.currentTimeMillis()))
            while (size > 7) removeAt(0)
        }
        val me = Person.Builder().setName(myName).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (!isDm) style.setConversationTitle(title).setGroupConversation(true)
        lines.forEach { l ->
            val person = if (l.sender == myName) me else Person.Builder().setName(l.sender).setKey(l.sender).build()
            style.addMessage(l.text, l.at, person)
        }
        val id = groupId.hashCode()
        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply",
            actionIntent(NotificationActions.REPLY, groupId, lastMessageId, id, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(NotificationActions.KEY_TEXT).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark read",
            actionIntent(NotificationActions.MARK_READ, groupId, lastMessageId, id, mutable = false),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()

        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("gng-messages")
            .setContentIntent(openChat(groupId, id))
            .addAction(reply)
            .addAction(markRead)
            .build()
        manager.notify(id, n)
    }

    /** Adds what you just replied from the notification, so the thread looks right. */
    fun appendMyReply(groupId: String, title: String, isDm: Boolean, myName: String, text: String) =
        showMessage(groupId, title, isDm, myName, text, lastIds[groupId] ?: 0, myName)

    fun clearConversation(groupId: String) {
        history.remove(groupId)
        lastIds.remove(groupId)
        manager.cancel(groupId.hashCode())
    }

    // --- calls --------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun showIncomingCall(callId: String, title: String, caller: String, video: Boolean) {
        if (!canPost()) return
        val full = PendingIntent.getActivity(
            context, callId.hashCode(),
            CallActivity.intent(context, callId, CallActivity.ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val accept = PendingIntent.getActivity(
            context, callId.hashCode() + 1,
            CallActivity.intent(context, callId, CallActivity.ACTION_ACCEPT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, callId.hashCode() + 2,
            Intent(context, NotificationActions::class.java).setAction(NotificationActions.DECLINE_CALL)
                .putExtra(NotificationActions.EXTRA_CALL, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(caller).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (video) "📹 $caller" else "📞 $caller")
            .setContentText(if (title == caller) "Incoming ${if (video) "video" else "voice"} call" else "Calling $title")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, accept).setIsVideo(video))
        manager.notify(callId.hashCode(), builder.build())
    }

    fun cancelCall(callId: String) = manager.cancel(callId.hashCode())

    fun ongoingCall(title: String, callId: String): Notification {
        val open = PendingIntent.getActivity(
            context, 7, CallActivity.intent(context, callId, CallActivity.ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getBroadcast(
            context, 8, Intent(context, NotificationActions::class.java).setAction(NotificationActions.HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(title).build()
        return NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("On a call · $title")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .build()
    }

    // --- intents -------------------------------------------------------------------

    private fun openChat(groupId: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_GROUP, groupId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, groupId: String, lastId: Long, requestCode: Int, mutable: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode * 31 + action.hashCode(),
            Intent(context, NotificationActions::class.java).setAction(action)
                .putExtra(NotificationActions.EXTRA_GROUP, groupId)
                .putExtra(NotificationActions.EXTRA_LAST_ID, lastId),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_CALLS = "calls"
        const val CH_ONGOING = "ongoing_call"
        const val RING_TIMEOUT_MS = 45_000L
    }
}
