package com.gpo.yoin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * One row the user can pick inside [AddToPlaylistSheet].
 *
 * UI-layer view of a [com.gpo.yoin.data.model.Playlist] where `canWrite`
 * has already been validated by the source — callers must not pass
 * followed-but-not-owned playlists, since the sheet has no way to reject
 * them back at the user.
 */
data class AddToPlaylistRow(
    val id: MediaId,
    val name: String,
    val songCount: Int?,
    val coverArtUrl: String?,
)

/**
 * Bottom sheet that lets the user either create a new playlist or add the
 * pending tracks to an existing one. Invoked from long-press on ❤️ (Now
 * Playing) and from long-press on a song row.
 *
 * The sheet is stateless about what tracks are being added — the caller
 * passes callbacks that already close over the target track list. This
 * means the same component serves "add current song" and "add multiple
 * songs from selection" without knowing the difference.
 *
 * @param writablePlaylists Rows for playlists where the current profile
 *   user can write. Must be pre-filtered — the sheet does not gate.
 * @param onCreateAndAdd `null` hides the "Create new playlist…" row (used
 *   when the active source lacks `Capability.PLAYLISTS_WRITE`). Invoked
 *   after the user types a name; implementation typically creates the
 *   playlist and then adds the pending tracks.
 * @param onAddToExisting Invoked with the chosen playlist id when the
 *   user taps an existing row.
 * @param onDismiss Called when the sheet is swiped / back-pressed away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    writablePlaylists: List<AddToPlaylistRow>,
    onCreateAndAdd: ((name: String) -> Unit)?,
    onAddToExisting: (playlistId: MediaId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // See convention comment on `QueueSheet` for why we strip the
        // navigation-bar inset off `contentWindowInsets` and fold it
        // into the LazyColumn's `contentPadding` below.
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        Column {
            Text(
                text = "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            val navBottom = WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding()
            LazyColumn(
                // No height cap and no outer bottom padding — the sheet is
                // free to grow up to its natural max and the list runs
                // edge-to-edge against the sheet boundary. The nav-bar
                // safe-area inset is added here (not on the sheet) so the
                // bar space is scrollable and the expanded sheet truly
                // reaches the screen bottom instead of leaving a gutter.
                contentPadding = PaddingValues(bottom = 16.dp + navBottom),
            ) {
                if (onCreateAndAdd != null) {
                    item("create") {
                        CreateNewRow(onClick = { showCreateDialog = true })
                    }
                }
                items(
                    items = writablePlaylists,
                    key = { it.id.toString() },
                ) { row ->
                    PlaylistRow(
                        row = row,
                        onClick = { onAddToExisting(row.id) },
                    )
                }
            }
        }
    }

    if (showCreateDialog && onCreateAndAdd != null) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreateAndAdd(name)
            },
        )
    }
}

@Composable
private fun CreateNewRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(YoinShapeTokens.Small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Create new playlist…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlaylistRow(
    row: AddToPlaylistRow,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(YoinShapeTokens.Small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (row.coverArtUrl != null) {
                AsyncImage(
                    model = row.coverArtUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.songCount?.let { count ->
                Text(
                    text = "$count song${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AddToPlaylistSheetContentPreview() {
    YoinTheme {
        Column(modifier = Modifier.height(400.dp)) {
            Text(
                text = "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            CreateNewRow(onClick = {})
            PlaylistRow(
                row = AddToPlaylistRow(
                    id = MediaId.subsonic("42"),
                    name = "Road Trip",
                    songCount = 18,
                    coverArtUrl = null,
                ),
                onClick = {},
            )
            PlaylistRow(
                row = AddToPlaylistRow(
                    id = MediaId.spotify("pl1"),
                    name = "Chill",
                    songCount = 1,
                    coverArtUrl = null,
                ),
                onClick = {},
            )
        }
    }
}
