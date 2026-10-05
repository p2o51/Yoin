package com.gpo.yoin.ui.nowplaying.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteLine
import com.gpo.yoin.ui.component.NoteLineEmphasis
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.sortNotes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import kotlinx.coroutines.flow.filter

/**
 * Read-only preview of the current song's notes, in the compact lyrics
 * language: plain timeline-ordered [NoteLine]s (the same lines as the
 * expanded page, at glance emphasis), the line the playhead is inside lit
 * up, the list gliding to keep it anchored — no cards, no controls.
 * Tapping the compact pager area promotes to [NowPlayingStageMode.Expanded]
 * where notes become editable.
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
                NoteLine(
                    stamp = note.positionMs?.let(::formatNotePosition),
                    text = note.content,
                    isActive = index == currentIndex && currentIndex >= 0,
                    emphasis = NoteLineEmphasis.Glance,
                    distance = if (currentIndex >= 0) abs(index - currentIndex) else null,
                )
            }
        }
    }
}

@Preview(name = "Note window", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun NoteCompactPanePreview() {
    fun note(id: String, at: Long, text: String) = SongNote(
        id = id,
        trackId = "preview",
        content = text,
        createdAt = at,
        updatedAt = at,
        title = "Streetlight Waltz",
        artist = "Mira Kade",
        positionMs = at,
    )
    YoinTheme {
        NoteCompactPane(
            notes = listOf(
                note("a", 12_000L, "Bass walks up the stairs"),
                note("b", 48_000L, "Everyone shouts here"),
                note("c", 83_000L, "Chorus comes in"),
            ),
            positionMs = { 50_000L },
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
