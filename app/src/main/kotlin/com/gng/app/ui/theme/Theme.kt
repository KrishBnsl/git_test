package com.gng.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gng.app.data.ThemeMode

// Bright & playful by day, neon by night.
private val Light = lightColorScheme(
    primary = Color(0xFFFF5C8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3F0018),
    secondary = Color(0xFFFFB627),
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = Color(0xFFFFEBC2),
    onSecondaryContainer = Color(0xFF2B1B00),
    tertiary = Color(0xFF13A89E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9F4EF),
    onTertiaryContainer = Color(0xFF00201D),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF221A1D),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF221A1D),
    surfaceVariant = Color(0xFFF6E7EA),
    onSurfaceVariant = Color(0xFF5A4A4F),
    surfaceContainer = Color(0xFFFCEFEF),
    surfaceContainerHigh = Color(0xFFF8E8EA),
    outline = Color(0xFF8E7A80),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF6FA3),
    onPrimary = Color(0xFF3F0018),
    primaryContainer = Color(0xFF7A1E44),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFB8FF5C),
    onSecondary = Color(0xFF1C3300),
    secondaryContainer = Color(0xFF34500A),
    onSecondaryContainer = Color(0xFFDBFFB0),
    tertiary = Color(0xFF4DE1FF),
    onTertiary = Color(0xFF00313B),
    tertiaryContainer = Color(0xFF004E5E),
    onTertiaryContainer = Color(0xFFB4F1FF),
    background = Color(0xFF15121C),
    onBackground = Color(0xFFECE3EA),
    surface = Color(0xFF15121C),
    onSurface = Color(0xFFECE3EA),
    surfaceVariant = Color(0xFF2A2533),
    onSurfaceVariant = Color(0xFFCDC2CC),
    surfaceContainer = Color(0xFF1F1B28),
    surfaceContainerHigh = Color(0xFF2A2533),
    outline = Color(0xFF968E99),
)

private val Shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

private val Type = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

val EmojiStyle = TextStyle(fontSize = 28.sp)

/** A stable, cheerful colour per friend so chat bubbles are easy to tell apart. */
fun friendColor(userId: String, dark: Boolean): Color {
    val palette = if (dark) {
        listOf(0xFF7A1E44, 0xFF34500A, 0xFF004E5E, 0xFF5B2A86, 0xFF7A4A00, 0xFF1F4E99)
    } else {
        listOf(0xFFFFD9E3, 0xFFFFEBC2, 0xFFC9F4EF, 0xFFE7DBFF, 0xFFFFDDC2, 0xFFD6E6FF)
    }
    return Color(palette[Math.floorMod(userId.hashCode(), palette.size)])
}

@Composable
fun GngTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = Type,
            shapes = Shapes,
            content = content,
        )
    }
}

val LocalIsDark = staticCompositionLocalOf { false }
