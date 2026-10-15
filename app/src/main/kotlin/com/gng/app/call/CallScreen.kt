package com.gng.app.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.container
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.theme.avatarColors
import com.gng.app.ui.theme.pickAvatarColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/**
 * The call screen. [pip]: shrunk to a picture-in-picture window, so only the video (or avatar) shows.
 * [onMinimize] leaves the call running in the background; the green bar in the app brings you back.
 */
@Composable
fun CallScreen(calls: CallManager, onClose: () -> Unit, pip: Boolean = false, onMinimize: () -> Unit = onClose) {
    val call by calls.state.collectAsStateWithLifecycle()
    val s = call ?: run {
        // Normally the call shows up within a moment; CallActivity closes us when it ends.
        LaunchedEffect(Unit) { delay(10_000); onClose() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val members by produceState(emptyMap<String, Profile>(), s.groupId) {
        value = context.container.chat.cachedMembers(s.groupId).orEmpty().associate { it.userId to it.profiles }
    }
    val needed = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (s.video) add(Manifest.permission.CAMERA)
    }
    var acceptVideo by remember { mutableStateOf(true) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) scope.launch { calls.accept(acceptVideo) }
    }
    fun accept(withVideo: Boolean) {
        acceptVideo = withVideo
        val missing = (if (withVideo) needed else listOf(Manifest.permission.RECORD_AUDIO))
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) scope.launch { calls.accept(withVideo) } else askPermissions.launch(missing.toTypedArray())
    }
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { calls.enableCamera() }
    }
    fun toggleCamera() = if (s.camOn || hasCamera()) calls.toggleCamera() else askCamera.launch(Manifest.permission.CAMERA)
    // Answered a video call from the notification without the camera permission: ask now.
    var askedCamera by remember { mutableStateOf(false) }
    LaunchedEffect(s.phase, s.video) {
        val inCall = s.phase == CallPhase.Connecting || s.phase == CallPhase.Calling || s.phase == CallPhase.Active
        if (inCall && s.video && s.localVideo == null && !askedCamera && !hasCamera()) {
            askedCamera = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    val remoteVideo = s.participants.filter { it.video != null && it.camOn }
    val oneToOneVideo = s.participants.size == 1 && remoteVideo.size == 1
    // In 1:1 video, tap your preview to swap which picture is big.
    var swapped by remember { mutableStateOf(false) }
    var controlsShown by remember { mutableStateOf(true) }
    val local = s.localVideo?.takeIf { s.camOn }
    val who = (s.participants.firstOrNull()?.userId ?: s.incomingFrom)?.let { members[it] }
    val palette = avatarColors(MaterialTheme.colorScheme)
    val tone = pickAvatarColor(palette, who?.id ?: s.groupId).container

    if (pip) {
        // Picture-in-picture: just the picture.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val big = remoteVideo.firstOrNull()?.video ?: local
            if (big != null) VideoTile(big, calls.eglBase, Modifier.fillMaxSize(), mirror = big === local)
            else Avatar(who?.displayName ?: s.title, who?.avatarUrl, size = 64.dp, colorKey = who?.id ?: s.groupId)
        }
        return
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(tone.copy(alpha = 0.55f).compositeOver(BG), BG, BG)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = remoteVideo.isNotEmpty() && s.phase == CallPhase.Active,
            ) { controlsShown = !controlsShown },
    ) {
        // The pictures.
        when {
            s.phase == CallPhase.Ringing || s.participants.isEmpty() -> Unit
            oneToOneVideo -> {
                val big = if (swapped && local != null) local else remoteVideo.first().video!!
                VideoTile(big, calls.eglBase, Modifier.fillMaxSize(), mirror = big === local)
            }
            s.participants.size == 1 -> Unit // voice call: the big avatar below
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 120.dp, bottom = 140.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.participants, key = { it.userId }) { p -> ParticipantTile(p, members[p.userId], calls.eglBase) }
            }
        }

        val chrome = controlsShown || remoteVideo.isEmpty() || s.phase != CallPhase.Active
        // Top bar: minimize, encryption, where the sound goes.
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s.phase != CallPhase.Ringing) {
                    IconButton(onClick = onMinimize) { GngIcon(GngIcons.ExpandMore, "Minimize", tint = Color.White) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    GngIcon(GngIcons.Lock, null, size = 14.dp, tint = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.size(6.dp))
                    Text("End-to-end encrypted", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                if (s.phase != CallPhase.Ringing && s.phase != CallPhase.Ended) RouteButton(s, calls) else Spacer(Modifier.size(48.dp))
            }
        }

        // Who and how long; a big pulsing avatar until there's video to show.
        val showAvatar = s.phase == CallPhase.Ringing || s.participants.isEmpty() || (s.participants.size == 1 && remoteVideo.isEmpty())
        if (chrome || showAvatar) {
            Column(
                Modifier.align(if (showAvatar) Alignment.Center else Alignment.TopCenter).safeDrawingPadding()
                    .padding(top = if (showAvatar) 0.dp else 56.dp, bottom = if (showAvatar) 120.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showAvatar) {
                    PulsingAvatar(
                        who?.displayName ?: s.title, who?.avatarUrl, who?.id ?: s.groupId,
                        pulsing = s.phase == CallPhase.Ringing || s.phase == CallPhase.Calling || s.phase == CallPhase.Connecting,
                    )
                    Spacer(Modifier.height(24.dp))
                }
                Text(s.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(statusLine(s), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Your own camera: drag it anywhere, tap to swap with theirs.
        if (local != null && s.phase != CallPhase.Ringing && !(oneToOneVideo && swapped)) {
            DraggablePreview(local, calls.eglBase, onTap = { if (oneToOneVideo) swapped = !swapped })
        } else if (oneToOneVideo && swapped) {
            DraggablePreview(remoteVideo.first().video!!, calls.eglBase, mirror = false, onTap = { swapped = false })
        }

        // Controls.
        when (s.phase) {
            CallPhase.Ringing -> Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(GngIcons.CallEnd, RED, Color.White, "Decline", size = 72.dp) { scope.launch { calls.decline(s.callId) } }
                    RoundButton(if (s.video) GngIcons.Videocam else GngIcons.Call, GREEN, Color.White, "Accept", size = 72.dp) { accept(true) }
                }
                if (s.video) {
                    TextButton(onClick = { accept(false) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Answer without video", color = Color.White)
                    }
                }
            }
            CallPhase.Ended -> Text(
                s.endedReason ?: "Call ended", color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 60.dp),
            )
            else -> if (chrome) Surface(
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(36.dp),
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 24.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Toggle(if (s.camOn) GngIcons.Videocam else GngIcons.VideocamOff, s.camOn, if (s.camOn) "Camera" else "Camera off") { toggleCamera() }
                    if (s.camOn) Toggle(GngIcons.Cameraswitch, false, "Flip") { calls.flipCamera() }
                    Toggle(if (s.micOn) GngIcons.Mic else GngIcons.MicOff, !s.micOn, if (s.micOn) "Mute" else "Muted") { calls.toggleMic() }
                    Toggle(GngIcons.VolumeUp, s.route == AudioRoute.Speaker, "Speaker") { calls.toggleSpeaker() }
                    RoundButton(GngIcons.CallEnd, RED, Color.White, "End") { scope.launch { calls.hangUp() } }
                }
            }
        }
    }
}

