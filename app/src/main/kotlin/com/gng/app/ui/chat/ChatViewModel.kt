package com.gng.app.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.ChatMessage
import com.gng.app.data.LiveEvent
import com.gng.app.data.MediaRef
import com.gng.app.data.Payload
import com.gng.app.data.ReadState
import com.gng.app.data.SendState
import com.gng.app.data.parseInstant
import com.gng.app.ui.friendly
import com.gng.app.ui.group.GroupContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What the little line under your latest message says, iMessage style. */
data class Receipt(val label: String, val failed: Boolean = false)

class ChatViewModel(private val c: AppContainer, private val ctx: GroupContext) : ViewModel() {
    private val groupId = ctx.group.id
    private val myId = ctx.me.id

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    /** messageId -> (userId -> emoji) */
    var reactions by mutableStateOf<Map<Long, Map<String, String>>>(emptyMap())
        private set
    var receipts by mutableStateOf<Map<String, ReadState>>(emptyMap())
        private set
    var replyTo by mutableStateOf<ChatMessage?>(null)
    var error by mutableStateOf<String?>(null)
    fun dismissError() { error = null }
    var loaded by mutableStateOf(false)
        private set
    var reachedStart by mutableStateOf(false)
        private set
    var uploads by mutableIntStateOf(0)
        private set
    private var loadingOlder = false
    private var visible = false

    init {
        viewModelScope.launch {
            // 1. Whatever we have on the phone, immediately.
            messages = sorted(c.chat.cached(groupId))
            reactions = c.chat.cachedReactions(groupId)
            if (messages.isNotEmpty()) loaded = true
            // 2. Catch up with the server.
            runCatching {
                merge(c.chat.syncNewer(groupId, myId))
                receipts = c.chat.readStates(groupId).associateBy { it.userId }
                val oldest = messages.firstOrNull { it.id > 0 }?.id ?: 0
                reactions = c.chat.syncReactions(groupId, oldest, myId)
                merge(c.chat.retryLocked(groupId, myId))
            }.onFailure { if (messages.isEmpty()) error = it.friendly() }
            loaded = true
            markSeen()
        }
        viewModelScope.launch { listen() }
        // Anything sent between the first sync and the realtime channel coming up.
        viewModelScope.launch {
            delay(2_500)
            runCatching { merge(c.chat.syncNewer(groupId, myId)) }
            markSeen()
        }
    }

    private var keyRetry: Job? = null

    /** Realtime, reconnecting if the socket drops. */
    private suspend fun listen() {
        while (true) {
            runCatching {
                c.chat.live(groupId).collect { event ->
                    when (event) {
                        is LiveEvent.NewMessage -> {
                            merge(listOf(c.chat.decrypt(event.row, myId)))
                            markSeen()
                        }
                        is LiveEvent.ReactionChanged -> {
                            val row = event.row
                            val emoji = c.chat.applyReaction(row, myId)
                            val who = row.userId ?: return@collect
                            val forMessage = reactions[row.messageId].orEmpty().toMutableMap()
                            if (emoji == null) forMessage.remove(who) else forMessage[who] = emoji
                            reactions = reactions + (row.messageId to forMessage)
                        }
                        is LiveEvent.Receipt -> receipts = receipts + (event.state.userId to event.state)
                        LiveEvent.KeysChanged -> {
                            // One event per member of the new epoch; react once.
                            c.e2ee.invalidate(groupId)
                            keyRetry?.cancel()
                            keyRetry = viewModelScope.launch {
                                delay(600)
                                runCatching { merge(c.chat.retryLocked(groupId, myId)) }
                            }
                        }
                    }
                }
            }
            delay(3_000)
            // Fill anything we missed while disconnected.
            runCatching { merge(c.chat.syncNewer(groupId, myId)) }
        }
    }

    fun onVisible(isVisible: Boolean) {
        visible = isVisible
        c.inbox.openConversation = if (isVisible) groupId else null
        if (isVisible) {
            c.notifier.clearConversation(groupId)
            markSeen()
        }
    }

    private fun markSeen() {
        val latest = messages.lastOrNull { it.id > 0 }?.id ?: return
        viewModelScope.launch {
            val read = if (visible && c.settings.readReceipts.first()) latest else null
            runCatching { c.chat.markSeen(groupId, latest, read) }
        }
    }

    // --- history -------------------------------------------------------------------

