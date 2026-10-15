package com.gng.app.data

import com.gng.app.crypto.Aead
import com.gng.app.crypto.E2ee
import com.gng.app.crypto.b64
import com.gng.app.crypto.unb64
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

sealed interface LiveEvent {
    data class NewMessage(val row: MessageRow) : LiveEvent
    /** Edited or deleted. */
    data class MessageChanged(val row: MessageRow) : LiveEvent
    data class ReactionChanged(val row: ReactionRow) : LiveEvent
    data class Receipt(val state: ReadState) : LiveEvent
    data object KeysChanged : LiveEvent
}

/**
 * Messages, reactions and read receipts for gangs and DMs. Everything a person types is encrypted
 * on this phone before it's sent (see [E2ee]); decrypted copies are kept in [ChatCache].
 */
class ChatRepository(
    private val client: SupabaseClient,
    private val cache: ChatCache,
    private val e2ee: E2ee,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // --- loading -----------------------------------------------------------------

    suspend fun cached(groupId: String): List<ChatMessage> = cache.latest(groupId)

    /**
     * Pulls everything we haven't synced yet. Returns the new messages, oldest first.
     *
     * "Synced up to" is kept separately from the newest message in the cache: messages also land in
     * the cache live, or when you send one, and taking the newest of those as the sync point used to
     * skip whatever arrived in between, so those messages vanished from the chat.
     */
    suspend fun syncNewer(groupId: String, myId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val markKey = "synced:$groupId"
        val mark = cache.getRaw(markKey)?.toLongOrNull()
        val rows = if (mark == null) {
            // First sync (or first since this fix): take a generous recent window, which also fills any old gaps in it.
            fetch(groupId, desc = true, limit = REPAIR_WINDOW).reversed()
        } else {
            val newer = fetch(groupId, afterId = mark, limit = MAX_CATCH_UP)
            // Way behind: start the cache over rather than leave a hole in the history.
            if (newer.size >= MAX_CATCH_UP) {
                cache.clearGroup(groupId)
                fetch(groupId, desc = true, limit = PAGE).reversed()
            } else {
                newer
            }
        }
        val decrypted = rows.map { decrypt(it, myId) }
        cache.put(decrypted)
        cache.putRaw(markKey, maxOf(mark ?: 0, rows.maxOfOrNull { it.id } ?: 0).toString())
        decrypted
    }

    /** Messages edited or deleted since we last looked (realtime catches the rest while a chat is open). */
    suspend fun syncChanges(groupId: String, myId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val key = "changes:$groupId"
        val since = cache.getRaw(key)
        val now = Instant.now().minusSeconds(120).toString() // a little overlap; seeing a change twice is harmless
        if (since == null) {
            cache.putRaw(key, now)
            return@withContext emptyList()
        }
        val rows = client.from("messages").select {
            filter {
                eq("group_id", groupId)
                or {
                    gt("edited_at", since)
                    gt("deleted_at", since)
                }
            }
        }.decodeList<MessageRow>()
        cache.putRaw(key, now)
        rows.map { decrypt(it, myId) }.also { cache.put(it) }
    }

    /** Older history for infinite scroll: from the server (so gaps fill in), the cache when offline. */
    suspend fun older(groupId: String, beforeId: Long, myId: String): List<ChatMessage> = try {
        withContext(Dispatchers.IO) {
            fetch(groupId, beforeId = beforeId, desc = true, limit = PAGE).reversed().map { decrypt(it, myId) }
                .also { cache.put(it) }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        cache.before(groupId, beforeId).ifEmpty { throw e }
    }

    /** Keeps a message that arrived live, so it's there next time without a refetch. */
    suspend fun remember(m: ChatMessage) {
        if (m.id > 0) cache.put(listOf(m))
    }

    /**
     * One message by id, e.g. when a push notification arrives. Not cached: the cache must stay a
     * gap-free tail of the chat, since [syncNewer] only asks the server for what's after it.
     */
    suspend fun fetchOne(id: Long, myId: String): ChatMessage? = withContext(Dispatchers.IO) {
        cache.get(id)?.takeIf { it.payload.t != "locked" }?.let { return@withContext it }
        client.from("messages").select { filter { eq("id", id) } }.decodeSingleOrNull<MessageRow>()
            ?.let { decrypt(it, myId) }
    }

    /** Tries again on messages we couldn't open, after a new key epoch shows up. */
    suspend fun retryLocked(groupId: String, myId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val ids = cache.lockedIds(groupId)
        if (ids.isEmpty()) return@withContext emptyList()
        client.from("messages").select { filter { isIn("id", ids) } }.decodeList<MessageRow>()
            .map { decrypt(it, myId) }.filter { it.payload.t != "locked" }.also { cache.put(it) }
    }

    private suspend fun fetch(
        groupId: String,
        afterId: Long? = null,
        beforeId: Long? = null,
        desc: Boolean = false,
        limit: Long,
    ): List<MessageRow> = client.from("messages").select {
        filter {
            eq("group_id", groupId)
            afterId?.let { gt("id", it) }
            beforeId?.let { lt("id", it) }
        }
        order("id", if (desc) Order.DESCENDING else Order.ASCENDING)
        limit(limit)
    }.decodeList()

    suspend fun decrypt(row: MessageRow, myId: String): ChatMessage {
        val payload = when {
            row.deletedAt != null -> Payload("deleted")
            row.kind != "text" -> Payload("event", text = row.body)
            row.ciphertext == null || row.epoch == null -> Payload("legacy", text = row.body)
            else -> {
                val key = e2ee.keyFor(row.groupId, row.epoch, myId)
                if (key == null) {
                    Payload("locked")
                } else {
                    runCatching {
                        val aad = messageAad(row.groupId, row.epoch, row.userId.orEmpty(), row.clientId.orEmpty())
                        json.decodeFromString<Payload>(Aead.open(key, row.ciphertext.unb64(), aad).decodeToString())
                            .let { if (row.editedAt != null) it.copy(edited = true) else it }
                    }.getOrDefault(Payload("locked"))
                }
            }
        }
        return ChatMessage(
            id = row.id, clientId = row.clientId, groupId = row.groupId, userId = row.userId, kind = row.kind,
            createdAt = parseInstant(row.createdAt), payload = payload, refId = row.refId,
        )
    }

    // --- sending ------------------------------------------------------------------

    /** A local copy shown straight away as "Sending…", replaced once the server has it. */
    suspend fun draft(groupId: String, myId: String, payload: Payload): ChatMessage {
        val m = ChatMessage(
            id = -System.currentTimeMillis() * 1000 - (0..999).random(),
            clientId = UUID.randomUUID().toString(), groupId = groupId, userId = myId, kind = "text",
            createdAt = Instant.now(), payload = payload, state = SendState.Sending,
        )
        cache.put(listOf(m))
        return m
    }

    suspend fun send(draft: ChatMessage, myId: String): ChatMessage = withContext(Dispatchers.IO) {
        try {
            val key = e2ee.sendingKey(draft.groupId, myId)
            val clientId = draft.clientId!!
            val plaintext = json.encodeToString(Payload.serializer(), draft.payload).toByteArray()
            val ciphertext = Aead.seal(key.key, plaintext, messageAad(draft.groupId, key.epoch, myId, clientId)).b64()
            val row = client.from("messages")
                .insert(NewEncryptedMessage(draft.groupId, key.epoch, ciphertext, clientId, draft.payload.mentions?.takeIf { it.isNotEmpty() })) { select() }
                .decodeSingle<MessageRow>()
            draft.copy(id = row.id, createdAt = parseInstant(row.createdAt), state = SendState.Sent)
                .also { cache.put(listOf(it)) }
        } catch (e: Throwable) {
            cache.setState(draft.id, SendState.Failed)
            throw e
        }
    }

    suspend fun discard(draft: ChatMessage) = cache.remove(draft.id)

    /** Replaces the text of one of my messages (re-encrypted with the current key). */
    suspend fun edit(m: ChatMessage, text: String, mentions: List<String>?, myId: String): ChatMessage = withContext(Dispatchers.IO) {
        val key = e2ee.sendingKey(m.groupId, myId)
        val payload = m.payload.copy(text = text, mentions = mentions, edited = null)
        val plaintext = json.encodeToString(Payload.serializer(), payload).toByteArray()
        val ciphertext = Aead.seal(key.key, plaintext, messageAad(m.groupId, key.epoch, myId, m.clientId.orEmpty())).b64()
        client.postgrest.rpc("edit_message", buildJsonObject {
            put("p_id", m.id)
            put("p_epoch", key.epoch)
            put("p_ciphertext", ciphertext)
            if (mentions.isNullOrEmpty()) put("p_mentions", JsonNull)
            else put("p_mentions", kotlinx.serialization.json.JsonArray(mentions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
        m.copy(payload = payload.copy(edited = true)).also { cache.put(listOf(it)) }
    }

    /** Wipes one of my messages for everyone (the server keeps only "deleted"). */
    suspend fun deleteForEveryone(m: ChatMessage): ChatMessage = withContext(Dispatchers.IO) {
        client.postgrest.rpc("delete_message_for_everyone", buildJsonObject { put("p_id", m.id) })
        // The attachment can't be opened without the key that was in the message, but don't keep it around either.
        m.payload.media?.path?.let { path -> runCatching { client.storage.from("chat-media").delete(path) } }
        m.copy(payload = Payload("deleted")).also { cache.put(listOf(it)) }
    }

    /** Hides a message on this phone only. */
    suspend fun deleteForMe(m: ChatMessage) {
        val ids = hiddenIds(m.groupId) + m.id
        cache.putRaw("hidden:${m.groupId}", ids.joinToString(","))
        cache.remove(m.id)
    }

    suspend fun hiddenIds(groupId: String): Set<Long> =
        cache.getRaw("hidden:$groupId")?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()

    /** When each person got and saw [messageId]. */
    suspend fun receipts(groupId: String, messageId: Long): List<MessageReceipt> =
        client.postgrest.rpc("message_receipts", buildJsonObject {
            put("gid", groupId)
            put("mid", messageId)
        }).decodeList()

    // --- reactions (one tapback per person per message, encrypted) ---------------------

    suspend fun cachedReactions(groupId: String) = cache.reactions(groupId)

    suspend fun syncReactions(groupId: String, sinceMessageId: Long, myId: String): Map<Long, Map<String, String>> =
        withContext(Dispatchers.IO) {
            client.from("reactions").select {
                filter {
                    eq("group_id", groupId)
                    gte("message_id", sinceMessageId)
                }
            }.decodeList<ReactionRow>().forEach { applyReaction(it, myId) }
            cache.reactions(groupId)
        }

    /** Decrypts and caches a reaction row; returns the emoji (null = removed). */
    suspend fun applyReaction(row: ReactionRow, myId: String): String? {
        val userId = row.userId ?: return null
        val emoji = openReaction(row, myId)
        cache.putReaction(row.groupId, row.messageId, userId, emoji)
        return emoji
    }

    /** Just the emoji, or null if it was removed or can't be opened. */
    suspend fun openReaction(row: ReactionRow, myId: String): String? {
        val userId = row.userId ?: return null
        if (row.ciphertext == null || row.epoch == null) return null
        val key = e2ee.keyFor(row.groupId, row.epoch, myId) ?: return null
        return runCatching {
            Aead.open(key, row.ciphertext.unb64(), reactionAad(row.groupId, row.messageId, userId)).decodeToString()
        }.getOrNull()
    }

    /** A message we already have locally (e.g. to quote it in a reaction notification). */
    suspend fun cachedMessage(id: Long): ChatMessage? = cache.get(id)

    suspend fun react(groupId: String, messageId: Long, myId: String, emoji: String?) = withContext(Dispatchers.IO) {
        val row = if (emoji == null) {
            buildJsonObject {
                put("message_id", messageId)
                put("group_id", groupId)
                put("epoch", JsonNull)
                put("ciphertext", JsonNull)
                put("updated_at", Instant.now().toString())
            }
        } else {
            val key = e2ee.sendingKey(groupId, myId)
            buildJsonObject {
                put("message_id", messageId)
                put("group_id", groupId)
                put("epoch", key.epoch)
                put("ciphertext", Aead.seal(key.key, emoji.toByteArray(), reactionAad(groupId, messageId, myId)).b64())
                put("updated_at", Instant.now().toString())
            }
        }
        client.from("reactions").upsert(row) { onConflict = "message_id,user_id" }
        cache.putReaction(groupId, messageId, myId, emoji)
    }

    // --- read receipts ---------------------------------------------------------------

    suspend fun readStates(groupId: String): List<ReadState> =
        client.from("read_states").select { filter { eq("group_id", groupId) } }.decodeList()

    /** Reports what this phone has received (and, if [read] is set, seen). */
    suspend fun markSeen(groupId: String, delivered: Long, read: Long?) {
        if (delivered <= 0) return
        client.postgrest.rpc("mark_seen", buildJsonObject {
            put("gid", groupId)
            put("p_delivered", delivered)
            if (read == null) put("p_read", JsonNull) else put("p_read", read)
        })
    }

    // --- realtime ------------------------------------------------------------------

    /** Everything that changes while a chat is open, on one realtime channel. */
    fun live(groupId: String): Flow<LiveEvent> = flow {
        val channel = client.channel("chat-$groupId-${UUID.randomUUID()}")
        val messages = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        val edits = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "messages"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        val reactions = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "reactions"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        val receipts = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "read_states"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        // New epochs, and old ones re-wrapped for us (we joined late or are on a new phone).
        val keys = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "conversation_keys"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        channel.subscribe()
        try {
            emitAll(
                merge(
                    messages.map { LiveEvent.NewMessage(it.decodeRecord<MessageRow>()) },
                    edits.map { LiveEvent.MessageChanged(it.decodeRecord<MessageRow>()) },
                    reactions.mapNotNull { (it as? HasRecord)?.decodeRecord<ReactionRow>()?.let(LiveEvent::ReactionChanged) },
                    receipts.mapNotNull { (it as? HasRecord)?.decodeRecord<ReadState>()?.let(LiveEvent::Receipt) },
                    keys.map { LiveEvent.KeysChanged },
                ),
            )
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    // --- conversations ---------------------------------------------------------------

    suspend fun cachedConversations(): List<Conversation>? =
        cache.getRaw(KEY_CONVERSATIONS)?.let { runCatching { json.decodeFromString<List<Conversation>>(it) }.getOrNull() }

    suspend fun conversations(): List<Conversation> {
        val list = client.postgrest.rpc("my_conversations").decodeList<Conversation>()
        cache.putRaw(KEY_CONVERSATIONS, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Conversation.serializer()), list))
        return list
    }

    /** Fires whenever a message lands in any conversation I'm in (RLS filters the rest). */
    fun anyMessage(): Flow<MessageRow> = flow {
        val channel = client.channel("inbox-${UUID.randomUUID()}")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "messages" }
        channel.subscribe()
        try {
            emitAll(inserts.map { it.decodeRecord<MessageRow>() })
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    /** Calls starting or ending in any of my conversations. */
    fun callChanges(): Flow<CallRow> = flow {
        val channel = client.channel("calls-${UUID.randomUUID()}")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "calls" }
        channel.subscribe()
        try {
            emitAll(changes.mapNotNull { (it as? HasRecord)?.decodeRecord<CallRow>() })
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    suspend fun call(id: String): CallRow? =
        client.from("calls").select { filter { eq("id", id) } }.decodeSingleOrNull()

    /** Opens a DM with anyone on gng: straight away with gang-mates, as a message request otherwise. */
    suspend fun openDm(otherUserId: String): Group =
        client.postgrest.rpc("request_direct_chat", buildJsonObject { put("p_other", otherUserId) }).decodeAs()

    /** Accept (it becomes a normal DM) or decline (it's deleted) a message request someone sent me. */
    suspend fun respondToRequest(groupId: String, accept: Boolean) {
        client.postgrest.rpc("respond_dm_request", buildJsonObject {
            put("gid", groupId)
            put("p_accept", accept)
        })
    }

    /** Anyone on gng, by @username or name. */
    suspend fun searchPeople(query: String): List<PersonCard> =
        client.postgrest.rpc("search_people", buildJsonObject { put("q", query) }).decodeList()

    /** Everyone I share a gang with (RLS only returns those), for starting a DM. */
    suspend fun people(myId: String): List<Profile> =
        client.from("profiles").select { filter { neq("id", myId) } }.decodeList<Profile>()
            .sortedBy { it.displayName.lowercase() }

    suspend fun mute(groupId: String, myId: String, until: Instant?) {
        client.from("group_members").update(buildJsonObject {
            if (until == null) put("muted_until", JsonNull) else put("muted_until", until.toString())
        }) {
            filter {
                eq("group_id", groupId)
                eq("user_id", myId)
            }
        }
    }

    suspend fun cachedMembers(groupId: String): List<Member>? =
        cache.getRaw("members:$groupId")?.let { runCatching { json.decodeFromString<List<Member>>(it) }.getOrNull() }

    suspend fun cacheMembers(groupId: String, members: List<Member>) =
        cache.putRaw("members:$groupId", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Member.serializer()), members))

    private fun messageAad(groupId: String, epoch: Int, senderId: String, clientId: String) =
        "gng-msg-v1|$groupId|$epoch|$senderId|$clientId"

    private fun reactionAad(groupId: String, messageId: Long, userId: String) = "gng-react-v1|$groupId|$messageId|$userId"

    private companion object {
        const val PAGE = 60L
        const val REPAIR_WINDOW = 200L
        const val MAX_CATCH_UP = 500L
        const val KEY_CONVERSATIONS = "conversations"
    }
}
