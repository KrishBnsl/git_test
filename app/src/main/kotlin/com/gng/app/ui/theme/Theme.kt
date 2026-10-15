package com.gng.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.gng.app.data.ThemeMode

val LocalIsDark = staticCompositionLocalOf { false }

/** Dynamic colour needs Android 12+. Older phones always get the brand palette. */
val DynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun GngTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val colors = remember(accent, dark) {
        if (accent == Accent.Wallpaper && DynamicColorSupported) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            accentScheme(accent, dark)
        }
    }

    // The activity is edge-to-edge; make the system bar icons readable when the app theme
    // is forced light or dark against the system setting.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = colors, typography = GngTypography, shapes = GngShapes, content = content)
    }
}

/**
 * Stable per-person colours for avatars and chat names. Derived by rotating the hue of the
 * current scheme's container colours, so they stay in harmony with whatever palette is active,
 * wallpaper-derived or brand.
 */
data class AvatarColor(val container: Color, val content: Color)

fun avatarColors(scheme: ColorScheme): List<AvatarColor> {
    fun rotate(color: Color, degrees: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[0] = (hsl[0] + degrees) % 360f
        // Keep rotated hues from going muddy or neon.
        hsl[1] = hsl[1].coerceIn(0.3f, 0.6f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
    return (0 until 8).map { i ->
        val shift = i * 45f
        AvatarColor(rotate(scheme.primaryContainer, shift), rotate(scheme.onPrimaryContainer, shift))
    }
}

fun pickAvatarColor(palette: List<AvatarColor>, key: String): AvatarColor =
    palette[Math.floorMod(key.hashCode(), palette.size)]