    fun loadOlder() {
        if (loadingOlder || reachedStart || !loaded) return
        val first = messages.firstOrNull { it.id > 0 }?.id ?: return
        loadingOlder = true
        viewModelScope.launch {
            runCatching {
                val older = c.chat.older(groupId, first, myId)
                if (older.isEmpty()) reachedStart = true
                merge(older)
                older.firstOrNull()?.let { reactions = c.chat.syncReactions(groupId, it.id, myId) }
            }
            loadingOlder = false
        }
    }

    private fun merge(incoming: List<ChatMessage>) {
        if (incoming.isEmpty()) return
        val byId = incoming.associateBy { it.id }
        val clientIds = incoming.mapNotNull { it.clientId }.toSet()
        messages = sorted(
            messages.filterNot { it.id in byId || (it.isPending && it.clientId in clientIds && incoming.any { n -> n.clientId == it.clientId && !n.isPending }) } +
                incoming.filterNot { n -> n.isPending && messages.any { it.clientId == n.clientId && !it.isPending } },
        )
    }

    private fun sorted(list: List<ChatMessage>) =
        list.distinctBy { it.id }.sortedWith(compareBy<ChatMessage>({ if (it.isPending) 1 else 0 }, { if (it.isPending) it.createdAt.toEpochMilli() else it.id }))

    // --- sending ----------------------------------------------------------------------

    /** [tagged]: people picked from the @ list; only those still named in the text are kept. */
    fun sendText(text: String, tagged: Collection<String> = emptyList()) {
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return
        val mentions = tagged.distinct().filter { id -> id != myId && ctx.members[id]?.let { "@${it.displayName}" in body } == true }
        send(withReply(Payload("text", text = body, mentions = mentions.takeIf { it.isNotEmpty() })))
    }

    fun sendImages(context: Context, uris: List<Uri>) = uris.take(10).forEach { uri ->
        upload {
            val picked = c.media.read(uri)
            val image = c.media.prepareImage(picked.bytes)
            val ref = c.media.upload(groupId, image.bytes, "image/jpeg", picked.name, image.width, image.height, image.thumb)
            withReply(Payload("image", media = ref))
        }
    }

    fun sendFile(uri: Uri) = upload {
        val picked = c.media.read(uri)
        if (picked.mime.startsWith("image/") && !picked.mime.contains("gif")) {
            val image = c.media.prepareImage(picked.bytes)
            withReply(Payload("image", media = c.media.upload(groupId, image.bytes, "image/jpeg", picked.name, image.width, image.height, image.thumb)))
        } else {
            withReply(Payload("file", media = c.media.upload(groupId, picked.bytes, picked.mime, picked.name)))
        }
    }

    fun sendSticker(file: File) = upload {
        Payload("sticker", media = c.media.uploadSticker(groupId, file.readBytes()))
    }

    private fun upload(build: suspend () -> Payload) {
        uploads++
        viewModelScope.launch {
            runCatching { build() }
                .onSuccess { send(it) }
                .onFailure { error = it.friendly() }
            uploads--
        }
    }

    private fun withReply(p: Payload): Payload {
        val target = replyTo ?: return p
        replyTo = null
        return p.copy(replyTo = target.id, replyUser = target.userId, replyText = target.payload.preview().take(140))
    }

    private fun send(payload: Payload) = viewModelScope.launch {
        val draft = c.chat.draft(groupId, myId, payload)
        merge(listOf(draft))
        deliver(draft)
    }

    private suspend fun deliver(draft: ChatMessage) {
        runCatching { c.chat.send(draft, myId) }
            .onSuccess { merge(listOf(it)) }
            .onFailure {
                messages = messages.map { m -> if (m.id == draft.id) m.copy(state = SendState.Failed) else m }
                error = it.friendly()
            }
    }

    fun retry(m: ChatMessage) = viewModelScope.launch {
        val again = m.copy(state = SendState.Sending)
        messages = messages.map { if (it.id == m.id) again else it }
        deliver(again)
    }

    fun discard(m: ChatMessage) = viewModelScope.launch {
        c.chat.discard(m)
        messages = messages.filterNot { it.id == m.id }
    }

    // --- reactions -------------------------------------------------------------------------

