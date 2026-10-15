package com.gng.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * The colour the app is themed around, picked in Settings. Like Telegram and WhatsApp: one accent
 * on clean neutral surfaces. [Wallpaper] follows the phone's Material You colours instead.
 */
enum class Accent(val label: String, val seed: Color?) {
    Blue("Blue", Color(0xFF2F8CF0)),
    Green("Green", Color(0xFF00A884)),
    Purple("Purple", Color(0xFF7C5CFA)),
    Orange("Orange", Color(0xFFF07B2F)),
    Pink("Pink", Color(0xFFE8506E)),
    Wallpaper("Wallpaper", null),
    ;

    companion object {
        val Default = Blue
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** [accent]'s colours on neutral surfaces (white and soft greys by day, graphite at night). */
fun accentScheme(accent: Accent, dark: Boolean): ColorScheme {
    val seed = accent.seed ?: Accent.Default.seed!!
    // Fidelity keeps the accent close to the seed, so blue looks like blue rather than a pastel of it.
    val generated = dynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false, style = PaletteStyle.Fidelity)
    return if (dark) {
        generated.copy(
            background = Color(0xFF0F1216), onBackground = Color(0xFFE6E9EC),
            surface = Color(0xFF0F1216), onSurface = Color(0xFFE6E9EC),
            surfaceVariant = Color(0xFF283038), onSurfaceVariant = Color(0xFF9AA4AE),
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color(0xFF0B0E11), surfaceContainerLow = Color(0xFF14181D),
            surfaceContainer = Color(0xFF181D23), surfaceContainerHigh = Color(0xFF1F252C),
            surfaceContainerHighest = Color(0xFF283038),
            surfaceBright = Color(0xFF2B323A), surfaceDim = Color(0xFF0F1216),
            inverseSurface = Color(0xFFE6E9EC), inverseOnSurface = Color(0xFF1A1F24),
            outline = Color(0xFF6B7580), outlineVariant = Color(0xFF2E363F),
        )
    } else {
        generated.copy(
            background = Color(0xFFFFFFFF), onBackground = Color(0xFF111418),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111418),
            surfaceVariant = Color(0xFFE4E8EC), onSurfaceVariant = Color(0xFF5B6570),
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F8FA),
            surfaceContainer = Color(0xFFF1F3F5), surfaceContainerHigh = Color(0xFFEBEEF1),
            surfaceContainerHighest = Color(0xFFE4E8EC),
            surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDDE1E5),
            inverseSurface = Color(0xFF2B3036), inverseOnSurface = Color(0xFFF1F3F5),
            outline = Color(0xFF8A949E), outlineVariant = Color(0xFFD5DBE1),
        )
    }
}
