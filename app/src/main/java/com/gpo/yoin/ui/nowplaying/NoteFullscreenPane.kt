package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteCard
import com.gpo.yoin.ui.component.NoteComposer
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteSortToggle
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import com.gpo.yoin.ui.component.sortNotes
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll

/**
 * Fullscreen Note page — the primary editable surface. Note history with
 * timeline/journal ordering, lyrics-style playhead highlight (tap an
 * anchored note to seek to its moment), inline delete, and a composer that
 * can auto-focus when the user enters from the Write pill.
 */
@Composable
fun NoteFullscreenPane(
    notes: List<SongNote>,
    sortMode: NoteSortMode,
    onSortModeChange: (NoteSortMode) -> Unit,
    positionMs: () -> Long,
    onSeekToMs: (Long) -> Unit,
    onSave: (String, Long?) -> Unit,
    onDelete: (String) -> Unit,
    autoFocusComposer: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth(),
    ) {
        if (notes.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        text = "还没有笔记",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "写下这首歌让你想到的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val sorted = remember(notes, sortMode) { sortNotes(notes, sortMode) }
            val currentPositionMs by rememberUpdatedState(positionMs)
            // derivedStateOf absorbs the 4Hz tick — rows recompose only when
            // the playhead crosses into another note's stretch.
            val currentNoteId by remember(sorted) {
                derivedStateOf { currentAnchoredNoteId(sorted, currentPositionMs()) }
            }
            val currentIndex by remember(sorted) {
                derivedStateOf { sorted.indexOfFirst { it.id == currentNoteId } }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${notes.size} 条笔记",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NoteSortToggle(mode = sortMode, onModeChange = onSortModeChange)
            }

            val notesListState = rememberLazyListState()
            // Lyrics-style follow: glide to the active note as playback moves,
            // but a user drag takes the wheel until the note list changes.
            var userScrolled by remember(sorted) { mutableStateOf(false) }
            LaunchedEffect(notesListState) {
                notesListState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) userScrolled = true
                }
            }
            LaunchedEffect(sorted, sortMode, currentIndex) {
                if (sortMode == NoteSortMode.Timeline && currentIndex >= 0 && !userScrolled) {
                    notesListState.animateScrollToItem(currentIndex)
                }
            }
            LazyColumn(
                state = notesListState,
                modifier = Modifier
                    .fillMaxWidth()
                    // weight(fill = false) instead of a fixed cap: when the IME
                    // opens the pane shrinks, and the list must yield so the
                    // composer stays visible above the keyboard.
                    .weight(1f, fill = false)
                    .heightIn(max = 360.dp)
                    .verticalEdgeFadeOnScroll(notesListState, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = sorted, key = SongNote::id) { note ->
                    NoteCard(
                        note = note,
                        isActive = note.id == currentNoteId,
                        onDelete = { onDelete(note.id) },
                        onClick = note.positionMs?.let { anchor -> { onSeekToMs(anchor) } },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 8.dp))

        NoteComposer(
            onSave = onSave,
            positionMs = positionMs,
            autoFocus = autoFocusComposer,
        )
    }
}
