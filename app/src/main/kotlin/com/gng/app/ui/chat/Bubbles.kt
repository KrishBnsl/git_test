package com.gng.app.ui.chat

import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import com.gng.app.ui.people.LocalOpenPerson
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    onShowReactions: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val openPerson = LocalOpenPerson.current
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
                        val before = swipe.value
                        val after = (before + amount).coerceIn(0f, maxSwipe)
                        // A tick when you've pulled far enough to reply.
                        if (before <= maxSwipe * 0.7f && after > maxSwipe * 0.7f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { swipe.snapTo(after) }
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
                    color = nameColor(pickAvatarColor(palette, person.key).container, scheme.surface, scheme.onSurfaceVariant),
                    modifier = Modifier.padding(start = 52.dp, bottom = 2.dp).clickable { m.userId?.let(openPerson) },
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (!mine && !oneToOne) {
                    if (run.last) {
                        Box(Modifier.clip(CircleShape).clickable { m.userId?.let(openPerson) }) {
                            Avatar(person.name, person.photoUrl, size = 32.dp, colorKey = person.key)
                        }
                    }
                    else Spacer(Modifier.width(32.dp))
                    Spacer(Modifier.width(8.dp))
                }
                // Reactions hang off the bottom edge, so they never cover what was written.
                Box(Modifier.padding(bottom = if (reactions.isNotEmpty()) 22.dp else 0.dp)) {
                    val clicks = Modifier.combinedClickable(
                        onClick = {
                            when {
                                m.state == SendState.Failed -> onRetry()
                                m.payload.t == "image" -> m.payload.media?.let(onOpenImage)
                                m.payload.t == "file" -> m.payload.media?.let(onOpenFile)
                            }
                        },
                        onDoubleClick = onDoubleTap,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    BubbleBody(m, ctx, mine, run, media, clicks, onQuoteTap)
                    if (reactions.isNotEmpty()) {
                        TapbackBadge(
                            reactions, ctx.me.id,
                            // The pill is ~22dp tall inside a 48dp touch target: this puts its top just over the bubble's edge.
                            Modifier.align(if (mine) Alignment.BottomEnd else Alignment.BottomStart)
                                .offset(x = if (mine) (-8).dp else 8.dp, y = 32.dp),
                            onClick = onShowReactions,
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
                    val stamp = if (m.payload.edited == true) "$time · Edited" else time
                    Text(
                        if (receipt == null) stamp else if (receipt.failed || receipt.label == "Sending…") receipt.label else "$stamp · ${receipt.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (receipt?.failed == true) scheme.error else scheme.onSurfaceVariant,
                        fontWeight = if (receipt?.label?.startsWith("Seen") == true) FontWeight.SemiBold else null,
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
            // A sticker has no bubble, so its quote gets the received-bubble look either way.
            p.replyTo?.let { id ->
                Box(Modifier.widthIn(max = 240.dp).padding(bottom = 4.dp)) { QuoteBlock(p.replyUser, p.replyText, ctx, onBubble = false) { onQuoteTap(id) } }
            }
            EncryptedImage(p.media, media, Modifier.size(140.dp), ContentScale.Fit)
        }
        return
    }

    if (p.isDeleted) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            modifier = modifier,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                GngIcon(GngIcons.Cancel, null, size = 16.dp, tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (mine) "You deleted this message" else "This message was deleted",
                    style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val big = 20.dp
    val tight = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (run.first) big else tight, tight, big)
    } else {
        RoundedCornerShape(if (run.first) big else tight, big, big, tight)
    }
    val taggedMe = !mine && p.mentions(ctx.me.id)
    // Material You, the way Google Messages does it: yours in your theme's primary colour, theirs neutral.
    Surface(
        color = if (mine) scheme.primary else scheme.surfaceContainerHigh,
        contentColor = if (mine) scheme.onPrimary else scheme.onSurface,
        shape = shape,
        // A message that tags you stands out.
        border = if (taggedMe) BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = modifier.widthIn(max = 296.dp).alpha(pendingAlpha),
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            p.replyTo?.let { id ->
                // Full width of the bubble, with the same gap on both sides.
                Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp)) {
                    QuoteBlock(p.replyUser, p.replyText, ctx, onBubble = mine) { onQuoteTap(id) }
                }
            }
            when (p.t) {
                "image" -> p.media?.let { ref ->
                    // A preview, not the photo: tall shots are cropped to at most 4:5, wide ones to 16:9.
                    val ratio = ((ref.w ?: 4).toFloat() / (ref.h ?: 3).coerceAtLeast(1)).coerceIn(0.8f, 1.78f)
                    EncryptedImage(
                        ref, media,
                        Modifier.width(232.dp).aspectRatio(ratio).padding(3.dp).clip(RoundedCornerShape(17.dp)),
                        ContentScale.Crop,
                    )
                }
                "file" -> p.media?.let { FileRow(it) }
                "locked" -> Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    GngIcon(GngIcons.Lock, null, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Waiting for this message. It unlocks when someone in the chat opens gng.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.8f),
                    )
                }
            }
            val text = p.text?.takeIf { p.t == "text" || p.t == "legacy" || p.t == "image" }
            if (text != null) {
                Text(
                    withMentions(text, p.mentions, ctx, mine, everyone = p.all == true),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Highlights each "@Name" that really is a tag (not just someone typing an @). */
@Composable
private fun withMentions(text: String, mentions: List<String>?, ctx: GroupContext, mine: Boolean, everyone: Boolean = false): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    val links = remember(text) { LINK.findAll(text).toList() }
    if (mentions.isNullOrEmpty() && links.isEmpty()) return AnnotatedString(text)
    val linkColor = if (mine) scheme.onPrimary else scheme.primary
    return remember(text, mentions, ctx.members, scheme, everyone) {
        val allSpans = if (everyone) {
            generateSequence(text.indexOf("@$ALL_TAG")) { prev -> text.indexOf("@$ALL_TAG", prev + 1).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }.map { Triple(it, it + ALL_TAG.length + 1, true) }.toList()
        } else {
            emptyList()
        }
        val spans = allSpans + mentions.orEmpty().mapNotNull { id ->
            val name = ctx.members[id]?.displayName ?: return@mapNotNull null
            val needle = "@$name"
            generateSequence(text.indexOf(needle)) { prev -> text.indexOf(needle, prev + needle.length).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }
                .map { start -> Triple(start, start + needle.length, id == ctx.me.id) }
                .toList()
        }.flatten().sortedBy { it.first }
        buildAnnotatedString {
            append(text)
            // Links open in the browser.
            links.forEach { match ->
                val url = match.value.let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
                addLink(
                    LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                    match.range.first, match.range.last + 1,
                )
            }
            spans.forEach { (start, end, isMe) ->
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = if (mine) scheme.onPrimary else scheme.primary,
                        background = if (isMe) scheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Unspecified,
                    ),
                    start, end,
                )
            }
        }
    }
}