    fun react(m: ChatMessage, emoji: String) {
        if (m.isPending || !m.isUserMessage) return
        val current = reactions[m.id]?.get(myId)
        val next = if (current == emoji) null else emoji
        val forMessage = reactions[m.id].orEmpty().toMutableMap()
        if (next == null) forMessage.remove(myId) else forMessage[myId] = next
        reactions = reactions + (m.id to forMessage)
        viewModelScope.launch {
            runCatching { c.chat.react(groupId, m.id, myId, next) }.onFailure {
                error = it.friendly()
                val rollback = reactions[m.id].orEmpty().toMutableMap()
                if (current == null) rollback.remove(myId) else rollback[myId] = current
                reactions = reactions + (m.id to rollback)
            }
        }
    }

    // --- receipts ---------------------------------------------------------------------------

    private val others get() = ctx.members.keys.filter { it != myId }

    /** Status for one of my messages: Sending… / Sent / Delivered / Read 10:42 / Read by Aman, Riya. */
    fun receiptFor(m: ChatMessage): Receipt = when {
        m.state == SendState.Sending -> Receipt("Sending…")
        m.state == SendState.Failed -> Receipt("Not delivered · tap to retry", failed = true)
        else -> {
            val states = others.mapNotNull { receipts[it] }
            val readers = states.filter { it.readId >= m.id }
            val delivered = states.any { it.deliveredId >= m.id }
            when {
                ctx.isDm && readers.isNotEmpty() -> Receipt("Read " + (readers.first().readAt?.let(::shortTime) ?: ""))
                readers.isNotEmpty() && readers.size >= others.size -> Receipt("Read by everyone")
                readers.isNotEmpty() -> {
                    val names = readers.map { ctx.nameOf(it.userId) }
                    Receipt("Read by " + names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else "")
                }
                delivered -> Receipt("Delivered")
                else -> Receipt("Sent")
            }
        }
    }

    /** Per-person status for the Info sheet. */
    fun detailsFor(m: ChatMessage): List<Pair<String, String>> = others.map { uid ->
        val s = receipts[uid]
        uid to when {
            s == null -> "Not delivered yet"
            s.readId >= m.id -> "Read" + (if (s.readId == latestMineId()) s.readAt?.let { " " + shortTime(it) } ?: "" else "")
            s.deliveredId >= m.id -> "Delivered"
            else -> "Not delivered yet"
        }
    }

    private fun latestMineId() = messages.lastOrNull { it.userId == myId && it.id > 0 }?.id

    // --- calls & media helpers ------------------------------------------------------------

    fun joinCall(callId: String, onCallScreen: () -> Unit) = viewModelScope.launch {
        val row = runCatching { c.chat.call(callId) }.getOrNull()
        when {
            row == null -> error = "Couldn't find that call"
            row.endedAt != null -> error = "That call's already over"
            else -> {
                onCallScreen()
                runCatching { c.calls.joinExisting(row, ctx.title) }.onFailure { error = it.friendly() }
            }
        }
    }

    suspend fun mediaFile(ref: MediaRef): File = c.media.file(ref)

    fun cachedMedia(ref: MediaRef): File? = c.media.cachedFile(ref)

    fun saveMedia(ref: MediaRef) = viewModelScope.launch {
        runCatching { c.media.saveToGallery(ref) }.onSuccess { error = null }.onFailure { error = it.friendly() }
    }

    fun openFile(context: Context, ref: MediaRef) = viewModelScope.launch {
        runCatching { c.media.openIntent(ref) }
            .onSuccess { context.startActivity(Intent.createChooser(it, ref.name ?: "Open with")) }
            .onFailure { error = it.friendly() }
    }

    fun saveSticker(ref: MediaRef) = viewModelScope.launch {
        runCatching { c.stickers.saveReceived(c.media.file(ref)) }
            .onSuccess { if (!it) error = "Already in your stickers" }
            .onFailure { error = it.friendly() }
    }

    override fun onCleared() {
        if (c.inbox.openConversation == groupId) c.inbox.openConversation = null
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("h:mm a")
        private val DAY = DateTimeFormatter.ofPattern("EEE")

        fun shortTime(iso: String): String {
            val t = parseInstant(iso).atZone(ZoneId.systemDefault())
            val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
            return when {
                t.toLocalDate() == today -> t.format(TIME)
                ChronoUnit.DAYS.between(t.toLocalDate(), today) < 7 -> t.format(DAY)
                else -> t.toLocalDate().toString()
            }
        }
    }
}
