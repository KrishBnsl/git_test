package com.gng.app.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun CallScreen(calls: CallManager, onClose: () -> Unit) {
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
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) scope.launch { calls.accept() }
    }
    fun accept() {
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) scope.launch { calls.accept() } else askPermissions.launch(missing.toTypedArray())
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0F0D14))) {
        val remoteWithVideo = s.participants.filter { it.video != null && it.camOn }
        when {
            s.phase == CallPhase.Ringing || s.participants.isEmpty() -> Unit
            s.participants.size == 1 && remoteWithVideo.size == 1 ->
                VideoTile(remoteWithVideo.first().video!!, calls.eglBase, Modifier.fillMaxSize(), mirror = false)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (s.participants.size <= 1) 1 else 2),
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 96.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.participants, key = { it.userId }) { p ->
                    ParticipantTile(p, members[p.userId], calls.eglBase)
                }
            }
        }

        // Header: who and how long.
        Column(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (s.phase == CallPhase.Ringing || s.participants.isEmpty()) {
                Spacer(Modifier.height(80.dp))
                val who = s.participants.firstOrNull()?.let { members[it.userId] }
                Avatar(who?.displayName ?: s.title, who?.avatarUrl, size = 112.dp, colorKey = who?.id ?: s.groupId)
                Spacer(Modifier.height(16.dp))
            }
            Text(s.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(statusLine(s), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
        }

        // Your own camera, small in the corner.
        val local = s.localVideo
        if (local != null && s.camOn && s.phase != CallPhase.Ringing) {
            VideoTile(
                local, calls.eglBase,
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp)),
                mirror = true,
            )
        }

        // Controls.
        Row(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (s.phase) {
                CallPhase.Ringing -> {
                    RoundButton(GngIcons.CallEnd, RED, Color.White, "Decline") { scope.launch { calls.decline(s.callId) } }
                    RoundButton(if (s.video) GngIcons.Videocam else GngIcons.Call, GREEN, Color.White, "Accept") { accept() }
                }
                CallPhase.Ended -> Text(s.endedReason ?: "Call ended", color = Color.White)
                else -> {
                    Toggle(if (s.micOn) GngIcons.Mic else GngIcons.MicOff, !s.micOn, if (s.micOn) "Mute" else "Unmute") { calls.toggleMic() }
                    if (s.video) {
                        Toggle(if (s.camOn) GngIcons.Videocam else GngIcons.VideocamOff, !s.camOn, "Camera") { calls.toggleCamera() }
                        Toggle(GngIcons.Cameraswitch, false, "Flip") { calls.flipCamera() }
                    }
                    Toggle(GngIcons.VolumeUp, s.speakerOn, "Speaker") { calls.toggleSpeaker() }
                    RoundButton(GngIcons.CallEnd, RED, Color.White, "Hang up") { scope.launch { calls.hangUp() } }
                }
            }
        }
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
        CallPhase.Ringing -> "${s.incomingFrom ?: "Someone"} · ${if (s.video) "video" else "voice"} call"
        CallPhase.Calling -> "Ringing…"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Ended -> s.endedReason ?: "Call ended"
        CallPhase.Active -> {
            val secs = ((now - (s.connectedAt ?: now)) / 1000).coerceAtLeast(0)
            val people = if (s.participants.size > 1) " · ${s.participants.size + 1} people" else ""
            "%d:%02d".format(secs / 60, secs % 60) + people
        }
    }
}

@Composable
private fun ParticipantTile(p: Participant, profile: Profile?, egl: EglBase) {
    Surface(color = Color(0xFF231F2B), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
        Box {
            val track = p.video
            if (track != null && p.camOn) {
                VideoTile(track, egl, Modifier.fillMaxSize(), mirror = false)
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(profile?.displayName ?: "?", profile?.avatarUrl, size = 72.dp, colorKey = p.userId)
                    if (!p.connected) Text("connecting…", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
private fun VideoTile(track: VideoTrack, egl: EglBase, modifier: Modifier, mirror: Boolean) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
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
        if (active) Color.White else Color.White.copy(alpha = 0.18f),
        if (active) Color(0xFF0F0D14) else Color.White,
        label, onClick = onClick,
    )

@Composable
private fun RoundButton(icon: Int, container: Color, content: Color, label: String, size: Dp = 64.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        ) { GngIcon(icon, label, size = 28.dp, tint = content) }
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Permissions a call needs before it can start. */
fun callPermissions(video: Boolean): Array<String> =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

