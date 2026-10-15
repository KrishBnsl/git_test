package com.gng.app.ui.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.icons.GngIcons
import com.gng.app.util.AvatarImage
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** WhatsApp's sticker size. */
private const val STICKER_PX = 512
/** Space around the image, relative to the sticker's width. */
private const val MARGIN = 0.06f
/** Sticker text, relative to the sticker's width. */
private const val TEXT_SIZE = 0.11f

private val TEXT_COLORS = listOf(Color.White, Color.Black, Color(0xFFFFD60A), Color(0xFFFF453A), Color(0xFF30D158))

/**
 * Make a sticker from a photo, like WhatsApp: cut out the subject (with a white sticker border),
 * pinch and drag to frame it, add a caption, and save it to "My stickers".
 *
 * [onSave] gets a 512x512 WebP and whether to send it straight away.
 */
@Composable
fun StickerMakerDialog(source: Uri, onDismiss: () -> Unit, onSave: (ByteArray, send: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var cutout by remember { mutableStateOf<Bitmap?>(null) }
    var useCutout by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.White) }
    // Framing, in fractions of the square (so it renders the same at any size).
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var textAt by remember { mutableStateOf(Offset(0.5f, 0.85f)) }

    LaunchedEffect(source) {
        photo = runCatching { withContext(Dispatchers.IO) { AvatarImage.decode(context, source, maxEdge = 1024) } }
            .onFailure { note = "Couldn't open that photo" }.getOrNull()
        // The cut-out model comes through Google Play services; start fetching it now if it isn't there yet.
        runCatching {
            ModuleInstall.getClient(context).installModules(
                ModuleInstallRequest.newBuilder().addApi(segmenter()).build(),
            )
        }
    }

    fun cutOut() {
        val p = photo ?: return
        if (cutout != null) {
            useCutout = !useCutout
            return
        }
        working = true
        note = null
        scope.launch {
            runCatching { removeBackground(p) }
                .onSuccess { cutout = it; useCutout = true }
                .onFailure {
                    note = if (it.message?.contains("download", ignoreCase = true) == true) {
                        "Getting the cut-out tool ready. Try again in a moment."
                    } else {
                        "Couldn't find a subject to cut out in this photo"
                    }
                }
            working = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { GngIcon(GngIcons.Close, "Cancel") }
                    Text("New sticker", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))

                val shown = if (useCutout) cutout else photo
                BoxWithConstraints(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).checkerboard()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panBy, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.3f, 4f)
                                pan += Offset(panBy.x / size.width, panBy.y / size.height)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val side = constraints.maxWidth.toFloat()
                    // The same margin the finished sticker gets (see render), so what you see is what you send.
                    val margin = with(LocalDensity.current) { (side * MARGIN).toDp() }
                    if (shown != null) {
                        Image(
                            shown.asImageBitmap(), null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(margin).graphicsLayer(
                                scaleX = scale, scaleY = scale, translationX = pan.x * side, translationY = pan.y * side,
                            ),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    if (caption.isNotBlank()) {
                        CaptionPreview(caption, textColor, side, textAt) { drag -> textAt = (textAt + drag).clampToUnit() }
                    }
                    if (working) CircularProgressIndicator()
                }
                Text(
                    note ?: "Pinch and drag to frame it. Drag the text to move it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                FilterChip(
                    selected = useCutout, enabled = photo != null && !working, onClick = ::cutOut,
                    label = { Text("Remove background") },
                    leadingIcon = { GngIcon(GngIcons.AutoAwesome, null, size = 18.dp) },
                )
                OutlinedTextField(
                    caption, { caption = it.take(40) }, label = { Text("Add text") }, singleLine = true,
                    leadingIcon = { GngIcon(GngIcons.Edit, null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLORS.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .border(if (c == textColor) 3.dp else 1.dp, MaterialTheme.colorScheme.primary.takeIf { c == textColor } ?: Color.Gray, CircleShape)
                                .clickable { textColor = c },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val ready = shown != null && !working
                    fun save(send: Boolean) {
                        val image = shown ?: return
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) {
                                render(image, outlined = useCutout, scale, pan, caption.trim(), textColor, textAt)
                            }
                            onSave(bytes, send)
                        }
                    }
                    OutlinedButton(onClick = { save(false) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Save") }
                    Button(onClick = { save(true) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Save & send") }
                }
            }
        }
    }
}

/** The caption over the preview, drawn the same way it'll be rendered: bold, with a dark outline. */
@Composable
private fun CaptionPreview(text: String, color: Color, side: Float, at: Offset, onDrag: (Offset) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontSize = with(density) { (side * TEXT_SIZE).toSp() }
    val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    val layout = remember(text, fontSize) { measurer.measure(text, style) }
    val w = layout.size.width
    val h = layout.size.height
    Box(
        Modifier.fillMaxSize(),
    ) {
        Canvas(
            Modifier
                .offset { IntOffset((at.x * side - w / 2f).roundToInt(), (at.y * side - h / 2f).roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(side) { detectDragGestures { change, drag -> change.consume(); onDrag(Offset(drag.x / side, drag.y / side)) } },
        ) {
            drawText(layout, color = outlineFor(color), drawStyle = Stroke(width = fontSize.toPx() * 0.16f))
            drawText(layout, color = color)
        }
    }
}

private fun outlineFor(c: Color) = if (c == Color.Black) Color.White else Color.Black

private fun Offset.clampToUnit() = Offset(x.coerceIn(0.05f, 0.95f), y.coerceIn(0.05f, 0.95f))

/** The usual "transparent" grey squares behind the sticker. */
private fun Modifier.checkerboard(): Modifier = this.background(Color(0xFFE8E8E8)).drawBehind {
    val cell = 12.dp.toPx()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(Color(0xFFD0D0D0), Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private fun segmenter() = SubjectSegmentation.getClient(
    SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
)

/** The photo's main subject on a transparent background (ML Kit, on the phone). */
private suspend fun removeBackground(photo: Bitmap): Bitmap {
    val result = segmenter().process(InputImage.fromBitmap(photo, 0)).await()
    val fg = result.foregroundBitmap ?: error("No subject found")
    return trimTransparent(fg)
}

/** Crops away fully transparent edges, so the subject fills the frame. */
private fun trimTransparent(b: Bitmap): Bitmap {
    val w = b.width
    val h = b.height
    val pixels = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((pixels[y * w + x] ushr 24) > 16) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < 0) error("No subject found")
    return Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/** The finished sticker: framed image (with a white border if it's a cut-out) and caption, as a 512px WebP. */
private fun render(
    image: Bitmap,
    outlined: Boolean,
    scale: Float,
    pan: Offset,
    caption: String,
    textColor: Color,
    textAt: Offset,
): ByteArray {
    val size = STICKER_PX.toFloat()
    val out = Bitmap.createBitmap(STICKER_PX, STICKER_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    // Same framing as the preview: fit inside the square with a margin, then the person's pinch and drag.
    val margin = size * MARGIN
    val fit = min((size - 2 * margin) / image.width, (size - 2 * margin) / image.height) * scale
    val w = image.width * fit
    val h = image.height * fit
    val left = (size - w) / 2 + pan.x * size
    val top = (size - h) / 2 + pan.y * size
    val dst = android.graphics.RectF(left, top, left + w, top + h)
    val smooth = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (outlined) {
        // A white sticker border: the cut-out's shape drawn in white, nudged in every direction, underneath.
        val border = Paint(smooth).apply { colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN) }
        val r = size * 0.018f
        for (i in 0 until 24) {
            val a = Math.PI * 2 * i / 24
            val dx = (cos(a) * r).toFloat()
            val dy = (sin(a) * r).toFloat()
            canvas.drawBitmap(image, null, android.graphics.RectF(dst.left + dx, dst.top + dy, dst.right + dx, dst.bottom + dy), border)
        }
    }
    canvas.drawBitmap(image, null, dst, smooth)

    if (caption.isNotEmpty()) {
        val textSize = size * TEXT_SIZE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = textColor.toArgb()
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = textSize * 0.16f
            strokeJoin = Paint.Join.ROUND
            color = outlineFor(textColor).toArgb()
        }
        // Shrink long captions to fit the width.
        val maxWidth = size * 0.92f
        val measured = fill.measureText(caption)
        if (measured > maxWidth) {
            fill.textSize *= maxWidth / measured
            stroke.textSize = fill.textSize
            stroke.strokeWidth = fill.textSize * 0.16f
        }
        val x = textAt.x * size
        val baseline = textAt.y * size - (fill.ascent() + fill.descent()) / 2
        canvas.drawText(caption, x, baseline, stroke)
        canvas.drawText(caption, x, baseline, fill)
    }

    return ByteArrayOutputStream().use { bytes ->
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        // WhatsApp's limit is 100 KB; step the quality down until it fits.
        var quality = 90
        do {
            bytes.reset()
            out.compress(format, quality, bytes)
            quality -= 15
        } while (bytes.size() > 100_000 && quality > 20)
        bytes.toByteArray()
    }
}
