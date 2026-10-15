package com.gng.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.gng.app.call.CallActivity
import com.gng.app.container
import com.gng.app.data.ChatMessage
import com.gng.app.data.IST
import com.gng.app.data.MediaRef
import com.gng.app.data.Profile
import com.gng.app.data.SendState
import com.gng.app.data.StickerPack
import com.gng.app.data.StickerStore
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.group.GroupContext
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.stickers.StickerMakerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Everything the chat shows, so [ChatContent] can be previewed without a view model. */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** messageId -> (userId -> emoji) */
    val reactions: Map<Long, Map<String, String>> = emptyMap(),
    val loaded: Boolean = true,
    val reachedStart: Boolean = false,
    val uploads: Int = 0,
    val replyTo: ChatMessage? = null,
    /** Someone you don't share a gang with wants to message you. */
    val incomingRequest: Boolean = false,
    /** You messaged someone outside your gangs and they haven't accepted yet. */
    val outgoingRequest: Boolean = false,
    /** Your message being edited in the composer. */
    val editing: ChatMessage? = null,
)

/** What the chat can do. Defaults do nothing, for previews. */
data class ChatActions(
    /** Text, plus the user ids picked from the @ list. */
    val onSend: (String, List<String>) -> Unit = { _, _ -> },
    val onReply: (ChatMessage?) -> Unit = {},
    val onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    val onRetry: (ChatMessage) -> Unit = {},
    val onDiscard: (ChatMessage) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onPhotos: () -> Unit = {},
    val onFile: () -> Unit = {},
    val onSticker: (File) -> Unit = {},
    val onOpenFile: (MediaRef) -> Unit = {},
    val onSaveMedia: (MediaRef) -> Unit = {},
    val onSaveSticker: (MediaRef) -> Unit = {},
    val onJoinCall: (String) -> Unit = {},
    val onOpenEvent: (ChatMessage) -> Unit = {},
    val onNotice: (String) -> Unit = {},
    val receiptFor: (ChatMessage) -> Receipt = { Receipt("Sent") },
    val detailsFor: (ChatMessage) -> List<Pair<String, String>> = { emptyList() },
    val onAcceptRequest: () -> Unit = {},
    val onDeclineRequest: () -> Unit = {},
    val canEdit: (ChatMessage) -> Boolean = { false },
    val onEdit: (ChatMessage) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val canDeleteForEveryone: (ChatMessage) -> Boolean = { false },
    val onDeleteForEveryone: (ChatMessage) -> Unit = {},
    val onDeleteForMe: (ChatMessage) -> Unit = {},
    /** Exact delivered/seen times per person, for the Info sheet. */
    val loadReceipts: suspend (ChatMessage) -> List<Pair<String, String>> = { emptyList() },
)

@Composable
fun ChatScreen(ctx: GroupContext, onOpenEvent: (ChatMessage) -> Unit, onClosed: () -> Unit = {}) {
    val c = LocalContext.current.container
    val vm = gngViewModel(key = "chat-${ctx.group.id}") { ChatViewModel(it, ctx) }
    val context = LocalContext.current
    MessageEffect(vm.error, vm::dismissError)

    // Read receipts follow whether you can actually see the chat.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.onVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.onVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onVisible(true)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.onVisible(false)
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) vm.sendImages(context, uris)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::sendFile) }

    // "Tap to join" on a call: needs the mic, then opens the call screen.
    var callToJoin by remember { mutableStateOf<String?>(null) }
    fun openCallScreen() = context.startActivity(CallActivity.intent(context, callToJoin ?: "", CallActivity.ACTION_SHOW))
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val id = callToJoin
        if (ok && id != null) vm.joinCall(id, ::openCallScreen) else if (!ok) vm.error = "Calls need the microphone"
    }

    val media = remember(vm) {
        object : MediaAccess {
            override fun cached(ref: MediaRef) = vm.cachedMedia(ref)
            override suspend fun load(ref: MediaRef) = vm.mediaFile(ref)
        }
    }

    ChatContent(
        ctx = ctx,
        state = ChatUiState(
            vm.messages, vm.reactions, vm.loaded, vm.reachedStart, vm.uploads, vm.replyTo,
            incomingRequest = vm.incomingRequest, outgoingRequest = vm.outgoingRequest,
            editing = vm.editing,
        ),
        media = media,
        stickers = c.stickers,
        actions = ChatActions(
            onSend = vm::sendText,
            onReply = { vm.replyTo = it },
            onReact = vm::react,
            onRetry = { vm.retry(it) },
            onDiscard = { vm.discard(it) },
            onLoadOlder = vm::loadOlder,
            onPhotos = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFile = { pickFile.launch(arrayOf("*/*")) },
            onSticker = vm::sendSticker,
            onOpenFile = { vm.openFile(context, it) },
            onSaveMedia = { vm.saveMedia(it) },
            onSaveSticker = { vm.saveSticker(it) },
            onJoinCall = { callId ->
                callToJoin = callId
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.joinCall(callId, ::openCallScreen)
                } else {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onOpenEvent = onOpenEvent,
            onNotice = { vm.error = it },
            receiptFor = vm::receiptFor,
            detailsFor = vm::detailsFor,
            onAcceptRequest = vm::acceptRequest,
            onDeclineRequest = { vm.declineRequest(onClosed) },
            canEdit = vm::canEdit,
            onEdit = vm::startEdit,
            onCancelEdit = vm::cancelEdit,
            canDeleteForEveryone = vm::canDeleteForEveryone,
            onDeleteForEveryone = { vm.deleteForEveryone(it) },
            onDeleteForMe = { vm.deleteForMe(it) },
            loadReceipts = vm::exactReceipts,
        ),
    )
}

