package com.gpo.yoin.ui.nowplaying.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteCard
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteSortToggle
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import com.gpo.yoin.ui.component.sortNotes

/**
 * Read-only preview of the current song's notes. Tapping the compact pager
 * area promotes to [NowPlayingStageMode.Expanded] where notes become
 * editable; this surface intentionally does NOT show a composer or delete
 * button. Like the compact lyrics window it follows the playhead: the note
 * whose anchor the song is currently inside highlights, and (in timeline
 * order) the list glides to keep it in view.
 */
@Composable
fun NoteCompactPane(
    notes: List<SongNote>,
    sortMode: NoteSortMode,
    onSortModeChange: (NoteSortMode) -> Unit,
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

    val sorted = remember(notes, sortMode) { sortNotes(notes, sortMode) }
    // derivedStateOf absorbs the 4Hz tick: rows only recompose when the
    // resolved note actually changes (same pattern as the lyrics panes).
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentNoteId by remember(sorted) {
        derivedStateOf { currentAnchoredNoteId(sorted, currentPositionMs()) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${notes.size} 条笔记",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoteSortToggle(mode = sortMode, onModeChange = onSortModeChange)
        }
        val listState = rememberLazyListState()
        // Keep the active note in view while playing through, timeline order
        // only — in journal order the jumps would be disorienting.
        val currentIndex by remember(sorted) {
            derivedStateOf { sorted.indexOfFirst { it.id == currentNoteId } }
        }
        LaunchedEffect(sorted, sortMode, currentIndex) {
            if (sortMode == NoteSortMode.Timeline && currentIndex >= 0) {
                listState.animateScrollToItem(currentIndex)
            }
        }
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = sorted,
                key = SongNote::id,
            ) { note ->
                NoteCard(
                    note = note,
                    isActive = note.id == currentNoteId,
                )
            }
        }
    }
}
