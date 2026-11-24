package com.gpo.yoin.data.search

import com.gpo.yoin.shared.search.SearchQuery
import com.gpo.yoin.shared.search.MatchStrength
import com.gpo.yoin.shared.search.MatchTier
import com.gpo.yoin.shared.search.SearchScore
import com.gpo.yoin.shared.search.SearchFragment
import com.gpo.yoin.shared.search.fragment
import com.gpo.yoin.shared.search.MemoryHitField
import com.gpo.yoin.shared.search.MemorySearchHit
import com.gpo.yoin.shared.search.NoteSearchHit
import com.gpo.yoin.shared.search.MemorySearchMatches
import com.gpo.yoin.shared.search.MemoryAttachment
import com.gpo.yoin.shared.search.SearchSection
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The All page: merged sections, their order, relevance folding, the best-match card and Home-style packing. */
class SearchPageTest {

    private val me = "p-me"

    private fun track(id: String, title: String, artist: String = "Someone", album: String = "Record") = Track(
        id = MediaId("subsonic", id), title = title, artist = artist, artistId = null, album = album, albumId = null,
        coverArt = null, durationSec = null, trackNumber = null, year = null, genre = null, userRating = null,
    )

    private fun album(id: String, name: String, artist: String = "Someone") = Album(
        id = MediaId("subsonic", id), name = name, artist = artist, artistId = null, coverArt = null,
        songCount = null, durationSec = null, year = null, genre = null,
    )

    private fun artist(id: String, name: String) = Artist(MediaId("subsonic", id), name, albumCount = null, coverArt = null)

    private fun playlist(id: String, name: String) =
        Playlist(MediaId("subsonic", id), name, owner = "51", coverArt = null, songCount = null, durationSec = null)

    private fun note(id: String, songId: String, text: String, profile: String = me, tier: MatchTier = MatchTier.Body) = NoteSearchHit(
        entryId = id, profileId = profile, provider = "subsonic", songId = songId, trackTitle = "t", album = null,
        positionMs = 1_000L, fragment = SearchFragment(text, IntRange.EMPTY), score = SearchScore(tier, MatchStrength.Contains),
        at = 0L, isOtherAccount = profile != me,
    )

    private fun memory(albumId: String, field: MemoryHitField, profile: String = me) = MemorySearchHit(
        album = MemoryLedgerAlbum(profile, "subsonic", albumId, "A $albumId", "Someone", null),
        field = field, entryId = "e-$albumId", fragment = SearchFragment("words", IntRange.EMPTY),
        score = SearchScore(field.tier, MatchStrength.Contains), title = null, albumScore = null, lastAt = 0L,
        isOtherAccount = profile != me,
    )

    private fun page(
        query: String,
        results: SearchResults,
        memories: MemorySearchMatches = MemorySearchMatches.Empty,
        attachments: Map<MediaId, MemoryAttachment> = emptyMap(),
        columns: Int = 3,
    ) = buildSearchPage(SearchQuery(query), results, memories, attachments, me, columns)

    private fun single(key: String) = SearchTile.ArtistRow(artist(key, key), SearchScore.None, true)

    private fun wide(key: String) = SearchTile.Note(note(key, key, key))

    @Test
    fun `songs lead by default, and a name typed in full floats its section and earns the card`() {
        val plain = page(
            "moon",
            SearchResults(
                tracks = listOf(track("t1", "Moonlight Drive")),
                albums = listOf(album("a1", "Moonlight Sessions")),
                artists = listOf(artist("r1", "The Moonlighters")),
            ),
        )
        assertEquals(listOf(SearchSection.Songs, SearchSection.Albums, SearchSection.Artists), plain.sections.map { it.section })

        val named = page(
            "羊文学",
            SearchResults(tracks = listOf(track("t1", "光るとき", artist = "羊文学")), artists = listOf(artist("r1", "羊文学"))),
        )
        assertEquals(listOf(SearchSection.Artists, SearchSection.Songs), named.sections.map { it.section })
        assertEquals(SearchBestMatch.ArtistMatch(artist("r1", "羊文学")), named.bestMatch)
    }

    @Test
    fun `the card needs an exact or prefix hit on the thing's own name`() {
        assertNull(page("light", SearchResults(tracks = listOf(track("t1", "Moonlight")))).bestMatch)
        assertNull(page("someone", SearchResults(tracks = listOf(track("t1", "Moonlight")))).bestMatch)
        assertTrue(page("moon", SearchResults(tracks = listOf(track("t1", "Moonlight")))).bestMatch is SearchBestMatch.Song)
    }

