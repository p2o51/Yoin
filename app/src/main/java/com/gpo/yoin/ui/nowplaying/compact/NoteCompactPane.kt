package com.gpo.yoin.ui.nowplaying.compact

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.sortNotes
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.abs
import kotlinx.coroutines.flow.filter

/**
 * Read-only preview of the current song's notes, in the compact lyrics
 * language: plain timeline-ordered lines (each prefixed with its song-moment
 * stamp), the line the playhead is inside lit up, the list gliding to keep
 * it anchored — no cards, no controls. Tapping the compact pager area
 * promotes to [NowPlayingStageMode.Expanded] where notes become editable.
 */
@Composable
fun NoteCompactPane(
    notes: List<SongNote>,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Tap to write a note",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Compact is always song-timeline ordered — like lyrics, the pane IS the
    // song's shape; the 先后 journal view lives in the expanded page.
    val sorted = remember(notes) { sortNotes(notes, NoteSortMode.Timeline) }
    // derivedStateOf absorbs the 4Hz tick: rows only recompose when the
    // resolved note actually changes (same pattern as the lyrics panes).
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentIndex by remember(sorted) {
        derivedStateOf {
            val id = currentAnchoredNoteId(sorted, currentPositionMs())
            sorted.indexOfFirst { it.id == id }
        }
    }

    val listState = rememberLazyListState()
    var hasCentered by remember(sorted) { mutableStateOf(false) }
    // Same settle driver as the compact lyrics window: anchor the active
    // line ~22% from the top, re-anchoring on every viewport resize (the
    // pane is resized in place as the NP stage reshapes).
    LaunchedEffect(sorted, currentIndex, listState) {
        if (currentIndex < 0) return@LaunchedEffect
        val target = currentIndex.coerceIn(0, sorted.lastIndex)
        var firstAnchor = true
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect { viewportPx ->
                val offsetPx = -(viewportPx * 0.22f).toInt()
                when {
                    !hasCentered -> {
                        listState.scrollToItem(index = target, scrollOffset = offsetPx)
                        hasCentered = true
                    }
                    firstAnchor -> listState.animateScrollToItem(index = target, scrollOffset = offsetPx)
                    else -> listState.scrollToItem(index = target, scrollOffset = offsetPx)
                }
                firstAnchor = false
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .edgeFade(top = 16.dp, bottom = 24.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(sorted, key = { _, note -> note.id }) { index, note ->
                NoteLineItem(
                    stamp = note.positionMs?.let(::formatNotePosition),
                    text = note.content,
                    isActive = index == currentIndex && currentIndex >= 0,
                    distance = if (currentIndex >= 0) abs(index - currentIndex) else 99,
                )
            }
        }
    }
}

@Composable
private fun NoteLineItem(
    stamp: String?,
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    distance: Int = 0,
) {
    // Distance-based falloff, borrowed from the compact lyric lines.
    val targetAlpha = when {
        isActive -> 1f
        distance <= 1 -> 0.55f
        distance == 2 -> 0.40f
        else -> 0.28f
    }
    val textColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteLineColor",
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteLineAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.94f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "noteLineScale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (stamp != null) {
            Text(
                text = stamp,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            text = text,
            style = if (isActive) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}
