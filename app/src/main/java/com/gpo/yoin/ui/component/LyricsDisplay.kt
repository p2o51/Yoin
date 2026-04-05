package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Synced lyrics display — auto-scrolls and highlights the current line.
 *
 * @param lyrics list of lyric lines (may be synced or unsynced)
 * @param positionMs current playback position in milliseconds
 */
@Composable
fun LyricsDisplay(
    lyrics: List<LyricLine>,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
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

    val scrollState = rememberScrollState()

    // Auto-scroll to current line
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            val lineHeight = 48 // approximate height per line in px
            val targetScroll = (currentIndex * lineHeight - lineHeight)
                .coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lyrics.forEachIndexed { index, line ->
            LyricLineItem(
                text = line.text,
                isActive = index == currentIndex,
            )
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
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Find the index of the lyric line active at [positionMs]. */
private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    // For unsynced lyrics (all startMs null), return -1
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
            ),
            positionMs = 12000L,
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
