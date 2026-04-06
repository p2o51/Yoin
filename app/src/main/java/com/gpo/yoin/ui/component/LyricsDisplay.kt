package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Synced lyrics display — fixed-height container showing the current line
 * (bold) plus the next few lines. Bottom fade mask hides overflow.
 * Near the end of a song, lines stay in place and only the highlight moves.
 *
 * @param lyrics list of lyric lines (may be synced or unsynced)
 * @param positionMs current playback position in milliseconds
 * @param visibleLines how many lines to show (including current)
 * @param fixedHeight the fixed height of the lyrics container
 */
@Composable
fun LyricsDisplay(
    lyrics: List<LyricLine>,
    positionMs: Long,
    modifier: Modifier = Modifier,
    visibleLines: Int = 5,
    fixedHeight: Dp = 160.dp,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "No lyrics available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val currentIndex = remember(lyrics, positionMs) {
        findCurrentLyricIndex(lyrics, positionMs)
    }

    // Clamp the window so end-of-song lyrics stay in place
    val maxStartIndex = (lyrics.size - visibleLines).coerceAtLeast(0)
    val startIndex = currentIndex.coerceAtLeast(0).coerceAtMost(maxStartIndex)
    val endIndex = (startIndex + visibleLines).coerceAtMost(lyrics.size)
    val visibleLyrics = lyrics.subList(startIndex, endIndex)
    val activeIndexInWindow = currentIndex - startIndex

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fixedHeight)
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Bottom fade mask
                val fadeHeight = 48.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - fadeHeight,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            visibleLyrics.forEachIndexed { index, line ->
                val isCurrentLine = (index == activeIndexInWindow && currentIndex >= 0)
                LyricLineItem(
                    text = line.text,
                    isActive = isCurrentLine,
                )
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricColor",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.6f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricAlpha",
    )

    Text(
        text = text,
        style = if (isActive) {
            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = textColor,
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .padding(vertical = 4.dp),
    )
}

/** Find the index of the lyric line active at [positionMs]. */
private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    if (lyrics.all { it.startMs == null }) return -1
    var result = -1
    for (i in lyrics.indices) {
        val start = lyrics[i].startMs ?: continue
        if (positionMs >= start) result = i
    }
    return result
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplaySyncedPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = listOf(
                LyricLine(startMs = 0, text = "First line of the song"),
                LyricLine(startMs = 5000, text = "Second line of the song"),
                LyricLine(startMs = 10000, text = "Third line — currently playing"),
                LyricLine(startMs = 15000, text = "Fourth line upcoming"),
                LyricLine(startMs = 20000, text = "Fifth line upcoming"),
                LyricLine(startMs = 25000, text = "Sixth line upcoming"),
            ),
            positionMs = 12000L,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplayEndOfSongPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = listOf(
                LyricLine(startMs = 0, text = "First line"),
                LyricLine(startMs = 5000, text = "Second line"),
                LyricLine(startMs = 10000, text = "Third line"),
                LyricLine(startMs = 15000, text = "Last line of the song"),
            ),
            positionMs = 16000L,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplayEmptyPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = emptyList(),
            positionMs = 0L,
        )
    }
}
