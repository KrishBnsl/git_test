package com.gng.app.ui.chat

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gng.app.data.ChatMessage
import com.gng.app.data.MediaRef
import com.gng.app.data.SendState
import com.gng.app.data.decodeThumb
import com.gng.app.ui.components.EmojiAvatar
import com.gng.app.ui.group.GroupContext
import com.gng.app.ui.theme.friendColor
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** iMessage's tapbacks, plus the two the gang can't live without. */
val TAPBACKS = listOf("❤️", "👍", "👎", "😂", "‼️", "❓", "🔥", "💀")

private val TIME = DateTimeFormatter.ofPattern("h:mm a")

/** How a row sits next to its neighbours: first/last of a run from the same person. */
data class RunPosition(val first: Boolean, val last: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    m: ChatMessage,
    ctx: GroupContext,
    mine: Boolean,
    dark: Boolean,
    run: RunPosition,
    reactions: Map<String, String>,
    highlighted: Boolean,
    receipt: Receipt?,
    media: MediaAccess,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onQuoteTap: (Long) -> Unit,
    onOpenImage: (MediaRef) -> Unit,
    onOpenFile: (MediaRef) -> Unit,
    onRetry: () -> Unit,
) {
    val density = LocalDensity.current
    val maxSwipe = with(density) { 72.dp.toPx() }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val flash by animateFloatAsState(if (highlighted) 1f else 0f, label = "flash")

    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * flash))
            // Swipe right to reply, like Telegram.
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipe.value > maxSwipe * 0.7f) onReply()
                        scope.launch { swipe.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { swipe.snapTo((swipe.value + amount).coerceIn(0f, maxSwipe)) }
                    },
                )
            },
    ) {
        if (swipe.value > 8f) {
            Text(
                "↩️", fontSize = 22.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).alpha((swipe.value / maxSwipe).coerceIn(0f, 1f)),
            )
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(swipe.value.roundToInt(), 0) }
                .padding(top = if (run.first) 6.dp else 1.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine && run.first && !ctx.isDm) {
                Text(
                    ctx.nameOf(m.userId), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                if (!mine && !ctx.isDm) {
                    if (run.last) EmojiAvatar(ctx.emojiOf(m.userId), friendColor(m.userId.orEmpty(), dark), size = 32.dp)
                    else Spacer(Modifier.width(32.dp))
                }
                Box(Modifier.padding(start = 6.dp, end = 6.dp, top = if (reactions.isNotEmpty()) 14.dp else 0.dp)) {
                    val bubbleMods = Modifier.combinedClickable(
                        onClick = {
                            when {
                                m.state == SendState.Failed -> onRetry()
                                m.payload.t == "image" -> m.payload.media?.let(onOpenImage)
                                m.payload.t == "file" -> m.payload.media?.let(onOpenFile)
                            }
                        },
                        onDoubleClick = onDoubleTap,
                        onLongClick = onLongPress,
                    )
                    BubbleBody(m, ctx, mine, dark, run, media, bubbleMods, onQuoteTap)
                    if (reactions.isNotEmpty()) {
                        TapbackBadge(
                            reactions, ctx.me.id,
                            Modifier.align(if (mine) Alignment.TopStart else Alignment.TopEnd)
                                .offset(x = if (mine) (-10).dp else 10.dp, y = (-14).dp),
                        )
                    }
                }
            }
            if (receipt != null) {
                Text(
                    receipt.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (receipt.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (receipt.label.startsWith("Read")) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp).clickable(enabled = receipt.failed, onClick = onRetry),
                )
            }
        }
    }
}

