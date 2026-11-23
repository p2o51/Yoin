package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumNoteSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = AlbumNoteSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()

    @After
    fun tearDown() = fixtures.close()

    private val remote = payload(
        """{"albumId":"al-1","provider":"spotify","content":"像 [@Andromeda](yoin:track/spotify:t2) 的白天",
           "createdAt":100,"updatedAt":150,"albumName":"Charm","artist":"Clairo","futureField":true}""",
    )

    @Test
    fun should_declareTheSongNoteContract_forTheAlbumNoteKind() {
        assertEquals("album_note", SyncKinds.ALBUM_NOTE)
        assertEquals(SyncKinds.ALBUM_NOTE, adapter.kind)
        assertTrue(adapter.perAccount)
        assertTrue(adapter.propagatesDeletes)
        assertEquals(ConflictPolicy.LWW_LIVE_WINS, adapter.conflictPolicy)
    }

    @Test
    fun should_readBackSameProjection_when_payloadApplied() = runTest {
        val outcome = adapter.apply("p1", "an1", remote, versionTs = 150L, expectedLocalHash = null)

        val row = adapter.readAll("p1").single()
        val expected = hashOf(adapter.project(remote))
        assertEquals(ApplyOutcome.Applied(expected), outcome)
        assertEquals(expected, hashOf(row.projection))
        assertEquals("an1", row.key)
        assertEquals(150L, row.rowTs)
        val stored = dao.albumNoteById("an1")
        assertEquals("p1", stored?.profileId)
        assertEquals("像 [@Andromeda](yoin:track/spotify:t2) 的白天", stored?.content)
    }

    @Test
    fun should_keepSeveralNotesPerAlbum() = runTest {
        adapter.apply("p1", "an1", remote, versionTs = 150L, expectedLocalHash = null)
        adapter.apply("p1", "an2", remote, versionTs = 160L, expectedLocalHash = null)

        assertEquals(setOf("an1", "an2"), adapter.readAll("p1").map { it.key }.toSet())
    }

    @Test
    fun should_skipChangedLocally_when_rowDiffersFromExpectedHash() = runTest {
        dao.writeAlbumNote(note("an1", "p1", content = "edited here"))
        val localHash = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.apply("p1", "an1", remote, versionTs = 200L, expectedLocalHash = "stale")

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, localHash), outcome)
        assertEquals("edited here", dao.albumNoteById("an1")?.content)
    }

    @Test
    fun should_skipOwnedElsewhere_when_noteIdLivesUnderAnotherProfile() = runTest {
        dao.writeAlbumNote(note("an1", "p2", content = "theirs"))

        val outcome = adapter.apply("p1", "an1", remote, versionTs = 200L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null), outcome)
        assertEquals("p2", dao.albumNoteById("an1")?.profileId)
        // and a delete from p1's scope never touches it
        assertEquals(ApplyOutcome.Applied(null), adapter.delete("p1", "an1", expectedLocalHash = null))
        assertNotNull(dao.albumNoteById("an1"))
    }

    @Test
    fun should_deleteNote_when_hashMatches_andKeepIt_whenEditedLocally() = runTest {
        dao.writeAlbumNote(note("an1", "p1"))
        val hash = hashOf(adapter.readAll("p1").single().projection)

        assertTrue((adapter.delete("p1", "an1", "older") as ApplyOutcome.Skipped).reason == SkipReason.CHANGED_LOCALLY)
        assertNotNull(dao.albumNoteById("an1"))
        assertEquals(ApplyOutcome.Applied(null), adapter.delete("p1", "an1", expectedLocalHash = hash))
        assertNull(dao.albumNoteById("an1"))
    }

    @Test
    fun should_skipUnsupported_when_payloadMissesRequiredField() = runTest {
        val broken = payload("""{"albumId":"al-1","provider":"spotify","createdAt":1,"updatedAt":1}""")

        val outcome = adapter.apply("p1", "an1", broken, versionTs = 1L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
        assertNull(adapter.project(broken))
        assertNull(dao.albumNoteById("an1"))
    }

    private fun note(id: String, profileId: String, content: String = "hello") = AlbumNote(
        id = id,
        profileId = profileId,
        albumId = "al-1",
        provider = "spotify",
        content = content,
        createdAt = 10L,
        updatedAt = 20L,
        albumName = "Charm",
        artist = "Clairo",
    )
}
