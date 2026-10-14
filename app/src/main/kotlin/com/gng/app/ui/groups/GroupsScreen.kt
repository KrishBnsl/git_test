package com.gng.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Group
import com.gng.app.AppContainer
import com.gng.app.data.Conversation
import com.gng.app.data.Profile
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.EmojiStrip
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.EmojiAvatar
import com.gng.app.ui.chat.ChatViewModel
import com.gng.app.ui.theme.LocalIsDark
import com.gng.app.ui.theme.friendColor
import com.gng.app.ui.components.GROUP_EMOJIS
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.core.InviteCodes
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class ConversationRow(val conversation: Conversation, val title: String, val emoji: String, val preview: String)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GroupsViewModel(private val c: AppContainer, private val me: Profile) : ViewModel() {
    var rows by mutableStateOf<List<ConversationRow>?>(null)
        private set
    var people by mutableStateOf<List<Profile>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            c.chat.cachedConversations()?.let { rows = build(it) }
            refresh()
        }
        // Keep the list live: any new message anywhere refreshes order, previews and badges.
        viewModelScope.launch {
            while (true) {
                runCatching { c.chat.anyMessage().debounce(400).collect { refresh() } }
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { c.chat.conversations() }
            .onSuccess { list ->
                rows = build(list)
                error = null
                // These are on the phone now, so senders can see "Delivered".
                list.forEach { conv ->
                    val last = conv.lastMessageId ?: return@forEach
                    if (conv.unreadCount > 0 && (markedDelivered[conv.id] ?: 0) < last) {
                        markedDelivered[conv.id] = last
                        launch { runCatching { c.chat.markSeen(conv.id, last, null) } }
                    }
                }
            }
            .onFailure { error = it.friendly(); if (rows == null) rows = emptyList() }
    }

    private suspend fun build(list: List<Conversation>): List<ConversationRow> = list.map { conv ->
        val members = c.chat.cachedMembers(conv.id).orEmpty().associate { it.userId to it.profiles }
        val other = conv.otherUserId?.let { members[it] ?: profileCache[it] ?: runCatching { c.profiles.get(it) }.getOrNull()?.also { p -> profileCache[it] = p } }
        val title = if (conv.isDm) other?.displayName ?: "DM" else conv.name
        val emoji = if (conv.isDm) other?.emoji ?: "💬" else conv.emoji
        ConversationRow(conv, title, emoji, preview(conv, members))
    }

    private val profileCache = mutableMapOf<String, Profile>()
    private val markedDelivered = mutableMapOf<String, Long>()

    private suspend fun preview(conv: Conversation, members: Map<String, Profile>): String {
        val id = conv.lastMessageId ?: return if (conv.isDm) "Say hi 👋" else "code ${conv.inviteCode}"
        val who = when (conv.lastMessageUserId) {
            null -> ""
            me.id -> "You: "
            else -> if (conv.isDm) "" else (members[conv.lastMessageUserId]?.displayName?.let { "$it: " } ?: "")
        }
        if (conv.lastMessageKind != "text") return conv.lastMessageBody.orEmpty()
        // Message text is encrypted on the server; show it only if this phone has already decrypted it.
        val cached = c.cache.get(id)
        return who + (cached?.payload?.preview()?.takeIf { cached.payload.t != "locked" } ?: conv.lastMessageBody ?: "🔒 New message")
    }

    fun loadPeople() = viewModelScope.launch {
        runCatching { c.chat.people(me.id) }.onSuccess { people = it }.onFailure { error = it.friendly() }
    }

    fun openDm(person: Profile, onDone: (Group) -> Unit) = act(onDone) { c.chat.openDm(person.id) }

    fun create(name: String, emoji: String, onDone: (Group) -> Unit) = act(onDone) { c.groups.create(name, emoji) }

    fun join(code: String, onDone: (Group) -> Unit) = act(onDone) { c.groups.join(code) }

    private fun act(onDone: (Group) -> Unit, block: suspend () -> Group) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { refresh(); onDone(it) }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(me: Profile, onOpen: (Group) -> Unit, onSettings: () -> Unit) {
    val vm = gngViewModel { GroupsViewModel(it, me) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    var dialog by remember { mutableStateOf<String?>(null) } // "create" | "join" | "dm"
    val dark = LocalIsDark.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("hey ${me.displayName} ${me.emoji}") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.loadPeople(); dialog = "dm" },
                icon = { Text("✉️", fontSize = 18.sp) },
                text = { Text("New DM") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    BouncyButton("➕ New gang", { dialog = "create" }, Modifier.weight(1f))
                    BouncyButton(
                        "🔑 Join with code", { dialog = "join" }, Modifier.weight(1f),
                        container = MaterialTheme.colorScheme.secondary,
                        content = MaterialTheme.colorScheme.onSecondary,
                    )
                }
                ErrorBanner(vm.error, Modifier.padding(horizontal = 16.dp))
            }
            val rows = vm.rows
            if (rows != null && rows.isEmpty()) {
                item { EmptyState("🦗", "No gangs yet", "Start one and drop the code in your WhatsApp group.") }
            }
            items(rows.orEmpty(), key = { it.conversation.id }) { row ->
                ConversationItem(row, dark) { onOpen(row.conversation.toGroup()) }
            }
        }
    }

    when (dialog) {
        "create" -> CreateDialog(busy = vm.busy, error = vm.error, onDismiss = { dialog = null }) { name, emoji ->
            vm.create(name, emoji) { dialog = null; onOpen(it) }
        }
        "join" -> JoinDialog(busy = vm.busy, error = vm.error, onDismiss = { dialog = null }) { code ->
            vm.join(code) { dialog = null; onOpen(it) }
        }
        "dm" -> PeopleDialog(vm.people, busy = vm.busy, onDismiss = { dialog = null }) { person ->
            vm.openDm(person) { dialog = null; onOpen(it) }
        }
    }
}

@Composable
private fun ConversationItem(row: ConversationRow, dark: Boolean, onClick: () -> Unit) {
    val conv = row.conversation
    val unread = conv.unreadCount > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAvatar(row.emoji, if (conv.isDm) friendColor(conv.otherUserId.orEmpty(), dark) else MaterialTheme.colorScheme.primaryContainer, 52.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false),
                )
                if (conv.isMuted) Text(" 🔕", fontSize = 12.sp)
            }
            Text(
                row.preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            conv.lastMessageAt?.let { Text(ChatViewModel.shortTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (unread) {
                Badge(
                    containerColor = if (conv.isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString()) }
            }
        }
    }
}

@Composable
private fun PeopleDialog(people: List<Profile>, busy: Boolean, onDismiss: () -> Unit, onPick: (Profile) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message someone") },
        text = {
            if (people.isEmpty()) {
                Text("People from your gangs show up here.")
            } else {
                LazyColumn(Modifier.height(360.dp)) {
                    items(people, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { onPick(p) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.emoji, fontSize = 26.sp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                                listOfNotNull(p.branch, p.year?.let { "Year $it" }).takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CreateDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(GROUP_EMOJIS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a gang") },
        text = {
            Column {
                EmojiStrip(GROUP_EMOJIS, emoji) { emoji = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("Hostel H4 Maggi Club") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorBanner(error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = { onCreate(name.trim(), emoji) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nah") } },
    )
}

@Composable
private fun JoinDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val normalized = InviteCodes.normalize(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Got a code?") },
        text = {
            Column {
                OutlinedTextField(
                    code, { code = it.take(12) }, label = { Text("6-character code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                ErrorBanner(error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null && !busy, onClick = { normalized?.let(onJoin) }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