@Composable
private fun BubbleBody(
    m: ChatMessage,
    ctx: GroupContext,
    mine: Boolean,
    dark: Boolean,
    run: RunPosition,
    media: MediaAccess,
    modifier: Modifier,
    onQuoteTap: (Long) -> Unit,
) {
    val p = m.payload
    val time = m.createdAt.atZone(ZoneId.systemDefault()).format(TIME)
    val pendingAlpha = if (m.state == SendState.Sending) 0.7f else 1f

    if (p.t == "sticker" && p.media != null) {
        Column(modifier.alpha(pendingAlpha), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            p.replyTo?.let { QuoteBlock(p.replyUser, p.replyText, ctx, mine) { onQuoteTap(it) } }
            EncryptedImage(p.media, media, Modifier.size(150.dp), ContentScale.Fit)
        }
        return
    }

    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (run.first) big else small, if (run.last) small else small, big)
    } else {
        RoundedCornerShape(if (run.first) big else small, big, big, if (run.last) small else small)
    }
    val container = if (mine) MaterialTheme.colorScheme.primary else friendColor(m.userId.orEmpty(), dark)
    val content = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(color = container, contentColor = content, shape = shape, modifier = modifier.widthIn(max = 290.dp).alpha(pendingAlpha)) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            p.replyTo?.let { id ->
                Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)) {
                    QuoteBlock(p.replyUser, p.replyText, ctx, mine) { onQuoteTap(id) }
                }
            }
            when (p.t) {
                "image" -> p.media?.let { ref ->
                    val ratio = ((ref.w ?: 4).toFloat() / (ref.h ?: 3).coerceAtLeast(1)).coerceIn(0.5f, 2f)
                    EncryptedImage(
                        ref, media,
                        Modifier.widthIn(min = 180.dp, max = 290.dp).aspectRatio(ratio).padding(3.dp).clip(RoundedCornerShape(17.dp)),
                        ContentScale.Crop,
                    )
                }
                "file" -> p.media?.let { FileCard(it) }
                "locked" -> Text(
                    "🔒 Can't decrypt this one (sent before you joined, or to your old phone)",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).alpha(0.8f),
                )
            }
            val text = p.text?.takeIf { p.t == "text" || p.t == "legacy" || p.t == "image" }
            Row(Modifier.padding(start = 14.dp, end = 10.dp, bottom = 6.dp, top = if (text != null && p.t != "image") 8.dp else 2.dp), verticalAlignment = Alignment.Bottom) {
                if (text != null) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    time + if (m.state == SendState.Failed) "  ⚠️" else "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp).alpha(0.7f),
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(userId: String?, text: String?, ctx: GroupContext, mine: Boolean, onClick: () -> Unit) {
    val accent = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .height(IntrinsicSize.Min).widthIn(min = 120.dp, max = 270.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                if (userId == ctx.me.id) "You" else ctx.nameOf(userId),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent,
            )
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TapbackBadge(reactions: Map<String, String>, myId: String, modifier: Modifier) {
    val mineToo = myId in reactions
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (mineToo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).forEach { (emoji, _) ->
                Text(emoji, fontSize = 14.sp)
            }
            if (reactions.size > 1) Text(" ${reactions.size}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FileCard(ref: MediaRef) {
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fileEmoji(ref.mime), fontSize = 30.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(ref.name ?: "File", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(prettySize(ref.size) + " · tap to open", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.75f))
        }
    }
}

/** Shows an encrypted image: blurred thumbnail first, the real thing once it's downloaded and decrypted. */
@Composable
fun EncryptedImage(ref: MediaRef, media: MediaAccess, modifier: Modifier, scale: ContentScale) {
    val file by produceState<File?>(media.cached(ref), ref.sha256) {
        if (value == null) value = runCatching { media.load(ref) }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val f = file
        if (f != null) {
            AsyncImage(model = f, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
        } else {
            val thumb = remember(ref.thumb) { decodeThumb(ref.thumb)?.asImageBitmap() }
            if (thumb != null) {
                Image(
                    thumb, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier),
                )
            }
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

/** Lets bubbles fetch attachments without knowing about the view model. */
interface MediaAccess {
    fun cached(ref: MediaRef): File?
    suspend fun load(ref: MediaRef): File
}

@Composable
fun EventRow(m: ChatMessage, ctx: GroupContext, onJoinCall: (String) -> Unit) {
    val who = m.userId?.takeIf { m.kind != "call" }?.let { "${ctx.emojiOf(it)} ${ctx.nameOf(it)} · " } ?: ""
    val joinable = m.kind == "call" && m.refId != null && m.payload.text?.contains("started") == true
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            who + m.payload.text.orEmpty() + if (joinable) "  · tap to join" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable(enabled = joinable) { m.refId?.let(onJoinCall) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun fileEmoji(mime: String) = when {
    mime.startsWith("image/") -> "🖼️"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    mime == "application/pdf" -> "📕"
    mime.contains("zip") || mime.contains("compressed") -> "🗜️"
    mime.contains("sheet") || mime.contains("excel") -> "📊"
    mime.contains("presentation") || mime.contains("powerpoint") -> "📽️"
    mime.contains("word") || mime.startsWith("text/") -> "📝"
    else -> "📄"
}

fun prettySize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
