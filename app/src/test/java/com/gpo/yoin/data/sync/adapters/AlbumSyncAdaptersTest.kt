package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumSyncAdaptersTest {
    private val fixtures = SyncDomainFixtures()
    private val ratings = AlbumRatingSyncAdapter(fixtures.db)
    private val reviews = AlbumReviewSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()
    private val key = SyncFormat.compositeKey("spotify", "album-1")

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_readBackSameProjection_when_ratingAndReviewApplied() = runTest {
        val rating = payload("""{"albumId":"album-1","provider":"spotify","rating":7.5}""")
        val review = payload("""{"albumId":"album-1","provider":"spotify","review":"Side B is the record."}""")

        val ratingOutcome = ratings.apply("p1", key, rating, versionTs = 100L, expectedLocalHash = null)
        val reviewOutcome = reviews.apply("p1", key, review, versionTs = 200L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Applied(hashOf(ratings.project(rating))), ratingOutcome)
        assertEquals(ApplyOutcome.Applied(hashOf(reviews.project(review))), reviewOutcome)
        assertEquals(hashOf(ratings.project(rating)), hashOf(ratings.readAll("p1").single().projection))
        assertEquals(hashOf(reviews.project(review)), hashOf(reviews.readAll("p1").single().projection))
    }

    @Test
    fun should_updateReviewOnly_when_rowHasRatingAndNeoDbState() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = "old words"))
        val expected = hashOf(reviews.readAll("p1").single().projection)

        reviews.apply("p1", key, review("new words"), versionTs = 900L, expectedLocalHash = expected)

        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertEquals("new words", stored.review)
        assertEquals(8f, stored.rating)
        assertEquals("neo-uuid", stored.neoDbReviewUuid)
        assertTrue(stored.ratingNeedsSync)
        assertTrue(stored.reviewNeedsSync)
        assertEquals(900L, stored.updatedAt)
    }

    @Test
    fun should_updateRatingOnly_when_rowHasReviewAndNeoDbState() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = "keep me"))
        val expected = hashOf(ratings.readAll("p1").single().projection)

        ratings.apply("p1", key, rating(3f), versionTs = 900L, expectedLocalHash = expected)

        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertEquals(3f, stored.rating)
        assertEquals("keep me", stored.review)
        assertEquals("neo-uuid", stored.neoDbReviewUuid)
        assertTrue(stored.ratingNeedsSync)
        assertTrue(stored.reviewNeedsSync)
    }

    @Test
    fun should_neverMoveUpdatedAtBack_when_olderVersionApplied() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = "x").copy(updatedAt = 5_000L))
        val expected = hashOf(ratings.readAll("p1").single().projection)

        ratings.apply("p1", key, rating(1f), versionTs = 100L, expectedLocalHash = expected)

        assertEquals(5_000L, dao.albumRating("p1", "album-1", "spotify")!!.updatedAt)
    }

    @Test
    fun should_insertEmptyRow_when_reviewArrivesForMissingAlbum() = runTest {
        reviews.apply("p1", key, review("first"), versionTs = 300L, expectedLocalHash = null)

        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertEquals(0f, stored.rating)
        assertEquals("first", stored.review)
        assertNull(stored.neoDbReviewUuid)
        assertEquals(false, stored.ratingNeedsSync)
        assertEquals(false, stored.reviewNeedsSync)
        assertEquals(300L, stored.updatedAt)
    }

    @Test
    fun should_applyReview_when_rowExistsOnlyBecauseRatingWasApplied() = runTest {
        ratings.apply("p1", key, rating(6f), versionTs = 100L, expectedLocalHash = null)

        val outcome = reviews.apply("p1", key, review("joined"), versionTs = 200L, expectedLocalHash = null)

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals("joined", dao.albumRating("p1", "album-1", "spotify")!!.review)
        assertEquals(6f, dao.albumRating("p1", "album-1", "spotify")!!.rating)
    }

    @Test
    fun should_skipChangedLocally_when_reviewEditedSinceExpectedHash() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = "mine"))
        val current = hashOf(reviews.readAll("p1").single().projection)

        val outcome = reviews.apply("p1", key, review("theirs"), versionTs = 900L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), outcome)
        assertEquals("mine", dao.albumRating("p1", "album-1", "spotify")!!.review)
    }

    @Test
    fun should_reportZeroRowTs_when_fieldIsEmpty() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = null).copy(rating = 0f, updatedAt = 777L))

        assertEquals(0L, ratings.readAll("p1").single().rowTs)
        assertEquals(0L, reviews.readAll("p1").single().rowTs)
    }

    @Test
    fun should_materializeEmptyRow_when_emptyValueArrivesForMissingAlbum() = runTest {
        val outcome = reviews.apply("p1", key, review(null), versionTs = 1L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Applied(hashOf(reviews.project(review(null)))), outcome)
        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertNull(stored.review)
        assertEquals(0f, stored.rating)
        assertNull(stored.neoDbReviewUuid)
    }

    @Test
    fun should_notLookLikeLocalEdit_when_siblingReviewArrivesAfterEmptyRating() = runTest {
        // A wrote a review only, so it publishes rating 0 (ts 0) next to the review.
        val ratingHash = appliedHash(ratings.apply("p1", key, rating(0f), versionTs = 0L, expectedLocalHash = null))
        reviews.apply("p1", key, review("A's words"), versionTs = 300L, expectedLocalHash = null)

        // The engine compares the next read against the hash it stored for album_rating: equal = no edit,
        // so a newer real rating from A applies instead of being overwritten by this device's "0".
        assertEquals(ratingHash, hashOf(ratings.readAll("p1").single().projection))
        val applied = ratings.apply("p1", key, rating(8f), versionTs = 400L, expectedLocalHash = ratingHash)
        assertEquals(ApplyOutcome.Applied(hashOf(ratings.project(rating(8f)))), applied)
        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertEquals(8f, stored.rating)
        assertEquals("A's words", stored.review)
    }

    @Test
    fun should_notLookLikeLocalEdit_when_userReviewsAfterEmptyRatingApplied() = runTest {
        val ratingHash = appliedHash(ratings.apply("p1", key, rating(0f), versionTs = 0L, expectedLocalHash = null))
        val row = dao.albumRating("p1", "album-1", "spotify")!!
        // What YoinRepository.setAlbumReview does with an existing row.
        dao.updateAlbumReviewValue("p1", "album-1", "spotify", review = "mine", updatedAt = 900L)

        assertEquals(0f, row.rating)
        assertEquals(ratingHash, hashOf(ratings.readAll("p1").single().projection))
    }

    @Test
    fun should_clearOnlyOwnField_when_deletingRatingOfReviewedAlbum() = runTest {
        dao.insertAlbumRatingIfAbsent(neoDbRow(review = "stays"))
        val hash = hashOf(ratings.readAll("p1").single().projection)

        ratings.delete("p1", key, expectedLocalHash = hash)

        val stored = dao.albumRating("p1", "album-1", "spotify")!!
        assertEquals(0f, stored.rating)
        assertEquals("stays", stored.review)
    }

    @Test
    fun should_keepBothTexts_when_concurrentReviewsDiffer() {
        val winner = payload("""{"albumId":"album-1","provider":"spotify","review":"Winner text","x":1}""")
        val loser = payload("""{"albumId":"album-1","provider":"spotify","review":"Loser text"}""")

        val merged = reviews.mergeConcurrent(winner, loser, "Pixel Tablet · 2026-10-04")!!

        assertEquals(
            "Winner text\n\n— Pixel Tablet · 2026-10-04 —\nLoser text",
            merged["review"]!!.jsonPrimitive.content,
        )
        assertEquals(JsonPrimitive(1), merged["x"])
    }

    @Test
    fun should_notMerge_when_textsEqualOrOneSideBlank() {
        val text = payload("""{"albumId":"a","provider":"p","review":"same"}""")
        val blank = payload("""{"albumId":"a","provider":"p","review":null}""")

        assertNull(reviews.mergeConcurrent(text, text, "label"))
        assertNull(reviews.mergeConcurrent(text, blank, "label"))
        assertNull(reviews.mergeConcurrent(blank, text, "label"))
    }

    private fun appliedHash(outcome: ApplyOutcome): String? = (outcome as ApplyOutcome.Applied).localHash

    private fun rating(value: Float) = payload("""{"albumId":"album-1","provider":"spotify","rating":$value}""")

    private fun review(text: String?) = payload(
        """{"albumId":"album-1","provider":"spotify","review":${text?.let { "\"$it\"" } ?: "null"}}""",
    )

    private fun neoDbRow(review: String?) = AlbumRating(
        profileId = "p1",
        albumId = "album-1",
        provider = "spotify",
        rating = 8f,
        review = review,
        neoDbReviewUuid = "neo-uuid",
        ratingNeedsSync = true,
        reviewNeedsSync = true,
        updatedAt = 50L,
    )
}
