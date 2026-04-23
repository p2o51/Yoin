package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.theme.YoinShapeTokens
import java.text.DateFormat
import java.util.Date

/**
 * Individual note row. Shared between the compact preview pane and the
 * fullscreen editable pane.
 */
@Composable
fun NoteCard(
    note: SongNote,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.Medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = if (onDelete != null) 4.dp else 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = formatRelativeTime(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) {
                Box(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Inline "write a new note" composer. Triggers [onSave] with the trimmed
 * text then clears itself. When [autoFocus] is true the text field grabs
 * focus on first composition — used when the user enters via the Write
 * pill so the keyboard pops up immediately.
 */
@Composable
fun NoteComposer(
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("这首歌让你想到什么？") },
            minLines = 4,
            maxLines = 10,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        onSave(trimmed)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return fmt.format(Date(epochMs))
}

/**
 * Lightweight modal sheet that wraps [NoteComposer] for the Write pill in
 * Now Playing. Mirrors the Devices / Queue sheet pattern so the bottom row
 * of pills behaves consistently — tap to open a sheet, save or dismiss.
 *
 * The text field auto-focuses on open so the keyboard is up right away.
 * Saving clears the draft and closes the sheet via [onDismiss]; the caller
 * owns the actual persistence through [onSave].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteNoteSheet(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 8.dp,
                    bottom = 16.dp + navBottom,
                ),
        ) {
            Text(
                text = "Write",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Jot a note for this song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(12.dp))
            NoteComposer(
                onSave = { text ->
                    onSave(text)
                    onDismiss()
                },
                autoFocus = true,
            )
        }
    }
}
