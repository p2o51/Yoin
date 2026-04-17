package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import com.gpo.yoin.data.remote.Song as SubsonicSong
import com.gpo.yoin.data.remote.StructuredLyrics
import com.gpo.yoin.data.remote.SyncedLine
import com.gpo.yoin.data.remote.LyricsList as SubsonicLyricsList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicMappersTest {

    @Test
    fun should_namespace_all_ids_with_subsonic_provider_when_mapping_song() {
        val song = SubsonicSong(
            id = "song-1",
            title = "Title",
            artist = "Artist",
            artistId = "artist-1",
            album = "Album",
            albumId = "album-1",
            coverArt = "cover-1",
            duration = 240,
            track = 3,
            year = 2024,
            genre = "Pop",
            userRating = 4,
            starred = "2024-01-01T00:00:00",
            bitRate = 320,
            size = 12_345_678L,
            contentType = "audio/flac",
            suffix = "flac",
            path = "Artist/Album/03 - Title.flac",
        )

        val track = song.toTrack()

        assertEquals(MediaId.subsonic("song-1"), track.id)
        assertEquals(MediaId.subsonic("artist-1"), track.artistId)
        assertEquals(MediaId.subsonic("album-1"), track.albumId)
        assertEquals(CoverRef.SourceRelative("cover-1"), track.coverArt)
        assertEquals(240, track.durationSec)
        assertEquals(3, track.trackNumber)
        assertEquals(4, track.userRating)
        assertTrue(track.isStarred)
        assertEquals("320", track.extras["subsonic.bitRate"])
        assertEquals("12345678", track.extras["subsonic.size"])
        assertEquals("audio/flac", track.extras["subsonic.contentType"])
    }

    @Test
    fun should_treat_blank_starred_as_not_starred_when_mapping_song() {
        val song = SubsonicSong(id = "s", title = null, starred = null)
        assertTrue(!song.toTrack().isStarred)

        val blankStarred = SubsonicSong(id = "s", title = null, starred = "")
        assertTrue(!blankStarred.toTrack().isStarred)
    }

    @Test
    fun should_fall_back_to_album_id_as_cover_ref_when_mapping_album_without_cover() {
        val album = SubsonicAlbum(id = "album-9", name = "Self Titled", coverArt = null)

        val mapped = album.toAlbum()

        assertEquals(CoverRef.SourceRelative("album-9"), mapped.coverArt)
    }

    @Test
    fun should_produce_synced_lyrics_when_line_starts_are_present() {
        val lyrics = SubsonicLyricsList(
            structuredLyrics = listOf(
                StructuredLyrics(
                    lang = "en",
                    synced = true,
                    line = listOf(
                        SyncedLine(start = 0L, value = "hello"),
                        SyncedLine(start = 1500L, value = "world"),
                    ),
                ),
            ),
        )

        val mapped = lyrics.toLyrics()

        assertNotNull(mapped)
        assertTrue(mapped is Lyrics.Synced)
        assertEquals(2, (mapped as Lyrics.Synced).lines.size)
        assertEquals(1500L, mapped.lines[1].startMs)
        assertEquals("en", mapped.language)
    }

    @Test
    fun should_produce_unsynced_lyrics_when_no_line_starts_are_present() {
        val lyrics = SubsonicLyricsList(
            structuredLyrics = listOf(
                StructuredLyrics(
                    lang = null,
                    synced = false,
                    line = listOf(
                        SyncedLine(start = null, value = "first"),
                        SyncedLine(start = null, value = "second"),
                    ),
                ),
            ),
        )

        val mapped = lyrics.toLyrics()

        assertNotNull(mapped)
        assertTrue(mapped is Lyrics.Unsynced)
        assertEquals("first\nsecond", (mapped as Lyrics.Unsynced).text)
    }

    @Test
    fun should_return_null_lyrics_when_list_is_empty() {
        val lyrics = SubsonicLyricsList(structuredLyrics = emptyList())
        assertNull(lyrics.toLyrics())
    }
}
