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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
)

/** What the chat can do. Defaults do nothing, for previews. */
data class ChatActions(
    val onSend: (String) -> Unit = {},
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
)

@Composable
fun ChatScreen(ctx: GroupContext, onOpenEvent: (ChatMessage) -> Unit) {
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
        state = ChatUiState(vm.messages, vm.reactions, vm.loaded, vm.reachedStart, vm.uploads, vm.replyTo),
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
    var draft by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<MediaRef?>(null) }
    var highlighted by remember { mutableStateOf<Long?>(null) }
    var stickersOpen by rememberSaveable { mutableStateOf(false) }

    val rows = remember(state.messages, today) { buildRows(state.messages, ctx.me.id, today) }
    val reversed = remember(rows) { rows.asReversed() }

    // Stick to the bottom when something new arrives and you're already there (or you sent it).
    val newest = state.messages.lastOrNull()
    LaunchedEffect(newest?.clientId ?: newest?.id) {
        if (newest != null && (listState.firstVisibleItemIndex <= 2 || newest.userId == ctx.me.id)) listState.animateScrollToItem(0)
    }
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
                SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                    GngIcon(GngIcons.ExpandMore, "Latest")
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
        if (ctx.canSendMessages) {
            Composer(
                draft = draft,
                onDraft = { draft = it.take(4000) },
                placeholder = if (ctx.isDm) "Message ${ctx.title.substringBefore(' ')}" else "Message the gang",
                stickersOpen = stickersOpen,
                onToggleStickers = { stickersOpen = !stickersOpen },
                onPhotos = actions.onPhotos,
                onFile = actions.onFile,
                onSend = {
                    actions.onSend(draft)
                    draft = ""
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
            onSaveMedia = { m.payload.media?.let(actions.onSaveMedia); actionsFor = null },
            onSaveSticker = { m.payload.media?.let(actions.onSaveSticker); actionsFor = null },
            onRetry = { actions.onRetry(m); actionsFor = null },
            onDiscard = { actions.onDiscard(m); actionsFor = null },
        )
    }
    infoFor?.let { m ->
        InfoSheet(m, ctx, actions.receiptFor(m), actions.detailsFor(m), state.reactions[m.id].orEmpty(), onDismiss = { infoFor = null })
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
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    stickersOpen: Boolean,
    onToggleStickers: () -> Unit,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onSend: () -> Unit,
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp)) {
            GngIcon(GngIcons.Send, "Send")
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
    LaunchedEffect(Unit) { store.load() }

    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { store.importWhatsAppFolder(tree) }.fold({ "Added $it stickers from WhatsApp" }, { it.message }) }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { store.importFiles(uris) }.fold({ "Added $it stickers" }, { it.message }) }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().height(300.dp)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(packs, key = { it.id }) { pack: StickerPack ->
                    FilterChip(selected = (selected ?: packs.firstOrNull()?.id) == pack.id, onClick = { selected = pack.id }, label = { Text(pack.name) })
                }
                item {
                    FilterChip(
                        selected = false, onClick = { importFolder.launch(StickerStore.WHATSAPP_STICKERS_URI) },
                        label = { Text("From WhatsApp") }, leadingIcon = { GngIcon(GngIcons.Add, null, size = 18.dp) },
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
                        "Tap \"From WhatsApp\" and pick the WhatsApp Stickers folder it opens, or import a .wastickers pack. " +
                            "You can also long-press any sticker in a chat to save it.",
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
    onSaveMedia: () -> Unit,
    onSaveSticker: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    GngSheet(title = m.payload.preview().take(60).ifBlank { "Message" }, onDismiss = onDismiss) {
        if (!m.isPending) {
            // Tapbacks, one per person.
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TAPBACKS.forEach { e ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (e == myReaction) scheme.secondaryContainer else Color.Transparent)
                                .clickable { onReact(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (m.state == SendState.Failed) {
            SheetAction(GngIcons.Refresh, "Try again", onRetry)
            SheetAction(GngIcons.Delete, "Delete", onDiscard)
        }
        if (!m.isPending) SheetAction(GngIcons.Reply, "Reply", onReply)
        m.payload.text?.takeIf { it.isNotBlank() }?.let { text ->
            SheetAction(GngIcons.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(text)); onDismiss() }
        }
        if (m.payload.t == "image") SheetAction(GngIcons.Download, "Save to gallery", onSaveMedia)
        if (m.payload.t == "sticker" && m.userId != ctx.me.id) SheetAction(GngIcons.Star, "Save sticker", onSaveSticker)
        if (m.userId == ctx.me.id && !m.isPending) SheetAction(GngIcons.Info, "Info", onInfo)
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
