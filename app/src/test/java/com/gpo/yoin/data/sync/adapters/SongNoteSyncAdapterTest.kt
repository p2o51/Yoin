package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
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
class SongNoteSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = SongNoteSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()

    @After
    fun tearDown() = fixtures.close()

    private val remote = payload(
        """{"trackId":"t1","provider":"spotify","content":"chorus hits","createdAt":100,"updatedAt":100,
           "title":"Song","artist":"Artist","positionMs":61000,"futureField":true}""",
    )

    @Test
    fun should_readBackSameProjection_when_payloadApplied() = runTest {
        val outcome = adapter.apply("p1", "n1", remote, versionTs = 100L, expectedLocalHash = null)

        val row = adapter.readAll("p1").single()
        val expected = hashOf(adapter.project(remote))
        assertEquals(ApplyOutcome.Applied(expected), outcome)
        assertEquals(expected, hashOf(row.projection))
        assertEquals("n1", row.key)
        assertEquals(100L, row.rowTs)
        assertEquals("p1", dao.noteById("n1")?.profileId)
    }

    @Test
    fun should_projectAbsentPositionAsNull_when_payloadOmitsIt() = runTest {
        val legacy = payload(
            """{"trackId":"t1","provider":"spotify","content":"x","createdAt":5,"updatedAt":6,
               "title":"S","artist":"A"}""",
        )

        adapter.apply("p1", "n1", legacy, versionTs = 6L, expectedLocalHash = null)

        assertNull(dao.noteById("n1")?.positionMs)
        assertEquals(hashOf(adapter.project(legacy)), hashOf(adapter.readAll("p1").single().projection))
    }

    @Test
    fun should_skipChangedLocally_when_rowDiffersFromExpectedHash() = runTest {
        dao.writeNote(note("n1", "p1", content = "edited here"))
        val localHash = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.apply("p1", "n1", remote, versionTs = 200L, expectedLocalHash = "stale")

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, localHash), outcome)
        assertEquals("edited here", dao.noteById("n1")?.content)
    }

    @Test
    fun should_skipChangedLocally_when_rowAppearedAndNoneWasExpected() = runTest {
        dao.writeNote(note("n1", "p1"))

        val outcome = adapter.apply("p1", "n1", remote, versionTs = 200L, expectedLocalHash = null)

        assertTrue((outcome as ApplyOutcome.Skipped).reason == SkipReason.CHANGED_LOCALLY)
    }

    @Test
    fun should_skipOwnedElsewhere_when_noteIdLivesUnderAnotherProfile() = runTest {
        dao.writeNote(note("n1", "p2", content = "theirs"))

        val outcome = adapter.apply("p1", "n1", remote, versionTs = 200L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null), outcome)
        val stored = dao.noteById("n1")
        assertEquals("p2", stored?.profileId)
        assertEquals("theirs", stored?.content)
    }

    @Test
    fun should_deleteOnlyWithinProfile_when_noteIdLivesUnderAnotherProfile() = runTest {
        dao.writeNote(note("n1", "p2"))

        val outcome = adapter.delete("p1", "n1", expectedLocalHash = null)

        assertEquals(ApplyOutcome.Applied(null), outcome)
        assertNotNull(dao.noteById("n1"))
    }

    @Test
    fun should_deleteNote_when_hashMatches() = runTest {
        dao.writeNote(note("n1", "p1"))
        val hash = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.delete("p1", "n1", expectedLocalHash = hash)

        assertEquals(ApplyOutcome.Applied(null), outcome)
        assertNull(dao.noteById("n1"))
    }

    @Test
    fun should_keepNote_when_deleteGuardSeesLocalEdit() = runTest {
        dao.writeNote(note("n1", "p1", content = "rewritten"))

        val outcome = adapter.delete("p1", "n1", expectedLocalHash = "older")

        assertTrue((outcome as ApplyOutcome.Skipped).reason == SkipReason.CHANGED_LOCALLY)
        assertNotNull(dao.noteById("n1"))
    }

    @Test
    fun should_skipUnsupported_when_payloadMissesRequiredField() = runTest {
        val broken = payload("""{"trackId":"t1","provider":"spotify","createdAt":1,"updatedAt":1}""")

        val outcome = adapter.apply("p1", "n1", broken, versionTs = 1L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
        assertNull(adapter.project(broken))
        assertNull(dao.noteById("n1"))
    }

    private fun note(id: String, profileId: String, content: String = "hello") = SongNote(
        id = id,
        profileId = profileId,
        trackId = "t1",
        provider = "spotify",
        content = content,
        createdAt = 10L,
        updatedAt = 20L,
        title = "Song",
        artist = "Artist",
        positionMs = null,
    )
}
