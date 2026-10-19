package com.gpo.yoin.data.backup

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistUnreadableItem
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSnapshotsTest {

    private val playlist = Playlist(
        id = MediaId.spotify("p1"),
        name = "Road trip",
        owner = "me",
        coverArt = null,
        songCount = 3,
        durationSec = null,
        comment = "  for the drive ",
        tracks = listOf(
            track("t1", "Entropy", "Beach Bunny", isrc = "USQE92100257", artists = listOf("Beach Bunny", "Tegan")),
            track("t2", "  ", "Nobody"),
            track("t3", "Creep", "Radiohead", album = "Pablo Honey"),
        ),
        unreadable = listOf(
            PlaylistUnreadableItem(4, "Demo", "Me", PlaylistUnreadableItem.Reason.LocalFile),
            PlaylistUnreadableItem(1, "Episode 4", null, PlaylistUnreadableItem.Reason.Episode),
        ),
    )

    @Test
    fun should_keepTracksInOrderAndListWhatWasLeftOut_when_backingUp() {
        val backup = PlaylistSnapshots.backup("b1", playlist, MediaId.PROVIDER_SPOTIFY, "Chen", now = 42L)

        assertEquals("Road trip", backup.name)
        assertEquals("spotify:p1", backup.sourcePlaylistId)
        assertEquals(42L, backup.backedUpAt)
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
        val backup = PlaylistSnapshots.backup("b1", playlist, MediaId.PROVIDER_SPOTIFY, "Chen", now = 42L)
            .copy(restores = listOf(PlaylistRestoreRecord(50L, MediaId.PROVIDER_APPLE_MUSIC, "Apple", 1, 0, 1)))

        val row = PlaylistBackupCodec.toEntity(backup)

        assertEquals(2, row.trackCount)
        assertEquals(3, row.unexportedCount)
        assertEquals(backup, PlaylistBackupCodec.fromEntity(row))
        // Deterministic bytes: the same backup always encodes alike (sync hashes the text).
        assertEquals(row.contentJson, PlaylistBackupCodec.toEntity(backup).contentJson)
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
        val track = PlaylistSnapshots.toTrack(
            BackupTrack("Entropy", listOf("Beach Bunny", "Tegan"), "Album", 200, "USQE92100257", "spotify:t1"),
        )

        assertEquals(MediaId.spotify("t1"), track.id)
        assertEquals("Beach Bunny, Tegan", track.artist)
        assertEquals(listOf("Beach Bunny", "Tegan"), track.creditedArtists)
        assertEquals("USQE92100257", track.extras[Track.EXTRA_ISRC])
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        artists: List<String> = emptyList(),
    ) = Track(
        id = MediaId.spotify(id),
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = buildMap {
            isrc?.let { put(Track.EXTRA_ISRC, it) }
            if (artists.size > 1) put(Track.EXTRA_ARTISTS, Track.joinArtists(artists))
        },
    )
}
