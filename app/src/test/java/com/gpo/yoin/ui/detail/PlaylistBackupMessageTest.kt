package com.gpo.yoin.ui.detail

import com.gpo.yoin.R
import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.BackupUnexported
import com.gpo.yoin.data.backup.PlaylistBackupContent
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBackupMessageTest {

    @Test
    fun should_countSongs_when_everythingWasBackedUp() {
        assertEquals(
            UiText.Plural(R.plurals.detail_playlist_backup_done, 2, listOf(2)),
            backupMessage(backup(tracks = 2, unexported = 0)),
        )
    }

    @Test
    fun should_countOfAllEntries_when_someCouldNotBeBackedUp() {
        assertEquals(
            UiText.Plural(R.plurals.detail_playlist_backup_partial, 5, listOf(3, 5)),
            backupMessage(backup(tracks = 3, unexported = 2)),
        )
    }

    private fun backup(tracks: Int, unexported: Int) = PlaylistBackupRecord(
        id = "b",
        name = "Mix",
        sourceProvider = MediaId.PROVIDER_SPOTIFY,
        sourceProfileLabel = "Chen",
        sourcePlaylistId = "spotify:p",
        sourceProfileId = null,
        playlistCreatedAt = null,
        firstBackedUpAt = 0L,
        lastBackedUpAt = 0L,
        lastChangedAt = 0L,
        sourceGoneAt = null,
        content = PlaylistBackupContent(
            tracks = List(tracks) { BackupTrack("T$it", sourceTrackId = "spotify:t$it") },
            unexported = List(unexported) { BackupUnexported(it, reason = "local_file") },
        ),
        restores = emptyList(),
        updatedAt = 0L,
    )
}
