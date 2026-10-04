package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackRatingSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = TrackRatingSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()
    private val key = SyncFormat.compositeKey("subsonic", "song-1")

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_readBackSameProjection_when_ratingArrivesAsIntegerLiteral() = runTest {
        val remote = payload("""{"songId":"song-1","provider":"subsonic","rating":8}""")

        val outcome = adapter.apply("p1", key, remote, versionTs = 500L, expectedLocalHash = null)

        val row = adapter.readAll("p1").single()
        assertEquals(key, row.key)
        assertEquals(hashOf(adapter.project(remote)), hashOf(row.projection))
        assertEquals(ApplyOutcome.Applied(hashOf(row.projection)), outcome)
        // "8", "8.0" and Room's 8.0f all hash alike.
        val decimal = payload("""{"songId":"song-1","provider":"subsonic","rating":8.0}""")
        assertEquals(hashOf(adapter.project(remote)), hashOf(adapter.project(decimal)))
    }

    @Test
    fun should_insertPushedRowAtVersionTs_when_ratingIsNew() = runTest {
        adapter.apply("p1", key, rating(7.0f), versionTs = 500L, expectedLocalHash = null)

        val stored = dao.trackRating("p1", "song-1", "subsonic")!!
        assertEquals(7.0f, stored.rating)
        assertEquals(4, stored.serverRating)
        assertFalse(stored.needsSync)
        assertEquals(500L, stored.updatedAt)
    }

    @Test
    fun should_keepPushState_when_ratingValueUnchanged() = runTest {
        dao.upsertTrackRating(local(rating = 6f, serverRating = 1, needsSync = true, updatedAt = 10L))
        val expected = hashOf(adapter.readAll("p1").single().projection)

        adapter.apply("p1", key, rating(6f), versionTs = 900L, expectedLocalHash = expected)

        val stored = dao.trackRating("p1", "song-1", "subsonic")!!
        assertEquals(1, stored.serverRating)
        assertTrue(stored.needsSync)
        assertEquals(900L, stored.updatedAt)
    }

    @Test
    fun should_recomputeServerRating_when_ratingValueChanged() = runTest {
        dao.upsertTrackRating(local(rating = 6f, serverRating = 3, needsSync = true, updatedAt = 10L))
        val expected = hashOf(adapter.readAll("p1").single().projection)

        adapter.apply("p1", key, rating(10f), versionTs = 900L, expectedLocalHash = expected)

        val stored = dao.trackRating("p1", "song-1", "subsonic")!!
        assertEquals(10f, stored.rating)
        assertEquals(5, stored.serverRating)
        assertFalse(stored.needsSync)
        assertEquals(900L, stored.updatedAt)
    }

    @Test
    fun should_skipChangedLocally_when_expectedHashIsStale() = runTest {
        dao.upsertTrackRating(local(rating = 2f, serverRating = 1, needsSync = false, updatedAt = 10L))
        val current = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.apply("p1", key, rating(9f), versionTs = 900L, expectedLocalHash = "stale")

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), outcome)
        assertEquals(2f, dao.trackRating("p1", "song-1", "subsonic")!!.rating)
    }

    @Test
    fun should_skipUnsupported_when_payloadDisagreesWithKey() = runTest {
        val other = payload("""{"songId":"song-2","provider":"subsonic","rating":4}""")

        val outcome = adapter.apply("p1", key, other, versionTs = 1L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
        assertNull(dao.trackRating("p1", "song-2", "subsonic"))
    }

    @Test
    fun should_readOnlyTheProfilesRows_when_otherProfilesRated() = runTest {
        dao.upsertTrackRating(local(rating = 2f, serverRating = 1, needsSync = false, updatedAt = 10L))
        val other = local(rating = 4f, serverRating = 2, needsSync = false, updatedAt = 10L)
        dao.upsertTrackRating(other.copy(profileId = "p2"))

        assertEquals(1, adapter.readAll("p1").size)
    }

    private fun rating(value: Float) = payload("""{"songId":"song-1","provider":"subsonic","rating":$value}""")

    private fun local(rating: Float, serverRating: Int, needsSync: Boolean, updatedAt: Long) = LocalRating(
        profileId = "p1",
        songId = "song-1",
        provider = "subsonic",
        rating = rating,
        serverRating = serverRating,
        needsSync = needsSync,
        updatedAt = updatedAt,
    )
}
