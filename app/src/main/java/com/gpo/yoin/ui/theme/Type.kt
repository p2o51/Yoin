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

/**
 * 记忆表面（Memories 卡 + 首页 Jump Back In）的字体法则（owner 定稿 2026-07-26，
 * 同日收窄）：
 *
 *  - **宋体只属于 AI 拟题（memoryTitle）**——那一枚生成的标题，在 JBI memory
 *    卡与 Memories 卡印章旁两处出现。其它标题（专辑名、歌名）维持 GSF，只做
 *    字号加大。Pixel 上 [FontFamily.Serif] 解析到 Noto Serif / Noto Serif CJK
 *    （思源宋体同源字形）；部分 OEM ROM 裁掉 CJK 衬线包时中文会静默落回黑体
 *    —— 层级仍由字号/字重兜底，不视为损坏。
 *  - **黑体 = 用户正文**：乐评、笔记的主体用系统默认字面（FontFamily.Default）。
 *  - **GSF = 其余一切**：界面标题、Yoin 文案（带署名）、数字、标签、按钮维持
 *    [GoogleSansFlex]。
 */
val YoinSerifTitle = FontFamily.Serif

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
