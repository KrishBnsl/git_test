package com.gng.app.ui.people

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.gng.app.call.CallActivity
import com.gng.app.container
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.friendly
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Opens someone's profile from anywhere below (a chat bubble, the Squad list, gang settings…). */
val LocalOpenPerson = compositionLocalOf<(String) -> Unit> { {} }

private val BIRTHDAY = DateTimeFormatter.ofPattern("d MMMM")

/**
 * Someone's profile: big photo (tap to see it full screen), name, @username, college, status,
 * birthday, and buttons to message or call them.
 */
@Composable
fun PersonSheet(profile: Profile, isMe: Boolean, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }

    fun call(video: Boolean) {
        scope.launch {
            runCatching {
                val dm = c.chat.openDm(profile.id)
                context.startActivity(CallActivity.intent(context, "", CallActivity.ACTION_SHOW))
                c.calls.start(dm.id, profile.displayName, video)
            }.onFailure { error = it.friendly() }
            onDismiss()
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingVideo ?: return@rememberLauncherForActivityResult
        if (granted[Manifest.permission.RECORD_AUDIO] == true) call(video) else error = "Calls need the microphone"
    }
    fun startCall(video: Boolean) {
        pendingVideo = video
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) call(video) else ask.launch(needed.toTypedArray())
    }

    GngSheet(title = "", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(CircleShape).clickable { viewing = true }) {
                Avatar(profile.displayName, profile.avatarUrl, size = 120.dp, colorKey = profile.id)
            }
            Spacer(Modifier.height(12.dp))
            Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            profile.handle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
            val line = listOfNotNull(profile.college, profile.branch, profile.year?.let { "Year $it" }).joinToString(" · ")
            if (line.isNotBlank()) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            profile.statusText?.takeIf { it.isNotBlank() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
            profile.birthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { day ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GngIcon(GngIcons.Cake, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Birthday ${day.format(BIRTHDAY)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (!isMe) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionButton(GngIcons.ChatBubble, "Message", Modifier.weight(1f)) { onDismiss(); onMessage(profile.id) }
                    ActionButton(GngIcons.Call, "Voice", Modifier.weight(1f)) { startCall(video = false) }
                    ActionButton(GngIcons.Videocam, "Video", Modifier.weight(1f)) { startCall(video = true) }
                }
            }
        }
    }
    if (viewing) PhotoViewer(profile.avatarUrl, profile.displayName, profile.id, onDismiss = { viewing = false })
}

@Composable
private fun ActionButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(64.dp), shape = MaterialTheme.shapes.large) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GngIcon(icon, null, size = 22.dp)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A photo full screen: pinch to zoom, drag to look around, double-tap to zoom in or out.
 * With no photo, a big version of the initials avatar.
 */
@Composable
fun PhotoViewer(url: String?, title: String, colorKey: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (url != null) {
                AsyncImage(
                    model = url, contentDescription = title, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offsetX = if (scale > 1f) offsetX + pan.x else 0f
                                offsetY = if (scale > 1f) offsetY + pan.y else 0f
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offsetX = 0f
                                offsetY = 0f
                            })
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                )
            } else {
                Box(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).aspectRatio(1f)) {
                    Avatar(title, null, size = 260.dp, colorKey = colorKey)
                }
            }
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { GngIcon(GngIcons.ArrowBack, "Close", tint = Color.White) }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