    @Test
    fun `this account's note joins its song's tile, another account's stands alone`() {
        val results = SearchResults(tracks = listOf(track("s1", "Ame"), track("s2", "Hare")))
        val memories = MemorySearchMatches(notes = listOf(note("n1", "s1", "雨"), note("n2", "s2", "雨", profile = "p-other")))
        val songs = page("雨", results, memories).section(SearchSection.Songs)!!
        val joined = songs.tiles.filterIsInstance<SearchTile.Song>().single { it.track.id.rawId == "s1" }
        assertEquals("n1", joined.note?.entryId)
        assertTrue(joined.wide)
        assertEquals(listOf("n2"), songs.tiles.filterIsInstance<SearchTile.Note>().map { it.note.entryId })
    }

    @Test
    fun `a memory's words join its album's tile, a hit on the album's name alone stays a mark`() {
        val results = SearchResults(albums = listOf(album("a1", "Peter Pan"), album("a2", "Neverland")))
        val memories = MemorySearchMatches(
            memories = listOf(memory("a2", MemoryHitField.Review), memory("a1", MemoryHitField.Album), memory("a9", MemoryHitField.AlbumNote)),
        )
        val albums = page("peter", results, memories).section(SearchSection.Albums)!!
        val rows = albums.tiles.filterIsInstance<SearchTile.AlbumRow>().associateBy { it.album.id.rawId }
        assertTrue(rows.getValue("a2").wide)
        assertTrue(!rows.getValue("a1").wide)
        assertEquals(listOf("a9"), albums.tiles.filterIsInstance<SearchTile.Memory>().map { it.hit.album.albumId })
    }

    @Test
    fun `rows the query doesn't appear in leave All when others match, and stay when none do`() {
        val mixed = page("雨", SearchResults(tracks = listOf(track("t1", "雨上がり"), track("t2", "Rain On Me"), track("t3", "Purple Rain"))))
        val songs = mixed.section(SearchSection.Songs)!!
        assertEquals(2, songs.folded)
        assertEquals(listOf("t1"), songs.allRows.flatMap { it.tiles }.map { (it as SearchTile.Song).track.id.rawId })
        assertEquals(listOf("t1", "t2", "t3"), songs.tiles.map { (it as SearchTile.Song).track.id.rawId })

        val romanised = page("hitsujibungaku", SearchResults(artists = listOf(artist("r1", "羊文学"))))
        assertEquals(0, romanised.section(SearchSection.Artists)!!.folded)
        assertEquals(1, romanised.section(SearchSection.Artists)!!.allRows.single().tiles.size)
    }

    @Test
    fun `the Memories list holds every note and memory hit, best first`() {
        val results = SearchResults(tracks = listOf(track("s1", "x")), albums = listOf(album("a1", "y")))
        val memories = MemorySearchMatches(
            memories = listOf(memory("a1", MemoryHitField.Title), memory("a5", MemoryHitField.Album)),
            notes = listOf(note("n1", "s1", "雨")),
        )
        val list = page("q", results, memories).memories
        assertEquals(2, list.size)
        assertTrue(list.first() is SearchTile.AlbumRow)
    }

    @Test
    fun `packing - a full row of singles leads, 1x2 rows alternate sides, a short row goes last`() {
        val rows = packSearchTiles(listOf(single("a"), single("b"), single("c"), single("d"), wide("w1"), wide("w2")), columns = 3)
        // 4 singles, 2 wides: pairs take the best singles, the short row of the rest goes last.
        assertEquals(listOf("note:w1", "artist:subsonic:a"), rows[0].tiles.map { it.key })
        assertEquals(false, rows[0].wideAtEnd)
        assertEquals(listOf("note:w2", "artist:subsonic:b"), rows[1].tiles.map { it.key })
        assertEquals(true, rows[1].wideAtEnd)
        assertEquals(listOf("artist:subsonic:c", "artist:subsonic:d"), rows[2].tiles.map { it.key })

        val led = packSearchTiles(List(6) { single("s$it") } + wide("w"), columns = 3)
        assertEquals(listOf(false, true, false), led.map { row -> row.tiles.any { it.wide } })
    }

    @Test
    fun `packing caps rows and 1x2s, and follows the column count`() {
        val many = List(20) { single("s$it") } + List(5) { wide("w$it") }
        val capped = packSearchTiles(many, columns = 3, maxRows = 4, maxWide = 3)
        assertEquals(4, capped.size)
        assertTrue(capped.sumOf { row -> row.tiles.count { it.wide } } <= 3)

        val wideGrid = packSearchTiles(List(5) { single("s$it") } + wide("w"), columns = 4)
        assertEquals(3, wideGrid.first { row -> row.tiles.any { it.wide } }.tiles.size)
    }
}
