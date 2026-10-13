package com.gng.app.ui.group

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.ChatRepository
import com.gng.app.data.IST
import com.gng.app.data.Message
import com.gng.app.data.Reaction
import com.gng.app.ui.components.EmojiAvatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.theme.LocalIsDark
import com.gng.app.ui.theme.friendColor
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val QUICK_REACTIONS = listOf("😂", "🔥", "💀", "🫡", "❤️", "👀")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")

class ChatViewModel(private val repo: ChatRepository, private val groupId: String, private val myId: String) : ViewModel() {
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var reactions by mutableStateOf<List<Reaction>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            runCatching {
                val loaded = repo.recent(groupId)
                // merge with anything realtime delivered while we were loading
                messages = (loaded + messages).distinctBy { it.id }.sortedBy { it.id }
                reactions = repo.reactions(groupId)
            }.onFailure { error = it.friendly() }
        }
        viewModelScope.launch {
            repo.incoming(groupId).collect { m ->
                if (messages.none { it.id == m.id }) messages = messages + m
            }
        }
        viewModelScope.launch {
            repo.reactionChanges(groupId).collect { runCatching { reactions = repo.reactions(groupId) } }
        }
    }

    fun send(text: String) {
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            runCatching { repo.send(groupId, body) }.onFailure { error = it.friendly() }
        }
    }

    fun react(message: Message, emoji: String) {
        val mine = reactions.any { it.messageId == message.id && it.userId == myId && it.emoji == emoji }
        // Optimistic: realtime doesn't deliver filtered deletes, so update locally right away.
        reactions = if (mine) {
            reactions.filterNot { it.messageId == message.id && it.userId == myId && it.emoji == emoji }
        } else {
            reactions + Reaction(message.id, myId, groupId, emoji)
        }
        viewModelScope.launch {
            runCatching { repo.toggleReaction(message, emoji, myId, mine) }
                .onFailure {
                    error = it.friendly()
                    reactions = runCatching { repo.reactions(groupId) }.getOrDefault(reactions)
                }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatTab(ctx: GroupContext) {
    val vm = gngViewModel(key = "chat-${ctx.group.id}") { ChatViewModel(it.chat, ctx.group.id, ctx.me.id) }
    var draft by rememberSaveable { mutableStateOf("") }
    var reactingTo by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val dark = LocalIsDark.current

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ErrorBanner(vm.error, Modifier.padding(8.dp))
        Box(Modifier.weight(1f)) {
            if (vm.messages.isEmpty()) {
                EmptyState("🦗", "It's quiet…", "Say hi, or start a lunch plan from the Plans tab.", Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.messages.asReversed(), key = { it.id }) { m ->
                    val reactionsHere = vm.reactions.filter { it.messageId == m.id }
                    if (m.kind == "text") {
                        Bubble(
                            m = m,
                            ctx = ctx,
                            mine = m.userId == ctx.me.id,
                            dark = dark,
                            reactions = reactionsHere,
                            showPicker = reactingTo == m.id,
                            onLongPress = { reactingTo = if (reactingTo == m.id) null else m.id },
                            onReact = { e -> vm.react(m, e); reactingTo = null },
                        )
                    } else {
                        EventPill(m, ctx)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft, { draft = it.take(2000) },
                placeholder = { Text("say something unhinged") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            FilledIconButton(
                onClick = { vm.send(draft); draft = "" },
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    ctx: GroupContext,
    mine: Boolean,
    dark: Boolean,
    reactions: List<Reaction>,
    showPicker: Boolean,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) EmojiAvatar(ctx.emojiOf(m.userId), friendColor(m.userId.orEmpty(), dark), size = 32.dp)
        Column(
            Modifier.padding(horizontal = 6.dp).widthIn(max = 290.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine) {
                Text(ctx.nameOf(m.userId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primary else friendColor(m.userId.orEmpty(), dark),
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = if (mine) 20.dp else 4.dp, bottomEnd = if (mine) 4.dp else 20.dp,
                ),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(m.body, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        m.createdInstant.atZone(IST).format(TIME),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
            if (reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                        val iReacted = list.any { it.userId == ctx.me.id }
                        Text(
                            "$emoji ${list.size}",
                            fontSize = 13.sp,
                            modifier = Modifier
                                .background(
                                    if (iReacted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onReact(emoji) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (showPicker) {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                    Row(Modifier.padding(horizontal = 6.dp)) {
                        QUICK_REACTIONS.forEach { e ->
                            Text(e, fontSize = 24.sp, modifier = Modifier.clickable { onReact(e) }.padding(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventPill(m: Message, ctx: GroupContext) {
    val who = m.userId?.let { "${ctx.emojiOf(it)} ${ctx.nameOf(it)} · " } ?: ""
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            who + m.body,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
