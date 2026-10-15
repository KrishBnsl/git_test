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
    /** The message being edited: its text is in the composer and Send saves the change. */
    var editing by mutableStateOf<ChatMessage?>(null)
        private set
    /** Deleted "for me" on this phone. */
    private var hidden: Set<Long> = emptySet()
    var error by mutableStateOf<String?>(null)
    fun dismissError() { error = null }
    var loaded by mutableStateOf(false)
        private set
    var reachedStart by mutableStateOf(false)
        private set
    var uploads by mutableIntStateOf(0)
        private set
    /** A message request someone sent me: I can read it, and accept or decline. */
    var incomingRequest by mutableStateOf(ctx.group.isRequestFor(myId))
        private set
    /** A request I sent that they haven't accepted yet. */
    val outgoingRequest = ctx.group.isRequest && ctx.group.requestedBy == myId
    /** Who's typing right now (user ids), each forgotten a few seconds after their last ping. */
    var typing by mutableStateOf<List<String>>(emptyList())
        private set
    private val typingSeen = mutableMapOf<String, Long>()
    private val typingRoom = c.chat.typingRoom(groupId)
    private var lastPing = 0L
    /** What you'd typed here last time and didn't send. */
    var initialDraft by mutableStateOf<String?>(null)
        private set
    private var draftSave: Job? = null
    private var loadingOlder = false
    private var visible = false

    init {
        viewModelScope.launch {
            // 1. Whatever we have on the phone, immediately.
            hidden = c.chat.hiddenIds(groupId)
            messages = sorted(c.chat.cached(groupId))
            reactions = c.chat.cachedReactions(groupId)
            if (messages.isNotEmpty()) loaded = true
            // 2. Catch up with the server.
            runCatching {
                merge(c.chat.syncNewer(groupId, myId))
                // Edits and deletions that happened while we weren't looking.
                merge(c.chat.syncChanges(groupId, myId))
                receipts = c.chat.readStates(groupId).associateBy { it.userId }
                val oldest = messages.firstOrNull { it.id > 0 }?.id ?: 0
                reactions = c.chat.syncReactions(groupId, oldest, myId)
                merge(c.chat.retryLocked(groupId, myId))
            }.onFailure { if (messages.isEmpty()) error = it.friendly() }
            loaded = true
            markSeen()
        }
        viewModelScope.launch { listen() }
        viewModelScope.launch { initialDraft = c.chat.draftText(groupId) }
        viewModelScope.launch {
            runCatching {
                typingRoom.events.collect { uid ->
                    if (uid == myId) return@collect
                    typingSeen[uid] = System.currentTimeMillis()
                    typing = typingSeen.keys.toList()
                }
            }
        }
        // Forget people who stopped typing.
        viewModelScope.launch {
            while (true) {
                delay(1_500)
                val cutoff = System.currentTimeMillis() - TYPING_TIMEOUT_MS
                if (typingSeen.values.any { it < cutoff }) {
                    typingSeen.entries.removeAll { it.value < cutoff }
                    typing = typingSeen.keys.toList()
                }
            }
        }
        // If someone here can't read older messages yet (new member, new phone), share the keys.
        viewModelScope.launch { runCatching { c.e2ee.shareHistory(myId) } }
        // Have the encryption key ready before you type, so the first message goes out instantly.
        viewModelScope.launch { runCatching { c.e2ee.sendingKey(groupId, myId) } }
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
                            event.row.userId?.let { uid -> if (typingSeen.remove(uid) != null) typing = typingSeen.keys.toList() }
                            val m = c.chat.decrypt(event.row, myId)
                            merge(listOf(m))
                            // Into the cache too, so it's still there next time the chat opens.
                            c.chat.remember(m)
                            markSeen()
                        }
                        is LiveEvent.MessageChanged -> {
                            val m = c.chat.decrypt(event.row, myId)
                            merge(listOf(m))
                            c.chat.remember(m)
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
                                runCatching {
                                    merge(c.chat.retryLocked(groupId, myId))
                                    // Reactions we couldn't open before may be readable now too.
                                    val oldest = messages.firstOrNull { it.id > 0 }?.id ?: 0
                                    reactions = c.chat.syncReactions(groupId, oldest, myId)
                                }
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
        // Looking at a message request doesn't tell the sender anything until you accept it.
        if (incomingRequest) return
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
        list.filter { it.id !in hidden }.distinctBy { it.id }.sortedWith(compareBy<ChatMessage>({ if (it.isPending) 1 else 0 }, { if (it.isPending) it.createdAt.toEpochMilli() else it.id }))

    /** The composer changed: tell the others you're typing (at most every few seconds), and keep the draft. */
    fun onDraftChanged(text: String) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastPing > TYPING_PING_MS && editing == null) {
            lastPing = now
            viewModelScope.launch { typingRoom.ping(myId) }
        }
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(500)
            c.chat.saveDraftText(groupId, text)
        }
    }

    // --- sending ----------------------------------------------------------------------

    /** [tagged]: people picked from the @ list; only those still named in the text are kept. */
    fun sendText(text: String, tagged: Collection<String> = emptyList()) {
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return
        // @all: everyone in the gang, as long as "@all" is still in the text.
        val everyone = EVERYONE in tagged && "@$ALL_TAG" in body
        val mentions = if (everyone) {
            ctx.members.keys.filter { it != myId }.take(MAX_MENTIONS)
        } else {
            tagged.distinct().filter { id -> id != myId && ctx.members[id]?.let { "@${it.displayName}" in body } == true }
        }
        // Keep tags that were already in an edited message and are still named in it.
        val kept = editing?.payload?.mentions.orEmpty().filter { id -> ctx.members[id]?.let { "@${it.displayName}" in body } == true }
        val all = (mentions + kept).distinct().takeIf { it.isNotEmpty() }
        editing?.let { target ->
            editing = null
            if (body == target.payload.text) return
            val optimistic = target.copy(payload = target.payload.copy(text = body, mentions = all, edited = true))
            messages = messages.map { if (it.id == target.id) optimistic else it }
            viewModelScope.launch {
                runCatching { c.chat.edit(target, body, all, myId) }
                    .onSuccess { merge(listOf(it)) }
                    .onFailure {
                        messages = messages.map { m -> if (m.id == target.id) target else m }
                        error = it.friendly()
                    }
            }
            return
        }
        send(withReply(Payload("text", text = body, mentions = all, all = everyone.takeIf { it })))
    }

    // --- edit & delete ----------------------------------------------------------------

    /** Your own text messages, for 15 minutes (the server enforces this too). */
    fun canEdit(m: ChatMessage) = m.userId == myId && !m.isPending && m.payload.t == "text" &&
        m.createdAt.isAfter(Instant.now().minusSeconds(EDIT_WINDOW_S - 15))

    /** Your own messages, for 2 days. */
    fun canDeleteForEveryone(m: ChatMessage) = m.userId == myId && !m.isPending && !m.payload.isDeleted &&
        m.isUserMessage && m.createdAt.isAfter(Instant.now().minusSeconds(DELETE_WINDOW_S - 60))

    fun startEdit(m: ChatMessage) {
        if (!canEdit(m)) return
        replyTo = null
        editing = m
    }

    fun cancelEdit() {
        editing = null
    }

    fun deleteForEveryone(m: ChatMessage) = viewModelScope.launch {
        runCatching { c.chat.deleteForEveryone(m) }
            .onSuccess {
                merge(listOf(it))
                reactions = reactions - m.id
            }
            .onFailure { error = it.friendly() }
    }

    fun deleteForMe(m: ChatMessage) = viewModelScope.launch {
        if (m.isPending) {
            discard(m)
            return@launch
        }
        c.chat.deleteForMe(m)
        hidden = hidden + m.id
        messages = messages.filterNot { it.id == m.id }
    }

    // Uploads take a while, so each of these takes the reply target when you hit send, not when the upload finishes.

    fun sendImages(context: Context, uris: List<Uri>) = uris.take(10).forEachIndexed { i, uri ->
        // Several photos at once: only the first one is the reply.
        val base = if (i == 0) withReply(Payload("image")) else Payload("image")
        upload {
            val picked = c.media.read(uri)
            val image = c.media.prepareImage(picked.bytes)
            base.copy(media = c.media.upload(groupId, image.bytes, "image/jpeg", picked.name, image.width, image.height, image.thumb))
        }
    }

    fun sendFile(uri: Uri) {
        val base = withReply(Payload("file"))
        upload {
            val picked = c.media.read(uri)
            if (picked.mime.startsWith("image/") && !picked.mime.contains("gif")) {
                val image = c.media.prepareImage(picked.bytes)
                base.copy(t = "image", media = c.media.upload(groupId, image.bytes, "image/jpeg", picked.name, image.width, image.height, image.thumb))
            } else {
                base.copy(media = c.media.upload(groupId, picked.bytes, picked.mime, picked.name))
            }
        }
    }

    /** Stickers can be replies too. */
    fun sendSticker(file: File) {
        val base = withReply(Payload("sticker"))
        upload { base.copy(media = c.media.uploadSticker(groupId, file.readBytes())) }
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

    // --- message requests -----------------------------------------------------------

    fun acceptRequest() = viewModelScope.launch {
        runCatching { c.chat.respondToRequest(groupId, accept = true) }
            .onSuccess {
                incomingRequest = false
                markSeen()
            }
            .onFailure { error = it.friendly() }
    }

    fun declineRequest(onDone: () -> Unit) = viewModelScope.launch {
        runCatching { c.chat.respondToRequest(groupId, accept = false) }
            .onSuccess {
                c.cache.clearGroup(groupId)
                onDone()
            }
            .onFailure { error = it.friendly() }
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
                ctx.isDm && readers.isNotEmpty() -> Receipt("Seen " + (readers.first().readAt?.let(::shortTime) ?: "").lowercase())
                readers.isNotEmpty() && readers.size >= others.size -> Receipt("Seen by everyone")
                readers.isNotEmpty() -> {
                    val names = readers.map { ctx.nameOf(it.userId).substringBefore(' ') }
                    Receipt("Seen by " + names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else "")
                }
                delivered -> Receipt("Delivered")
                else -> Receipt("Sent")
            }
        }
    }

    /** Per person, exactly when they got and saw [m] (from the server's receipt log). */
    suspend fun exactReceipts(m: ChatMessage): List<Pair<String, String>> {
        val times = runCatching { c.chat.receipts(groupId, m.id) }.getOrNull() ?: return detailsFor(m)
        return times.sortedBy { it.readAt == null }.map { r ->
            r.userId to when {
                r.readAt != null -> "Seen ${longTime(r.readAt)}"
                r.deliveredAt != null -> "Delivered ${longTime(r.deliveredAt)}"
                else -> "Not delivered yet"
            }
        }
    }

    /** Per-person status for the Info sheet. */
    fun detailsFor(m: ChatMessage): List<Pair<String, String>> = others.map { uid ->
        val s = receipts[uid]
        uid to when {
            s == null -> "Not delivered yet"
            s.readId >= m.id -> "Seen" + (if (s.readId == latestMineId()) s.readAt?.let { " " + shortTime(it) } ?: "" else "")
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
        // The view model's scope is gone; leave the typing channel from the app's side instead.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { typingRoom.close() } }
        if (c.inbox.openConversation == groupId) c.inbox.openConversation = null
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("h:mm a")
        private val DAY = DateTimeFormatter.ofPattern("EEE")

        private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a")
        const val EDIT_WINDOW_S = 15 * 60L
        private const val TYPING_PING_MS = 3_000L
        private const val TYPING_TIMEOUT_MS = 6_000L
        /** Marks "@all" among the picked tags. */
        const val EVERYONE = "*"
        /** The server accepts up to 50 tags per message. */
        private const val MAX_MENTIONS = 50
        const val DELETE_WINDOW_S = 2 * 24 * 3600L

        /** "10:42 pm" today, "Yesterday, 10:42 pm", otherwise "3 Oct, 10:42 pm". */
        fun longTime(iso: String): String {
            val t = parseInstant(iso).atZone(ZoneId.systemDefault())
            val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
            return when (t.toLocalDate()) {
                today -> t.format(TIME)
                today.minusDays(1) -> "yesterday, " + t.format(TIME)
                else -> t.format(DAY_TIME)
            }.lowercase()
        }

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
