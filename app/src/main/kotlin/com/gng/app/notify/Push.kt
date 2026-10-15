package com.gng.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.gng.app.AppContainer
import com.gng.app.BuildConfig
import com.gng.app.call.CallActivity
import com.gng.app.container
import com.gng.app.data.CallRow
import com.gng.app.update.AutoUpdate
import com.gng.app.data.ChatMessage
import com.gng.app.data.MessageRow
import com.gng.app.data.Payload
import com.gng.app.data.ReactionRow
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Keeps this phone's FCM token on the server while push is switched on, and off it otherwise. */
class PushRegistry(private val context: Context, private val client: SupabaseClient) {
    val available: Boolean
        get() = BuildConfig.HAS_FIREBASE && FirebaseApp.getApps(context).isNotEmpty()

    suspend fun sync(enabled: Boolean) {
        if (!available) return
        runCatching {
            if (enabled) register(FirebaseMessaging.getInstance().token.await()) else unregister()
        }
    }

    suspend fun register(token: String) {
        client.from("device_tokens").upsert(TokenRow(token, "android", java.time.Instant.now().toString())) {
            onConflict = "token"
        }
    }

    suspend fun unregister() {
        if (!available) return
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            client.from("device_tokens").delete { filter { eq("token", token) } }
            FirebaseMessaging.getInstance().deleteToken().await()
        }
    }

    @Serializable
    private data class TokenRow(val token: String, val platform: String, @SerialName("updated_at") val updatedAt: String)
}

/**
 * Turns "something happened" into a notification. The server can't read messages, so a push
 * carries the encrypted message (plus names and unread counts, which aren't secret) and the phone
 * decrypts it and writes the notification itself. Everything needed usually arrives in the push and
 * the epoch key is cached on the phone, so nothing waits on the network before the notification shows.
 */
class Inbox(private val c: AppContainer) {
    /** Conversation currently on screen, so we don't notify about what you're already looking at. */
    @Volatile var openConversation: String? = null

    suspend fun onMessage(data: Map<String, String>) {
        val groupId = data["group_id"] ?: return
        val messageId = data["message_id"]?.toLongOrNull() ?: return
        val me = c.auth.awaitUserId() ?: return
        val m = fromPush(data, groupId, messageId, me) ?: c.chat.fetchOne(messageId, me) ?: return
        // A message request from someone outside your gangs: no receipts until you accept.
        val isRequest = data["request_from"]?.let { it != me } == true
        try {
            if (m.userId == me) return
            if (openConversation == groupId && c.foreground) return
            if (!c.settings.push.first()) return

            val conversation = c.chat.cachedConversations()?.firstOrNull { it.id == groupId }
            val taggedMe = m.payload.mentions(me) || data["mentions"]?.split(',')?.contains(me) == true
            // Muted chats stay quiet, unless someone tags you. (The server already skips them; this covers old servers.)
            if (conversation?.isMuted == true && !taggedMe) return
            val members = c.chat.cachedMembers(groupId).orEmpty().associate { it.userId to it.profiles }
            val senderName = data["sender_name"] ?: m.userId?.let { uid ->
                members[uid]?.displayName ?: runCatching { c.profiles.get(uid)?.displayName }.getOrNull()
            }
            val myName = members[me]?.displayName ?: "You"
            val isDm = data["group_kind"]?.let { it == "dm" } ?: (conversation?.isDm == true)
            val title = when {
                isRequest -> "Message request · ${senderName ?: "someone new"}"
                isDm -> senderName ?: "Direct message"
                else -> data["group_name"] ?: conversation?.name ?: "gng"
            }
            val previews = c.settings.previews.first()
            val text = when {
                !previews -> if (taggedMe) "You were mentioned" else "New message"
                m.payload.t == "event" -> m.payload.text.orEmpty()
                else -> m.payload.preview()
            }
            val shownSender = when {
                m.userId == null -> "gng"
                previews -> (senderName ?: "Someone") + if (taggedMe && !isDm) " mentioned you" else ""
                else -> "Someone"
            }
            c.notifier.showMessage(
                groupId, title, isDm, shownSender, text, messageId, myName,
                unread = data["unread"]?.toIntOrNull(),
                totalUnread = data["unread_total"]?.toIntOrNull(),
                unreadChats = data["unread_chats"]?.toIntOrNull(),
            )
        } finally {
            // We've got it, so the sender can see "Delivered". After the notification, not before.
            if (!isRequest) runCatching { c.chat.markSeen(groupId, messageId, null) }
        }
    }