private sealed interface ChatRow {
    val key: Any

    data class Day(val label: String, val epochDay: Long) : ChatRow { override val key: Any = "d$epochDay" }
    data class Msg(val m: ChatMessage, val run: RunPosition, val showReceipt: Boolean) : ChatRow {
        // Optimistic and confirmed copies share a client id, so the row doesn't jump when it's sent.
        override val key: Any get() = m.clientId ?: m.id
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM")

/** Groups messages into runs (same person, within 3 minutes) and adds day separators. */
private fun buildRows(messages: List<ChatMessage>, myId: String, today: LocalDate): List<ChatRow> {
    val lastMine = messages.lastOrNull { it.userId == myId && it.isUserMessage && !it.isPending }?.id
    val out = ArrayList<ChatRow>(messages.size + 8)
    var lastDay: LocalDate? = null
    messages.forEachIndexed { i, m ->
        val day = m.createdAt.atZone(IST).toLocalDate()
        if (day != lastDay) {
            out += ChatRow.Day(
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(DAY)
                },
                day.toEpochDay(),
            )
            lastDay = day
        }
        fun joins(a: ChatMessage?, b: ChatMessage?) = a != null && b != null && a.isUserMessage && b.isUserMessage &&
            a.userId == b.userId && kotlin.math.abs(a.createdAt.epochSecond - b.createdAt.epochSecond) < 180 &&
            a.createdAt.atZone(IST).toLocalDate() == b.createdAt.atZone(IST).toLocalDate()
        out += ChatRow.Msg(
            m,
            RunPosition(first = !joins(messages.getOrNull(i - 1), m), last = !joins(m, messages.getOrNull(i + 1))),
            showReceipt = m.id == lastMine,
        )
    }
    return out
}

@Composable
fun ChatContent(
    ctx: GroupContext,
    state: ChatUiState,
    media: MediaAccess,
    stickers: StickerStore?,
    actions: ChatActions,
    today: LocalDate = LocalDate.now(IST),
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    // People picked from the @ list while writing this message.
    val tagged = remember { mutableStateListOf<String>() }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var viewing by remember { mutableStateOf<MediaRef?>(null) }
    var highlighted by remember { mutableStateOf<Long?>(null) }
    var stickersOpen by rememberSaveable { mutableStateOf(false) }

    val rows = remember(state.messages, today) { buildRows(state.messages, ctx.me.id, today) }
    val reversed = remember(rows) { rows.asReversed() }

    // Stick to the bottom when something new arrives and you're already there (or you sent it).
    // Otherwise count it on the "jump to latest" button.
    val newest = state.messages.lastOrNull()
    var unseen by remember { mutableIntStateOf(0) }
    LaunchedEffect(newest?.clientId ?: newest?.id) {
        if (newest == null) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 2 || newest.userId == ctx.me.id) listState.animateScrollToItem(0)
        else if (newest.isUserMessage) unseen++
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { if (it <= 2) unseen = 0 }
    }
    // Replying: put the cursor in the box, like WhatsApp.
    LaunchedEffect(state.replyTo?.clientId ?: state.replyTo?.id) {
        if (state.replyTo != null && ctx.canSendMessages && !stickersOpen) {
            runCatching { composerFocus.requestFocus() }
            keyboard?.show()
        }
    }
    BackHandler(enabled = stickersOpen) { stickersOpen = false }
    // Editing: the message's text goes into the box, cursor at the end.
    LaunchedEffect(state.editing?.id) {
        val target = state.editing ?: return@LaunchedEffect
        val text = target.payload.text.orEmpty()
        draft = TextFieldValue(text, TextRange(text.length))
        stickersOpen = false
        runCatching { composerFocus.requestFocus() }
        keyboard?.show()
    }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    // Infinite scroll upwards.
    val nearTop by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 8 }
    }
    LaunchedEffect(nearTop, rows.size) { if (nearTop) actions.onLoadOlder() }
    val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }

    fun jumpTo(id: Long) {
        val index = reversed.indexOfFirst { it is ChatRow.Msg && it.m.id == id }
        if (index < 0) {
            actions.onNotice("That message is further back, scroll up to load it")
            return
        }
        scope.launch {
            listState.animateScrollToItem(index)
            highlighted = id
            delay(1500)
            highlighted = null
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (state.loaded && state.messages.isEmpty()) {
                EmptyState(
                    if (ctx.isDm) GngIcons.ChatBubble else GngIcons.Forum,
                    if (ctx.isDm) "Say hi to ${ctx.title.substringBefore(' ')}" else "It's quiet in here",
                    "Messages here are end-to-end encrypted. Only the people in this chat can read them.",
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
                items(reversed, key = { it.key }, contentType = { if (it is ChatRow.Day) 0 else 1 }) { row ->
                    when (row) {
                        is ChatRow.Day -> DaySeparator(row.label)
                        is ChatRow.Msg -> {
                            val m = row.m
                            if (!m.isUserMessage) {
                                EventRow(m, ctx, actions.onOpenEvent, actions.onJoinCall)
                            } else {
                                val mine = m.userId == ctx.me.id
                                MessageBubble(
                                    m = m, ctx = ctx, mine = mine, run = row.run,
                                    reactions = state.reactions[m.id].orEmpty(),
                                    highlighted = highlighted == m.id,
                                    receipt = if (mine && (row.showReceipt || m.state != SendState.Sent)) actions.receiptFor(m) else null,
                                    media = media,
                                    onReply = { actions.onReply(m) },
                                    onLongPress = { actionsFor = m },
                                    onDoubleTap = { actions.onReact(m, "❤️") },
                                    onQuoteTap = ::jumpTo,
                                    onOpenImage = { viewing = it },
                                    onOpenFile = actions.onOpenFile,
                                    onRetry = { actions.onRetry(m) },
                                    onShowReactions = { reactionsFor = m },
                                )
                            }
                        }
                    }
                }
                if (state.reachedStart && state.messages.isNotEmpty()) {
                    item(key = "start") { EncryptedNotice() }
                }
            }
            androidx.compose.animation.AnimatedVisibility(scrolledUp, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 99) "99+" else "$unseen") } }) {
                    SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                        GngIcon(GngIcons.ExpandMore, if (unseen > 0) "$unseen new messages" else "Latest")
                    }
                }
            }
        }

        if (state.uploads > 0) {
            Text(
                "Encrypting and uploading ${state.uploads}…", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        state.replyTo?.let { target -> ReplyBanner(target, ctx, onClose = { actions.onReply(null) }) }
        state.editing?.let { target ->
            EditBanner(target, onClose = {
                actions.onCancelEdit()
                draft = TextFieldValue("")
            })
        }
        if (state.outgoingRequest) RequestNote("Message request sent. ${ctx.title.substringBefore(' ')} sees your messages and can accept.")
        if (state.incomingRequest) {
            RequestBar(ctx, actions.onAcceptRequest, actions.onDeclineRequest)
        } else if (ctx.canSendMessages) {
            // "@ar" right before the cursor: suggest people to tag (gangs only).
            val query = if (ctx.isDm) null else mentionQuery(draft)
            val suggestions = remember(query, ctx.members) {
                query?.let { q ->
                    ctx.members.values.filter { it.id != ctx.me.id && it.displayName.contains(q.text, ignoreCase = true) }
                        .sortedBy { !it.displayName.startsWith(q.text, ignoreCase = true) }
                        .take(5)
                }.orEmpty()
            }
            if (query != null && suggestions.isNotEmpty()) {
                MentionSuggestions(suggestions) { person ->
                    val insert = "@${person.displayName} "
                    val text = draft.text.replaceRange(query.start, draft.selection.start, insert)
                    draft = TextFieldValue(text, TextRange(query.start + insert.length))
                    tagged += person.id
                }
            }
            Composer(
                draft = draft,
                onDraft = { if (it.text.length <= 4000) draft = it },
                placeholder = if (ctx.isDm) "Message ${ctx.title.substringBefore(' ')}" else "Message the gang",
                stickersOpen = stickersOpen,
                editing = state.editing != null,
                focusRequester = composerFocus,
                onToggleStickers = {
                    // Stickers and the keyboard take the same space: show one or the other.
                    if (!stickersOpen) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                    stickersOpen = !stickersOpen
                },
                onFocused = { stickersOpen = false },
                onPhotos = actions.onPhotos,
                onFile = actions.onFile,
                onSend = {
                    actions.onSend(draft.text, tagged.toList())
                    draft = TextFieldValue("")
                    tagged.clear()
                },
            )
            if (stickers != null) {
                AnimatedVisibility(stickersOpen) {
                    StickerPanel(stickers, onSend = { actions.onSticker(it); stickersOpen = false })
                }
            }
        } else {
            ReadOnlyBar()
        }
    }

    actionsFor?.let { m ->
        MessageActionsSheet(
            m = m, ctx = ctx, myReaction = state.reactions[m.id]?.get(ctx.me.id),
            onDismiss = { actionsFor = null },
            onReact = { actions.onReact(m, it); actionsFor = null },
            onReply = { actions.onReply(m); actionsFor = null },
            onInfo = { infoFor = m; actionsFor = null },
            reactionCount = state.reactions[m.id].orEmpty().size,
            onShowReactions = { reactionsFor = m; actionsFor = null },
            onSaveMedia = { m.payload.media?.let(actions.onSaveMedia); actionsFor = null },
            onSaveSticker = { m.payload.media?.let(actions.onSaveSticker); actionsFor = null },
            onRetry = { actions.onRetry(m); actionsFor = null },
            onDiscard = { actions.onDiscard(m); actionsFor = null },
            canEdit = actions.canEdit(m),
            onEdit = { actions.onEdit(m); actionsFor = null },
            onDelete = { deleting = m; actionsFor = null },
        )
    }
    deleting?.let { m ->
        DeleteDialog(
            forEveryone = actions.canDeleteForEveryone(m),
            onDismiss = { deleting = null },
            onDeleteForEveryone = { actions.onDeleteForEveryone(m); deleting = null },
            onDeleteForMe = { actions.onDeleteForMe(m); deleting = null },
        )
    }
    reactionsFor?.let { m ->
        val forMessage = state.reactions[m.id].orEmpty()
        if (forMessage.isEmpty()) {
            // Everyone took theirs back.
            LaunchedEffect(m.id) { reactionsFor = null }
        } else {
            ReactionsSheet(
                ctx, forMessage,
                onRemoveMine = { forMessage[ctx.me.id]?.let { actions.onReact(m, it) } },
                onDismiss = { reactionsFor = null },
            )
        }
    }
    infoFor?.let { m ->
        // Rough statuses straight away, exact times once the server answers.
        val details by produceState(actions.detailsFor(m), m.id) { value = actions.loadReceipts(m).ifEmpty { value } }
        InfoSheet(m, ctx, actions.receiptFor(m), details, state.reactions[m.id].orEmpty(), onDismiss = { infoFor = null })
    }
    viewing?.let { ref -> ImageViewer(ref, media, onSave = { actions.onSaveMedia(ref) }, onDismiss = { viewing = null }) }
}

