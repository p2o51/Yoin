package com.gpo.yoin.data.search

import com.gpo.yoin.shared.search.MemoryHitField
import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LedgerAiTitleRow
import com.gpo.yoin.data.local.LedgerAlbumFactRow
import com.gpo.yoin.data.local.LedgerSongNoteRow
import com.gpo.yoin.data.local.LedgerTrackRatingRow
import com.gpo.yoin.data.local.MemoryLedgerDao
import com.gpo.yoin.data.local.SongNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search's view of the ledger: built once per change, every counted account's, AI titles fresh. */
class MemorySearchSourceTest {

    private class FakeDao : MemoryLedgerDao {
        var notes = listOf<AlbumNote>()
        var songNotes = listOf<LedgerSongNoteRow>()
        var ai = listOf<LedgerAiTitleRow>()
        val facts = mutableListOf<LedgerAlbumFactRow>()
        val stamp = MutableStateFlow(0L)
        var builds = 0

        override suspend fun albumNotes(): List<AlbumNote> = notes.also { builds++ }
        override suspend fun songNotes(): List<LedgerSongNoteRow> = songNotes
        override suspend fun albumRatings(): List<AlbumRating> = emptyList()
        override suspend fun trackRatings(): List<LedgerTrackRatingRow> = emptyList()
        override suspend fun titles(): List<AlbumMemoryTitle> = emptyList()
        override suspend fun aiTitles(): List<LedgerAiTitleRow> = ai
        override suspend fun albumFacts(): List<LedgerAlbumFactRow> = facts
        override fun changes(): Flow<Long> = stamp
    }

    private fun note(id: String, profile: String, album: String, text: String) =
        AlbumNote(id, profile, album, "subsonic", text, 1L, 1L, "Album $album", "Artist")

    @Test
    fun `finds every counted account's writing, and only rebuilds after a change`() = runTest {
        val dao = FakeDao()
        dao.notes = listOf(note("1", "a", "x", "rain on the window"), note("2", "b", "y", "rain again"))
        val source = MemorySearchSource(dao, profileIds = { setOf("a", "b") })

        val first = source.search("rain", activeProfileId = "a")
        assertEquals(listOf("a", "b"), first.memories.map { it.album.profileId })
        source.search("window", activeProfileId = "a")
        assertEquals(1, dao.builds)

        dao.notes = dao.notes + note("3", "a", "z", "rain, third")
        dao.stamp.value = 1L
        assertEquals(3, source.search("rain", activeProfileId = "a").memories.size)
        assertEquals(2, dao.builds)
    }

    @Test
    fun `a removed account's rows don't count`() = runTest {
        val dao = FakeDao()
        dao.notes = listOf(note("1", "gone", "x", "rain"))
        val source = MemorySearchSource(dao, profileIds = { setOf("a") })
        assertTrue(source.search("rain", "a").isEmpty)
    }

    @Test
    fun `an AI title is searched as soon as it lands`() = runTest {
        val dao = FakeDao()
        dao.notes = listOf(note("1", "a", "x", "words"))
        val source = MemorySearchSource(dao, profileIds = { null })
        assertTrue(source.search("midnight", "a").isEmpty)
        dao.ai = listOf(LedgerAiTitleRow("a", "subsonic", "x", "Midnight Letters"))
        val hit = source.search("midnight", "a").memories.single()
        assertEquals(MemoryHitField.Title, hit.field)
        assertEquals("Midnight Letters", hit.title)
    }

    @Test
    fun `song notes surface with their position`() = runTest {
        val dao = FakeDao()
        dao.songNotes = listOf(
            LedgerSongNoteRow(SongNote("n", "a", "t1", "subsonic", "the bridge", 1L, 1L, "Song", "Artist", 61_000L), null),
        )
        val note = MemorySearchSource(dao, profileIds = { null }).search("bridge", "a").notes.single()
        assertEquals(61_000L, note.positionMs)
        assertEquals("t1", note.songId)
        assertEquals("subsonic", note.provider)
    }
}