private val BG = Color(0xFF0F1216)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** Shows where the sound is going; tap to pick another (Bluetooth, headset, speaker, phone). */
@Composable
private fun RouteButton(s: CallUi, calls: CallManager) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { calls.refreshRoutes() }
    Box {
        IconButton(onClick = { calls.refreshRoutes(); open = true }) {
            GngIcon(routeIcon(s.route), "Audio: ${s.route.label}", tint = Color.White)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            s.routes.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label, fontWeight = if (r == s.route) FontWeight.Bold else null) },
                    leadingIcon = { GngIcon(routeIcon(r), null) },
                    trailingIcon = { if (r == s.route) GngIcon(GngIcons.Check, null) },
                    onClick = { open = false; calls.setRoute(r) },
                )
            }
        }
    }
}

private fun routeIcon(r: AudioRoute) = when (r) {
    AudioRoute.Earpiece -> GngIcons.PhoneInTalk
    AudioRoute.Speaker -> GngIcons.VolumeUp
    AudioRoute.Bluetooth -> GngIcons.Bluetooth
    AudioRoute.Headset -> GngIcons.Headphones
}

@Composable
private fun PulsingAvatar(name: String, photo: String?, key: String, pulsing: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val grow by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "grow")
    val fade by pulse.animateFloat(0.35f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "fade")
    Box(contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(Modifier.size(132.dp).scale(grow).clip(CircleShape).background(Color.White.copy(alpha = fade)))
        }
        Avatar(name, photo, size = 132.dp, colorKey = key)
    }
}