/** The quoted message at the top of a reply. [onBubble]: sits on a sent (primary-coloured) bubble. */
@Composable
private fun QuoteBlock(userId: String?, text: String?, ctx: GroupContext, onBubble: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (onBubble) scheme.onPrimary else scheme.primary
    val background = if (onBubble) scheme.onPrimary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest
    val body = if (onBubble) scheme.onPrimary.copy(alpha = 0.85f) else scheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().widthIn(min = 140.dp).clip(RoundedCornerShape(12.dp)).background(background)
            .clickable(onClick = onClick).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                if (userId == ctx.me.id) "You" else ctx.nameOf(userId),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent,
            )
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The little reaction pill on a bubble. Tap it to see who reacted. */
@Composable
private fun TapbackBadge(reactions: Map<String, String>, myId: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
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
    if (m.kind == "call") {
        CallPill(m, onJoinCall)
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
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 28.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalIcon(icon, size = 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    "$label · ${ctx.nameOf(m.userId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            GngIcon(GngIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * One small centred line per call, updated in place: "Aman started a voice call · Join" while it's
 * going, then "Voice call · 3 min" or "Missed voice call".
 */
@Composable
private fun CallPill(m: ChatMessage, onJoinCall: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val body = m.payload.text.orEmpty()
    val ongoing = m.refId != null && "started" in body
    val missed = body.startsWith("Missed")
    val video = "video" in body.lowercase()
    val time = m.createdAt.atZone(IST).format(TIME).lowercase()
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = { m.refId?.takeIf { ongoing }?.let(onJoinCall) },
            enabled = ongoing,
            shape = CircleShape,
            color = if (ongoing) CALL_PILL_GREEN.copy(alpha = 0.14f) else scheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                GngIcon(
                    when {
                        missed -> GngIcons.CallMissed
                        video -> GngIcons.Videocam
                        else -> GngIcons.Call
                    },
                    null, size = 16.dp,
                    tint = when {
                        missed -> scheme.error
                        ongoing -> CALL_PILL_GREEN
                        else -> scheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ongoing) body else "$body · $time",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (missed) scheme.error else scheme.onSurfaceVariant,
                )
                if (ongoing) {
                    Spacer(Modifier.width(10.dp))
                    Text("Join", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = CALL_PILL_GREEN)
                }
            }
        }
    }
}

private val CALL_PILL_GREEN = Color(0xFF1FA855)

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

/** A person's colour for their name above their messages, or a plain grey if it wouldn't be readable here. */
private fun nameColor(color: Color, background: Color, fallback: Color): Color =
    if (androidx.core.graphics.ColorUtils.calculateContrast(color.copy(alpha = 1f).toArgb(), background.copy(alpha = 1f).toArgb()) >= 3.0) color
    else fallback

/** Web links in a message: http(s)://… or www.… */
private val LINK = Regex("""(?i)\b(https?://|www\.)[^\s<>"]+[^\s<>".,;:!?)\]]""")
