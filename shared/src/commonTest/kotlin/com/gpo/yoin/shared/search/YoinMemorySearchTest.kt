package com.gpo.yoin.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Swift facade: flat in, flat out, the same answers as the core it wraps. */
class YoinMemorySearchTest {

    private val me = "p-me"
    private val other = "p-other"
    private val mine = YoinLedgerAlbum(me, "subsonic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ", null)
    private val theirs = YoinLedgerAlbum(other, "applemusic", "1440", "盗作", "ヨルシカ", "c-3")

    private fun entry(id: String, kind: YoinLedgerKind, album: YoinLedgerAlbum?, text: String?, at: Long, profile: String = album?.profileId ?: me) =
        YoinLedgerEntry(id, kind, at, profile, album, text, null, null, null, null, null)

    private val entries = listOf(
        entry("title:1", YoinLedgerKind.Title, mine, "夏の終わり", 5),
        entry("review:1", YoinLedgerKind.Review, mine, "**雨**の夏", 9),
        YoinLedgerEntry("rating:1", YoinLedgerKind.AlbumRating, 7, me, mine, null, null, null, null, null, 8.5f),
        entry("note:2", YoinLedgerKind.AlbumNote, theirs, "雨の日に聴いた", 3),
        YoinLedgerEntry("song-note:1", YoinLedgerKind.SongNote, 11, me, null, "雨音 at 1:24", "Ame", "s1", "subsonic", 84_000L, null),
    )

    private class Ledger(val list: List<YoinLedgerEntry>) : YoinMemorySearchLedger {
        var reads = 0

        override fun entries(): List<YoinLedgerEntry> {
            reads++
            return list
        }

        override fun aiTitles(): List<YoinAiTitle> = listOf(YoinAiTitle("p-other", "applemusic", "1440", "Summer theft"))
    }

    @Test
    fun should_searchThePortsLedger_and_readNothingForABlankQuery() {
        val ledger = Ledger(entries)
        val search = YoinMemorySearch(ledger)
        assertTrue(search.search("  ", me).isEmpty)
        assertEquals(0, ledger.reads)
        val result = search.search("雨", me)
        assertEquals(1, ledger.reads)
        assertEquals(listOf("al-1", "1440"), result.memories.map { it.album.albumId })
        assertEquals(YoinMemoryHitField.Review, result.memories[0].field)
        assertEquals(YoinSearchFragment("雨の夏", 0, 1), result.memories[0].fragment)
        assertEquals("Summer theft", result.memories[1].title)
        assertEquals(YoinLedgerMark(YoinLedgerMarkKind.AlbumRating, 8.5f, 0), result.memories[0].mark)
        assertEquals(listOf("song-note:1"), result.notes.map { it.entryId })
        // One snapshot of the ledger serves both, read once.
        val snapshot = ledger.entries()
        val titles = ledger.aiTitles()
        assertEquals(result, YoinMemorySearch.searchEntries(snapshot, titles, "雨", me))
    }

    @Test
    fun should_passThePortsThrowOn() {
        val failing = object : YoinMemorySearchLedger {
            override fun entries(): List<YoinLedgerEntry> = throw IllegalStateException("db")
            override fun aiTitles(): List<YoinAiTitle> = emptyList()
        }
        assertFailsWith<IllegalStateException> { YoinMemorySearch(failing).search("x", me) }
    }

    @Test
    fun should_layOutThePage_withTilesPointingBackIntoTheRows() {
        val search = YoinMemorySearch(Ledger(entries))
        val result = search.search("雨", me)
        val albums = listOf(YoinSearchAlbum("subsonic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ"))
        val attachments = search.attachments(albums, me)
        assertEquals(listOf("al-1"), attachments.map { it.albumId })
        val page = YoinMemorySearch.page(
            "雨",
            songs = listOf(YoinSearchSong("subsonic", "s1", "Ame", "Someone", "Record")),
            albums = albums,
            artists = listOf(YoinSearchArtist("subsonic", "r1", "雨のパレード")),
            playlists = emptyList(),
            result = result,
            attachments = attachments,
            activeProfileId = me,
            columns = 3,
            maxRows = YoinMemorySearch.allMaxRows,
            maxWide = YoinMemorySearch.allMaxWide,
        )
        val songs = page.sections.single { it.section == YoinSearchSection.Songs }
        assertEquals(0, songs.tiles.single().index)
        assertEquals("song-note:1", songs.tiles.single().note?.entryId)
        val albumTile = page.sections.single { it.section == YoinSearchSection.Albums }.tiles.first()
        assertEquals(YoinSearchTileKind.Album, albumTile.kind)
        assertEquals("al-1", albumTile.attachment?.albumId)
        assertTrue(albumTile.wide)
        assertEquals(YoinSearchBestMatch(YoinSearchTileKind.Artist, 0), page.best)
        assertEquals(page.memories.size, 3)
    }

    @Test
    fun should_matchFragmentFilterAndTargetLikeTheCore_withoutALedger() {
        assertEquals(YoinSearchMatch(YoinSearchStrength.WordPrefix, 3, 8), YoinMemorySearch.match("radio", "Ｍｙ ＲＡＤＩＯ song"))
        assertEquals(YoinSearchMatch(YoinSearchStrength.None, -1, -1), YoinMemorySearch.match("x", null))
        assertNull(YoinMemorySearch.fragment("x", "abc", 80))
        assertEquals("bjork", YoinMemorySearch.fold("BJÖRK"))
        assertEquals("with 晴天", YoinMemorySearch.plainWords("with [@晴天](yoin:track/subsonic:tr-1?a=%E5%91%A8)"))
        assertEquals(YoinSearchFilter.entries.toList(), YoinMemorySearch.filters(canSearchPlaylists = true, canReadPlaylists = true, savedLibrary = true))
        assertEquals(40, YoinMemorySearch.shownRows(YoinSearchFilter.All, YoinSearchSection.Songs, 99))
        val note = YoinNoteHit(
            "n", other, "applemusic", "am-3", "Ame", theirs, 84_000L, YoinSearchFragment("x", -1, -1),
            YoinSearchTier.Body, YoinSearchStrength.Contains, 0L, true,
        )
        val play = YoinMemorySearch.noteTarget(note, "applemusic", "am-3", me)
        assertEquals(YoinNoteOpenAction.PlaySong, play.action)
        assertEquals(84_000L, play.seekToMs)
        val open = YoinMemorySearch.noteTarget(note, null, null, me)
        assertEquals(YoinNoteOpenAction.OpenAlbum, open.action)
        assertEquals("1440", open.albumId)
        assertTrue(open.switchAccount)
        assertEquals(4_000L, YoinMemorySearch.noteSeekTimeoutMs)
    }
}
