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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gng.app.data.ChatMessage
import com.gng.app.data.IST
import com.gng.app.data.MediaRef
import com.gng.app.data.SendState
import com.gng.app.data.decodeThumb
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.group.GroupContext
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.theme.avatarColors
import com.gng.app.ui.theme.pickAvatarColor
import kotlinx.coroutines.launch
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** iMessage's tapbacks, plus the two the gang can't live without. */
val TAPBACKS = listOf("❤️", "👍", "👎", "😂", "‼️", "❓", "🔥", "💀")

private val TIME = DateTimeFormatter.ofPattern("h:mm a")

/** How a bubble sits next to its neighbours: first/last of a run from the same person. */
data class RunPosition(val first: Boolean, val last: Boolean)

/** Lets bubbles fetch attachments without knowing about the view model. */
interface MediaAccess {
    fun cached(ref: MediaRef): File?
    suspend fun load(ref: MediaRef): File
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: ChatMessage,
    ctx: GroupContext,
    mine: Boolean,
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
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { avatarColors(scheme) }
    val oneToOne = ctx.isDm
    val person = ctx.person(m.userId)
    val density = LocalDensity.current
    val maxSwipe = with(density) { 72.dp.toPx() }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val flash by animateFloatAsState(if (highlighted) 1f else 0f, label = "flash")

