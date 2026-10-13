package com.gng.app.ui.group

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gng.app.container
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.components.LocalSnackbar
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.map.GngMapState
import com.gng.app.ui.map.MAP_ATTRIBUTION
import com.gng.app.ui.map.OsmMap
import com.gng.app.ui.map.rememberGngMapState
import com.gng.app.ui.theme.LocalIsDark
import com.gng.core.LatLng
import com.gng.core.MapPin
import com.gng.core.MapRules
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

private val AVATAR = 46.dp
private val RING = 3.dp
private val MARKER_WIDTH = 84.dp

@Composable
fun MapTab(ctx: GroupContext, onOpenSquad: () -> Unit, onMessage: (String) -> Unit) {
    val pv = gngViewModel(key = "presence-${ctx.group.id}") { PresenceViewModel(it, ctx.group.id) }
    PresencePolling(pv)
    MessageEffect(pv.error, pv::dismissError)
    val context = LocalContext.current

    var selected by remember { mutableStateOf<String?>(null) }
    val pins = remember(pv.presence) { MapRules.pins(pv.presence.values.map { it.toMapSource() }, Instant.now()) }

    MapContent(
        ctx = ctx, pins = pins, selected = selected, loaded = pv.loaded,
        onSelect = { selected = it },
        onMyLocation = { context.container.checkIn.currentLatLng() },
        onOpenSquad = onOpenSquad,
        onMessage = onMessage,
        onDirections = { pin, name ->
            val uri = Uri.parse("geo:${pin.point.lat},${pin.point.lng}?q=${pin.point.lat},${pin.point.lng}(${Uri.encode(name)})")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        },
    )
}

