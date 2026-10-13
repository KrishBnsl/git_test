package com.gng.app.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.container
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.icons.GngIcons
import com.gng.app.update.UpdateInfo
import com.gng.app.update.UpdateManager
import com.gng.app.update.UpdateState
import java.util.Locale

/** Everything a person can do from an update card. */
data class UpdateActions(
    val onUpdate: () -> Unit = {},
    val onLater: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onAllow: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCheck: () -> Unit = {},
)

fun UpdateManager.actions() = UpdateActions(
    onUpdate = ::download, onLater = ::dismiss, onSkip = ::skip, onInstall = ::install,
    onAllow = ::allowInstalls,
    onRetry = { if ((state.value as? UpdateState.Failed)?.info != null) download() else check() },
    onCheck = { check() },
)

/** The card tracking the live [UpdateManager]. */
@Composable
fun UpdateHost(modifier: Modifier = Modifier) {
    val updates = LocalContext.current.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    UpdateCard(state, updates.actions(), modifier)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** Shows nothing for states that don't need attention (idle, checking, up to date). */
@Composable
fun UpdateCard(state: UpdateState, actions: UpdateActions, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(state, modifier = modifier.animateContentSize(), label = "update-card") { s ->
        when (s) {
            is UpdateState.Available -> Shell(scheme.primaryContainer, scheme.onPrimaryContainer) {
                Header(GngIcons.SystemUpdateFill, "Update available", "gng ${s.info.version}${sizeSuffix(s.info)}", scheme.primary, scheme.onPrimary)
                Notes(s.info)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onSkip) { Text("Skip") }
                    TextButton(onClick = actions.onLater) { Text("Later") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = actions.onUpdate) {
                        GngIcon(GngIcons.Download, null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Update")
                    }
                }
            }
            is UpdateState.Downloading -> Shell(scheme.primaryContainer, scheme.onPrimaryContainer) {
                Header(
                    GngIcons.Download, "Downloading gng ${s.info.version}",
                    if (s.progress >= 0) "${(s.progress * 100).toInt()}%" else "Hang on…", scheme.primary, scheme.onPrimary,
                )
                Spacer(Modifier.height(14.dp))
                if (s.progress >= 0) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.ReadyToInstall -> Shell(scheme.primaryContainer, scheme.onPrimaryContainer) {
                Header(GngIcons.SystemUpdateFill, "Ready to install", "gng ${s.info.version}", scheme.primary, scheme.onPrimary)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = actions.onInstall) { Text("Install") }
                }
            }
            is UpdateState.NeedsInstallPermission -> Shell(scheme.tertiaryContainer, scheme.onTertiaryContainer) {
                Header(GngIcons.Lock, "One-time permission", "Allow gng to install updates", scheme.tertiary, scheme.onTertiary)
                Text(
                    "Android asks you to switch on \"Allow from this source\" for gng. After that, updates take one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = actions.onAllow) { Text("Open settings") }
                }
            }
            is UpdateState.Failed -> Shell(scheme.errorContainer, scheme.onErrorContainer) {
                Header(GngIcons.Info, "Update didn't work", s.message, scheme.error, scheme.onError)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions.onLater) { Text("Dismiss") }
                    Button(onClick = actions.onRetry) { Text("Try again") }
                }
            }
            else -> Unit
        }
    }
}

private fun sizeSuffix(info: UpdateInfo) = formatSize(info.sizeBytes).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""

@Composable
private fun Shell(container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, body: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp)) { body() } }
}

@Composable
private fun Header(
    icon: Int, title: String, subtitle: String,
    iconContainer: androidx.compose.ui.graphics.Color, iconContent: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TonalIcon(icon, container = iconContainer, content = iconContent)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Notes(info: UpdateInfo) {
    if (info.notes.isEmpty()) return
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        info.notes.take(4).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        if (info.notes.size > 4) Text("and ${info.notes.size - 4} more", style = MaterialTheme.typography.bodySmall)
    }
}
