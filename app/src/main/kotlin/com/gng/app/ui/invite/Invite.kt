package com.gng.app.ui.invite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gng.app.container
import com.gng.app.data.GangPreview
import com.gng.app.data.Group
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.GroupAvatar
import com.gng.app.ui.friendly
import com.gng.app.ui.icons.GngIcons
import com.gng.core.InviteCodes
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A QR code, drawn crisp at any size. Always dark on white so every scanner reads it, in dark mode too. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = modifier.semantics { contentDescription = "Invite QR code" }) {
        Canvas(Modifier.padding(16.dp).aspectRatio(1f)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color(0xFF111111), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Everything for inviting people to a gang: a QR code to scan, a link to share, and the code itself. */
@Composable
fun InviteSheet(group: Group, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = InviteCodes.link(group.inviteCode)
    GngSheet(title = "Invite to ${group.name}", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(link, Modifier.width(260.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Friends scan this with gng (Join a gang › Scan QR code) or their phone's camera.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text("Code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(group.inviteCode, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 4.sp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { copy(context, group.inviteCode) }, modifier = Modifier.weight(1f)) {
                    GngIcon(GngIcons.ContentCopy, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy code")
                }
                Button(onClick = { shareInvite(context, group) }, modifier = Modifier.weight(1f)) {
                    GngIcon(GngIcons.Share, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Share link")
                }
            }
        }
    }
}

/** Shares the invite link (WhatsApp, etc.) with the code as a fallback. */
fun shareInvite(context: Context, group: Group) {
    val text = "Join ${group.name} on gng: ${InviteCodes.link(group.inviteCode)}\n(or enter code ${group.inviteCode})"
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite the gang"),
    )
}

private fun copy(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("gng code", code))
}

/**
 * Opens Google's QR scanner (part of Play services: no camera permission, nothing to install) and
 * returns the invite code it read, or null if cancelled. Throws with a readable message otherwise.
 */
suspend fun scanInvite(context: Context): String? {
    val scanner = GmsBarcodeScanning.getClient(
        context,
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build(),
    )
    val result = try {
        scanner.startScan().await()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException || e.message?.contains("cancel", ignoreCase = true) == true) return null
        throw IllegalStateException("Couldn't open the scanner. Type the code instead.")
    }
    return InviteCodes.fromScan(result.rawValue.orEmpty()) ?: throw IllegalStateException("That QR code isn't a gng invite")
}

/** "Join Hostel H4 Maggi Club? 12 members" before actually joining, from a scan or a link. */
@Composable
fun JoinGangDialog(code: String, onDismiss: () -> Unit, onJoined: (Group) -> Unit, onOpenExisting: (String) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<GangPreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        loading = true
        runCatching { c.groups.preview(code) }
            .onSuccess { preview = it; if (it == null) error = "No gang has the code $code. It may have changed." }
            .onFailure { error = it.friendly() }
        loading = false
    }
    val p = preview
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (p != null) GroupAvatar(p.icon, p.photoUrl, size = 72.dp) else GngIcon(GngIcons.Groups, null)
        },
        title = {
            Text(
                when {
                    loading -> "Finding the gang…"
                    p == null -> "Can't find that gang"
                    p.alreadyMember -> "You're already in ${p.name}"
                    else -> "Join ${p.name}?"
                },
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    p == null -> Text(error.orEmpty(), textAlign = TextAlign.Center)
                    else -> Text(
                        (if (p.memberCount == 1) "1 member" else "${p.memberCount} members") +
                            if (error != null) "\n$error" else "",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            when {
                p == null -> TextButton(onClick = onDismiss) { Text("OK") }
                p.alreadyMember -> FilledTonalButton(onClick = { onOpenExisting(p.id) }) { Text("Open") }
                else -> Button(
                    enabled = !joining,
                    onClick = {
                        joining = true
                        scope.launch {
                            runCatching { c.groups.join(code) }
                                .onSuccess(onJoined)
                                .onFailure { error = it.friendly(); joining = false }
                        }
                    },
                ) { Text(if (joining) "Joining…" else "Join") }
            }
        },
        dismissButton = { if (p != null) TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
