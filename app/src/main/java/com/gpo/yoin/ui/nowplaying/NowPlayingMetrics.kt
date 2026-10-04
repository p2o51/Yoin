package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Content minimums for the height budgets (NowPlayingBudget.kt), read from
 * the real text styles at the current font scale — once per window / font
 * scale / Cast state, never per frame. A larger font grows a slot instead
 * of clipping it.
 */

/** The single column, laid out [width] wide with controls of [controlSize]. */
@Composable
internal fun rememberStageColumnMetrics(
    width: Dp,
    controlSize: Dp,
    castVisible: Boolean,
): StageColumnMetrics {
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val transportWidth = (width - ColumnContentInset * 2).coerceAtLeast(0.dp)
    val fit = rememberPlaybackControlsFit(maxWidth = transportWidth, controlSize = controlSize, hasExpandToggle = false)
    return remember(transportWidth, fit, castVisible, typography, density) {
        StageColumnMetrics(
            tabText = typography.labelLarge.lineDp(density),
            transport = playbackControlsHeight(fit.controlSize, transportWidth, typography.labelLarge.lineDp(density)),
            hero = typography.titleMedium.lineDp(density) + HeroLineGap + typography.displaySmall.lineDp(density),
            heroOneLine = typography.displaySmall.lineDp(density),
            pills = pillRowHeight(castVisible),
            oneLineContent = typography.bodyLarge.lineDp(density) + LyricLinePads,
            lyric = lyricWindowMetrics(typography.bodyLarge, typography.bodyMedium, density),
        )
    }
}

/** The DualPane left stack, always measured at [DualPaneStackWidth]. */
@Composable
internal fun rememberDualPaneMetrics(
    castVisible: Boolean,
    navBottom: Dp,
): DualPaneMetrics {
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val fit = rememberPlaybackControlsFit(maxWidth = DualPaneStackWidth, controlSize = 56.dp, hasExpandToggle = false)
    return remember(fit, castVisible, navBottom, typography, density) {
        val artist = typography.titleMedium.lineDp(density) + WideTitleLineGap
        val titleLine = typography.headlineMedium.lineDp(density)
        DualPaneMetrics(
            transport = playbackControlsHeight(
                fit.controlSize,
                DualPaneStackWidth,
                typography.labelLarge.lineDp(density),
            ),
            titleOneLine = artist + titleLine,
            titleTwoLines = artist + titleLine * 2,
            pills = pillRowHeight(castVisible),
            navBottom = navBottom,
        )
    }
}

private fun TextStyle.lineDp(density: Density): Dp = with(density) {
    if (lineHeight.isSp) lineHeight.toDp() else fontSize.toDp() * LineHeightFallback
}

private fun lyricWindowMetrics(active: TextStyle, inactive: TextStyle, density: Density) = LyricWindowMetrics(
    activeLine = active.lineDp(density) + LyricLinePads,
    inactiveLine = inactive.lineDp(density) + LyricLinePads,
)

/** The pills are 44; the Cast button's 48dp touch target sets the row when it shows. */
private fun pillRowHeight(castVisible: Boolean): Dp = if (castVisible) 48.dp else 44.dp

/** The column's side padding the transport and pills sit inside. */
private val ColumnContentInset = 24.dp
private val HeroLineGap = 2.dp
private val WideTitleLineGap = 2.dp

/** LyricsDisplay / OneLineLyricPreview lines carry 4dp above and below. */
private val LyricLinePads = 8.dp
private const val LineHeightFallback = 1.25f
