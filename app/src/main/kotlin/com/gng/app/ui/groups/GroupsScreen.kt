package com.gng.app.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.container
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Group
import com.gng.app.AppContainer
import com.gng.app.data.Conversation
import com.gng.app.data.parseInstant
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.dm.NewMessageSheet
import com.gng.app.ui.dm.inboxTime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import com.gng.app.data.Profile
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GroupAvatar
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.IconPicker
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.components.Loading
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.icons.GroupIcons
import com.gng.app.ui.update.UpdateActions
import com.gng.app.ui.update.actions
import com.gng.app.ui.update.UpdateCard
import com.gng.app.update.UpdateState
import com.gng.core.InviteCodes
import kotlinx.coroutines.launch

/** One line of the home list: a gang or a DM, with what to show for it. */
data class ConversationRow(
    val conversation: Conversation,
    val title: String,
    val preview: String,
    /** The other person, for DMs. */
    val other: Profile? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GroupsViewModel(private val c: AppContainer, private val me: Profile) : ViewModel() {
    var rows by mutableStateOf<List<ConversationRow>?>(null)
        private set
    var people by mutableStateOf<List<Profile>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val profileCache = mutableMapOf<String, Profile>()
    private val markedDelivered = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            c.chat.cachedConversations()?.let { rows = build(it) }
            refresh()
        }
        // Keep the list live: a new message anywhere refreshes order, previews and badges.
        viewModelScope.launch {
            while (true) {
                runCatching { c.chat.anyMessage().debounce(400).collect { refresh() } }
                delay(5_000)
            }
        }
    }

    fun dismissError() { error = null }

    fun refresh() = viewModelScope.launch {
        refreshing = rows == null
        runCatching { c.chat.conversations() }
            .onSuccess { list ->
                rows = build(list)
                error = null
                // These are on the phone now, so senders can see "Delivered".
                list.forEach { conv ->
                    val last = conv.lastMessageId ?: return@forEach
                    if (conv.unreadCount > 0 && !conv.isRequestFor(me.id) && (markedDelivered[conv.id] ?: 0) < last) {
                        markedDelivered[conv.id] = last
                        launch { runCatching { c.chat.markSeen(conv.id, last, null) } }
                    }
                }
            }
            .onFailure { error = it.friendly(); if (rows == null) rows = emptyList() }
        refreshing = false
    }

    private suspend fun build(list: List<Conversation>): List<ConversationRow> = list.map { conv ->
        val members = c.chat.cachedMembers(conv.id).orEmpty().associate { it.userId to it.profiles }
        val other = conv.otherUserId?.takeIf { conv.isDm }?.let { id ->
            members[id] ?: profileCache[id]
                ?: runCatching { c.profiles.get(id) }.getOrNull()?.also { profileCache[id] = it }
                // Message requests: their full profile is private until accepted, but the card comes with the members.
                ?: runCatching { c.groups.members(conv.id) }.getOrNull()?.firstOrNull { it.userId == id }?.profiles
                    ?.also { profileCache[id] = it }
        }
        ConversationRow(conv, if (conv.isDm) other?.displayName ?: "Direct message" else conv.name, preview(conv, members), other)
    }

    private suspend fun preview(conv: Conversation, members: Map<String, Profile>): String {
        val id = conv.lastMessageId ?: return if (conv.isDm) "Say hi" else "No messages yet"
        val who = when (conv.lastMessageUserId) {
            null -> ""
            me.id -> "You: "
            else -> if (conv.isDm) "" else members[conv.lastMessageUserId]?.displayName?.substringBefore(' ')?.let { "$it: " } ?: ""
        }
        if (conv.lastMessageKind != "text") return conv.lastMessageBody.orEmpty()
        // Message text is encrypted on the server; show it only if this phone has already decrypted it.
        val cached = c.cache.get(id)?.payload?.takeIf { it.t != "locked" }
        return who + (cached?.preview() ?: conv.lastMessageBody ?: "New message")
    }

    fun loadPeople() = viewModelScope.launch {
        runCatching { c.chat.people(me.id) }.onSuccess { people = it }.onFailure { error = it.friendly() }
    }

    fun openDm(userId: String, onDone: (Group) -> Unit) = act(onDone) { c.chat.openDm(userId) }

    suspend fun search(query: String) = c.chat.searchPeople(query)

    fun create(name: String, icon: String, onDone: (Group) -> Unit) = act(onDone) { c.groups.create(name, icon) }

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

