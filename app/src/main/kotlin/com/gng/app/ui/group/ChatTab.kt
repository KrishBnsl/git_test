package com.gng.app.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.ChatRepository
import com.gng.app.data.IST
import com.gng.app.data.Message
import com.gng.app.data.Reaction
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.theme.avatarColors
import com.gng.app.ui.theme.pickAvatarColor
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val QUICK_REACTIONS = listOf("😂", "🔥", "💀", "🫡", "❤️", "👀")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")
private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM")

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

    fun dismissError() { error = null }

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

@Composable
fun ChatTab(ctx: GroupContext, onOpenEvent: (Message) -> Unit) {
    val vm = gngViewModel(key = "chat-${ctx.group.id}") { ChatViewModel(it.chat, ctx.group.id, ctx.me.id) }
    MessageEffect(vm.error, vm::dismissError)
    ChatContent(ctx, vm.messages, vm.reactions, vm::send, vm::react, onOpenEvent)
}

private sealed interface ChatRow {
    val key: String

    data class Day(val label: String, val epochDay: Long) : ChatRow { override val key = "d$epochDay" }
    data class Bubble(val m: Message, val first: Boolean, val last: Boolean) : ChatRow { override val key = "m${m.id}" }
    data class Event(val m: Message) : ChatRow { override val key = "m${m.id}" }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DAY)
}

private fun buildRows(messages: List<Message>, today: LocalDate): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var lastDay: LocalDate? = null
    messages.forEachIndexed { i, m ->
        val day = m.createdInstant.atZone(IST).toLocalDate()
        if (day != lastDay) {
            rows += ChatRow.Day(dayLabel(day, today), day.toEpochDay())
            lastDay = day
        }
        if (m.kind != "text") {
            rows += ChatRow.Event(m)
            return@forEachIndexed
        }
        fun joins(other: Message?) = other != null && other.kind == "text" && other.userId == m.userId &&
            Duration.between(minOf(other.createdInstant, m.createdInstant), maxOf(other.createdInstant, m.createdInstant))
                .toMinutes() < 5 && other.createdInstant.atZone(IST).toLocalDate() == day
        rows += ChatRow.Bubble(m, first = !joins(messages.getOrNull(i - 1)), last = !joins(messages.getOrNull(i + 1)))
    }
    return rows
}

@Composable
fun ChatContent(
    ctx: GroupContext,
    messages: List<Message>,
    reactions: List<Reaction>,
    onSend: (String) -> Unit,
    onReact: (Message, String) -> Unit,
    onOpenEvent: (Message) -> Unit,
    today: LocalDate = LocalDate.now(IST),
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var reactingTo by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val rows = remember(messages, today) { buildRows(messages, today) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(
                    GngIcons.Forum, "It's quiet in here",
                    "Say hi, or start a lunch plan from the Plans tab.",
                    Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows.asReversed(), key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Day -> DaySeparator(row.label)
                        is ChatRow.Event -> EventRow(row.m, ctx, onOpenEvent)
                        is ChatRow.Bubble -> {
                            val mine = row.m.userId == ctx.me.id
                            MessageBubble(
                                m = row.m, ctx = ctx, mine = mine, first = row.first, last = row.last,
                                reactions = reactions.filter { it.messageId == row.m.id },
                                showPicker = reactingTo == row.m.id,
                                onLongPress = { reactingTo = if (reactingTo == row.m.id) null else row.m.id },
                                onReact = { e -> onReact(row.m, e); reactingTo = null },
                            )
                        }
                    }
                }
            }
        }
        if (ctx.canSendMessages) {
            MessageInput(draft, { draft = it }, onSend = { onSend(draft); draft = "" })
        } else {
            ReadOnlyBar()
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    m: Message,
    ctx: GroupContext,
    mine: Boolean,
    first: Boolean,
    last: Boolean,
    reactions: List<Reaction>,
    showPicker: Boolean,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { avatarColors(scheme) }
    val big = 20.dp
    val tight = 5.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (first) big else tight, if (last) tight else tight, big)
    } else {
        RoundedCornerShape(if (first) big else tight, big, big, if (last) tight else tight)
    }
    val person = ctx.person(m.userId)

    Column(
        Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 0.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine && first) {
            Text(
                person.name,
                style = MaterialTheme.typography.labelMedium,
                color = pickAvatarColor(palette, person.key).content,
                modifier = Modifier.padding(start = 52.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                if (last) Avatar(person.name, person.photoUrl, size = 32.dp, colorKey = person.key)
                else Spacer(Modifier.width(32.dp))
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                color = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (mine) scheme.onPrimaryContainer else scheme.onSurface,
                shape = shape,
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                Text(
                    m.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Column(
            Modifier.padding(start = if (mine) 0.dp else 40.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                        val iReacted = list.any { it.userId == ctx.me.id }
                        Surface(
                            onClick = { onReact(emoji) },
                            shape = CircleShape,
                            color = if (iReacted) scheme.secondaryContainer else scheme.surfaceContainerHighest,
                        ) {
                            Text(
                                "$emoji ${list.size}",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            if (showPicker) {
                Surface(
                    shape = CircleShape, tonalElevation = 6.dp, shadowElevation = 4.dp,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        QUICK_REACTIONS.forEach { e ->
                            Text(e, fontSize = 24.sp, modifier = Modifier.clickable { onReact(e) }.padding(7.dp))
                        }
                    }
                }
            }
            if (last) {
                Text(
                    m.createdInstant.atZone(IST).format(TIME).lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EventRow(m: Message, ctx: GroupContext, onOpen: (Message) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (m.kind == "system") {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                m.body,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val (icon, label) = when (m.kind) {
        "plan" -> GngIcons.EventFill to "New plan"
        "exam" -> GngIcons.HourglassTop to "Exam added"
        else -> GngIcons.Description to "New in the stack"
    }
    Surface(
        onClick = { onOpen(m) },
        shape = MaterialTheme.shapes.large,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalIcon(icon, container = scheme.tertiary, content = scheme.onTertiary, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    "$label · ${ctx.nameOf(m.userId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
                Text(m.body, style = MaterialTheme.typography.bodyMedium)
            }
            GngIcon(GngIcons.ChevronRight, null)
        }
    }
}

/** Shown instead of the input when an admin has limited who can message. */
@Composable
private fun ReadOnlyBar() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GngIcon(GngIcons.Lock, null, tint = scheme.onSurfaceVariant, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Only admins can send messages in this gang",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            TextField(
                value = value,
                onValueChange = { onValueChange(it.take(2000)) },
                placeholder = { Text("Message the gang") },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = value.isNotBlank(),
            modifier = Modifier.padding(bottom = 4.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) { GngIcon(GngIcons.Send, "Send") }
    }
}
