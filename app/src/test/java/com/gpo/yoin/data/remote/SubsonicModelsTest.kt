package com.gpo.yoin.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicModelsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun should_parsePingResponse_when_statusOk() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1"
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        assertEquals("ok", response.subsonicResponse.status)
        assertEquals("1.16.1", response.subsonicResponse.version)
        assertNull(response.subsonicResponse.error)
    }

    @Test
    fun should_parseErrorResponse_when_statusFailed() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "failed",
                "version": "1.16.1",
                "error": {
                  "code": 40,
                  "message": "Wrong username or password"
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        assertEquals("failed", response.subsonicResponse.status)
        assertEquals(40, response.subsonicResponse.error?.code)
        assertEquals("Wrong username or password", response.subsonicResponse.error?.message)
    }

    @Test
    fun should_parseAlbum_when_fullFieldsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "album": {
                  "id": "al-1",
                  "name": "Test Album",
                  "artist": "Test Artist",
                  "artistId": "ar-1",
                  "coverArt": "al-1",
                  "songCount": 10,
                  "duration": 3600,
                  "year": 2024,
                  "genre": "Rock",
                  "starred": "2024-01-01T00:00:00Z",
                  "song": [
                    {
                      "id": "s-1",
                      "title": "Song One",
                      "album": "Test Album",
                      "artist": "Test Artist",
                      "track": 1,
                      "duration": 240,
                      "bitRate": 320,
                      "albumId": "al-1",
                      "artistId": "ar-1"
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val album = response.subsonicResponse.album!!
        assertEquals("al-1", album.id)
        assertEquals("Test Album", album.name)
        assertEquals("Test Artist", album.artist)
        assertEquals(10, album.songCount)
        assertEquals(3600, album.duration)
        assertEquals(2024, album.year)
        assertEquals("Rock", album.genre)
        assertEquals(1, album.song.size)
        assertEquals("s-1", album.song[0].id)
        assertEquals("Song One", album.song[0].title)
        assertEquals(320, album.song[0].bitRate)
    }

    @Test
    fun should_parseArtists_when_indexPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artists": {
                  "index": [
                    {
                      "name": "A",
                      "artist": [
                        {"id": "ar-1", "name": "ABBA", "albumCount": 5},
                        {"id": "ar-2", "name": "AC/DC", "albumCount": 12}
                      ]
                    },
                    {
                      "name": "B",
                      "artist": [
                        {"id": "ar-3", "name": "Beatles", "albumCount": 20}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val artists = response.subsonicResponse.artists!!
        assertEquals(2, artists.index.size)
        assertEquals("A", artists.index[0].name)
        assertEquals(2, artists.index[0].artist.size)
        assertEquals("ABBA", artists.index[0].artist[0].name)
        assertEquals(5, artists.index[0].artist[0].albumCount)
    }

    @Test
    fun should_parseSearchResult_when_allTypesPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {
                  "artist": [{"id": "ar-1", "name": "Queen"}],
                  "album": [{"id": "al-1", "name": "A Night at the Opera"}],
                  "song": [{"id": "s-1", "title": "Bohemian Rhapsody", "duration": 354}]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val result = response.subsonicResponse.searchResult3!!
        assertEquals(1, result.artist.size)
        assertEquals("Queen", result.artist[0].name)
        assertEquals(1, result.album.size)
        assertEquals("A Night at the Opera", result.album[0].name)
        assertEquals(1, result.song.size)
        assertEquals("Bohemian Rhapsody", result.song[0].title)
    }

    @Test
    fun should_parseLyrics_when_syncedLyricsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "lyricsList": {
                  "structuredLyrics": [
                    {
                      "lang": "eng",
                      "synced": true,
                      "line": [
                        {"start": 0, "value": "First line"},
                        {"start": 5000, "value": "Second line"}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val lyrics = response.subsonicResponse.lyricsList!!
        assertEquals(1, lyrics.structuredLyrics.size)
        assertTrue(lyrics.structuredLyrics[0].synced)
        assertEquals("eng", lyrics.structuredLyrics[0].lang)
        assertEquals(2, lyrics.structuredLyrics[0].line.size)
        assertEquals(0L, lyrics.structuredLyrics[0].line[0].start)
        assertEquals("First line", lyrics.structuredLyrics[0].line[0].value)
        assertEquals(5000L, lyrics.structuredLyrics[0].line[1].start)
    }

    @Test
    fun should_parseStarred_when_mixedContent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "starred2": {
                  "artist": [{"id": "ar-1", "name": "Radiohead", "starred": "2024-06-01"}],
                  "album": [{"id": "al-1", "name": "OK Computer", "starred": "2024-06-01"}],
                  "song": [{"id": "s-1", "title": "Karma Police", "starred": "2024-06-01"}]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val starred = response.subsonicResponse.starred2!!
        assertEquals(1, starred.artist.size)
        assertEquals("Radiohead", starred.artist[0].name)
        assertEquals(1, starred.album.size)
        assertEquals(1, starred.song.size)
    }

    @Test
    fun should_parseRandomSongs_when_songsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "randomSongs": {
                  "song": [
                    {"id": "s-1", "title": "Random Song 1", "duration": 180},
                    {"id": "s-2", "title": "Random Song 2", "duration": 210}
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val random = response.subsonicResponse.randomSongs!!
        assertEquals(2, random.song.size)
        assertEquals("Random Song 1", random.song[0].title)
    }

    @Test
    fun should_ignoreUnknownFields_when_extraFieldsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "unknownField": "hello",
                "album": {
                  "id": "al-1",
                  "name": "Test",
                  "futureField": true,
                  "anotherFuture": [1, 2, 3]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        assertEquals("ok", response.subsonicResponse.status)
        assertEquals("al-1", response.subsonicResponse.album?.id)
    }

    @Test
    fun should_parseAlbumList2_when_albumsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "albumList2": {
                  "album": [
                    {"id": "al-1", "name": "Album A", "artist": "Artist A"},
                    {"id": "al-2", "name": "Album B", "artist": "Artist B"}
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val list = response.subsonicResponse.albumList2!!
        assertEquals(2, list.album.size)
        assertEquals("Album A", list.album[0].name)
    }

    @Test
    fun should_parseSongWithAllFields_when_fullSongPresent() {
        val raw =
            """
            {
              "id": "s-99",
              "parent": "al-1",
              "title": "Full Song",
              "album": "Full Album",
              "artist": "Full Artist",
              "track": 3,
              "year": 2023,
              "genre": "Jazz",
              "coverArt": "s-99",
              "size": 8500000,
              "contentType": "audio/flac",
              "suffix": "flac",
              "duration": 300,
              "bitRate": 1411,
              "path": "Full Artist/Full Album/03 - Full Song.flac",
              "albumId": "al-1",
              "artistId": "ar-1",
              "starred": "2024-01-15T10:30:00Z",
              "userRating": 5
            }
            """.trimIndent()

        val song = json.decodeFromString<Song>(raw)
        assertEquals("s-99", song.id)
        assertEquals("al-1", song.parent)
        assertEquals("Full Song", song.title)
        assertEquals(3, song.track)
        assertEquals(8500000L, song.size)
        assertEquals("audio/flac", song.contentType)
        assertEquals("flac", song.suffix)
        assertEquals(1411, song.bitRate)
        assertEquals(5, song.userRating)
    }

    @Test
    fun should_parseArtistDetail_when_albumsPresent() {
        val raw =
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artist": {
                  "id": "ar-1",
                  "name": "Pink Floyd",
                  "albumCount": 15,
                  "coverArt": "ar-1",
                  "album": [
                    {"id": "al-1", "name": "The Wall", "year": 1979},
                    {"id": "al-2", "name": "Wish You Were Here", "year": 1975}
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<SubsonicResponse>(raw)
        val artist = response.subsonicResponse.artist!!
        assertEquals("ar-1", artist.id)
        assertEquals("Pink Floyd", artist.name)
        assertEquals(15, artist.albumCount)
        assertEquals(2, artist.album.size)
        assertEquals("The Wall", artist.album[0].name)
    }
}
