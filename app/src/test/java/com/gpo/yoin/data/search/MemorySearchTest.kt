package com.gpo.yoin.data.search

import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memories and notes in search: read across accounts, ranked, attached to album rows only exactly. */
class MemorySearchTest {

    private val active = "p-subsonic"
    private val other = "p-apple"

    private fun album(profile: String, id: String, name: String, artist: String = "ヨルシカ", provider: String = "subsonic") =
        MemoryLedgerAlbum(profile, provider, id, name, artist, coverKey = null)

    private fun entry(
        id: String,
        kind: MemoryLedgerKind,
        album: MemoryLedgerAlbum?,
        text: String? = null,
        at: Long = 1_000L,
        profile: String = album?.profileId ?: active,
        trackTitle: String? = null,
        positionMs: Long? = null,
        score: Float? = null,
    ) = MemoryLedgerEntry(
        id = id,
        kind = kind,
        at = at,
        profileId = profile,
        album = album,
        text = text,
        trackTitle = trackTitle,
        songId = if (kind == MemoryLedgerKind.SongNote) "song-$id" else null,
        songProvider = if (kind == MemoryLedgerKind.SongNote) "subsonic" else null,
        positionMs = positionMs,
        score = score,
    )

    private fun search(entries: List<MemoryLedgerEntry>, query: String, aiTitles: Map<String, String> = emptyMap()) =
        searchMemoryLedger(entries, SearchQuery(query), active, aiTitles)

    @Test
    fun `a note written on another account is found from this one`() {
        val elsewhere = album(other, "a1", "盗作", provider = "applemusic")
        val result = search(listOf(entry("n1", MemoryLedgerKind.AlbumNote, elsewhere, "雨の日に聴いた")), "雨")
        assertEquals(1, result.memories.size)
        assertTrue(result.memories.single().isOtherAccount)
        assertEquals(MemoryHitField.AlbumNote, result.memories.single().field)
        assertEquals("n1", result.memories.single().entryId)
    }

    @Test
    fun `one row per album, at its best hit, title over body`() {
        val a = album(active, "a1", "だから僕は音楽を辞めた")
        val entries = listOf(
            entry("t", MemoryLedgerKind.Title, a, "夏の終わり", at = 5),
            entry("r", MemoryLedgerKind.Review, a, "夏が来るたびに思い出す", at = 9),
            entry("s", MemoryLedgerKind.AlbumRating, a, score = 8.5f, at = 7),
        )
        val hit = search(entries, "夏").memories.single()
        assertEquals(MemoryHitField.Title, hit.field)
        assertEquals("夏の終わり", hit.title)
        assertEquals(8.5f, hit.albumScore)
        assertEquals(9L, hit.lastAt)
        assertFalse(hit.isOtherAccount)
    }

    @Test
    fun `the listener's title wins over the AI's, and the AI's is searched when there is none`() {
        val a = album(active, "a1", "エルマ")
        val b = album(active, "a2", "盗作")
        val entries = listOf(
            entry("t", MemoryLedgerKind.Title, a, "手紙", at = 5),
            entry("r", MemoryLedgerKind.Review, b, "good", at = 6),
        )
        val ai = mapOf(a.key to "AI 手紙", b.key to "盗まれた夜")
        assertEquals("手紙", search(entries, "手紙", ai).memories.single { it.album == a }.title)
        val viaAi = search(entries, "盗まれた", ai).memories.single()
        assertEquals(b, viaAi.album)
        assertEquals(MemoryHitField.Title, viaAi.field)
        assertNull(viaAi.entryId)
    }

    @Test
    fun `an equal hit from the account in use comes first, a stronger one from elsewhere still leads`() {
        val mine = album(active, "a1", "Album A")
        val theirs = album(other, "a2", "Album B")
        val equal = search(
            listOf(
                entry("x", MemoryLedgerKind.AlbumNote, theirs, "blue night", at = 99),
                entry("y", MemoryLedgerKind.AlbumNote, mine, "blue morning", at = 1),
            ),
            "blue",
        )
        assertEquals(listOf(mine, theirs), equal.memories.map { it.album })

        val stronger = search(
            listOf(
                entry("x", MemoryLedgerKind.Title, theirs, "Blue", at = 1),
                entry("y", MemoryLedgerKind.AlbumNote, mine, "so blue", at = 99),
            ),
            "blue",
        )
        assertEquals(listOf(theirs, mine), stronger.memories.map { it.album })
    }

