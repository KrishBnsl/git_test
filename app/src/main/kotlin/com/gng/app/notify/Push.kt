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
import com.gng.app.data.Payload
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
 * Turns "something happened" into a notification. Pushes only carry ids (the server can't read
 * messages), so the phone fetches the message, decrypts it, and writes the notification itself.
 */
class Inbox(private val c: AppContainer) {
    /** Conversation currently on screen, so we don't notify about what you're already looking at. */
    @Volatile var openConversation: String? = null

    suspend fun onMessage(groupId: String, messageId: Long) {
        val me = c.auth.awaitUserId() ?: return
        val m = c.chat.fetchOne(messageId, me) ?: return
        // We've got it, so the sender can see "Delivered".
        runCatching { c.chat.markSeen(groupId, messageId, null) }
        if (m.userId == me) return
        if (openConversation == groupId && c.foreground) return
        if (!c.settings.push.first()) return

        val conversation = (c.chat.cachedConversations() ?: runCatching { c.chat.conversations() }.getOrNull())
            ?.firstOrNull { it.id == groupId }
        if (conversation?.isMuted == true) return
        val members = c.chat.cachedMembers(groupId).orEmpty().associate { it.userId to it.profiles }
        val sender = m.userId?.let { uid -> members[uid] ?: runCatching { c.profiles.get(uid) }.getOrNull() }
        val myName = members[me]?.displayName ?: runCatching { c.profiles.get(me)?.displayName }.getOrNull() ?: "You"
        val isDm = conversation?.isDm == true
        val title = when {
            conversation == null -> "gng"
            isDm -> sender?.displayName ?: "DM"
            else -> conversation.name
        }
        val text = if (c.settings.previews.first()) {
            if (m.payload.t == "event") m.payload.text.orEmpty() else m.payload.preview()
        } else {
            "New message"
        }
        val senderName = when {
            m.userId == null -> "gng"
            c.settings.previews.first() -> sender?.displayName ?: "Someone"
            else -> "Someone"
        }
        c.notifier.showMessage(groupId, title, isDm, senderName, text, messageId, myName)
    }

    suspend fun onCall(call: CallRow) {
        val me = c.auth.awaitUserId() ?: return
        if (call.startedBy == me || call.endedAt != null) return
        val conversation = (c.chat.cachedConversations() ?: runCatching { c.chat.conversations() }.getOrNull())
            ?.firstOrNull { it.id == call.groupId }
        val caller = runCatching { c.profiles.get(call.startedBy) }.getOrNull()
        val callerName = caller?.displayName ?: "Someone"
        val title = if (conversation == null || conversation.isDm) callerName else conversation.name
        if (!c.calls.ring(call, title, callerName, isDm = conversation?.isDm != false)) return
        if (c.foreground) {
            // App is open: go straight to the ringing screen.
            c.appContext.startActivity(CallActivity.intent(c.appContext, call.id, CallActivity.ACTION_SHOW))
        } else {
            c.notifier.showIncomingCall(call.id, title, callerName, call.video)
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
        // We have roughly ten seconds here; fetching and decrypting one message takes well under that.
        runBlocking {
            withTimeoutOrNull(9_000) {
                runCatching {
                    when (data["type"]) {
                        "message" -> c.inbox.onMessage(data["group_id"] ?: return@runCatching, data["message_id"]?.toLongOrNull() ?: return@runCatching)
                        "call" -> c.inbox.onCall(
                            CallRow(
                                id = data["call_id"] ?: return@runCatching,
                                groupId = data["group_id"] ?: return@runCatching,
                                startedBy = data["started_by"] ?: return@runCatching,
                                video = data["video"] == "true",
                                startedAt = data["started_at"] ?: java.time.Instant.now().toString(),
                            ),
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
        val myName = runCatching { c.profiles.get(me)?.displayName }.getOrNull() ?: "You"
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
