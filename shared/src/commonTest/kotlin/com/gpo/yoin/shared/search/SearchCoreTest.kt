package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.MemoryLedgerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The shared search core (Android's SearchMatchTest / MemorySearchTest / SearchPageTest cases), run on the
 * JVM and the iOS simulator: folding there goes through NSString's NFKD, so these pin that it folds the same.
 */
class SearchCoreTest {

    private fun strength(query: String, text: String) = SearchQuery(query).match(text).strength

    @Test
    fun should_gradeExactPrefixWordPrefixAndContains() {
        assertEquals(MatchStrength.Exact, strength("ヨルシカ", "ヨルシカ"))
        assertEquals(MatchStrength.Prefix, strength("ヨル", "ヨルシカ"))
        assertEquals(MatchStrength.WordPrefix, strength("dark", "The Dark Side of the Moon"))
        assertEquals(MatchStrength.Contains, strength("ark", "The Dark Side"))
        assertEquals(MatchStrength.None, strength("swim", "ヨルシカ"))
    }

    @Test
    fun should_foldCaseWidthAccentsAndSpacing_but_keepKanaVoicingAndHangul() {
        assertEquals("bjork", FoldedText.of("BJÖRK").text)
        assertEquals("radwimps", FoldedText.of("Ｒａｄｗｉｍｐｓ").text)
        assertEquals(FoldedText.of("ガール").text, FoldedText.of("ｶﾞｰﾙ").text)
        assertEquals("the band", FoldedText.of("  The   Band \n").text)
        assertEquals("five", FoldedText.of("ﬁve").text)
        assertEquals("creme brulee", FoldedText.of("Crème Brûlée").text)
        assertEquals("москва елка", FoldedText.of("Москва Ёлка").text)
        assertEquals("パピプペポ".length + 5, FoldedText.of("ﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ").text.length)
        assertEquals(MatchStrength.Exact, strength("ｶﾞｰﾙ", "ガール"))
        assertEquals(MatchStrength.None, strength("か", "が"))
        assertEquals(MatchStrength.None, strength("가", "각"))
        assertEquals("🎧 music 🌧️", FoldedText.of("🎧 Music 🌧️").text)
    }

    @Test
    fun should_mapAHitBackToTheOriginal_throughWidthFolding() {
        val match = SearchQuery("radio").match("Ｍｙ ＲＡＤＩＯ song")
        assertEquals(3..7, match.originalRange)
        val emoji = SearchQuery("falls").match("😀😀 falls")
        assertEquals(5..9, emoji.originalRange)
    }

    @Test
    fun should_cutALongTextAroundTheHit_withEllipses() {
        val text = "a".repeat(100) + " Peter Pan " + "b".repeat(100)
        val query = SearchQuery("peter pan")
        val fragment = query.fragment(query.match(text))!!
        assertTrue(fragment.text.startsWith("…") && fragment.text.endsWith("…"))
        assertEquals("Peter Pan", fragment.text.substring(fragment.highlight.first, fragment.highlight.last + 1))
        val short = query.fragment(SearchQuery("夏").match("あの夏の\n帰り道"))!!
        assertEquals(SearchFragment("あの夏の 帰り道", 2..2), short)
    }

    private val me = "p-me"
    private val other = "p-other"

    private fun album(profile: String, id: String, name: String, artist: String = "ヨルシカ") =
        MemoryLedgerAlbum(profile, "subsonic", id, name, artist, coverKey = null)

    @Test
    fun should_findAnotherAccountsNote_and_rankTitleOverBody() {
        val elsewhere = album(other, "a1", "盗作")
        val mine = album(me, "a2", "だから僕は音楽を辞めた")
        val entries = listOf(
            MemoryLedgerEntry("n1", MemoryLedgerKind.AlbumNote, 1, other, elsewhere, text = "雨の日に聴いた"),
            MemoryLedgerEntry("t", MemoryLedgerKind.Title, 5, me, mine, text = "雨の終わり"),
            MemoryLedgerEntry("r", MemoryLedgerKind.Review, 9, me, mine, text = "**雨**が来るたびに"),
        )
        val result = searchMemoryLedger(entries, SearchQuery("雨"), me)
        assertEquals(listOf("a2", "a1"), result.memories.map { it.album.albumId })
        assertEquals(MemoryHitField.Title, result.memories[0].field)
        assertTrue(result.memories[1].isOtherAccount)
    }

    @Test
    fun should_searchANotesWords_notItsMentionLinks() {
        val link = "[@晴天](yoin:track/subsonic:tr-1?a=%E5%91%A8)"
        val entry = MemoryLedgerEntry(
            "s1", MemoryLedgerKind.SongNote, 1, me, null, text = "with $link again", trackTitle = "x",
            songId = "tr-1", songProvider = "subsonic", positionMs = 84_000L,
        )
        val hit = searchMemoryLedger(listOf(entry), SearchQuery("晴天"), me).notes.single()
        assertEquals(SearchFragment("with 晴天 again", 5..6), hit.fragment)
        assertTrue(searchMemoryLedger(listOf(entry), SearchQuery("yoin"), me).isEmpty)
    }

    @Test
    fun should_attachOnlyOnTheExactAlbumInTheAccountInUse() {
        val mine = album(me, "a1", "x")
        val entries = listOf(
            MemoryLedgerEntry("t", MemoryLedgerKind.Title, 5, me, mine, text = "Title"),
            MemoryLedgerEntry("o", MemoryLedgerKind.Title, 5, other, album(other, "a1", "x"), text = "Other"),
        )
        val refs = listOf(MediaRef("subsonic", "a1"), MediaRef("subsonic", "a9"))
        val attached = memoryAttachments(refs, entries, me)
        assertEquals(setOf(MediaRef("subsonic", "a1")), attached.keys)
        assertEquals("Title", attached.values.single().title)
        assertTrue(memoryAttachments(refs, entries, null).isEmpty())
    }

    @Test
    fun should_joinThisAccountsNoteToItsSong_and_leadWithTheStrongName() {
        val song = SearchSongRow(MediaRef("subsonic", "s1"), "Ame", "Someone", "Record")
        val note = NoteSearchHit(
            "n1", me, "subsonic", "s1", "Ame", null, 1_000L, SearchFragment("雨", 0..0),
            SearchScore(MatchTier.Body, MatchStrength.Exact), 0L, false,
        )
        val artist = SearchArtistRow(MediaRef("subsonic", "r1"), "雨")
        val page = layoutSearchPage(
            SearchQuery("雨"), SearchServiceRows(songs = listOf(song), artists = listOf(artist)),
            MemorySearchMatches(notes = listOf(note)), emptyMap(), me,
        )
        val songs = page.sections.single { it.section == SearchSection.Songs }
        assertEquals("n1", songs.tiles.single().note?.entryId)
        assertTrue(songs.tiles.single().wide)
        assertEquals(SearchPageBest(SearchTileKind.Artist, 0), page.best)
        assertEquals(listOf("song:subsonic:s1"), page.memories.map { it.key })
        assertTrue(layoutSearchPage(SearchQuery(" "), SearchServiceRows(), MemorySearchMatches.Empty, emptyMap(), me).isEmpty)
    }

    @Test
    fun should_packALeadingFullRow_and_alternateWideSides() {
        val rows = packSearchTiles("ssssww".toList(), 3) { it == 'w' }
        assertEquals(listOf("ws", "ws", "ss"), rows.map { it.tiles.joinToString("") })
        assertEquals(listOf(false, true, false), rows.map { it.wideAtEnd })
    }

    @Test
    fun should_offerPlaylistsOnlyWhereTheyCanBeSearched() {
        assertFalse(SearchFilter.Playlists in searchFilters(canSearchPlaylists = false, canReadPlaylists = true, savedLibrary = false))
        assertFalse(SearchFilter.Playlists in searchFilters(canSearchPlaylists = true, canReadPlaylists = false, savedLibrary = true))
        assertEquals(SearchFilter.entries, searchFilters(canSearchPlaylists = true, canReadPlaylists = false, savedLibrary = false))
        assertEquals(40, SearchFilter.All.shownRows(SearchSection.Songs, 45))
        assertEquals(45, SearchFilter.Songs.shownRows(SearchSection.Songs, 45))
        assertEquals(0, SearchFilter.Memories.shownRows(SearchSection.Songs, 45))
    }

    @Test
    fun should_playAReturnedSongFromTheNotesSecond_or_openTheAlbumElsewhere() {
        val ledgerAlbum = album(other, "al-1", "盗作")
        val note = NoteSearchHit(
            "n1", other, "applemusic", "am-3", "雨とカプチーノ", ledgerAlbum, 84_000L, SearchFragment("x", IntRange.EMPTY),
            SearchScore.None, 0L, true,
        )
        val play = noteOpenTarget(note, MediaRef("applemusic", "am-3"), me)
        assertEquals(NoteOpenAction.PlaySong, play.action)
        assertEquals(84_000L, play.seekToMs)
        val open = noteOpenTarget(note, null, me)
        assertEquals(NoteOpenAction.OpenAlbum, open.action)
        assertEquals(MediaRef("subsonic", "al-1"), open.album)
        assertEquals(MediaRef("applemusic", "am-3"), open.song)
        assertTrue(open.switchAccount)
        assertEquals("雨とカプチーノ", open.subject)
        assertNull(noteOpenTarget(note.copy(positionMs = 0L), MediaRef("applemusic", "am-3"), me).seekToMs)
        assertEquals(NoteOpenAction.None, noteOpenTarget(note.copy(album = null), null, me).action)
        assertTrue(noteSeekReady("am-3", 1L, "am-3"))
        assertFalse(noteSeekReady("am-3", 0L, "am-3"))
    }

    @Test
    fun should_readNothing_when_theQueryIsBlank() = runTest {
        var reads = 0
        val service = MemorySearchService(
            object : MemorySearchPort {
                override suspend fun ledger(): List<MemoryLedgerEntry> {
                    reads++
                    return emptyList()
                }

                override suspend fun aiTitles(): Map<String, String> = emptyMap()
            },
        )
        assertTrue(service.search("  ", me).isEmpty)
        assertTrue(service.attachments(listOf(MediaRef("subsonic", "a")), null).isEmpty())
        assertEquals(0, reads)
        service.search("x", me)
        assertEquals(1, reads)
    }
}
