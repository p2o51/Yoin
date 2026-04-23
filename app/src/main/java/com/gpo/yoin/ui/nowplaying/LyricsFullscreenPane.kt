package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.edgeFade

/**
 * Fullscreen Lyrics viewer. Unlike the compact [LyricsDisplay] window
 * (which only renders a fixed 5-line slice and has no scroll), this one:
 *
 * - Renders every line in a scrollable [LazyColumn] at large-display typography.
 * - Smoothly auto-scrolls the current line towards the vertical center
 *   whenever playback advances or the user taps away.
 * - Accepts manual scroll — the user can peek ahead/back without fighting
 *   the auto-scroll; we only re-center when the active line actually
 *   changes.
 * - Masks the top + bottom with a fade so the moving scroll looks
 *   like it is breathing instead of hard-cutting at the tab/bar edges.
 */
@Composable
fun LyricsFullscreenPane(
    lyrics: List<LyricLine>,
    positionMs: Long,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                YoinLoadingIndicator(size = 36.dp)
            } else {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val currentIndex = remember(lyrics, positionMs) {
        findCurrentLyricIndex(lyrics, positionMs)
    }
    val listState = rememberLazyListState()

    // Centering: LazyColumn's scrollToItem takes a pixel offset relative
    // to the top of the visible area. We offset by ~-40% of the viewport
    // height so the active line lands roughly a third down from the top,
    // which reads as "center of attention" rather than literal midpoint.
    LaunchedEffect(currentIndex, listState) {
        if (currentIndex < 0) return@LaunchedEffect
        val viewportPx = listState.layoutInfo.viewportSize.height
        val offsetPx = -(viewportPx * 0.38f).toInt()
        listState.animateScrollToItem(
            index = currentIndex.coerceIn(0, lyrics.lastIndex),
            scrollOffset = offsetPx,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .edgeFade(top = 64.dp, bottom = 64.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 48.dp),
        ) {
            itemsIndexed(lyrics) { index, line ->
                LyricRow(
                    text = line.text,
                    isActive = index == currentIndex,
                )
            }
            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun LyricRow(
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
        animationSpec = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring(),
        label = "lyricColor",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.55f,
        animationSpec = com.gpo.yoin.ui.theme.YoinMotion.effectsSpring(),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = com.gpo.yoin.ui.theme.YoinMotion.defaultSpatialSpec(),
        label = "lyricScale",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
        ),
        color = textColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
    )
}

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

