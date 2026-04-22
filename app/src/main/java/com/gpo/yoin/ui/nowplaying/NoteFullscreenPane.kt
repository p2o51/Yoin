package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteCard
import com.gpo.yoin.ui.component.NoteComposer

/**
 * Fullscreen Note page — the primary editable surface after retiring
 * `NoteEditorSheet`. Shows note history with inline delete + a composer
 * that can auto-focus when the user enters from the Write pill.
 */
@Composable
fun NoteFullscreenPane(
    notes: List<SongNote>,
    onSave: (String) -> Unit,
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
            Text(
                text = "没有笔记",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = notes, key = SongNote::id) { note ->
                    NoteCard(
                        note = note,
                        onDelete = { onDelete(note.id) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 8.dp))

        NoteComposer(
            onSave = onSave,
            autoFocus = autoFocusComposer,
        )
    }
}
