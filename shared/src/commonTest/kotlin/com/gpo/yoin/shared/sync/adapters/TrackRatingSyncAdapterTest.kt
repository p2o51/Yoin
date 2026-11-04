package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The track_rating wire format for "not rated" vs a 0.0 score, the same five cases as
 * Android's TrackRatingSyncAdapterTest (557a58681): a cleared row travels as the old bare
 * `"rating": 0`, a real 0.0 adds `"zero": true`.
 */
class TrackRatingSyncAdapterTest {
    private val domain = FakeSyncDomain()
    private val adapter = TrackRatingSyncAdapter(domain)
    private val key = SyncFormat.compositeKey("subsonic", "song-1")

    private fun payload(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun rating(value: Float) = payload("""{"songId":"song-1","provider":"subsonic","rating":$value}""")
    private fun zeroScore() = payload("""{"songId":"song-1","provider":"subsonic","rating":0,"zero":true}""")
    private fun hashOf(o: JsonObject?) = o?.let(CanonicalJson::hash)
    private fun local(rating: Double?, updatedAt: Long = 10L) =
        domain.putTrackRating("p1", TrackRatingRow("subsonic", "song-1", rating, updatedAt))

    @Test
    fun should_projectBareZero_when_trackIsNotRated() = runTest {
        local(null)
        val row = adapter.readAll("p1").single()
        assertEquals(hashOf(adapter.project(rating(0f))), hashOf(row.projection))
        assertFalse(row.projection.containsKey(TrackRatingSyncAdapter.ZERO))
    }

    @Test
    fun should_markZeroScore_when_trackIsRatedZero() = runTest {
        local(0.0)
        val row = adapter.readAll("p1").single()
        assertEquals(hashOf(adapter.project(zeroScore())), hashOf(row.projection))
        assertTrue(row.projection.containsKey(TrackRatingSyncAdapter.ZERO))
        assertFalse(hashOf(adapter.project(rating(0f))) == hashOf(row.projection))
    }

    @Test
    fun should_storeNotRated_when_bareZeroArrives() = runTest {
        adapter.apply("p1", key, rating(0f), versionTs = 500L, expectedLocalHash = null)
        val stored = domain.trackRating("p1", "subsonic", "song-1")!!
        assertNull(stored.rating)
        assertEquals(500L, stored.updatedAt)
    }

    @Test
    fun should_storeZeroScore_when_markedZeroArrives() = runTest {
        adapter.apply("p1", key, zeroScore(), versionTs = 500L, expectedLocalHash = null)
        assertEquals(0.0, domain.trackRating("p1", "subsonic", "song-1")!!.rating)
    }

    @Test
    fun should_clearTheScore_when_bareZeroOverwritesAScore() = runTest {
        local(6.0)
        val expected = hashOf(adapter.readAll("p1").single().projection)
        val outcome = adapter.apply("p1", key, rating(0f), versionTs = 900L, expectedLocalHash = expected)
        assertTrue(outcome is ApplyOutcome.Applied)
        val stored = domain.trackRating("p1", "subsonic", "song-1")!!
        assertNull(stored.rating)
        assertEquals(900L, stored.updatedAt)
    }

    @Test
    fun should_keepAScore_when_nonZeroArrives() = runTest {
        adapter.apply("p1", key, rating(7.3f), versionTs = 500L, expectedLocalHash = null)
        assertEquals(7.3, domain.trackRating("p1", "subsonic", "song-1")!!.rating)
        assertFalse(adapter.readAll("p1").single().projection.containsKey(TrackRatingSyncAdapter.ZERO))
    }
}
