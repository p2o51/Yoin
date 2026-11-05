package com.gpo.yoin.shared.backup

import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test

/** Android's PlaylistSnapshotsTest, against the neutral [SnapshotPlaylist]. */
class PlaylistSnapshotsTest {

    private val playlist = SnapshotPlaylist(
        id = "spotify:p1",
        name = "Road trip",
        comment = "  for the drive ",
        createdAt = null,
        tracks = listOf(
            track("t1", "Entropy", listOf("Beach Bunny", "Tegan"), isrc = "USQE92100257"),
            track("t2", "  ", listOf("Nobody")),
            track("t3", "Creep", listOf("Radiohead"), album = "Pablo Honey"),
        ),
        unreadable = listOf(
            SnapshotUnreadable(4, "Demo", "Me", UnexportedReason.LocalFile),
            SnapshotUnreadable(1, "Episode 4", null, UnexportedReason.Episode),
        ),
    )

    @Test
    fun should_keepTracksInOrderAndListWhatWasLeftOut_when_backingUp() {
        val backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 42L)

        assertEquals("Road trip", backup.name)
        assertEquals("spotify:p1", backup.sourcePlaylistId)
        assertEquals(42L, backup.firstBackedUpAt)
        assertEquals(42L, backup.lastChangedAt)
        assertEquals("p1", backup.sourceProfileId)
        assertEquals("for the drive", backup.content.description)
        assertEquals(
            listOf(
                BackupTrack("Entropy", listOf("Beach Bunny", "Tegan"), null, 200, "USQE92100257", "spotify:t1"),
                BackupTrack("Creep", listOf("Radiohead"), "Pablo Honey", 200, null, "spotify:t3"),
            ),
            backup.content.tracks,
        )
        assertEquals(
            listOf(
                BackupUnexported(1, "Episode 4", null, "episode"),
                BackupUnexported(4, "Demo", "Me", "local_file"),
                BackupUnexported(1, null, "Nobody", "unavailable"),
            ),
            backup.content.unexported,
        )
        assertEquals(2, backup.trackCount)
        assertEquals(3, backup.unexportedCount)
        assertEquals(emptyList<PlaylistRestoreRecord>(), backup.restores)
    }

    @Test
    fun should_readBackTheSameBackup_when_roundTrippedThroughTheRow() {
        val backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 42L)
            .copy(restores = listOf(PlaylistRestoreRecord(50L, "applemusic", "Apple", 1, 0, 1)))

        val row = PlaylistBackupCodec.toRow(backup)

        assertEquals(backup, PlaylistBackupCodec.fromRow(row))
        // Deterministic bytes: the same backup always encodes alike (sync hashes the text).
        assertEquals(row.contentJson, PlaylistBackupCodec.toRow(backup).contentJson)
    }

    @Test
    fun should_encodeAsAndroidDoes_when_writingTheJson() {
        val content = PlaylistBackupContent(
            tracks = listOf(BackupTrack("夜航", listOf("林间"), "夜航", 241, null, "subsonic:s-yehang-01")),
            unexported = listOf(BackupUnexported(3, null, "Me", "local_file")),
        )
        val restores = listOf(PlaylistRestoreRecord(5L, "subsonic", "demo", 7, 1, 2))

        // Android's Json { encodeDefaults; explicitNulls }: every field, nulls written, declaration order.
        assertEquals(
            """{"description":null,"tracks":[{"title":"夜航","artists":["林间"],"album":"夜航","durationSec":241,""" +
                """"isrc":null,"sourceId":"subsonic:s-yehang-01"}],"unexported":[{"position":3,"title":null,""" +
                """"artist":"Me","reason":"local_file"}]}""",
            PlaylistBackupCodec.encodeContent(content),
        )
        assertEquals(
            """[{"at":5,"targetProvider":"subsonic","targetLabel":"demo","added":7,"skipped":1,"notFound":2,"failed":false}]""",
            PlaylistBackupCodec.encodeRestores(restores),
        )
        assertEquals(
            """[{"since":1,"until":2,"content":{"description":null,"tracks":[],"unexported":[]}}]""",
            PlaylistBackupCodec.encodeVersions(listOf(BackupVersion(1L, 2L, PlaylistBackupContent()))),
        )
    }

    @Test
    fun should_readUnknownFieldsAndReasons_when_aNewerDeviceWroteTheBackup() {
        val content = PlaylistBackupCodec.decodeContent(
            """{"tracks":[{"title":"A","sourceId":"spotify:x","mood":"sunny"}],
               "unexported":[{"position":0,"reason":"hologram"}],"future":1}""",
        )

        assertEquals("A", content.tracks.single().title)
        assertNull(content.description)
        assertEquals(UnexportedReason.Unavailable, content.unexported.single().reasonKind)
    }

    @Test
    fun should_matchOnEveryArtistAndIsrc_when_backupTrackIsTurnedBackIntoATrack() {
        val track = PlaylistSnapshots.toMatchTrack(
            BackupTrack("Entropy", listOf("Beach Bunny", "Tegan"), "Album", 200, "USQE92100257", "spotify:t1"),
        )

        assertEquals("spotify:t1", track.id)
        assertEquals("Beach Bunny, Tegan", track.artist)
        assertEquals("USQE92100257", track.isrc)
        assertEquals("spotify", PlaylistSnapshots.providerOf(track.id))
        assertEquals("t1", PlaylistSnapshots.rawIdOf(track.id))
        assertNull(PlaylistSnapshots.providerOf("nocolon"))
    }

    @Test
    fun should_keepTheLatestFiveVersions_when_thePlaylistKeepsChanging() {
        var backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 0L)
        repeat(7) { change ->
            val changed = playlist.copy(tracks = playlist.tracks.take(1) + playlist.tracks.take(1).map {
                it.copy(id = "spotify:extra$change")
            })
            backup = PlaylistSnapshots.refreshed(backup, changed, "p1", "Chen", now = (change + 1) * 10L)
        }

        assertEquals(PlaylistSnapshots.KEEP_VERSIONS - 1, backup.versions.size)
        // Newest first: the version before the last change began at 60 and ended at 70.
        assertEquals(60L, backup.versions.first().since)
        assertEquals(70L, backup.versions.first().until)
        assertEquals(70L, backup.lastChangedAt)
    }

    @Test
    fun should_addNoVersion_when_nothingChanged() {
        val backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 0L)

        val again = PlaylistSnapshots.refreshed(backup, playlist, "p1", "Chen", now = 10L)

        assertEquals(emptyList<BackupVersion>(), again.versions)
        assertEquals(0L, again.lastChangedAt)
        assertEquals(10L, again.lastBackedUpAt)
    }

    @Test
    fun should_followAgainAndMoveForward_when_aGonePlaylistIsBackAndTheClockWentBack() {
        val backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 100L).markedGone(200L)
        assertFalse(backup.following)

        val again = PlaylistSnapshots.refreshed(backup, playlist, null, "Chen", now = 150L)

        assertTrue(again.following)
        assertEquals("p1", again.sourceProfileId)
        assertEquals(201L, again.updatedAt)
    }

    @Test
    fun should_readVersionsBack_when_roundTrippedThroughTheRow() {
        val backup = PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 0L)
        val changed = PlaylistSnapshots.refreshed(backup, playlist.copy(tracks = playlist.tracks.take(1)), "p1", "Chen", 5L)

        assertEquals(changed, PlaylistBackupCodec.fromRow(PlaylistBackupCodec.toRow(changed)))
        // A restore of the earlier version takes that version's songs.
        assertEquals(backup.content, changed.atVersion(0L).content)
        assertEquals(changed, changed.atVersion(null))
    }

    @Test
    fun should_appendToHistoryOnly_when_aRestoreIsRecorded() {
        val row = PlaylistBackupCodec.toRow(PlaylistSnapshots.backup("b1", playlist, "spotify", "p1", "Chen", now = 500L))

        val first = PlaylistBackupCodec.withRestore(row, PlaylistRestoreRecord(9L, "subsonic", "demo", 2, 0, 0), now = 9L)
        val second = PlaylistBackupCodec.withRestore(first, PlaylistRestoreRecord(10L, "subsonic", "demo", 1, 1, 0), now = 10L)

        assertEquals(row.contentJson, second.contentJson)
        assertEquals(listOf(9L, 10L), PlaylistBackupCodec.decodeRestores(second.restoresJson).map { it.at })
        // The clock is behind the row: updatedAt still moves forward.
        assertEquals(502L, second.updatedAt)
    }

    private fun track(
        id: String,
        title: String,
        artists: List<String>,
        album: String? = null,
        isrc: String? = null,
    ) = SnapshotTrack(id = "spotify:$id", title = title, artists = artists, album = album, durationSec = 200, isrc = isrc)
}
