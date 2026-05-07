package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.edgeFade

/**
 * Fullscreen Lyrics viewer. Unlike the compact [com.gpo.yoin.ui.component.LyricsDisplay]
 * window, this one renders every line, supports tap-to-seek, and lets the
 * parent action bar suspend / resume auto-centering.
 */
@Composable
fun LyricsFullscreenPane(
    lyrics: List<LyricLine>,
    positionMs: Long,
    loading: Boolean,
    showTranslation: Boolean,
    autoScrollEnabled: Boolean,
    recenterRequestKey: Int,
    onUserScroll: () -> Unit,
    onSeekToMs: (Long) -> Unit,
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

    // Offset by ~38% of the viewport so the active line reads as the
    // center of attention instead of a literal midpoint.
    LaunchedEffect(currentIndex, listState, autoScrollEnabled, recenterRequestKey) {
        if (!autoScrollEnabled) return@LaunchedEffect
        if (currentIndex < 0) return@LaunchedEffect
        val viewportPx = listState.layoutInfo.viewportSize.height
        val offsetPx = -(viewportPx * 0.38f).toInt()
        listState.animateScrollToItem(
            index = currentIndex.coerceIn(0, lyrics.lastIndex),
            scrollOffset = offsetPx,
        )
    }

    val latestOnUserScroll by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                latestOnUserScroll()
            }
        }
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
                    translation = line.translation,
                    showTranslation = showTranslation,
                    isActive = index == currentIndex,
                    onTap = line.startMs?.let { ms -> { onSeekToMs(ms) } },
                )
            }
            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    translation: String?,
    showTranslation: Boolean,
    isActive: Boolean,
    onTap: (() -> Unit)?,
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
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier = if (onTap != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onTap,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(vertical = 6.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = textColor,
        )
        if (showTranslation && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = textColor.copy(alpha = if (isActive) 0.82f else 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
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