@Composable
private fun EncryptedNotice() {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        GngIcon(GngIcons.Encrypted, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Start of the chat · end-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReplyBanner(target: ChatMessage, ctx: GroupContext, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GngIcon(GngIcons.Reply, null, tint = scheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "Replying to " + if (target.userId == ctx.me.id) "yourself" else ctx.nameOf(target.userId),
                    style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                )
                Text(target.payload.preview(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { GngIcon(GngIcons.Close, "Cancel reply") }
        }
    }
}

@Composable
private fun RequestNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** Instead of the input on a message request: who it's from, and accept or decline. */
@Composable
private fun RequestBar(ctx: GroupContext, onAccept: () -> Unit, onDecline: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val other = ctx.other
    Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${ctx.title} wants to message you", style = MaterialTheme.typography.titleSmall)
            val line = listOfNotNull(other?.handle, other?.college).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(
                "You don't share a gang. They won't know you've seen this unless you accept.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Decline") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun EditBanner(target: ChatMessage, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GngIcon(GngIcons.Edit, null, tint = scheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Editing message", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(target.payload.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { GngIcon(GngIcons.Close, "Cancel edit") }
        }
    }
}

/** WhatsApp's choice: gone for everyone (your messages, for 2 days), or just hidden on this phone. */
@Composable
private fun DeleteDialog(forEveryone: Boolean, onDismiss: () -> Unit, onDeleteForEveryone: () -> Unit, onDeleteForMe: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { GngIcon(GngIcons.Delete, null) },
        title = { Text("Delete message?") },
        text = {
            Text(
                if (forEveryone) "Delete for everyone removes it from everyone's phones and leaves a note that it was deleted."
                else "It'll be removed from this phone only.",
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (forEveryone) TextButton(onClick = onDeleteForEveryone) { Text("Delete for everyone") }
                TextButton(onClick = onDeleteForMe) { Text("Delete for me") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
            Text("Only admins can send messages in this gang", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    placeholder: String,
    stickersOpen: Boolean,
    onToggleStickers: () -> Unit,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocused: () -> Unit = {},
    editing: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var attach by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box {
            IconButton(onClick = { attach = true }, modifier = Modifier.padding(bottom = 4.dp)) { GngIcon(GngIcons.Add, "Attach") }
            DropdownMenu(attach, onDismissRequest = { attach = false }) {
                DropdownMenuItem(
                    text = { Text("Photos") }, leadingIcon = { GngIcon(GngIcons.PhotoLibrary, null) },
                    onClick = { attach = false; onPhotos() },
                )
                DropdownMenuItem(
                    text = { Text("File") }, leadingIcon = { GngIcon(GngIcons.AttachFile, null) },
                    onClick = { attach = false; onFile() },
                )
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(placeholder) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    IconButton(onClick = onToggleStickers) {
                        GngIcon(GngIcons.Mood, if (stickersOpen) "Hide stickers" else "Stickers", tint = if (stickersOpen) scheme.primary else scheme.onSurfaceVariant)
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocused() },
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = draft.text.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp)) {
            if (editing) GngIcon(GngIcons.Check, "Save edit") else GngIcon(GngIcons.Send, "Send")
        }
    }
}

/** The "@query" being typed right before the cursor, if any. */
private data class MentionQuery(val start: Int, val text: String)

private fun mentionQuery(value: TextFieldValue): MentionQuery? {
    val cursor = value.selection.start
    if (!value.selection.collapsed || cursor <= 0) return null
    val before = value.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0 || (at > 0 && !before[at - 1].isWhitespace())) return null
    val q = before.substring(at + 1)
    // Names have spaces ("Aarav Mehta"), so allow one, but not a new line or a long sentence.
    if (q.length > 30 || '\n' in q || q.count { it == ' ' } > 1) return null
    return MentionQuery(at, q)
}

@Composable
private fun MentionSuggestions(people: List<Profile>, onPick: (Profile) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            people.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.displayName, p.avatarUrl, size = 32.dp, colorKey = p.id)
                    Text(p.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerPanel(store: StickerStore, onSend: (File) -> Unit) {
    val packs by store.packs.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var linked by remember { mutableStateOf(store.hasWhatsAppSource) }
    LaunchedEffect(Unit) {
        store.load()
        // WhatsApp's folder was picked before: pull in anything new, no taps needed.
        store.syncWhatsApp()?.let { if (it.added > 0) message = it.describe() }
    }

    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        message = "Looking for stickers…"
        scope.launch {
            message = runCatching { store.importWhatsAppFolder(tree) }
                .onSuccess { if (it.added > 0) selected = "whatsapp" }
                .fold({ it.describe() }, { it.message ?: "Couldn't read that folder" })
            linked = store.hasWhatsAppSource
        }
    }
    // Make your own: pick a photo, then cut out / frame / caption it.
    var making by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickForSticker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> making = uri }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        message = "Importing…"
        scope.launch { message = runCatching { store.importFiles(uris) }.fold({ it.describe() }, { it.message ?: "Couldn't import that" }) }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().height(300.dp)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(packs, key = { it.id }) { pack: StickerPack ->
                    FilterChip(selected = (selected ?: packs.firstOrNull()?.id) == pack.id, onClick = { selected = pack.id }, label = { Text(pack.name) })
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            if (linked) {
                                message = "Checking WhatsApp for new stickers…"
                                scope.launch { message = store.syncWhatsApp()?.describe() ?: "Couldn't read WhatsApp's folder" }
                            } else {
                                message = "Tap \"Use this folder\" on the next screen to bring in all your WhatsApp stickers"
                                importFolder.launch(StickerStore.WHATSAPP_STICKERS_URI)
                            }
                        },
                        label = { Text(if (linked) "Sync WhatsApp" else "From WhatsApp") },
                        leadingIcon = { GngIcon(if (linked) GngIcons.Sync else GngIcons.Add, null, size = 18.dp) },
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { pickForSticker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        label = { Text("Make sticker") }, leadingIcon = { GngIcon(GngIcons.AutoAwesome, null, size = 18.dp) },
                    )
                }
                item {
                    FilterChip(
                        selected = false, onClick = { importFiles.launch(arrayOf("application/zip", "application/octet-stream", "image/*")) },
                        label = { Text("Pack or images") }, leadingIcon = { GngIcon(GngIcons.UploadFile, null, size = 18.dp) },
                    )
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            val pack = packs.firstOrNull { it.id == selected } ?: packs.firstOrNull()
            if (pack == null) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No stickers yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap \"From WhatsApp\", then \"Use this folder\": all your WhatsApp (and WhatsApp Business) stickers come in, " +
                            "and new ones keep coming automatically. Or make your own, import a .wastickers pack, or long-press " +
                            "any sticker in a chat to save it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(76.dp), contentPadding = PaddingValues(8.dp)) {
                    items(pack.stickers, key = { it.absolutePath }) { file ->
                        AsyncImage(
                            model = file, contentDescription = "Sticker", contentScale = ContentScale.Fit,
                            modifier = Modifier.size(76.dp).padding(4.dp).clip(RoundedCornerShape(8.dp))
                                .combinedClickable(onClick = { onSend(file) }, onLongClick = { deleting = file }),
                        )
                    }
                }
            }
        }
    }
    making?.let { uri ->
        StickerMakerDialog(uri, onDismiss = { making = null }) { webp, send ->
            making = null
            scope.launch {
                runCatching { store.saveMade(webp) }
                    .onSuccess { file ->
                        selected = "mine"
                        message = "Saved to My stickers"
                        if (send) onSend(file)
                    }
                    .onFailure { message = it.message ?: "Couldn't save that sticker" }
            }
        }
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { GngIcon(GngIcons.Delete, null) },
            title = { Text("Remove this sticker?") },
            confirmButton = { TextButton(onClick = { scope.launch { store.delete(file) }; deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MessageActionsSheet(
    m: ChatMessage,
    ctx: GroupContext,
    myReaction: String?,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    reactionCount: Int = 0,
    onShowReactions: () -> Unit = {},
    onSaveMedia: () -> Unit,
    onSaveSticker: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val deleted = m.payload.isDeleted
    val scheme = MaterialTheme.colorScheme
    var pickAny by remember { mutableStateOf(false) }
    GngSheet(title = m.payload.preview().take(60).ifBlank { "Message" }, onDismiss = onDismiss) {
        if (!m.isPending && !deleted) {
            // Tapbacks, one per person: the usual ones, whatever you reacted with, and + for any emoji.
            val quick = (listOfNotNull(myReaction?.takeIf { it !in QUICK_REACTIONS }) + QUICK_REACTIONS).take(QUICK_REACTIONS.size)
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    quick.forEach { e ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                                .background(if (e == myReaction) scheme.secondaryContainer else Color.Transparent)
                                .clickable { onReact(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).clickable { pickAny = true },
                        contentAlignment = Alignment.Center,
                    ) { GngIcon(GngIcons.AddReaction, "Any emoji", tint = scheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (m.state == SendState.Failed) {
            SheetAction(GngIcons.Refresh, "Try again", onRetry)
            SheetAction(GngIcons.Delete, "Delete", onDiscard)
        }
        if (!m.isPending && !deleted) SheetAction(GngIcons.Reply, "Reply", onReply)
        if (canEdit) SheetAction(GngIcons.Edit, "Edit", onEdit)
        if (reactionCount > 0 && !deleted) SheetAction(GngIcons.AddReaction, "Reactions ($reactionCount)", onShowReactions)
        m.payload.text?.takeIf { it.isNotBlank() && !deleted }?.let { text ->
            SheetAction(GngIcons.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(text)); onDismiss() }
        }
        if (m.payload.t == "image") SheetAction(GngIcons.Download, "Save to gallery", onSaveMedia)
        if (m.payload.t == "sticker" && m.userId != ctx.me.id) SheetAction(GngIcons.Star, "Save sticker", onSaveSticker)
        if (m.userId == ctx.me.id && !m.isPending && !deleted) SheetAction(GngIcons.Info, "Info", onInfo)
        if (m.state != SendState.Failed) SheetAction(GngIcons.Delete, "Delete", onDelete)
    }
    if (pickAny) EmojiPickerSheet(onDismiss = { pickAny = false }) { pickAny = false; onReact(it) }
}

/** The tapbacks one tap away; anything else is under +. */
private val QUICK_REACTIONS = listOf("❤️", "😂", "👍", "😮", "😢", "🔥", "🙏")

/** Every emoji, with categories, recents and skin tones (AndroidX's picker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = 8
                    setOnEmojiPickedListener { onPick(it.emoji) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(420.dp).nestedScroll(rememberNestedScrollInteropConnection()),
        )
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { GngIcon(icon, null) },
        headlineContent = { Text(label) },
    )
}

/** Who reacted with what, WhatsApp style: a tab per emoji, and tap your own to take it back. */
@Composable
private fun ReactionsSheet(
    ctx: GroupContext,
    reactions: Map<String, String>,
    onRemoveMine: () -> Unit,
    onDismiss: () -> Unit,
) {
    val byEmoji = remember(reactions) {
        reactions.entries.groupBy({ it.value }, { it.key }).entries.sortedByDescending { it.value.size }
    }
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = reactions.entries
        .filter { filter == null || it.value == filter }
        // You first, then everyone else by name.
        .sortedWith(compareBy({ it.key != ctx.me.id }, { ctx.nameOf(it.key).lowercase() }))
    GngSheet(title = "Reactions", onDismiss = onDismiss) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            item {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${reactions.size}") })
            }
            items(byEmoji, key = { it.key }) { (emoji, people) ->
                FilterChip(
                    selected = filter == emoji,
                    onClick = { filter = if (filter == emoji) null else emoji },
                    label = { Text("$emoji ${people.size}") },
                )
            }
        }
        shown.forEach { (uid, emoji) ->
            val person = ctx.person(uid)
            val mine = uid == ctx.me.id
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = if (mine) Modifier.clickable { onRemoveMine(); onDismiss() } else Modifier,
                leadingContent = { Avatar(person.name, person.photoUrl, size = 40.dp, colorKey = person.key) },
                headlineContent = { Text(if (mine) "You" else person.name) },
                supportingContent = if (mine) ({ Text("Tap to remove") }) else null,
                trailingContent = { Text(emoji, fontSize = 22.sp) },
            )
        }
    }
}

@Composable
private fun InfoSheet(
    m: ChatMessage,
    ctx: GroupContext,
    receipt: Receipt,
    details: List<Pair<String, String>>,
    reactions: Map<String, String>,
    onDismiss: () -> Unit,
) {
    GngSheet(title = "Message info", onDismiss = onDismiss) {
        Text(receipt.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        details.forEach { (uid, status) ->
            val person = ctx.person(uid)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Avatar(person.name, person.photoUrl, size = 40.dp, colorKey = person.key) },
                headlineContent = { Text(person.name) },
                supportingContent = { Text(status) },
                trailingContent = { reactions[uid]?.let { Text(it, fontSize = 20.sp) } },
            )
        }
    }
}

@Composable
private fun ImageViewer(ref: MediaRef, media: MediaAccess, onSave: () -> Unit, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            EncryptedImage(
                ref, media,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX = if (scale > 1f) offsetX + pan.x else 0f
                            offsetY = if (scale > 1f) offsetY + pan.y else 0f
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                ContentScale.Fit,
            )
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                FilledTonalButton(onClick = onSave) {
                    GngIcon(GngIcons.Download, null, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { GngIcon(GngIcons.Close, "Close", tint = Color.White) }
            }
        }
    }
}
