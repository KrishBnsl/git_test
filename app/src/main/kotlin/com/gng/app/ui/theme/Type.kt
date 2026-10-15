package com.gng.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gng.app.R

/** Plus Jakarta Sans (OFL), one variable font file covering every weight we use. */
@OptIn(ExperimentalTextApi::class)
val GngFont = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map { w ->
        Font(
            resId = R.font.plus_jakarta_sans,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private fun style(size: Int, line: Int, weight: Int, tracking: Float = 0f, family: FontFamily = GngFont) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

/**
 * The Material 3 type scale. Headings and titles are set in Plus Jakarta Sans (our look); body text and
 * labels use the phone's own font (Google Sans / Roboto on most phones), which reads best at chat sizes
 * and matches the rest of Material You.
 */
val GngTypography = Typography(
    displayLarge = style(57, 64, 800, -0.02f),
    displayMedium = style(45, 52, 800, -0.015f),
    displaySmall = style(36, 44, 700, -0.01f),
    headlineLarge = style(32, 40, 700, -0.01f),
    headlineMedium = style(28, 36, 700, -0.005f),
    headlineSmall = style(24, 32, 700),
    titleLarge = style(22, 28, 700),
    titleMedium = style(16, 24, 600, 0.005f),
    titleSmall = style(14, 20, 600, 0.005f),
    bodyLarge = style(16, 24, 400, 0.005f, FontFamily.Default),
    bodyMedium = style(14, 20, 400, 0.015f, FontFamily.Default),
    bodySmall = style(12, 16, 400, 0.025f, FontFamily.Default),
    labelLarge = style(14, 20, 500, 0.006f, FontFamily.Default),
    labelMedium = style(12, 16, 500, 0.03f, FontFamily.Default),
    labelSmall = style(11, 16, 500, 0.04f, FontFamily.Default),
)