    /** Decrypts the message carried in the push itself, if it fit. */
    private suspend fun fromPush(data: Map<String, String>, groupId: String, messageId: Long, me: String): ChatMessage? {
        val kind = data["kind"] ?: return null
        val hasBody = kind != "text" && data["body"] != null
        val hasCiphertext = kind == "text" && data["ciphertext"] != null && data["epoch"] != null
        if (!hasBody && !hasCiphertext) return null
        val row = MessageRow(
            id = messageId, groupId = groupId, userId = data["user_id"], body = data["body"], kind = kind,
            refId = data["ref_id"], epoch = data["epoch"]?.toIntOrNull(), ciphertext = data["ciphertext"],
            clientId = data["client_id"], createdAt = data["created_at"]?.takeIf { it.isNotBlank() } ?: Instant.now().toString(),
        )
        return runCatching { c.chat.decrypt(row, me) }.getOrNull()
    }

    /** "Aman reacted ❤️ to "see you at 6"", added to that chat's notification. */
    suspend fun onReaction(data: Map<String, String>) {
        val groupId = data["group_id"] ?: return
        val messageId = data["message_id"]?.toLongOrNull() ?: return
        val reactor = data["user_id"] ?: return
        val me = c.auth.awaitUserId() ?: return
        if (reactor == me || (openConversation == groupId && c.foreground) || !c.settings.push.first()) return
        val row = ReactionRow(messageId, reactor, groupId, data["epoch"]?.toIntOrNull(), data["ciphertext"])
        val emoji = c.chat.openReaction(row, me) ?: return
        val members = c.chat.cachedMembers(groupId).orEmpty().associate { it.userId to it.profiles }
        val name = data["sender_name"] ?: members[reactor]?.displayName ?: "Someone"
        val isDm = data["group_kind"] == "dm"
        val title = if (isDm) name else data["group_name"] ?: "gng"
        val quoted = c.chat.cachedMessage(messageId)?.payload?.preview()?.take(60)
        val text = when {
            !c.settings.previews.first() -> "Reacted to your message"
            quoted.isNullOrBlank() -> "Reacted $emoji to your message"
            else -> "Reacted $emoji to \u201c$quoted\u201d"
        }
        c.notifier.showMessage(
            groupId, title, isDm, name, text, 0, members[me]?.displayName ?: "You",
            counts = false,
        )
    }

    /** Someone joined a chat or got a new phone: wrap the older keys for them so they can read the history. */
    suspend fun onShareKeys() {
        val me = c.auth.awaitUserId() ?: return
        runCatching { c.e2ee.shareHistory(me) }
    }

    /** [callerName], [groupName], [isDm]: from the push, so the phone rings without looking anything up. */
    suspend fun onCall(call: CallRow, callerName: String? = null, groupName: String? = null, isDm: Boolean? = null) {
        val me = c.auth.awaitUserId() ?: return
        if (call.startedBy == me || call.endedAt != null) return
        val conversation = if (isDm == null || (!isDm && groupName == null)) {
            (c.chat.cachedConversations() ?: runCatching { c.chat.conversations() }.getOrNull())?.firstOrNull { it.id == call.groupId }
        } else {
            null
        }
        // Cached names first, so the phone starts ringing without waiting on the network.
        val caller = callerName
            ?: c.chat.cachedMembers(call.groupId)?.firstOrNull { it.userId == call.startedBy }?.profiles?.displayName
            ?: runCatching { c.profiles.get(call.startedBy)?.displayName }.getOrNull()
            ?: "Someone"
        val dm = isDm ?: (conversation?.isDm != false)
        val title = if (dm) caller else groupName ?: conversation?.name ?: caller
        if (!c.calls.ring(call, title, caller, isDm = dm)) return
        if (c.foreground) {
            // App is open: go straight to the ringing screen.
            c.appContext.startActivity(CallActivity.intent(c.appContext, call.id, CallActivity.ACTION_SHOW))
        } else {
            c.notifier.showIncomingCall(call.id, title, caller, call.video)
        }
    }
}

