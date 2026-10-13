package com.gng.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.random.Random

@Composable
fun EmojiAvatar(emoji: String, background: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}

/** A button that squishes when pressed. Small thing, big vibe. */
@Composable
fun BouncyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.scale(scale),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { Text(text) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun FunCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = MaterialTheme.shapes.medium,
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun EmptyState(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 56.sp)
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(
        "😵 $message",
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(12.dp),
    )
}

@Composable
fun RowGap(width: Dp = 8.dp) = Spacer(Modifier.width(width))

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

private class Particle(var x: Float, var y: Float, val vx: Float, var vy: Float, var rot: Float, val spin: Float, val emoji: String)

/** Emoji confetti that rains down for a few seconds. Used on birthdays. */
@Composable
fun EmojiConfetti(
    emojis: List<String> = listOf("🎉", "🎂", "🎈", "✨", "🥳"),
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val particles = remember { mutableStateListOf<Particle>() }
    val measurer: TextMeasurer = rememberTextMeasurer()
    var tick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        repeat(40) {
            particles += Particle(
                x = Random.nextFloat(), y = -Random.nextFloat() * 0.5f,
                vx = (Random.nextFloat() - 0.5f) * 0.002f, vy = 0.004f + Random.nextFloat() * 0.006f,
                rot = Random.nextFloat() * 360f, spin = (Random.nextFloat() - 0.5f) * 8f,
                emoji = emojis.random(),
            )
        }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            particles.forEach { p ->
                p.x += p.vx; p.y += p.vy; p.vy += 0.00008f; p.rot += p.spin
            }
            tick = now
            if (now - start > 6_000_000_000L) {
                particles.clear()
                break
            }
        }
    }
    Canvas(modifier) {
        if (tick < 0) return@Canvas // reading tick makes the canvas redraw every frame
        particles.forEach { p ->
            val pos = androidx.compose.ui.geometry.Offset(p.x * size.width, p.y * size.height)
            rotate(p.rot, pos) {
                drawText(measurer, p.emoji, topLeft = pos, style = TextStyle(fontSize = 26.sp))
            }
        }
    }
}
