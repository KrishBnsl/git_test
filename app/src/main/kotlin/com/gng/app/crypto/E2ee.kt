package com.gng.app.crypto

import android.content.Context
import com.gng.app.data.ChatCache
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UserKey(
    @SerialName("user_id") val userId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("key_id") val keyId: String,
)

@Serializable
private data class NewUserKey(
    @SerialName("public_key") val publicKey: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class EpochRow(
    val epoch: Int,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("wrapped_key") val wrappedKey: String? = null,
)

@Serializable
private data class MemberId(@SerialName("user_id") val userId: String)

/** An epoch some current member can't open yet, and their key to wrap it for. */
@Serializable
private data class NeededShare(
    @SerialName("group_id") val groupId: String,
    val epoch: Int,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("recipient_key_id") val recipientKeyId: String,
    @SerialName("public_key") val publicKey: String,
)

/** A conversation key that's ready to encrypt with. */
class EpochKey(val epoch: Int, val key: ByteArray)

/**
 * End-to-end encryption for conversations.
 *
 * Each conversation has numbered key epochs. An epoch is a random AES-256 key wrapped with HPKE
 * for every member's device key. Before sending, a client checks that the latest epoch was wrapped
 * for exactly the current members' current keys; if not (someone joined, left, or reinstalled) it
 * publishes a new epoch, so people who leave can't read what comes after.
 *
 * History is shared the other way: when someone joins, or reinstalls (a new device key), any
 * member's phone that can open the older epochs wraps them for that person too ([shareHistory]),
 * so they see the chat's earlier messages instead of "can't decrypt".
 *
 * Opened epoch keys are kept in [ChatCache] (app-private, like the decrypted messages), so a
 * notification can be decrypted without a network round trip.
 */
class E2ee(context: Context, private val client: SupabaseClient, private val cache: ChatCache) {
    private val identity by lazy { IdentityKeys(context) }
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val sendKeys = ConcurrentHashMap<String, Pair<Long, EpochKey>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val sharing = Mutex()

    val myPublicKey: String get() = identity.publicKey
    val myKeyId: String get() = identity.keyId

    /** Uploads this device's public key if the server has a different one (first run, new phone). */
    suspend fun publishIdentity(userId: String) = withContext(Dispatchers.IO) {
        val current = client.from("user_keys").select { filter { eq("user_id", userId) } }.decodeSingleOrNull<UserKey>()
        if (current?.keyId != identity.keyId) {
            client.from("user_keys").upsert(
                NewUserKey(identity.publicKey, identity.keyId, java.time.Instant.now().toString()),
            ) { onConflict = "user_id" }
        }
    }

    suspend fun publicKeyOf(userId: String): UserKey? =
        client.from("user_keys").select { filter { eq("user_id", userId) } }.decodeSingleOrNull()

    /** The key for [epoch] in [groupId], or null if it wasn't wrapped for this device. */
    suspend fun keyFor(groupId: String, epoch: Int, myId: String): ByteArray? {
        keys["$groupId:$epoch"]?.let { return it }
        cache.getRaw(diskKey(groupId, epoch))?.let { saved ->
            return runCatching { saved.unb64() }.getOrNull()?.also { keys["$groupId:$epoch"] = it }
        }
        return withContext(Dispatchers.IO) {
            val row = client.from("conversation_keys").select {
                filter {
                    eq("group_id", groupId)
                    eq("epoch", epoch)
                    eq("recipient_id", myId)
                }
            }.decodeSingleOrNull<EpochRow>() ?: return@withContext null
            if (row.recipientKeyId != identity.keyId || row.wrappedKey == null) return@withContext null
            runCatching { identity.unwrap(row.wrappedKey, wrapContext(groupId, epoch, myId)) }.getOrNull()
                ?.also { remember(groupId, epoch, it) }
        }
    }

    private suspend fun remember(groupId: String, epoch: Int, key: ByteArray) {
        keys["$groupId:$epoch"] = key
        cache.putRaw(diskKey(groupId, epoch), key.b64())
    }

    private fun diskKey(groupId: String, epoch: Int) = "epoch:$groupId:$epoch"

    /**
     * Wraps every epoch this phone can open for the members who can't (new to the chat, or on a
     * new phone). Returns the conversations where something was shared. Cheap when there's nothing to do.
     */
    suspend fun shareHistory(myId: String): Set<String> = sharing.withLock {
        withContext(Dispatchers.IO) {
            val needed = client.postgrest.rpc("key_shares_needed").decodeList<NeededShare>()
            val shared = mutableSetOf<String>()
            needed.groupBy { it.groupId }.forEach { (groupId, rows) ->
                val wraps = buildJsonArray {
                    rows.forEach { r ->
                        val secret = keyFor(groupId, r.epoch, myId) ?: return@forEach
                        add(buildJsonObject {
                            put("epoch", r.epoch)
                            put("recipient_id", r.recipientId)
                            put("recipient_key_id", r.recipientKeyId)
                            put("wrapped_key", IdentityKeys.wrap(r.publicKey, secret, wrapContext(groupId, r.epoch, r.recipientId)))
                        })
                    }
                }
                if (wraps.isEmpty()) return@forEach
                runCatching {
                    client.postgrest.rpc("share_epoch_keys", buildJsonObject {
                        put("gid", groupId)
                        put("p_wraps", wraps)
                    })
                }.onSuccess { shared += groupId }
            }
            shared
        }
    }

    /** The epoch to encrypt new messages with, starting a new one if membership or keys changed. */
    suspend fun sendingKey(groupId: String, myId: String): EpochKey =
        locks.getOrPut(groupId) { Mutex() }.withLock {
            sendKeys[groupId]?.let { (checkedAt, key) ->
                if (System.currentTimeMillis() - checkedAt < RECHECK_MS) return@withLock key
            }
            withContext(Dispatchers.IO) { resolveSendingKey(groupId, myId) }
                .also { sendKeys[groupId] = System.currentTimeMillis() to it }
        }

    /** Forget the cached send key so the next send re-checks membership (call after joins/leaves). */
    fun invalidate(groupId: String) {
        sendKeys.remove(groupId)
    }

    private suspend fun resolveSendingKey(groupId: String, myId: String): EpochKey {
        repeat(4) {
            val memberIds = client.from("group_members")
                .select(Columns.list("user_id")) { filter { eq("group_id", groupId) } }
                .decodeList<MemberId>().map { it.userId }
            val memberKeys = client.from("user_keys").select { filter { isIn("user_id", memberIds) } }
                .decodeList<UserKey>().associateBy { it.userId }
            if (memberKeys[myId]?.keyId != identity.keyId) {
                // Our key isn't published yet (first launch raced us); fix that and look again.
                publishIdentity(myId)
                return@repeat
            }
            val latest = client.from("conversation_keys")
                .select(Columns.list("epoch")) {
                    filter { eq("group_id", groupId) }
                    order("epoch", Order.DESCENDING)
                    limit(1)
                }.decodeList<EpochOnly>().firstOrNull()?.epoch ?: 0

            if (latest > 0) {
                val recipients = client.from("conversation_keys")
                    .select(Columns.list("epoch", "recipient_id", "recipient_key_id")) {
                        filter {
                            eq("group_id", groupId)
                            eq("epoch", latest)
                        }
                    }.decodeList<EpochRow>().associate { it.recipientId to it.recipientKeyId }
                val wanted = memberKeys.mapValues { it.value.keyId }
                if (recipients == wanted && wanted[myId] == identity.keyId) {
                    keyFor(groupId, latest, myId)?.let { return EpochKey(latest, it) }
                }
            }

            // Start a new epoch for exactly the current members' current keys.
            val next = latest + 1
            val secret = randomBytes(32)
            val wraps = buildJsonArray {
                memberKeys.values.forEach { k ->
                    add(buildJsonObject {
                        put("recipient_id", k.userId)
                        put("recipient_key_id", k.keyId)
                        put("wrapped_key", IdentityKeys.wrap(k.publicKey, secret, wrapContext(groupId, next, k.userId)))
                    })
                }
            }
            val published = client.postgrest.rpc("publish_epoch", buildJsonObject {
                put("gid", groupId)
                put("p_epoch", next)
                put("p_wraps", wraps)
            }).decodeAs<Boolean>()
            if (published) {
                remember(groupId, next, secret)
                return EpochKey(next, secret)
            }
            // Someone else published this epoch a moment ago; loop and use theirs if it fits.
        }
        error("Couldn't agree on an encryption key, try again")
    }

    private fun wrapContext(groupId: String, epoch: Int, recipientId: String) = "gng-epoch-v1|$groupId|$epoch|$recipientId"

    @Serializable
    private data class EpochOnly(val epoch: Int)

    private companion object {
        const val RECHECK_MS = 20_000L
    }
}

