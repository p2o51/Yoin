package com.gpo.yoin.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.BackupUnexported
import com.gpo.yoin.data.backup.PlaylistBackupContent
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Settings › Playlist backups, inside [com.gpo.yoin.ui.settings.SettingsFeatureScreen]:
 * every backup, newest first. A row opens the backup's own page
 * ([PlaylistBackupActivity], Pattern A).
 */
@Composable
internal fun PlaylistBackupsPageContent() {
    val context = LocalContext.current
    val app = context.applicationContext as YoinApplication
    val viewModel: PlaylistBackupsViewModel = viewModel(factory = PlaylistBackupsViewModel.Factory(app.container))
    val uiState by viewModel.uiState.collectAsState()
    PlaylistBackupsList(
        uiState = uiState,
        onOpen = { backup -> context.startActivity(PlaylistBackupActivity.intent(context, backup.id)) },
    )
}

@Composable
internal fun PlaylistBackupsList(
    uiState: PlaylistBackupsUiState,
    onOpen: (PlaylistBackupRecord) -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = remember { System.currentTimeMillis() },
) {
    AnimatedContent(
        targetState = uiState,
        contentKey = { state ->
            when (state) {
                PlaylistBackupsUiState.Loading -> 0
                is PlaylistBackupsUiState.Content -> if (state.backups.isEmpty()) 1 else 2
            }
        },
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        label = "playlistBackups",
        modifier = modifier.fillMaxWidth(),
    ) { state ->
        when {
            state is PlaylistBackupsUiState.Loading ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { YoinLoadingIndicator() }
            state is PlaylistBackupsUiState.Content && state.backups.isEmpty() -> Text(
                text = stringResource(R.string.playlist_backups_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
            )
            state is PlaylistBackupsUiState.Content -> SettingsSegments {
                state.backups.forEach { backup ->
                    item(key = backup.id) { BackupRow(backup, nowMs, onClick = { onOpen(backup) }) }
                }
            }
        }
    }
}

@Composable
private fun BackupRow(backup: PlaylistBackupRecord, nowMs: Long, onClick: () -> Unit) {
    val service = serviceOf(backup.sourceProvider)
    val resources = LocalContext.current.resources
    SettingsItem(
        icon = service.glyph,
        iconTone = service.hue.tone(),
        title = backup.name,
        onClick = onClick,
        summaryContent = {
            Text(
                text = backup.sourceProfileLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // Following (a dot in the service's colour) or kept after its playlist went, then the last backup.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (backup.following) service.hue.tone().accent else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
                Text(
                    text = if (backup.following) {
                        formatBackupTime(backup.lastBackedUpAt, nowMs, resources)
                    } else {
                        stringResource(R.string.playlist_backup_source_gone)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (backup.unexportedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_backup_unexported,
                        backup.unexportedCount,
                        backup.unexportedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        },
        trailing = {
            Text(
                text = pluralStringResource(R.plurals.playlist_backup_songs, backup.trackCount, backup.trackCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

internal fun previewBackup(id: String = "b1", unexported: Int = 2, restores: Int = 0) = PlaylistBackupRecord(
    id = id,
    name = "Night drive",
    sourceProvider = MediaId.PROVIDER_SPOTIFY,
    sourceProfileLabel = "Chen",
    sourcePlaylistId = "spotify:p1",
    sourceProfileId = "p1",
    playlistCreatedAt = "2024-03-01T10:00:00Z",
    firstBackedUpAt = 1_760_600_000_000L,
    lastBackedUpAt = 1_760_850_000_000L,
    lastChangedAt = 1_760_800_000_000L,
    sourceGoneAt = null,
    content = PlaylistBackupContent(
        tracks = listOf(
            BackupTrack("Entropy", listOf("Beach Bunny"), "Emotional Creature", 221, null, "spotify:t1"),
            BackupTrack("Lemon", listOf("Kenshi Yonezu"), "Lemon", 255, null, "spotify:t2"),
            BackupTrack("Creep", listOf("Radiohead"), "Pablo Honey", 238, null, "spotify:t3"),
        ),
        unexported = List(unexported) { BackupUnexported(it + 3, "Voice memo $it", null, "local_file") },
    ),
    restores = List(restores) {
        PlaylistRestoreRecord(
            at = 1_760_900_000_000L,
            targetProvider = MediaId.PROVIDER_APPLE_MUSIC,
            targetLabel = "Apple Music",
            added = 2,
            skipped = 0,
            notFound = 1,
        )
    },
    updatedAt = 1_760_850_000_000L,
)

@Preview(showBackground = true)
@Composable
private fun PlaylistBackupsListPreview() {
    YoinTheme {
        Surface {
            Column(Modifier.padding(16.dp)) {
                PlaylistBackupsList(
                    uiState = PlaylistBackupsUiState.Content(
                        listOf(previewBackup(), previewBackup(id = "b2", unexported = 0)),
                    ),
                    onOpen = {},
                    nowMs = 1_760_900_000_000L,
                )
            }
        }
    }
}