    @Test
    fun `a song note carries its position, and its own words outrank its song's name`() {
        val a = album(active, "a1", "幻燈")
        val entries = listOf(
            entry("n1", MemoryLedgerKind.SongNote, a, "ここのギターが好き", trackTitle = "夜行", positionMs = 83_000L, at = 1),
            entry("n2", MemoryLedgerKind.SongNote, a, "サビ", trackTitle = "ギター", positionMs = 10_000L, at = 2),
        )
        val notes = search(entries, "ギター").notes
        assertEquals(listOf("n1", "n2"), notes.map { it.entryId })
        assertEquals(83_000L, notes.first().positionMs)
        assertEquals(MatchTier.Body, notes.first().score.tier)
        assertEquals("subsonic", notes.first().provider)
        assertEquals("song-n1", notes.first().songId)
        // A hit on the song's name shows the note itself, unmarked.
        assertEquals("サビ", notes[1].fragment.text)
        assertTrue(notes[1].fragment.highlight.isEmpty())
    }

    @Test
    fun `song notes and track scores aren't a memory to search, yet they mark the cover as on Home`() {
        val a = album(active, "a1", "Peter Pan")
        val entries = listOf(
            entry("n1", MemoryLedgerKind.SongNote, a, "note", trackTitle = "x"),
            entry("r1", MemoryLedgerKind.TrackRating, a, score = 9f),
        )
        assertTrue(search(entries, "peter").memories.isEmpty())
        val mark = memoryAttachments(listOf(remoteAlbum("a1", "Peter Pan")), entries, active).values.single().mark
        assertEquals(LedgerMark(LedgerMarkKind.TrackAverage, 9f, 1), mark)
    }

    @Test
    fun `an album-name hit drops when that album row carries the memory, its own words stay`() {
        val a = album(active, "a1", "Peter Pan")
        val b = album(active, "a2", "Neverland")
        val result = search(
            listOf(
                entry("s", MemoryLedgerKind.AlbumRating, a, score = 7f),
                entry("r", MemoryLedgerKind.Review, b, "like Peter Pan"),
            ),
            "peter pan",
        )
        assertEquals(2, result.memories.size)
        val shown = result.withoutAttached(setOf(a.key, b.key))
        assertEquals(listOf(b), shown.memories.map { it.album })
    }

    @Test
    fun `attachment is exact - this account, this provider, this album`() {
        val here = album(active, "a1", "盗作")
        val sameIdElsewhere = album(other, "a2", "盗作")
        val entries = listOf(
            entry("t", MemoryLedgerKind.Title, here, "盗まれた夜", at = 3),
            entry("s", MemoryLedgerKind.AlbumRating, here, score = 9f, at = 4),
            entry("o", MemoryLedgerKind.Review, sameIdElsewhere, "other account"),
        )
        val rows = listOf(remoteAlbum("a1", "盗作"), remoteAlbum("a2", "盗作"), remoteAlbum("a1", "盗作", provider = "spotify"))
        val attached = memoryAttachments(rows, entries, active)
        assertEquals(setOf(MediaId("subsonic", "a1")), attached.keys)
        val memory = attached.getValue(MediaId("subsonic", "a1"))
        assertEquals("盗まれた夜", memory.title)
        assertEquals(9f, memory.albumScore)
        assertEquals(here.key, memory.albumKey)
    }

    @Test
    fun `a blank query finds nothing`() {
        val a = album(active, "a1", "x")
        assertTrue(search(listOf(entry("r", MemoryLedgerKind.Review, a, "x")), "  ").isEmpty)
    }

    private fun remoteAlbum(id: String, name: String, provider: String = "subsonic") = Album(
        id = MediaId(provider, id),
        name = name,
        artist = "ヨルシカ",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
    )
}
