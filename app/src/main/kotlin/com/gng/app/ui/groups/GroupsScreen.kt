package com.gng.app.ui.groups

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.gng.app.call.CallActivity
import com.gng.app.call.CallPhase
import com.gng.app.call.CallUi
import com.gng.app.container
import com.gng.app.data.parseInstant
import com.gng.app.ui.dm.inboxTime
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Group
import com.gng.app.AppContainer
import com.gng.app.data.Conversation
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.dm.NewMessageSheet
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
import com.gng.app.ui.invite.scanInvite
import androidx.compose.runtime.rememberCoroutineScope
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
    /** Chats pinned to the top (kept on this phone). */
    var pinned by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The server has answered at least once, so an empty list really means "no chats". */
    var loaded by mutableStateOf(false)
        private set
    private var latest: List<Conversation> = emptyList()

    init {
        viewModelScope.launch {
            pinned = c.chat.pinned()
            // What's on the phone first, without touching the network, so the list is there instantly.
            c.chat.cachedConversations()?.let { cached ->
                latest = cached
                rows = build(cached)
                if (cached.isNotEmpty()) loaded = true
            }
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
    fun showError(message: String) { error = message }

    fun refresh() = viewModelScope.launch {
        refreshing = rows == null
        runCatching { c.chat.conversations() }
            .onSuccess { list ->
                latest = list
                rows = build(list)
                loaded = true
                error = null
                // These are on the phone now, so senders can see "Delivered".
                list.forEach { conv ->
                    val last = conv.lastMessageId ?: return@forEach
                    if (conv.unreadCount > 0 && !conv.isRequestFor(me.id) && (markedDelivered[conv.id] ?: 0) < last) {
                        markedDelivered[conv.id] = last
                        launch { runCatching { c.chat.markSeen(conv.id, last, null) } }
                    }
                }
                fetchMissingPeople(list)
            }
            // Offline: keep showing what we have. Only a first-ever start with nothing saved shows the error.
            .onFailure { error = it.friendly() }
        refreshing = false
    }

    /** Builds rows from what's on the phone only (never waits on the network). */
    private suspend fun build(list: List<Conversation>): List<ConversationRow> = list.map { conv ->
        val members = c.chat.cachedMembers(conv.id).orEmpty().associate { it.userId to it.profiles }
        val other = conv.otherUserId?.takeIf { conv.isDm }?.let { id ->
            members[id] ?: profileCache[id] ?: c.chat.cachedProfile(id)?.also { profileCache[id] = it }
        }
        ConversationRow(conv, if (conv.isDm) other?.displayName ?: "Direct message" else conv.name, preview(conv, members), other)
    }

    /** DM partners we've never seen: look them up once, save them, and redraw. */
    private fun fetchMissingPeople(list: List<Conversation>) = viewModelScope.launch {
        val missing = list.filter { it.isDm }.mapNotNull { conv -> conv.otherUserId?.let { conv to it } }
            .filter { (_, id) -> profileCache[id] == null && c.chat.cachedProfile(id) == null }
        if (missing.isEmpty()) return@launch
        missing.forEach { (conv, id) ->
            val p = runCatching { c.profiles.get(id) }.getOrNull()
                // Message requests: their full profile is private until accepted, but the card comes with the members.
                ?: runCatching { c.groups.members(conv.id) }.getOrNull()?.firstOrNull { it.userId == id }?.profiles
            p?.let { profileCache[id] = it; c.chat.rememberProfile(it) }
        }
        rows = build(latest)
    }

    fun togglePin(row: ConversationRow) = viewModelScope.launch {
        val id = row.conversation.id
        c.chat.setPinned(id, id !in pinned)
        pinned = c.chat.pinned()
    }

    fun toggleMute(row: ConversationRow) = viewModelScope.launch {
        val until = if (row.conversation.isMuted) null else java.time.Instant.now().plus(java.time.Duration.ofDays(3650))
        runCatching { c.chat.mute(row.conversation.id, me.id, until) }.onSuccess { refresh() }.onFailure { error = it.friendly() }
    }

    fun markRead(row: ConversationRow) = viewModelScope.launch {
        val last = row.conversation.lastMessageId ?: return@launch
        runCatching { c.chat.markSeen(row.conversation.id, last, if (c.settings.readReceipts.first()) last else null) }
        c.notifier.clearConversation(row.conversation.id)
        refresh()
    }

    fun joinCall(row: ConversationRow, onCallScreen: () -> Unit) = viewModelScope.launch {
        val call = c.calls.activeCalls.value[row.conversation.id] ?: return@launch
        onCallScreen()
        runCatching { c.calls.joinExisting(call, row.title) }.onFailure { error = it.friendly() }
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
fun GroupsScreen(me: Profile, onOpen: (Group) -> Unit, onSettings: () -> Unit, onJoinCode: (String) -> Unit = {}) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val vm = gngViewModel { GroupsViewModel(it, me) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var sheet by remember { mutableStateOf<GangSheetMode?>(null) }
    var picker by remember { mutableStateOf(false) }
    val updates = c.updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    val activeCalls by c.calls.activeCalls.collectAsStateWithLifecycle()
    val ongoing by c.calls.state.collectAsStateWithLifecycle()

    // Joining a call from the list needs the mic first.
    var joining by remember { mutableStateOf<ConversationRow?>(null) }
    fun openCall(id: String) = context.startActivity(CallActivity.intent(context, id, CallActivity.ACTION_SHOW))
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val row = joining
        if (ok && row != null) vm.joinCall(row) { openCall(activeCalls[row.conversation.id]?.id.orEmpty()) }
        else if (!ok) vm.showError("Calls need the microphone")
    }

    val all = vm.rows
    GroupsContent(
        me = me,
        rows = all?.filterNot { it.conversation.isRequestFor(me.id) },
        refreshing = vm.refreshing, error = vm.error, onDismissError = vm::dismissError,
        onRefresh = { vm.refresh() }, onOpen = { onOpen(it.conversation.toGroup()) }, onSettings = onSettings,
        onNewMessage = { vm.loadPeople(); picker = true },
        onStart = { sheet = GangSheetMode.Start }, onJoin = { sheet = GangSheetMode.Join },
        updateState = updateState, updateActions = updates.actions(),
        requests = all.orEmpty().filter { it.conversation.isRequestFor(me.id) },
        loaded = vm.loaded,
        pinned = vm.pinned,
        activeCalls = activeCalls.keys,
        ongoing = ongoing?.takeIf { it.phase != CallPhase.Ended && it.phase != CallPhase.Ringing },
        onReturnToCall = { ongoing?.let { openCall(it.callId) } },
        onJoinCall = { row ->
            joining = row
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                vm.joinCall(row) { openCall(activeCalls[row.conversation.id]?.id.orEmpty()) }
            } else {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onPin = vm::togglePin, onMute = vm::toggleMute, onMarkRead = vm::markRead,
        onScan = {
            scope.launch {
                runCatching { scanInvite(context) }
                    .onSuccess { code -> code?.let(onJoinCode) }
                    .onFailure { vm.showError(it.message ?: "Couldn't scan that") }
            }
        },
    )

    sheet?.let { mode ->
        NewGangSheet(
            initial = mode, busy = vm.busy, error = vm.error,
            onDismiss = { sheet = null; vm.dismissError() },
            onCreate = { name, icon -> vm.create(name, icon) { sheet = null; onOpen(it) } },
            // Typed or scanned, show the gang first and join from there.
            onJoin = { code -> sheet = null; onJoinCode(code) },
            onScan = {
                scope.launch {
                    runCatching { scanInvite(context) }
                        .onSuccess { code -> if (code != null) { sheet = null; onJoinCode(code) } }
                        .onFailure { vm.showError(it.message ?: "Couldn't scan that") }
                }
            },
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

/** Which chats the home list shows. */
private enum class ChatFilter(val label: String) { All("All"), Unread("Unread"), Gangs("Gangs"), Dms("DMs") }

/**
 * The home list, WhatsApp style: a plain top bar (it doesn't jump or collapse), search, filter
 * chips, and flat rows. Pinned chats first; a bar to get back to a call you're in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    loaded: Boolean = true,
    pinned: Set<String> = emptySet(),
    /** Conversations with a call going on right now. */
    activeCalls: Set<String> = emptySet(),
    /** The call you're in, if any. */
    ongoing: CallUi? = null,
    onReturnToCall: () -> Unit = {},
    onJoinCall: (ConversationRow) -> Unit = {},
    onPin: (ConversationRow) -> Unit = {},
    onMute: (ConversationRow) -> Unit = {},
    onMarkRead: (ConversationRow) -> Unit = {},
    onScan: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    var showRequests by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All) }
    var newMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ConversationRow?>(null) }
    LaunchedEffect(error) {
        if (error != null && rows != null) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }
    BackHandler(enabled = searching) { searching = false; query = "" }

    val shown = remember(rows, query, filter, pinned) {
        rows.orEmpty()
            .filter { r -> query.isBlank() || r.title.contains(query.trim(), ignoreCase = true) }
            .filter { r ->
                when (filter) {
                    ChatFilter.All -> true
                    ChatFilter.Unread -> r.conversation.unreadCount > 0
                    ChatFilter.Gangs -> !r.conversation.isDm
                    ChatFilter.Dms -> r.conversation.isDm
                }
            }
            .sortedByDescending { it.conversation.id in pinned } // stable: keeps recency order within each part
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            query, { query = it }, placeholder = { Text("Search chats") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text("gng", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = scheme.primary)
                    }
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) { GngIcon(GngIcons.ArrowBack, "Close search") }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = onScan) { GngIcon(GngIcons.QrCodeScanner, "Scan a gang's QR code") }
                        IconButton(onClick = { searching = true }) { GngIcon(GngIcons.Search, "Search") }
                        IconButton(onClick = onSettings) { Avatar(me.displayName, me.avatarUrl, size = 32.dp, colorKey = me.id) }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { newMenu = true }) { GngIcon(GngIcons.AddComment, "New chat") }
                DropdownMenu(newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New message") }, leadingIcon = { GngIcon(GngIcons.ChatBubble, null) },
                        onClick = { newMenu = false; onNewMessage() },
                    )
                    DropdownMenuItem(
                        text = { Text("New gang") }, leadingIcon = { GngIcon(GngIcons.GroupAdd, null) },
                        onClick = { newMenu = false; onStart() },
                    )
                    DropdownMenuItem(
                        text = { Text("Join a gang") }, leadingIcon = { GngIcon(GngIcons.QrCodeScanner, null) },
                        onClick = { newMenu = false; onJoin() },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ongoing?.let { OngoingCallBar(it, onReturnToCall) }
            UpdateCard(updateState, updateActions, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (rows != null && rows.isNotEmpty() && !searching) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ChatFilter.entries) { f ->
                        val count = if (f == ChatFilter.Unread) rows.count { it.conversation.unreadCount > 0 } else 0
                        FilterChip(
                            selected = filter == f, onClick = { filter = f },
                            label = { Text(if (count > 0) "${f.label} $count" else f.label) },
                            shape = CircleShape,
                        )
                    }
                }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                when {
                    rows == null || (!loaded && rows.isEmpty() && error == null) -> Loading()
                    rows.isEmpty() && requests.isEmpty() && error != null -> Column(
                        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        EmptyState(
                            GngIcons.WifiOff, "Can't reach the gang", "Check your internet and try again.",
                            action = { Button(onClick = onRefresh) { Text("Retry") } },
                        )
                    }
                    rows.isEmpty() && requests.isEmpty() -> Column(
                        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        EmptyState(
                            GngIcons.Groups, "No chats yet",
                            "Start a gang and share its QR code, scan a friend's, or message anyone by their @username.",
                            action = {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = onStart) { Text("Start a gang") }
                                    OutlinedButton(onClick = onJoin) { Text("Join one") }
                                }
                            },
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (requests.isNotEmpty() && !searching) {
                            item(key = "requests") { RequestsHeader(requests.size, showRequests) { showRequests = !showRequests } }
                            if (showRequests) {
                                items(requests, key = { "r" + it.conversation.id }) { row ->
                                    ConversationRowItem(row, pinned = false, inCall = false, onClick = { onOpen(row) }, onLongClick = {}, onJoinCall = {})
                                }
                            }
                        }
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    if (query.isNotBlank()) "No chats match \"$query\"" else "Nothing here",
                                    color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        items(shown, key = { it.conversation.id }) { row ->
                            Box(Modifier.animateItem()) {
                                ConversationRowItem(
                                    row, pinned = row.conversation.id in pinned, inCall = row.conversation.id in activeCalls,
                                    onClick = { onOpen(row) }, onLongClick = { menuFor = row }, onJoinCall = { onJoinCall(row) },
                                )
                                DropdownMenu(menuFor == row, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(if (row.conversation.id in pinned) "Unpin" else "Pin to top") },
                                        leadingIcon = { GngIcon(GngIcons.PushPin, null) },
                                        onClick = { menuFor = null; onPin(row) },
                                    )
                                    if (row.conversation.unreadCount > 0) {
                                        DropdownMenuItem(
                                            text = { Text("Mark as read") }, leadingIcon = { GngIcon(GngIcons.DoneAll, null) },
                                            onClick = { menuFor = null; onMarkRead(row) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (row.conversation.isMuted) "Unmute" else "Mute") },
                                        leadingIcon = { GngIcon(if (row.conversation.isMuted) GngIcons.Notifications else GngIcons.NotificationsOff, null) },
                                        onClick = { menuFor = null; onMute(row) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "On a call with Hostel H4 · 03:12 · tap to return", across the top of the home list. */
@Composable
fun OngoingCallBar(call: CallUi, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = call.connectedAt?.let { ((now - it) / 1000).coerceAtLeast(0) }
    Surface(onClick = onClick, color = CALL_GREEN, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GngIcon(if (call.video) GngIcons.Videocam else GngIcons.Call, null, tint = Color.White, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "On a call · ${call.title}" + (secs?.let { " · %d:%02d".format(it / 60, it % 60) } ?: ""),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("Return", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

val CALL_GREEN = Color(0xFF1FA855)

@Composable
private fun RequestsHeader(count: Int, open: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            GngIcon(GngIcons.Mail, null, tint = scheme.onSecondaryContainer)
        }
        Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
            Text("Message requests", style = MaterialTheme.typography.titleMedium)
            Text(
                "$count ${if (count == 1) "person wants" else "people want"} to message you",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
        }
        GngIcon(if (open) GngIcons.ExpandMore else GngIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}

/** One chat in the list: avatar, name, last message, time, unread count. Flat, like WhatsApp. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowItem(
    row: ConversationRow,
    pinned: Boolean,
    inCall: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onJoinCall: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val conv = row.conversation
    val unread = conv.unreadCount > 0
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conv.isDm) Avatar(row.title, row.other?.avatarUrl, size = 54.dp, colorKey = conv.otherUserId ?: conv.id)
        else GroupAvatar(conv.emoji, conv.photoUrl, size = 54.dp, colorKey = conv.id)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    conv.lastMessageAt?.let { inboxTime(parseInstant(it)) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread && !conv.isMuted) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (unread) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (conv.isMuted) GngIcon(GngIcons.NotificationsOff, "Muted", size = 16.dp, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                if (pinned) GngIcon(GngIcons.PushPinFill, "Pinned", size = 16.dp, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                if (conv.unreadMentions > 0) {
                    Badge(containerColor = scheme.primary, modifier = Modifier.padding(start = 6.dp)) { Text("@") }
                }
                if (unread) {
                    Badge(
                        containerColor = if (conv.isMuted) scheme.outline else scheme.primary,
                        contentColor = if (conv.isMuted) scheme.surface else scheme.onPrimary,
                        modifier = Modifier.padding(start = 6.dp),
                    ) { Text(if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(), modifier = Modifier.padding(horizontal = 2.dp)) }
                }
            }
        }
        if (inCall) {
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onJoinCall,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = CALL_GREEN, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                GngIcon(GngIcons.Call, null, size = 16.dp, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("Join")
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
    onScan: () -> Unit = {},
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
                Choice(GangSheetMode.Join, "Join", GngIcons.QrCodeScanner),
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
            // Scanning a friend's QR code is the quick way in; typing the code still works.
            FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                GngIcon(GngIcons.QrCodeScanner, null, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code")
            }
            Text(
                "or type the code", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
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
