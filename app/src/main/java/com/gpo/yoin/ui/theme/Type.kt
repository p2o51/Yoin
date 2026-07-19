package com.gpo.yoin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R

val GoogleSansFlex = FontFamily(
    Font(R.font.google_sans_flex_400, FontWeight.Normal),
    Font(R.font.google_sans_flex_500, FontWeight.Medium),
    Font(R.font.google_sans_flex_600, FontWeight.SemiBold),
    Font(R.font.google_sans_flex_700, FontWeight.Bold),
)

/**
 * Rounded cuts from the FULL Google Sans Flex variable font (ROND 100 +
 * exact wght instancing) — the softened-terminal voice the four static
 * files can't produce. Reserved for the lyric current line, where the
 * rounding reads at display sizes.
 */
@OptIn(ExperimentalTextApi::class)
val GoogleSansFlexRounded = FontFamily(
    Font(
        R.font.google_sans_flex_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Medium.weight),
            FontVariation.Setting("ROND", 100f),
        ),
    ),
    Font(
        R.font.google_sans_flex_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.SemiBold.weight),
            FontVariation.Setting("ROND", 100f),
        ),
    ),
)

private val defaultTypography = Typography()

/**
 * MD3 type scale using Google Sans Flex, with tracking retuned for it.
 *
 * The M3 defaults carry Roboto's letter-spacing (body +0.25…0.5sp, labels
 * +0.5sp). Google Sans Flex is drawn with wider, rounder counters and is
 * meant to be set solid — and half of this app's content is CJK, where
 * positive tracking visibly scatters the glyphs. So: 0 across the scale,
 * except a hair (+0.1sp) on the two smallest label styles where tiny Latin
 * text benefits from a touch of air. Deliberate per-moment exceptions (the
 * Now Playing time's play-state tracking pulse) stay local to their screens.
 */
val YoinTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    displayMedium = defaultTypography.displayMedium.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    displaySmall = defaultTypography.displaySmall.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    headlineLarge = defaultTypography.headlineLarge.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    headlineMedium = defaultTypography.headlineMedium.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    headlineSmall = defaultTypography.headlineSmall.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    titleLarge = defaultTypography.titleLarge.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    titleMedium = defaultTypography.titleMedium.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    titleSmall = defaultTypography.titleSmall.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    bodyLarge = defaultTypography.bodyLarge.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    bodyMedium = defaultTypography.bodyMedium.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    bodySmall = defaultTypography.bodySmall.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    labelLarge = defaultTypography.labelLarge.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.sp,
    ),
    labelMedium = defaultTypography.labelMedium.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = defaultTypography.labelSmall.copy(
        fontFamily = GoogleSansFlex,
        letterSpacing = 0.1.sp,
    ),
)
