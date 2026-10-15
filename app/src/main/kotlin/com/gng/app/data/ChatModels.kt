package com.gng.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// --- rows as they live in Postgres -----------------------------------------

@Serializable
data class MessageRow(
    val id: Long,
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String? = null,
    val body: String? = null,
    val kind: String = "text",
    @SerialName("ref_id") val refId: String? = null,
    val epoch: Int? = null,
    val ciphertext: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("created_at") val createdAt: String,
    /** Who was @mentioned (user ids). Plain, so the server can notify them; the text itself is encrypted. */
    val mentions: List<String>? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** When each person got and saw one message (null: not yet). */
@Serializable
data class MessageReceipt(
    @SerialName("user_id") val userId: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class NewEncryptedMessage(
    @SerialName("group_id") val groupId: String,
    val epoch: Int,
    val ciphertext: String,
    @SerialName("client_id") val clientId: String,
    val mentions: List<String>? = null,
)

@Serializable
data class ReactionRow(
    @SerialName("message_id") val messageId: Long,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("group_id") val groupId: String,
    val epoch: Int? = null,
    val ciphertext: String? = null,
)

@Serializable
data class ReadState(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("delivered_id") val deliveredId: Long = 0,
    @SerialName("read_id") val readId: Long = 0,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val name: String,
    /** Icon key for gangs (the column is called `emoji` for history). */
    val emoji: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    val kind: String = "gang",
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("other_user_id") val otherUserId: String? = null,
    @SerialName("muted_until") val mutedUntil: String? = null,
    @SerialName("last_message_id") val lastMessageId: Long? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_message_kind") val lastMessageKind: String? = null,
    @SerialName("last_message_user_id") val lastMessageUserId: String? = null,
    @SerialName("last_message_body") val lastMessageBody: String? = null,
    @SerialName("unread_count") val unreadCount: Long = 0,
    /** Unread messages that @mention you. */
    @SerialName("unread_mentions") val unreadMentions: Long = 0,
    @SerialName("dm_status") val dmStatus: String = "accepted",
    @SerialName("requested_by") val requestedBy: String? = null,
) {
    fun isRequestFor(myId: String) = isDm && dmStatus == "pending" && requestedBy != myId
    val isDm get() = kind == "dm"
    val isMuted get() = mutedUntil?.let { parseInstant(it).isAfter(Instant.now()) } ?: false
    fun toGroup() = Group(
        id = id, name = name, icon = emoji, inviteCode = inviteCode, createdBy = createdBy, photoUrl = photoUrl, kind = kind,
        dmStatus = dmStatus, requestedBy = requestedBy,
    )
}

@Serializable
data class CallRow(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("started_by") val startedBy: String,
    val video: Boolean = false,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
)

// --- what's inside the encryption -------------------------------------------

/** An encrypted attachment: the blob in the `chat-media` bucket plus the key to open it. */
@Serializable
data class MediaRef(
    val path: String,
    val key: String,
    val sha256: String,
    val mime: String,
    val name: String? = null,
    val size: Long = 0,
    val w: Int? = null,
    val h: Int? = null,
    /** A ~24px JPEG, base64, shown blurred while the real image downloads. */
    val thumb: String? = null,
)

@Serializable
data class Payload(
    /** text | image | file | sticker | reaction, or locally: event | locked | legacy */
    val t: String,
    val text: String? = null,
    @SerialName("reply_to") val replyTo: Long? = null,
    @SerialName("reply_user") val replyUser: String? = null,
    @SerialName("reply_text") val replyText: String? = null,
    val media: MediaRef? = null,
    /** User ids @mentioned in [text]; each appears in the text as "@" + their display name. */
    val mentions: List<String>? = null,
    /** Set on this phone (never sent): the sender changed it after sending. */
    val edited: Boolean? = null,
    /** The message tags everyone (@all). */
    val all: Boolean? = null,
) {
    val isDeleted get() = t == "deleted"
    fun mentions(userId: String) = mentions?.contains(userId) == true

    /** One line for previews, notifications and reply quotes. */
    fun preview(): String = when (t) {
        "image" -> "📷 Photo" + (text?.let { " · $it" } ?: "")
        "file" -> "📎 ${media?.name ?: "File"}"
        "sticker" -> "Sticker"
        "locked" -> "🔒 Message (waiting to unlock)"
        "deleted" -> "This message was deleted"
        else -> text.orEmpty()
    }
}

// --- what the UI shows ---------------------------------------------------------

enum class SendState { Sending, Sent, Failed }

data class ChatMessage(
    /** Server id, or a negative local id while it's still sending. */
    val id: Long,
    val clientId: String?,
    val groupId: String,
    val userId: String?,
    val kind: String,
    val createdAt: Instant,
    val payload: Payload,
    val refId: String? = null,
    val state: SendState = SendState.Sent,
) {
    val isUserMessage get() = kind == "text"
    val isPending get() = id < 0
}