    Box(
        Modifier
            .fillMaxWidth()
            .background(scheme.primary.copy(alpha = 0.10f * flash), RoundedCornerShape(12.dp))
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
            TonalIcon(
                GngIcons.Reply, size = 32.dp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).alpha((swipe.value / maxSwipe).coerceIn(0f, 1f)),
            )
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(swipe.value.roundToInt(), 0) }.padding(top = if (run.first) 8.dp else 0.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine && run.first && !oneToOne) {
                Text(
                    person.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = pickAvatarColor(palette, person.key).content,
                    modifier = Modifier.padding(start = 52.dp, bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (!mine && !oneToOne) {
                    if (run.last) Avatar(person.name, person.photoUrl, size = 32.dp, colorKey = person.key)
                    else Spacer(Modifier.width(32.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.padding(top = if (reactions.isNotEmpty()) 14.dp else 0.dp)) {
                    val clicks = Modifier.combinedClickable(
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
                    BubbleBody(m, ctx, mine, run, media, clicks, onQuoteTap)
                    if (reactions.isNotEmpty()) {
                        TapbackBadge(
                            reactions, ctx.me.id,
                            Modifier.align(if (mine) Alignment.TopStart else Alignment.TopEnd)
                                .offset(x = if (mine) (-10).dp else 10.dp, y = (-14).dp),
                        )
                    }
                }
            }
            // Time under the last bubble of a run; for your latest message, its status too.
            if (run.last || receipt != null) {
                val time = m.createdAt.atZone(IST).format(TIME).lowercase()
                Row(
                    Modifier.padding(start = if (mine || oneToOne) 6.dp else 46.dp, end = 6.dp, top = 3.dp)
                        .clickable(enabled = receipt?.failed == true, onClick = onRetry),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (receipt?.failed == true) {
                        GngIcon(GngIcons.Error, null, size = 14.dp, tint = scheme.error)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (receipt == null) time else if (receipt.failed || receipt.label == "Sending…") receipt.label else "$time · ${receipt.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (receipt?.failed == true) scheme.error else scheme.onSurfaceVariant,
                        fontWeight = if (receipt?.label?.startsWith("Read") == true) FontWeight.SemiBold else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(
    m: ChatMessage,
    ctx: GroupContext,
    mine: Boolean,
    run: RunPosition,
    media: MediaAccess,
    modifier: Modifier,
    onQuoteTap: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val p = m.payload
    val pendingAlpha = if (m.state == SendState.Sending) 0.65f else 1f

    if (p.t == "sticker" && p.media != null) {
        Column(modifier.alpha(pendingAlpha), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            p.replyTo?.let { id -> QuoteBlock(p.replyUser, p.replyText, ctx, mine) { onQuoteTap(id) } }
            EncryptedImage(p.media, media, Modifier.size(150.dp), ContentScale.Fit)
        }
        return
    }

    val big = 20.dp
    val tight = 5.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (run.first) big else tight, tight, big)
    } else {
        RoundedCornerShape(if (run.first) big else tight, big, big, tight)
    }
    val taggedMe = !mine && p.mentions(ctx.me.id)
    Surface(
        color = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (mine) scheme.onPrimaryContainer else scheme.onSurface,
        shape = shape,
        // A message that tags you stands out.
        border = if (taggedMe) BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = modifier.widthIn(max = 300.dp).alpha(pendingAlpha),
    ) {
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
                        Modifier.widthIn(min = 180.dp, max = 300.dp).aspectRatio(ratio).padding(3.dp).clip(RoundedCornerShape(17.dp)),
                        ContentScale.Crop,
                    )
                }
                "file" -> p.media?.let { FileRow(it) }
                "locked" -> Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    GngIcon(GngIcons.Lock, null, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Can't decrypt this one (sent before you joined, or to your old phone)",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.8f),
                    )
                }
            }
            val text = p.text?.takeIf { p.t == "text" || p.t == "legacy" || p.t == "image" }
            if (text != null) {
                Text(
                    withMentions(text, p.mentions, ctx, mine),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Highlights each "@Name" that really is a tag (not just someone typing an @). */
@Composable
private fun withMentions(text: String, mentions: List<String>?, ctx: GroupContext, mine: Boolean): AnnotatedString {
    if (mentions.isNullOrEmpty()) return AnnotatedString(text)
    val scheme = MaterialTheme.colorScheme
    return remember(text, mentions, ctx.members, scheme) {
        val spans = mentions.mapNotNull { id ->
            val name = ctx.members[id]?.displayName ?: return@mapNotNull null
            val needle = "@$name"
            generateSequence(text.indexOf(needle)) { prev -> text.indexOf(needle, prev + needle.length).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }
                .map { start -> Triple(start, start + needle.length, id == ctx.me.id) }
                .toList()
        }.flatten().sortedBy { it.first }
        buildAnnotatedString {
            append(text)
            spans.forEach { (start, end, isMe) ->
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = if (mine) scheme.onPrimaryContainer else scheme.primary,
                        background = if (isMe) scheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Unspecified,
                    ),
                    start, end,
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(userId: String?, text: String?, ctx: GroupContext, mine: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (mine) scheme.primary else scheme.tertiary
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .clickable(onClick = onClick).height(IntrinsicSize.Min).widthIn(min = 120.dp, max = 280.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (myId in reactions) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).forEach { (emoji, _) ->
                Text(emoji, fontSize = 14.sp)
            }
            if (reactions.size > 1) Text(" ${reactions.size}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FileRow(ref: MediaRef) {
    Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TonalIcon(if (ref.mime == "application/pdf") GngIcons.PictureAsPdf else GngIcons.Description, size = 40.dp)
        Column(Modifier.padding(start = 12.dp)) {
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

/** Server-made rows: joins, plans, exams, new notes, calls. */
@Composable
fun EventRow(m: ChatMessage, ctx: GroupContext, onOpen: (ChatMessage) -> Unit, onJoinCall: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val body = m.payload.text.orEmpty()
    if (m.kind == "system") {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(body, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val joinable = m.kind == "call" && m.refId != null && "started" in body
    val (icon, label) = when (m.kind) {
        "plan" -> GngIcons.EventFill to "New plan"
        "exam" -> GngIcons.HourglassTop to "Exam added"
        "call" -> (if ("video" in body.lowercase()) GngIcons.Videocam else GngIcons.Call) to if (joinable) "Tap to join" else "Call"
        else -> GngIcons.Description to "New in the stack"
    }
    Surface(
        onClick = { if (m.kind == "call") m.refId?.takeIf { joinable }?.let(onJoinCall) else onOpen(m) },
        enabled = m.kind != "call" || joinable,
        shape = MaterialTheme.shapes.large,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalIcon(icon, container = scheme.tertiary, content = scheme.onTertiary, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (m.kind == "call") label else "$label · ${ctx.nameOf(m.userId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (m.kind != "call" || joinable) GngIcon(GngIcons.ChevronRight, null)
        }
    }
}

@Composable
fun DaySeparator(label: String) {
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

fun prettySize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

