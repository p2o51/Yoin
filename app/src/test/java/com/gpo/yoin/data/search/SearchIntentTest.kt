package com.gpo.yoin.data.search

import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The All page's order: sections by how well they met the query, songs first by default, and the best-match card. */
class SearchIntentTest {

    private fun track(id: String, title: String, artist: String = "Someone", album: String = "Record") = Track(
        id = MediaId("subsonic", id),
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    private fun album(id: String, name: String, artist: String = "Someone") = Album(
        id = MediaId("subsonic", id),
        name = name,
        artist = artist,
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
    )

    private fun artist(id: String, name: String) = Artist(MediaId("subsonic", id), name, albumCount = null, coverArt = null)

    private fun layout(query: String, results: SearchResults, memories: MemorySearchMatches = MemorySearchMatches.Empty) =
        searchLayout(SearchQuery(query), results, memories)

    @Test
    fun `songs lead by default`() {
        val results = SearchResults(
            tracks = listOf(track("t1", "Moonlight Drive")),
            albums = listOf(album("a1", "Moonlight Sessions")),
            artists = listOf(artist("r1", "The Moonlighters")),
        )
        val layout = layout("moon", results)
        assertEquals(listOf(SearchSection.Songs, SearchSection.Albums, SearchSection.Artists), layout.sections)
    }

    @Test
    fun `a name typed in full floats its artist above songs that only mention it`() {
        val results = SearchResults(
            tracks = listOf(track("t1", "ただ君に晴れ", artist = "ヨルシカ")),
            artists = listOf(artist("r1", "ヨルシカ")),
        )
        val layout = layout("ヨルシカ", results)
        assertEquals(listOf(SearchSection.Artists, SearchSection.Songs), layout.sections)
        assertEquals(SearchBestMatch.ArtistMatch(results.artists.single()), layout.bestMatch)
    }

    @Test
    fun `the best-match card needs an exact or prefix hit on a name`() {
        val contains = layout("light", SearchResults(tracks = listOf(track("t1", "Moonlight"))))
        assertNull(contains.bestMatch)
        val byArtistOnly = layout("someone", SearchResults(tracks = listOf(track("t1", "Moonlight"))))
        assertNull(byArtistOnly.bestMatch)
        val prefix = layout("moon", SearchResults(tracks = listOf(track("t1", "Moonlight"))))
        assertEquals("t1", (prefix.bestMatch as SearchBestMatch.Song).track.id.rawId)
    }

    @Test
    fun `memories and notes take their place by score, below names, above rows that met nothing locally`() {
        val a = MemoryLedgerAlbum("p", "subsonic", "a1", "Record", "Someone", null)
        val memories = MemorySearchMatches(
            memories = listOf(
                MemorySearchHit(
                    album = a,
                    field = MemoryHitField.Review,
                    entryId = "r",
                    fragment = SearchFragment("rainy", 0..4),
                    score = SearchScore(MatchTier.Body, MatchStrength.Exact),
                    title = null,
                    albumScore = null,
                    lastAt = 0,
                    isOtherAccount = false,
                ),
            ),
        )
        // The service matched "rainy" on something Yoin doesn't see (a lyric, a romanisation).
        val results = SearchResults(tracks = listOf(track("t1", "Ame")), albums = listOf(album("a2", "Rainy Days")))
        val layout = layout("rainy", results, memories)
        assertEquals(listOf(SearchSection.Albums, SearchSection.Memories, SearchSection.Songs), layout.sections)
    }

    @Test
    fun `empty sections are left out, and the caps come from the shared tokens`() {
        val layout = layout("x", SearchResults(tracks = listOf(track("t1", "x"))))
        assertEquals(listOf(SearchSection.Songs), layout.sections)
        assertEquals(5, SearchSection.Songs.allPreview)
        assertEquals(3, SearchSection.Albums.allPreview)
        assertEquals(2, SearchSection.Memories.allPreview)
        assertEquals(2, SearchSection.Notes.allPreview)
        assertEquals(3, SearchSection.Playlists.allPreview)
        assertNull(SearchSection.Artists.allPreview)
    }
}
