package com.gng.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

private class Piece(
    var x: Float, var y: Float,
    val vx: Float, var vy: Float,
    var rotation: Float, val spin: Float,
    val color: Color, val shape: Int, val size: Float,
)

/** Coloured confetti in the current theme's accent colours. Plays once for a few seconds. */
@Composable
fun Confetti(modifier: Modifier = Modifier.fillMaxSize(), durationMs: Long = 4500) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) {
        listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.tertiaryContainer)
    }
    val pieces = remember { mutableListOf<Piece>() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        repeat(46) {
            pieces += Piece(
                x = Random.nextFloat(), y = -Random.nextFloat() * 0.6f,
                vx = (Random.nextFloat() - 0.5f) * 0.0016f, vy = 0.003f + Random.nextFloat() * 0.005f,
                rotation = Random.nextFloat() * 360f, spin = (Random.nextFloat() - 0.5f) * 10f,
                color = palette.random().copy(alpha = 0.85f), shape = Random.nextInt(3), size = 5f + Random.nextFloat() * 6f,
            )
        }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            pieces.forEach { p ->
                p.x += p.vx; p.y += p.vy; p.vy += 0.00005f; p.rotation += p.spin
            }
            frame = now
            if ((now - start) / 1_000_000 > durationMs) {
                pieces.clear()
                frame = 0
                break
            }
        }
    }

    Canvas(modifier) {
        if (frame < 0) return@Canvas // reading the frame state makes the canvas redraw every frame
        pieces.forEach { p ->
            val centre = Offset(p.x * size.width, p.y * size.height)
            val s = p.size * density
            rotate(p.rotation, centre) {
                when (p.shape) {
                    0 -> drawCircle(p.color, radius = s / 2, center = centre)
                    1 -> drawRoundRect(p.color, centre - Offset(s / 2, s / 2), Size(s, s), CornerRadius(s * 0.2f))
                    else -> drawRoundRect(p.color, centre - Offset(s, s * 0.25f), Size(s * 2, s * 0.5f), CornerRadius(s * 0.25f))
                }
            }
        }
    }
}
