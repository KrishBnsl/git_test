package com.gng.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gng.app.ui.icons.GroupIcons
import com.gng.app.ui.theme.avatarColors
import com.gng.app.ui.theme.pickAvatarColor

// ---------------------------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------------------------

@Composable
fun GngIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint)
}

/** An icon on a tonal shape: the building block for list leading content and quick actions. */
@Composable
fun TonalIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
) {
    Box(
        modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        GngIcon(icon, null, tint = content, size = size * 0.5f)
    }
}

// ---------------------------------------------------------------------------------------------
// Avatars
// ---------------------------------------------------------------------------------------------

/** A person's avatar: their photo if they have one, otherwise initials on a stable tonal colour. */
@Composable
fun Avatar(
    name: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    colorKey: String = name,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { avatarColors(scheme) }
    val color = pickAvatarColor(palette, colorKey)
    Box(
        modifier.size(size).clip(CircleShape).background(color.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            color = color.content,
            fontSize = (size.value * 0.38f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** A gang's picture: its photo if one is set, otherwise its chosen icon on a tonal shape. */
@Composable
fun GroupAvatar(
    iconKey: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    if (photoUrl.isNullOrBlank()) {
        TonalIcon(
            GroupIcons.resolve(iconKey).icon, modifier, size = size,
            container = scheme.primaryContainer, content = scheme.onPrimaryContainer,
        )
    } else {
        Box(modifier.size(size).clip(CircleShape).background(scheme.primaryContainer)) {
            AsyncImage(
                model = photoUrl, contentDescription = contentDescription,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** An avatar with a small status dot at the bottom-end corner. */
@Composable
fun StatusAvatar(
    name: String,
    photoUrl: String?,
    online: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    colorKey: String = name,
) {
    Box(modifier) {
        Avatar(name, photoUrl, size = size, colorKey = colorKey)
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
            )
        }
    }
}

data class AvatarInfo(val key: String, val name: String, val photoUrl: String?)

/** Overlapping avatars, with a "+N" bubble when there are more than [max]. */
@Composable
fun AvatarStack(
    people: List<AvatarInfo>,
    modifier: Modifier = Modifier,
    max: Int = 4,
    size: Dp = 28.dp,
    ringColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val shown = people.take(max)
    val extra = people.size - shown.size
    val ring = 2.dp
    val full = size + ring * 2
    val step = size * 0.7f + ring // how far each avatar is shifted from the previous one
    val count = shown.size + if (extra > 0) 1 else 0
    Box(modifier.width(full + step * (count - 1).coerceAtLeast(0)).height(full)) {
        shown.forEachIndexed { i, p ->
            Box(
                Modifier.offset(x = step * i).background(ringColor, CircleShape).padding(ring),
            ) { Avatar(p.name, p.photoUrl, size = size, colorKey = p.key) }
        }
        if (extra > 0) {
            Box(
                Modifier
                    .offset(x = step * shown.size)
                    .background(ringColor, CircleShape)
                    .padding(ring)
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$extra", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout pieces
// ---------------------------------------------------------------------------------------------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalIcon(
            icon, size = 88.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A small rounded label, used for times, places, counts and urgency. */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) GngIcon(icon, null, tint = content, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

data class Choice<T>(val value: T, val label: String, @DrawableRes val icon: Int? = null)

/** A Material 3 single-choice segmented button row. The selected segment shows a check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes checkIcon: Int,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            val isSelected = choice.value == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(choice.value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {
                    val icon = if (isSelected) checkIcon else choice.icon
                    if (icon != null) GngIcon(icon, null, size = SegmentedButtonDefaults.IconSize)
                },
                label = { Text(choice.label, maxLines = 1) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------

/** Provided by the screen scaffold so any child can surface a message. */
val LocalSnackbar = compositionLocalOf<SnackbarHostState> { error("No SnackbarHostState provided") }

/** Shows [message] in the scaffold's snackbar, then calls [onShown] so the view model can clear it. */
@Composable
fun MessageEffect(message: String?, onShown: () -> Unit) {
    val host = LocalSnackbar.current
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onShown()
        }
    }
}
