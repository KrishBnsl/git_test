package com.gng.app.notify

import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.LocusIdCompat
import android.net.Uri
import android.graphics.Bitmap
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.gng.app.MainActivity
import com.gng.app.R
import com.gng.app.call.CallActivity

/** Builds and posts every notification the app shows. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            // Separate channels so DMs and gangs can have their own sound (Android settings › Notifications).
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "Gang messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages in your gangs"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DM, "Direct messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages from one person"
                },
            )
            nm.createNotificationChannel(
                // The ringtone and vibration are played by the app (CallSounds) so they loop until you answer,
                // so this channel itself is silent.
                NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            nm.deleteNotificationChannel("calls") // the old, noisy channel from 0.4.0
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(
                NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "When gng updates itself, or needs a tap to finish"
                },
            )
        }
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    // --- messages ---------------------------------------------------------------

    /**
     * Adds a line to a chat's notification, WhatsApp style: the last few messages, how many are
     * unread, and a summary across chats. What's already showing is read back from the system, so
     * the thread survives the app being killed between pushes.
     *
     * [unread]: the server's count of unread messages in this chat (null: count locally).
     * [counts]: false for lines that aren't messages (reactions), so the unread count stays put.
     * [totalUnread]/[unreadChats]: the server's counts across all chats, for the summary.
     */
    @SuppressLint("MissingPermission")
    fun showMessage(
        groupId: String,
        title: String,
        isDm: Boolean,
        sender: String,
        text: String,
        lastMessageId: Long,
        myName: String,
        unread: Int? = null,
        counts: Boolean = true,
        totalUnread: Int? = null,
        unreadChats: Int? = null,
        quiet: Boolean = false,
        /** Who sent it: a stable key, and their face. */
        senderKey: String? = null,
        senderIcon: Bitmap? = null,
        /** The chat's picture (gang photo, or the other person in a DM), for the conversation shortcut. */
        chatIcon: Bitmap? = null,
        /** A photo in the message, shown inline. */
        image: Uri? = null,
        /** When it was sent (old messages arriving late shouldn't look new). */
        sentAt: Long = System.currentTimeMillis(),
        /** Post without sound or vibration. */
        silent: Boolean = false,
        /** Swap the newest line instead of adding one (the same message, now with its photo). */
        replaceLast: Boolean = false,
    ) {
        if (!canPost()) return
        val id = groupId.hashCode()
        val previous = active(id)
        val lastId = lastMessageId.takeIf { it > 0 } ?: previous?.extras?.getLong(EXTRA_LAST_ID) ?: 0
        val shownBefore = previous?.extras?.getInt(EXTRA_COUNT) ?: 0
        val count = when {
            !counts || replaceLast -> shownBefore
            unread != null && unread > 0 -> unread
            else -> shownBefore + 1
        }

        val me = Person.Builder().setName(myName).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (!isDm) style.setConversationTitle(title).setGroupConversation(true)
        val earlier = previous?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }?.messages.orEmpty()
        (if (replaceLast) earlier.dropLast(1) else earlier).takeLast(MAX_LINES - 1).forEach { style.addMessage(it) }
        val person = if (sender == myName) me else Person.Builder().setName(sender).setKey(senderKey ?: sender)
            .apply { senderIcon?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
        style.addMessage(
            NotificationCompat.MessagingStyle.Message(text, sentAt, person).apply { image?.let { setData("image/jpeg", it) } },
        )
        chatIcon?.let { NotificationArt.pushConversation(context, groupId, title, it, if (isDm) person else null) }
        // A pile of messages arriving at once (the phone was offline) makes one sound, not twenty.
        val now = System.currentTimeMillis()
        val alert = !silent && !quiet && now - lastAlert > ALERT_GAP_MS
        if (alert) lastAlert = now

        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply",
            actionIntent(NotificationActions.REPLY, groupId, lastId, id, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(NotificationActions.KEY_TEXT).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark as read",
            actionIntent(NotificationActions.MARK_READ, groupId, lastId, id, mutable = false),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val n = NotificationCompat.Builder(context, if (isDm) CH_DM else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setShortcutId(groupId)
            .setLocusId(LocusIdCompat(groupId))
            .setSilent(!alert)
            .apply { chatIcon?.let { setLargeIcon(it) } }
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSubText(if (count > 1) "$count new messages" else null)
            .setNumber(count)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setWhen(System.currentTimeMillis())
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openChat(groupId, id))
            .addAction(reply)
            .addAction(markRead)
            .addExtras(Bundle().apply {
                putInt(EXTRA_COUNT, count)
                putLong(EXTRA_LAST_ID, lastId)
                putString(EXTRA_TITLE, title)
                putString(EXTRA_PREVIEW, "$sender: $text")
            })
            .build()
        manager.notify(id, n)
        updateSummary(totalUnread, unreadChats)
    }

    /** Adds what you just replied from the notification, so the thread looks right. */
    fun appendMyReply(groupId: String, title: String, isDm: Boolean, myName: String, text: String) =
        showMessage(groupId, title, isDm, myName, text, 0, myName, counts = false, quiet = true)

    fun clearConversation(groupId: String) {
        manager.cancel(groupId.hashCode())
        updateSummary(null, null)
    }

    /** The bundle header: "12 messages from 3 chats", with a line per chat. */
    @SuppressLint("MissingPermission")
    private fun updateSummary(totalUnread: Int?, unreadChats: Int?) {
        val chats = activeMessages()
        if (chats.isEmpty()) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val messages = maxOf(totalUnread ?: 0, chats.sumOf { it.extras.getInt(EXTRA_COUNT).coerceAtLeast(1) })
        val chatCount = maxOf(unreadChats ?: 0, chats.size)
        val summary = "$messages message${if (messages == 1) "" else "s"}" +
            if (chatCount > 1) " from $chatCount chats" else ""
        val inbox = NotificationCompat.InboxStyle().setSummaryText(summary)
        chats.take(6).forEach { n ->
            val title = n.extras.getString(EXTRA_TITLE).orEmpty()
            val preview = n.extras.getString(EXTRA_PREVIEW).orEmpty()
            inbox.addLine(if (title.isEmpty()) preview else "$title  $preview")
        }
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("gng")
            .setContentText(summary)
            .setStyle(inbox)
            .setNumber(messages)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, SUMMARY_ID, Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        manager.notify(SUMMARY_ID, n)
    }

    private fun active(id: Int): Notification? =
        runCatching { manager.activeNotifications.firstOrNull { it.id == id && it.packageName == context.packageName }?.notification }
            .getOrNull()

    private fun activeMessages(): List<Notification> =
        runCatching {
            manager.activeNotifications
                .filter { it.notification.channelIdCompat() in setOf(CH_MESSAGES, CH_DM) && it.id != SUMMARY_ID && it.notification.group == GROUP }
                .sortedByDescending { it.postTime }
                .map { it.notification }
        }.getOrDefault(emptyList())

    private fun Notification.channelIdCompat(): String? = if (Build.VERSION.SDK_INT >= 26) channelId else CH_MESSAGES

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
            .setSmallIcon(R.drawable.ic_notification)
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

    /** "Missed voice call from Aman" (in a gang: "… in Hostel H4"), tap to open the chat. */
    @SuppressLint("MissingPermission")
    fun showMissedCall(groupId: String, callId: String, caller: String, gang: String?, video: Boolean) {
        if (!canPost()) return
        val kind = if (video) "video" else "voice"
        manager.notify(
            "missed:$callId".hashCode(),
            NotificationCompat.Builder(context, CH_MISSED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Missed $kind call")
                .setContentText(if (gang != null) "$caller in $gang" else "from $caller")
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setContentIntent(openChat(groupId, "missed:$callId".hashCode()))
                .addAction(NotificationCompat.Action.Builder(null, "Call back", openChat(groupId, "cb:$callId".hashCode(), callBack = video)).build())
                .setAutoCancel(true)
                .build(),
        )
    }

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("On a call · $title")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .build()
    }

    // --- updates ------------------------------------------------------------------

    /** Android wants a tap to install this update (the first one, or on older Android). */
    @SuppressLint("MissingPermission")
    fun showUpdateReady(install: PendingIntent) {
        if (!canPost()) return
        manager.notify(
            UPDATE_ID,
            NotificationCompat.Builder(context, CH_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("gng update ready")
                .setContentText("Tap to install. Later updates install by themselves.")
                .setContentIntent(install)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelUpdateReady() = manager.cancel(UPDATE_ID)

    @SuppressLint("MissingPermission")
    fun showUpdated(version: String) {
        if (!canPost()) return
        manager.notify(
            UPDATE_ID,
            NotificationCompat.Builder(context, CH_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("gng updated to $version")
                .setContentText("Tap to see what's new")
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, UPDATE_ID, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setTimeoutAfter(6 * 60 * 60 * 1000L)
                .setAutoCancel(true)
                .build(),
        )
    }

    // --- intents -------------------------------------------------------------------

    /** Opens a chat; [callBack] (true = video) also starts a call there. */
    private fun openChat(groupId: String, requestCode: Int, callBack: Boolean? = null): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_GROUP, groupId)
            .apply { callBack?.let { putExtra(MainActivity.EXTRA_CALL_BACK, if (it) "video" else "voice") } }
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
        const val CH_CALLS = "incoming_calls"
        const val CH_ONGOING = "ongoing_call"
        const val RING_TIMEOUT_MS = 45_000L
        const val CH_UPDATES = "updates"
        const val CH_DM = "direct_messages"
        /** Within this long of the last sound, new messages arrive quietly. */
        private const val ALERT_GAP_MS = 3_000L
        @Volatile private var lastAlert = 0L
        const val CH_MISSED = "missed_calls"
        private const val UPDATE_ID = 2
        private const val GROUP = "gng-messages"
        private const val SUMMARY_ID = 1
        private const val MAX_LINES = 8
        private const val EXTRA_COUNT = "gng.count"
        private const val EXTRA_LAST_ID = "gng.last_id"
        private const val EXTRA_TITLE = "gng.title"
        private const val EXTRA_PREVIEW = "gng.preview"
    }
}
