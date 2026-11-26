package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteSaveRequest
import com.gpo.yoin.ui.component.NoteTarget
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.notes.editor.NoteEditCard
import com.gpo.yoin.ui.notes.editor.NoteEditorState
import com.gpo.yoin.ui.notes.editor.noteEditCardColors
import kotlinx.coroutines.launch

/**
 * What a long press on a note opens in Now Playing (owner 2026-11-26): the app's one note menu
 * ([NoteEditCard], the album scrapbook's) in a sheet. The note's words are in the editable box, the keyboard
 * waits for a tap; under them Delete, Align to now (the playhead's moment, live) and Done; Share as image in
 * the corner while the words are as stored. Everything saves through [onSave] as an edit of the same note
 * ([NoteSaveRequest.editingNoteId]: an emptied note is deleted, with the page's undo). Put away with words
 * changed (a swipe, back), the change is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteMenuSheet(
    note: SongNote,
    target: NoteTarget,
    positionMs: () -> Long,
    onSave: (NoteSaveRequest) -> Unit,
    onDelete: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onShare: ((noteId: String) -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editor = remember(note.id) { NoteEditorState(note.content) }
    val unchanged by remember(editor, note.content) { derivedStateOf { editor.text.trim() == note.content.trim() } }
    // Closed by one of its own buttons (already saved, deleted or shared): the dismissal adds nothing.
    val handled = remember { booleanArrayOf(false) }
    val close: () -> Unit = {
        handled[0] = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
    fun save(text: String, atMs: Long?) {
        onSave(NoteSaveRequest(content = text.trim(), anchorMs = atMs, target = target, editingNoteId = note.id))
    }
    YoinModalBottomSheet(
        onDismissRequest = {
            // Swiped or backed away with words changed: they are kept (an emptied box is not a delete here).
            if (!handled[0] && !unchanged && editor.text.isNotBlank()) save(editor.text, note.positionMs)
            onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            NoteEditCard(
                editor = editor,
                positionMs = note.positionMs,
                colors = noteEditCardColors(),
                canDelete = true,
                autoFocus = false,
                onDelete = {
                    haptics.performReject()
                    onDelete(note.id)
                    close()
                },
                onDone = { text ->
                    if (!unchanged) {
                        haptics.performConfirm()
                        save(text, note.positionMs)
                    }
                    close()
                },
                nowMs = positionMs,
                onAlignToNow = { text, atMs ->
                    haptics.performTick()
                    save(text.ifBlank { note.content }, atMs)
                    close()
                },
                onShare = onShare?.takeIf { unchanged }?.let { share ->
                    {
                        haptics.performContextClick()
                        close()
                        share(note.id)
                    }
                },
            )
        }
    }
}