/** Your camera (or theirs, when swapped), small and draggable. */
@Composable
private fun DraggablePreview(track: VideoTrack, egl: EglBase, mirror: Boolean = true, onTap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val density = LocalDensity.current
        val w = with(density) { 108.dp.toPx() }
        val h = with(density) { 160.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val maxX = constraints.maxWidth - w - margin
        val maxY = constraints.maxHeight - h - with(density) { 120.dp.toPx() }
        var x by remember { mutableFloatStateOf(maxX) }
        var y by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(108.dp, 160.dp).clip(RoundedCornerShape(16.dp))
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = { x = if (x + w / 2 < constraints.maxWidth / 2) margin else maxX },
                    ) { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(margin, maxX)
                        y = (y + drag.y).coerceIn(margin, maxY.coerceAtLeast(margin))
                    }
                }
                .clickable(onClick = onTap),
        ) { VideoTile(track, egl, Modifier.fillMaxSize(), mirror = mirror, onTop = true) }
    }
}

@Composable
private fun statusLine(s: CallUi): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) {
        while (s.connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return when (s.phase) {
        CallPhase.Ringing -> "${if (s.video) "Video" else "Voice"} call from ${s.incomingFrom ?: "someone"}"
        CallPhase.Calling -> if (s.participants.isEmpty()) "Ringing…" else "Connecting…"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Ended -> s.endedReason ?: "Call ended"
        CallPhase.Active -> {
            if (s.participants.isNotEmpty() && s.participants.all { it.reconnecting }) return "Reconnecting…"
            val secs = ((now - (s.connectedAt ?: now)) / 1000).coerceAtLeast(0)
            val people = if (s.participants.size > 1) " · ${s.participants.size + 1} people" else ""
            "%d:%02d".format(secs / 60, secs % 60) + people
        }
    }
}

@Composable
private fun ParticipantTile(p: Participant, profile: Profile?, egl: EglBase) {
    Surface(color = Color(0xFF1F252C), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
        Box {
            val track = p.video
            if (track != null && p.camOn) {
                VideoTile(track, egl, Modifier.fillMaxSize(), mirror = false)
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(profile?.displayName ?: "?", profile?.avatarUrl, size = 72.dp, colorKey = p.userId)
                    if (!p.connected || p.reconnecting) {
                        Text(if (p.reconnecting) "reconnecting…" else "connecting…", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                if (!p.micOn) GngIcon(GngIcons.MicOff, "Muted", size = 14.dp, tint = Color.White, modifier = Modifier.padding(end = 4.dp))
                Text(profile?.displayName ?: "someone", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun VideoTile(track: VideoTrack, egl: EglBase, modifier: Modifier, mirror: Boolean, onTop: Boolean = false) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            // Your own small preview must draw above the other person's full-screen video.
            if (onTop) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) {
        renderer.setMirror(mirror)
        runCatching { track.addSink(renderer) }
        onDispose { runCatching { track.removeSink(renderer) } }
    }
    AndroidView({ renderer }, modifier)
}

private val RED = Color(0xFFE5484D)
private val GREEN = Color(0xFF30A46C)

@Composable
private fun Toggle(icon: Int, active: Boolean, label: String, onClick: () -> Unit) =
    RoundButton(
        icon,
        if (active) Color.White else Color.White.copy(alpha = 0.16f),
        if (active) Color(0xFF0F1216) else Color.White,
        label, onClick = onClick,
    )

@Composable
private fun RoundButton(icon: Int, container: Color, content: Color, label: String, size: Dp = 56.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        ) { GngIcon(icon, label, size = if (size > 60.dp) 32.dp else 26.dp, tint = content) }
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Permissions a call needs before it can start. */
fun callPermissions(video: Boolean): Array<String> =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
