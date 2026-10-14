package com.gng.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import com.gng.app.call.CallActivity
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.gng.app.container
import com.gng.app.data.ChatMessage
import com.gng.app.data.MediaRef
import com.gng.app.data.StickerStore
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.group.GroupContext
import com.gng.app.ui.theme.LocalIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface Row_ {
    val key: Any
    data class Msg(val m: ChatMessage, val run: RunPosition, val showReceipt: Boolean) : Row_ {
        override val key: Any get() = m.clientId ?: m.id
    }
    data class Day(val label: String, val date: LocalDate) : Row_ {
        override val key: Any get() = "day-$date"
    }
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE d MMM")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(ctx: GroupContext) {
    val c = LocalContext.current.container
    val vm = gngViewModel(key = "chat-${ctx.group.id}") { ChatViewModel(it, ctx) }
    val dark = LocalIsDark.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<MediaRef?>(null) }
    var highlighted by remember { mutableStateOf<Long?>(null) }
    var stickersOpen by rememberSaveable { mutableStateOf(false) }

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

    val media = remember(vm) {
        object : MediaAccess {
            override fun cached(ref: MediaRef) = vm.cachedMedia(ref)
            override suspend fun load(ref: MediaRef) = vm.mediaFile(ref)
        }
    }

    val rows = remember(vm.messages) { buildRows(vm.messages, ctx.me.id) }
    val reversed = remember(rows) { rows.asReversed() }

    // Stick to the bottom when something new arrives and you're already there (or you sent it).
    val newest = vm.messages.lastOrNull()
    LaunchedEffect(newest?.clientId ?: newest?.id) {
        if (newest != null && (listState.firstVisibleItemIndex <= 2 || newest.userId == ctx.me.id)) listState.animateScrollToItem(0)
    }
    // Infinite scroll upwards.
    val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearTop, rows.size) { if (nearTop) vm.loadOlder() }
    val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) vm.sendImages(context, uris)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::sendFile)
    }

    var callToJoin by remember { mutableStateOf<String?>(null) }
    fun openCallScreen() = context.startActivity(CallActivity.intent(context, callToJoin ?: "", CallActivity.ACTION_SHOW))
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val id = callToJoin
        if (ok && id != null) vm.joinCall(id, ::openCallScreen) else if (!ok) vm.error = "Calls need the microphone"
    }
    fun joinCall(callId: String) {
        callToJoin = callId
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.joinCall(callId, ::openCallScreen)
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun jumpTo(id: Long) {
        val index = reversed.indexOfFirst { it is Row_.Msg && it.m.id == id }
        if (index < 0) {
            vm.error = "That message is further back, scroll up to load it"
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
        ErrorBanner(vm.error, Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clickable { vm.error = null })
        Box(Modifier.weight(1f)) {
            if (vm.loaded && vm.messages.isEmpty()) {
                EmptyState(
                    if (ctx.isDm) "👋" else "🦗",
                    if (ctx.isDm) "Say hi to ${ctx.title}" else "It's quiet…",
                    "Messages here are end-to-end encrypted 🔒",
                    Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                items(reversed, key = { it.key }, contentType = { if (it is Row_.Day) 0 else 1 }) { row ->
                    when (row) {
                        is Row_.Day -> DayDivider(row.label)
                        is Row_.Msg -> {
                            val m = row.m
                            if (!m.isUserMessage) {
                                EventRow(m, ctx, onJoinCall = ::joinCall)
                            } else {
                                val mine = m.userId == ctx.me.id
                                MessageRow(
                                    m = m, ctx = ctx, mine = mine, dark = dark, run = row.run,
                                    reactions = vm.reactions[m.id].orEmpty(),
                                    highlighted = highlighted == m.id,
                                    receipt = if (mine && (row.showReceipt || m.state != com.gng.app.data.SendState.Sent)) vm.receiptFor(m) else null,
                                    media = media,
                                    onReply = { vm.replyTo = m },
                                    onLongPress = { actionsFor = m },
                                    onDoubleTap = { vm.react(m, "❤️") },
                                    onQuoteTap = ::jumpTo,
                                    onOpenImage = { viewing = it },
                                    onOpenFile = { vm.openFile(context, it) },
                                    onRetry = { vm.retry(m) },
                                )
                            }
                        }
                    }
                }
                if (vm.reachedStart && vm.messages.isNotEmpty()) {
                    item(key = "start") { DayDivider("🔒 Start of the chat · end-to-end encrypted") }
                }
            }
            androidx.compose.animation.AnimatedVisibility(scrolledUp, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Latest")
                }
            }
        }

        if (vm.uploads > 0) {
            Text(
                "Encrypting & uploading ${vm.uploads}…", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        vm.replyTo?.let { target ->
            ReplyBanner(target, ctx, onClose = { vm.replyTo = null })
        }
        Composer(
            draft = draft,
            onDraft = { draft = it.take(4000) },
            stickersOpen = stickersOpen,
            onToggleStickers = { stickersOpen = !stickersOpen },
            onPhoto = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFile = { pickFile.launch(arrayOf("*/*")) },
            onSend = {
                vm.sendText(draft)
                draft = ""
            },
        )
        AnimatedVisibility(stickersOpen) {
            StickerPanel(c.stickers, onSend = { vm.sendSticker(it); stickersOpen = false })
        }
    }

    actionsFor?.let { m ->
        MessageActions(
            m = m, ctx = ctx, myReaction = vm.reactions[m.id]?.get(ctx.me.id),
            onDismiss = { actionsFor = null },
            onReact = { vm.react(m, it); actionsFor = null },
            onReply = { vm.replyTo = m; actionsFor = null },
            onInfo = { infoFor = m; actionsFor = null },
            onSaveMedia = { m.payload.media?.let(vm::saveMedia); actionsFor = null },
            onSaveSticker = { m.payload.media?.let(vm::saveSticker); actionsFor = null },
            onRetry = { vm.retry(m); actionsFor = null },
            onDiscard = { vm.discard(m); actionsFor = null },
        )
    }
    infoFor?.let { m -> InfoSheet(m, ctx, vm, onDismiss = { infoFor = null }) }
    viewing?.let { ref -> ImageViewer(ref, media, onSave = { vm.saveMedia(ref) }, onDismiss = { viewing = null }) }
}

/** Groups messages into runs (same person, within 3 minutes) and adds day dividers. */
private fun buildRows(messages: List<ChatMessage>, myId: String): List<Row_> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val lastMine = messages.lastOrNull { it.userId == myId && it.isUserMessage && !it.isPending }?.id
    val out = ArrayList<Row_>(messages.size + 8)
    var lastDay: LocalDate? = null
    messages.forEachIndexed { i, m ->
        val day = m.createdAt.atZone(zone).toLocalDate()
        if (day != lastDay) {
            out += Row_.Day(
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(DAY_LABEL)
                },
                day,
            )
            lastDay = day
        }
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        fun sameRun(a: ChatMessage?, b: ChatMessage?) = a != null && b != null && a.isUserMessage && b.isUserMessage &&
            a.userId == b.userId && kotlin.math.abs(a.createdAt.epochSecond - b.createdAt.epochSecond) < 180 &&
            a.createdAt.atZone(zone).toLocalDate() == b.createdAt.atZone(zone).toLocalDate()
        out += Row_.Msg(m, RunPosition(first = !sameRun(prev, m), last = !sameRun(m, next)), showReceipt = m.id == lastMine)
    }
    return out
}

@Composable
private fun ReplyBanner(target: ChatMessage, ctx: GroupContext, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    "Replying to " + if (target.userId == ctx.me.id) "yourself" else ctx.nameOf(target.userId),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                Text(target.payload.preview(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel reply") }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    stickersOpen: Boolean,
    onToggleStickers: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onSend: () -> Unit,
) {
    var attach by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { attach = true }) { Text("➕", fontSize = 20.sp) }
            DropdownMenu(attach, onDismissRequest = { attach = false }) {
                DropdownMenuItem(text = { Text("📷  Photos") }, onClick = { attach = false; onPhoto() })
                DropdownMenuItem(text = { Text("📎  File") }, onClick = { attach = false; onFile() })
            }
        }
        TextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text("say something unhinged") },
            modifier = Modifier.weight(1f),
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            trailingIcon = {
                IconButton(onClick = onToggleStickers) { Text(if (stickersOpen) "⌨️" else "😀", fontSize = 20.sp) }
            },
        )
        FilledIconButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
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
        scope.launch {
            message = runCatching { store.importWhatsAppFolder(tree) }
                .fold({ "Added $it stickers from WhatsApp" }, { it.message })
        }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { store.importFiles(uris) }.fold({ "Added $it stickers" }, { it.message }) }
    }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().height(300.dp).navigationBarsPadding()) {
            LazyRow(contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(packs, key = { it.id }) { pack ->
                    FilterChip(selected = (selected ?: packs.firstOrNull()?.id) == pack.id, onClick = { selected = pack.id }, label = { Text(pack.name) })
                }
                item {
                    FilterChip(selected = false, onClick = { importFolder.launch(StickerStore.WHATSAPP_STICKERS_URI) }, label = { Text("➕ From WhatsApp") })
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { importFiles.launch(arrayOf("application/zip", "application/octet-stream", "image/*")) },
                        label = { Text("➕ Pack / images") },
                    )
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp)) }
            val pack = packs.firstOrNull { it.id == selected } ?: packs.firstOrNull()
            if (pack == null) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No stickers yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap \"From WhatsApp\" and pick the WhatsApp Stickers folder it opens, " +
                            "or import a .wastickers pack. You can also long-press any sticker in a chat to save it.",
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove this sticker?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { scope.launch { store.delete(file) }; deleting = null }) { Text("Remove") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (!m.isPending) {
                // Tapback bar
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TAPBACKS.forEach { e ->
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (e == myReaction) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { onReact(e) },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 22.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(m.payload.preview(), maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            HorizontalDivider()
            if (m.state == com.gng.app.data.SendState.Failed) {
                ActionItem("🔁  Try again", onRetry)
                ActionItem("🗑️  Delete", onDiscard)
            }
            if (!m.isPending) ActionItem("↩️  Reply", onReply)
            m.payload.text?.takeIf { it.isNotBlank() }?.let { text ->
                ActionItem("📋  Copy") { clipboard.setText(AnnotatedString(text)); onDismiss() }
            }
            if (m.payload.t == "image") ActionItem("💾  Save to gallery", onSaveMedia)
            if (m.payload.t == "sticker" && m.userId != ctx.me.id) ActionItem("⭐  Save sticker", onSaveSticker)
            if (m.userId == ctx.me.id && !m.isPending) ActionItem("ℹ️  Info", onInfo)
        }
    }
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(m: ChatMessage, ctx: GroupContext, vm: ChatViewModel, onDismiss: () -> Unit) {
    val reactions = vm.reactions[m.id].orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Message info", style = MaterialTheme.typography.titleLarge)
            Text(vm.receiptFor(m).label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
            vm.detailsFor(m).forEach { (uid, status) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.emojiOf(uid), fontSize = 22.sp)
                    Text(ctx.nameOf(uid), modifier = Modifier.weight(1f).padding(start = 12.dp))
                    reactions[uid]?.let { Text(it, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp)) }
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                OutlinedButton(onClick = onSave) { Text("💾 Save", color = Color.White) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDismiss) { Text("✕", color = Color.White) }
            }
        }
    }
}