@Composable
fun MapContent(
    ctx: GroupContext,
    pins: List<MapPin>,
    selected: String?,
    loaded: Boolean,
    onSelect: (String?) -> Unit,
    onMyLocation: suspend () -> LatLng?,
    onOpenSquad: () -> Unit,
    onDirections: (MapPin, String) -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val state = rememberGngMapState()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val fan = remember(pins) { MapRules.fanOut(pins) }
    val fitPadding = with(density) { 96.dp.roundToPx() }

    // Frame everyone the first time we have data; after that the person is in control.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(pins, loaded) {
        if (!framed && loaded) {
            framed = true
            state.fit(MapRules.bounds(pins.map { it.point }), fitPadding)
        }
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            state = state, dark = LocalIsDark.current, modifier = Modifier.fillMaxSize(),
            background = scheme.surfaceContainerHigh, onTap = { onSelect(null) },
        )

        // Avatars. The offset lambda reads the map state, so they follow the map without recomposing.
        val ordered = pins.sortedBy { if (it.userId == selected) 1 else 0 }
        ordered.forEach { pin ->
            val person = ctx.person(pin.userId)
            val (dx, dy) = fan[pin.userId] ?: (0.0 to 0.0)
            val isSelected = pin.userId == selected
            AvatarMarker(
                name = person.name, photoUrl = person.photoUrl, colorKey = person.key, pin = pin, selected = isSelected,
                modifier = Modifier
                    .zIndex(if (isSelected) 2f else 1f)
                    .offset {
                        val p = state.project(pin.point)
                        val full = (AVATAR + RING * 2).toPx()
                        if (p == null) IntOffset(-10_000, -10_000)
                        else IntOffset(
                            (p.x + dx * full - MARKER_WIDTH.toPx() / 2).roundToInt(),
                            (p.y + dy * full - full / 2).roundToInt(),
                        )
                    },
                onClick = { onSelect(if (isSelected) null else pin.userId) },
            )
        }

        // Controls
        Column(
            Modifier.align(Alignment.TopEnd).zIndex(10f).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(GngIcons.ZoomOutMap, "Show everyone") { state.fit(MapRules.bounds(pins.map { it.point }), fitPadding) }
            MapButton(GngIcons.MyLocation, "My location") {
                scope.launch {
                    val here = onMyLocation()
                    if (here != null) state.animateTo(here) else snackbar.showSnackbar("Turn on location sharing in Squad first")
                }
            }
        }

        val picked = pins.firstOrNull { it.userId == selected }
        Column(
            // Above the avatar markers (which use zIndex 1 and 2), so a card is never drawn through.
            Modifier.align(Alignment.BottomCenter).zIndex(10f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 30.dp),
        ) {
            AnimatedVisibility(
                visible = picked != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                // Keep the last card content while animating out.
                var shown by remember { mutableStateOf(picked) }
                if (picked != null) shown = picked
                shown?.let { pin ->
                    PinCard(
                        ctx, pin, onClose = { onSelect(null) },
                        onDirections = { onDirections(pin, ctx.nameOf(pin.userId)) },
                        onMessage = { onMessage(pin.userId) },
                    )
                }
            }
            AnimatedVisibility(visible = loaded && pins.isEmpty() && picked == null) {
                EmptyMapCard(onOpenSquad)
            }
        }

        Text(
            MAP_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .zIndex(11f)
                .padding(8.dp)
                .background(scheme.surface.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MapButton(icon: Int, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) { GngIcon(icon, label) }
}

/** A Snap Map style marker: the person's photo in a ring, their name underneath, and a pulse while they're live. */
@Composable
private fun AvatarMarker(
    name: String,
    photoUrl: String?,
    colorKey: String,
    pin: MapPin,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val live = pin.exact && !pin.stale
    val scale by animateFloatAsState(if (selected) 1.18f else 1f, label = "marker-scale")
    Column(
        modifier
            .width(MARKER_WIDTH)
            .alpha(if (pin.stale) 0.6f else 1f)
            .scale(scale)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (live) PulseRing(scheme.tertiary)
            Box(
                Modifier
                    .size(AVATAR + RING * 2)
                    .shadow(6.dp, CircleShape)
                    .background(if (selected) scheme.primary else scheme.surface, CircleShape)
                    .padding(RING),
            ) { Avatar(name, photoUrl, size = AVATAR, colorKey = colorKey) }
            if (pin.exact) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .background(scheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(scheme.tertiary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { GngIcon(GngIcons.Sensors, null, tint = scheme.onTertiary, size = 10.dp) }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            name.substringBefore(' '),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(scheme.surface.copy(alpha = 0.92f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PulseRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "pulse-progress",
    )
    Box(
        Modifier
            .size(AVATAR + RING * 2)
            .scale(1f + progress * 0.9f)
            .alpha((1f - progress) * 0.55f)
            .background(color, CircleShape),
    )
}

@Composable
private fun PinCard(ctx: GroupContext, pin: MapPin, onClose: () -> Unit, onDirections: () -> Unit, onMessage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val person = ctx.person(pin.userId)
    val profile = ctx.members[pin.userId]
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(person.name, person.photoUrl, size = 52.dp, colorKey = person.key)
                Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    Text(person.name, style = MaterialTheme.typography.titleMedium)
                    val where = when {
                        pin.exact && pin.spotName != null -> "Live, near ${pin.spotName}"
                        pin.exact -> "Live location"
                        pin.spotName != null -> "At ${pin.spotName}"
                        else -> "Somewhere on campus"
                    }
                    Text(
                        "$where · ${pin.ago}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    profile?.statusText?.takeIf { it.isNotBlank() }?.let {
                        Text("“$it”", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) { GngIcon(GngIcons.Close, "Close") }
            }
            Spacer(Modifier.height(10.dp))
            if (pin.exact) {
                InfoChip("Exact location", icon = GngIcons.Sensors, container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
            } else {
                InfoChip("On their spot, not exact", icon = GngIcons.LocationOn)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pin.userId != ctx.me.id) {
                    OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f)) {
                        GngIcon(GngIcons.ChatBubble, null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Message")
                    }
                }
                Button(onClick = onDirections, modifier = Modifier.weight(1f)) {
                    GngIcon(GngIcons.Directions, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Maps")
                }
            }
        }
    }
}

@Composable
private fun EmptyMapCard(onOpenSquad: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { GngIcon(GngIcons.LocationOff, null, tint = scheme.onPrimaryContainer) }
            Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text("Nobody's on the map yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Share your spot or go live to show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Button(onClick = onOpenSquad) { Text("Share") }
        }
    }
}
