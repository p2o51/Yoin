package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll

/**
 * Fullscreen Lyrics viewer. Unlike the compact [com.gpo.yoin.ui.component.LyricsDisplay]
 * window, this one renders every line, supports tap-to-seek, and lets the
 * parent action bar suspend / resume auto-centering.
 */
@Composable
fun LyricsFullscreenPane(
    lyrics: List<LyricLine>,
    positionMs: () -> Long,
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

    // derivedStateOf absorbs the 4Hz position tick: the index recomputes per
    // tick, but readers (the LaunchedEffect key, per-row isActive) only
    // recompose when the resolved line actually advances.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentIndex by remember(lyrics) {
        derivedStateOf { findCurrentLyricIndex(lyrics, currentPositionMs()) }
    }
    val listState = rememberLazyListState()
    // First centring is INSTANT (no animated jump) and happens the moment the
    // pane has a real viewport — so when the stage finishes expanding the active
    // line is already centred, instead of the "page expands, then lyrics jump"
    // beat. Resets per song so a new track re-centres instantly.
    var hasCentered by remember(lyrics) { mutableStateOf(false) }

    // Offset by ~38% of the viewport so the active line reads as the centre of
    // attention rather than a literal midpoint. KEYED on currentIndex / showTranslation
    // so the effect RESTARTS and re-reads them when the line advances or the
    // translation layer toggles — a captured `val` read inside snapshotFlow never
    // re-emits (records no snapshot state), which is why the expanded pane stopped
    // following. The compact LyricsDisplay keys on currentIndex for the same reason.
    // While the pane is still expanding the viewport grows 0 -> full, so we re-anchor
    // on every growth frame and the active line stays centred through and after it.
    // `lyrics` MUST be a key (same rule as LyricsDisplay): hasCentered is
    // remember(lyrics)-scoped, so a track change that leaves the derived index
    // numerically equal would otherwise keep the old coroutine alive with the
    // OLD list and OLD hasCentered captured, skipping the per-song instant
    // recentre and turning the next advance into a snap instead of a glide.
    LaunchedEffect(lyrics, listState, autoScrollEnabled, recenterRequestKey, currentIndex, showTranslation) {
        if (!autoScrollEnabled || currentIndex < 0) return@LaunchedEffect
        val target = currentIndex.coerceIn(0, lyrics.lastIndex)
        var firstAnchor = true
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect { vp ->
                val offsetPx = -(vp * 0.38f).toInt()
                when {
                    // Very first centre of the song: instant, no animation.
                    !hasCentered -> {
                        listState.scrollToItem(target, offsetPx)
                        hasCentered = true
                    }
                    // First reaction to this advance / translation toggle / recenter: glide.
                    firstAnchor -> listState.animateScrollToItem(target, offsetPx)
                    // Later viewport-growth frames mid-expand: snap so it tracks the
                    // growing viewport without lagging behind.
                    else -> listState.scrollToItem(target, offsetPx)
                }
                firstAnchor = false
            }
    }

    val latestOnUserScroll by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                latestOnUserScroll()
            }
        }
    }

    // Scroll-aware: the fades release at the list's ends, so the first and
    // last lyric lines read crisp instead of sitting permanently half-faded
    // above the tab group / Ask Gemini bar.
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalEdgeFadeOnScroll(listState, top = 64.dp, bottom = 64.dp),
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
    val clickableModifier = if (onTap != null) {
        Modifier.tapWithoutConsumingDrag(onTap = onTap)
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

private fun Modifier.tapWithoutConsumingDrag(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onTap()
            }
        }
    }

/**
 * Find the index of the lyric line active at [positionMs] — the LAST
 * timestamped line whose startMs is at or before the playhead. Untimed
 * lines (null startMs) are skipped; an all-untimed list yields -1. Runs
 * once per 250ms tick, so break as soon as a timestamped line passes the
 * playhead (provider lines arrive in ascending startMs order) instead of
 * scanning the whole list every call.
 */
private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    var result = -1
    for (i in lyrics.indices) {
        val start = lyrics[i].startMs ?: continue
        if (start > positionMs) break
        result = i
    }
    return result
}