class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            val c = applicationContext.container
            if (c.auth.awaitUserId() != null && c.settings.push.first()) runCatching { c.push.register(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val c = applicationContext.container
        val data = message.data
        // We have roughly ten seconds here; everything below normally takes a fraction of that.
        runBlocking {
            withTimeoutOrNull(9_000) {
                runCatching {
                    when (data["type"]) {
                        "message" -> c.inbox.onMessage(data)
                        "reaction" -> c.inbox.onReaction(data)
                        "share_keys" -> c.inbox.onShareKeys()
                        // A new version was just released: fetch and install it in the background.
                        "update" -> AutoUpdate.runNow(applicationContext)
                        "call" -> c.inbox.onCall(
                            CallRow(
                                id = data["call_id"] ?: return@runCatching,
                                groupId = data["group_id"] ?: return@runCatching,
                                startedBy = data["started_by"] ?: return@runCatching,
                                video = data["video"] == "true",
                                startedAt = data["started_at"] ?: java.time.Instant.now().toString(),
                            ),
                            callerName = data["sender_name"],
                            groupName = data["group_name"],
                            isDm = data["group_kind"]?.let { it == "dm" },
                        )
                        "call_end" -> data["call_id"]?.let { c.calls.remoteEnded(it) }
                    }
                }
            }
        }
    }
}

/** Reply / mark read / decline / hang up, straight from a notification. */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    REPLY -> reply(c, intent)
                    MARK_READ -> {
                        val groupId = intent.getStringExtra(EXTRA_GROUP) ?: return@launch
                        val lastId = intent.getLongExtra(EXTRA_LAST_ID, 0)
                        runCatching { c.chat.markSeen(groupId, lastId, if (c.settings.readReceipts.first()) lastId else null) }
                        c.notifier.clearConversation(groupId)
                    }
                    DECLINE_CALL -> intent.getStringExtra(EXTRA_CALL)?.let { c.calls.decline(it) }
                    HANG_UP -> c.calls.hangUp()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reply(c: AppContainer, intent: Intent) {
        val groupId = intent.getStringExtra(EXTRA_GROUP) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val me = c.auth.awaitUserId() ?: return
        val lastId = intent.getLongExtra(EXTRA_LAST_ID, 0)
        val draft = c.chat.draft(groupId, me, Payload("text", text = text))
        runCatching { c.chat.send(draft, me) }
        runCatching { c.chat.markSeen(groupId, lastId, if (c.settings.readReceipts.first()) lastId else null) }
        val conversation = c.chat.cachedConversations()?.firstOrNull { it.id == groupId }
        val myName = c.chat.cachedMembers(groupId)?.firstOrNull { it.userId == me }?.profiles?.displayName ?: "You"
        val title = conversation?.let { if (it.isDm) "Direct message" else it.name } ?: "gng"
        c.notifier.appendMyReply(groupId, title, conversation?.isDm == true, myName, text)
    }

    companion object {
        const val REPLY = "com.gng.app.REPLY"
        const val MARK_READ = "com.gng.app.MARK_READ"
        const val DECLINE_CALL = "com.gng.app.DECLINE_CALL"
        const val HANG_UP = "com.gng.app.HANG_UP"
        const val KEY_TEXT = "text"
        const val EXTRA_GROUP = "group_id"
        const val EXTRA_LAST_ID = "last_id"
        const val EXTRA_CALL = "call_id"
    }
}
