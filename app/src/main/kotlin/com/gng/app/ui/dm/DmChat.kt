package com.gng.app.ui.dm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.DmMessage
import com.gng.app.data.DmReaction
import com.gng.app.data.Group
import com.gng.app.data.GroupRole
import com.gng.app.data.Message
import com.gng.app.data.Profile
import com.gng.app.data.Reaction
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.LocalSnackbar
import com.gng.app.ui.components.Loading
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.group.ChatContent
import com.gng.app.ui.group.GroupContext
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.launch

/** The existing chat UI speaks in gangs, so a conversation is presented to it as a gang of two. */
fun dmContext(me: Profile, other: Profile, threadId: String) = GroupContext(
    group = Group(id = threadId, name = other.displayName, icon = "groups", inviteCode = "", createdBy = me.id),
    me = me,
    members = mapOf(me.id to me, other.id to other),
    roles = mapOf(me.id to GroupRole.ADMIN, other.id to GroupRole.ADMIN),
)

fun DmMessage.asMessage() = Message(
    id = id, groupId = threadId, userId = senderId, body = body, kind = "text", refId = null, createdAt = createdAt,
)

fun DmReaction.asReaction() = Reaction(messageId = messageId, userId = userId, groupId = threadId, emoji = emoji)

class DmViewModel(private val c: AppContainer, private val myId: String, private val otherId: String) : ViewModel() {
    var other by mutableStateOf<Profile?>(null)
        private set
    var threadId by mutableStateOf<String?>(null)
        private set
    var messages by mutableStateOf<List<DmMessage>>(emptyList())
        private set
    var reactions by mutableStateOf<List<DmReaction>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            runCatching {
                other = c.profiles.get(otherId)
                val thread = c.dms.open(otherId)
                threadId = thread.id
                viewModelScope.launch {
                    runCatching {
                        c.dms.incoming(thread.id).collect { m ->
                            if (messages.none { it.id == m.id }) messages = messages + m
                            if (m.senderId != myId) runCatching { c.dms.markRead(thread.id) }
                        }
                    }
                }
                viewModelScope.launch {
                    runCatching { c.dms.reactionChanges(thread.id).collect { runCatching { reactions = c.dms.reactions(thread.id) } } }
                }
                val loaded = c.dms.messages(thread.id)
                messages = (loaded + messages).distinctBy { it.id }.sortedBy { it.id }
                reactions = c.dms.reactions(thread.id)
                c.dms.markRead(thread.id)
            }.onFailure { error = it.friendly() }
            loading = false
        }
    }

    fun dismissError() { error = null }

    fun send(text: String) {
        val id = threadId ?: return
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch { runCatching { c.dms.send(id, body) }.onFailure { error = it.friendly() } }
    }

    fun react(message: Message, emoji: String) {
        val id = threadId ?: return
        val target = messages.firstOrNull { it.id == message.id } ?: return
        val mine = reactions.any { it.messageId == target.id && it.userId == myId && it.emoji == emoji }
        reactions = if (mine) {
            reactions.filterNot { it.messageId == target.id && it.userId == myId && it.emoji == emoji }
        } else {
            reactions + DmReaction(target.id, id, myId, emoji)
        }
        viewModelScope.launch {
            runCatching { c.dms.toggleReaction(target, emoji, myId, mine) }.onFailure {
                error = it.friendly()
                reactions = runCatching { c.dms.reactions(id) }.getOrDefault(reactions)
            }
        }
    }
}

@Composable
fun DmChatScreen(me: Profile, otherId: String, onBack: () -> Unit) {
    val vm = gngViewModel(key = "dm-${me.id}-$otherId") { DmViewModel(it, me.id, otherId) }
    val other = vm.other
    val thread = vm.threadId
    val snackbar = remember { SnackbarHostState() }

    DmChatContent(
        me = me, other = other, threadId = thread, loading = vm.loading, error = vm.error,
        messages = vm.messages.map { it.asMessage() }, reactions = vm.reactions.map { it.asReaction() },
        snackbar = snackbar, onDismissError = vm::dismissError, onBack = onBack,
        onSend = vm::send, onReact = vm::react,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmChatContent(
    me: Profile,
    other: Profile?,
    threadId: String?,
    loading: Boolean,
    error: String?,
    messages: List<Message>,
    reactions: List<Reaction>,
    snackbar: SnackbarHostState,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onReact: (Message, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (other != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(other.displayName, other.avatarUrl, size = 40.dp, colorKey = other.id)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(other.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                val line = other.statusText?.takeIf { it.isNotBlank() }
                                    ?: listOfNotNull(other.branch, other.year?.let { "Year $it" }).joinToString(" · ")
                                if (line.isNotBlank()) {
                                    Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            MessageEffect(error, onDismissError)
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    other == null && loading -> Loading()
                    other == null || threadId == null -> EmptyState(
                        GngIcons.WifiOff, "Couldn't open this chat", error ?: "Try again in a moment.",
                        Modifier.align(Alignment.Center),
                    )
                    else -> ChatContent(
                        ctx = dmContext(me, other, threadId),
                        messages = messages, reactions = reactions,
                        onSend = onSend, onReact = onReact, onOpenEvent = {},
                        placeholder = "Message ${other.displayName.substringBefore(' ')}",
                        oneToOne = true,
                        emptyTitle = "Say hi to ${other.displayName.substringBefore(' ')}",
                        emptyBody = "Only you two can see this conversation.",
                    )
                }
            }
        }
    }
}