@Composable
fun GroupsScreen(me: Profile, onOpen: (Group) -> Unit, onSettings: () -> Unit) {
    val vm = gngViewModel { GroupsViewModel(it, me) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var sheet by remember { mutableStateOf<GangSheetMode?>(null) }
    var picker by remember { mutableStateOf(false) }

    val updates = LocalContext.current.container.updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    val all = vm.rows
    GroupsContent(
        updateState = updateState, updateActions = updates.actions(),
        me = me, rows = all?.filterNot { it.conversation.isRequestFor(me.id) },
        requests = all.orEmpty().filter { it.conversation.isRequestFor(me.id) }, refreshing = vm.refreshing, error = vm.error, onDismissError = vm::dismissError,
        onRefresh = { vm.refresh() }, onOpen = { onOpen(it.conversation.toGroup()) }, onSettings = onSettings,
        onNewMessage = { vm.loadPeople(); picker = true },
        onStart = { sheet = GangSheetMode.Start }, onJoin = { sheet = GangSheetMode.Join },
    )

    sheet?.let { mode ->
        NewGangSheet(
            initial = mode, busy = vm.busy, error = vm.error,
            onDismiss = { sheet = null; vm.dismissError() },
            onCreate = { name, icon -> vm.create(name, icon) { sheet = null; onOpen(it) } },
            onJoin = { code -> vm.join(code) { sheet = null; onOpen(it) } },
        )
    }
    if (picker) {
        NewMessageSheet(
            vm.people, onDismiss = { picker = false },
            onPick = { userId -> vm.openDm(userId) { picker = false; onOpen(it) } },
            search = vm::search,
        )
    }
}

enum class GangSheetMode { Start, Join }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsContent(
    me: Profile,
    rows: List<ConversationRow>?,
    refreshing: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (ConversationRow) -> Unit,
    onSettings: () -> Unit,
    onNewMessage: () -> Unit,
    onStart: () -> Unit,
    onJoin: () -> Unit,
    updateState: UpdateState = UpdateState.Idle,
    updateActions: UpdateActions = UpdateActions(),
    /** DMs from people you don't share a gang with, waiting for you to accept. */
    requests: List<ConversationRow> = emptyList(),
) {
    var showRequests by rememberSaveable { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    // Sheet errors show inside the sheet; list errors show as a snackbar.
    LaunchedEffect(error, rows) {
        if (error != null && rows != null && rows.isEmpty()) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Hey, ${me.displayName.substringBefore(' ')}") },
                actions = {
                    IconButton(onClick = onNewMessage) { GngIcon(GngIcons.AddComment, "New message") }
                    IconButton(onClick = onSettings) { Avatar(me.displayName, me.avatarUrl, size = 36.dp, colorKey = me.id) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (!rows.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onStart,
                    icon = { GngIcon(GngIcons.Add, null) },
                    text = { Text("New gang") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
        UpdateCard(updateState, updateActions, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                rows == null -> Loading()
                rows.isEmpty() && requests.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        GngIcons.Groups, "No gangs yet",
                        "Start one and share the code in your WhatsApp group, or join a friend's.",
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onStart) { Text("Start a gang") }
                                OutlinedButton(onClick = onJoin) { Text("Join with code") }
                            }
                        },
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (requests.isNotEmpty()) {
                        item(key = "requests") {
                            RequestsHeader(requests.size, showRequests) { showRequests = !showRequests }
                        }
                        if (showRequests) {
                            items(requests, key = { "r" + it.conversation.id }) { row -> ConversationCard(row, onClick = { onOpen(row) }) }
                        }
                    }
                    items(rows, key = { it.conversation.id }) { row -> ConversationCard(row, onClick = { onOpen(row) }) }
                    item {
                        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            GngIcon(GngIcons.Key, null, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Join with a code")
                        }
                        Spacer(Modifier.height(96.dp)) // room for the FAB
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun RequestsHeader(count: Int, open: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GngIcon(GngIcons.Mail, null, tint = scheme.onSecondaryContainer)
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text("Message requests", style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer)
                Text(
                    "$count ${if (count == 1) "person wants" else "people want"} to message you",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer,
                )
            }
            GngIcon(if (open) GngIcons.ExpandMore else GngIcons.ChevronRight, null, tint = scheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ConversationCard(row: ConversationRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val conv = row.conversation
    val unread = conv.unreadCount > 0
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (conv.isDm) Avatar(row.title, row.other?.avatarUrl, size = 56.dp, colorKey = conv.otherUserId ?: conv.id)
            else GroupAvatar(conv.emoji, conv.photoUrl, size = 56.dp)
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (unread) FontWeight.ExtraBold else null, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conv.isMuted) {
                        Spacer(Modifier.width(6.dp))
                        GngIcon(GngIcons.NotificationsOff, "Muted", size = 16.dp, tint = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    row.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                conv.lastMessageAt?.let {
                    Text(
                        inboxTime(parseInstant(it)), style = MaterialTheme.typography.labelSmall,
                        color = if (unread) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                if (unread) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Someone tagged you here: always shown, even when the chat is muted.
                        if (conv.unreadMentions > 0) Badge(containerColor = scheme.primary) { Text("@") }
                        Badge(containerColor = if (conv.isMuted) scheme.outline else scheme.primary) {
                            Text(if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString())
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun NewGangSheet(
    initial: GangSheetMode,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, icon: String) -> Unit,
    onJoin: (code: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(GroupIcons.default.key) }
    var code by remember { mutableStateOf("") }
    val normalized = InviteCodes.normalize(code)

    GngSheet(title = if (mode == GangSheetMode.Start) "Start a gang" else "Join a gang", onDismiss = onDismiss) {
        SegmentedChoice(
            choices = listOf(
                Choice(GangSheetMode.Start, "Start new", GngIcons.Add),
                Choice(GangSheetMode.Join, "Join with code", GngIcons.Key),
            ),
            selected = mode, onSelect = { mode = it }, checkIcon = GngIcons.Check,
        )
        Spacer(Modifier.height(20.dp))
        if (mode == GangSheetMode.Start) {
            IconPicker(GroupIcons, icon) { icon = it }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                name, { name = it.take(40) }, label = { Text("Name") }, placeholder = { Text("Hostel H4 Maggi Club") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                code, { code = it.take(12) }, label = { Text("6-character code") },
                leadingIcon = { GngIcon(GngIcons.Key, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) {
            Text(error, color = scheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !busy && if (mode == GangSheetMode.Start) name.isNotBlank() else normalized != null,
            onClick = { if (mode == GangSheetMode.Start) onCreate(name.trim(), icon) else normalized?.let(onJoin) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (mode == GangSheetMode.Start) "Create gang" else "Join gang") }
    }
}
