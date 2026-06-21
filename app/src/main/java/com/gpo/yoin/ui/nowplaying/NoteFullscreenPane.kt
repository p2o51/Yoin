package com.gpo.yoin.ui.nowplaying

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteCard
import com.gpo.yoin.ui.component.NoteComposer
import com.gpo.yoin.ui.component.edgeFade

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .edgeFade(bottom = 32.dp),
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
