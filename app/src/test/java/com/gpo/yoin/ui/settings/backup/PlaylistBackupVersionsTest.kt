package com.gpo.yoin.ui.settings.backup

import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.BackupVersion
import com.gpo.yoin.data.backup.PlaylistBackupContent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBackupVersionsTest {
    private fun t(id: String) = BackupTrack(title = id, sourceTrackId = id)

    @Test
    fun should_restoreTheChosenVersionsSongs_when_anEarlierVersionIsPicked() {
        val old = PlaylistBackupContent(tracks = listOf(t("a"), t("b")))
        val backup = previewBackup().copy(versions = listOf(BackupVersion(since = 5L, until = 9L, content = old)))

        assertEquals(old, restoreSnapshot(backup, 5L).content)
        assertEquals(backup, restoreSnapshot(backup, null))
        // A version that is gone (synced away) restores the current one.
        assertEquals(backup, restoreSnapshot(backup, 99L))
    }

    @Test
    fun should_countAddedAndRemovedSongs_when_comparingVersions() {
        assertEquals(2 to 1, versionDiff(listOf(t("a"), t("b")), listOf(t("b"), t("c"), t("d"))))
    }
}
