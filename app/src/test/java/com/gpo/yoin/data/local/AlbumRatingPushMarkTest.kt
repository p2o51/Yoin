package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A NeoDB push clears a dirty flag only for what it sent: an edit made while the push was in flight (the
 * album page and the Memories diary both push) keeps its value and its flag.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumRatingPushMarkTest {

    private lateinit var database: YoinDatabase
    private lateinit var dao: AlbumRatingDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.albumRatingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(rating: Float, review: String?) = dao.upsert(
        AlbumRating(
            profileId = "p",
            albumId = "al-1",
            provider = "subsonic",
            rating = rating,
            review = review,
            neoDbReviewUuid = null,
            ratingNeedsSync = true,
            reviewNeedsSync = true,
            updatedAt = 1L,
        ),
    )

    private suspend fun row() = requireNotNull(dao.get("al-1", "subsonic", "p"))

    @Test
    fun should_clearTheFlags_when_theRowStillHoldsWhatWasSent() = runTest {
        seed(rating = 7.5f, review = "副歌一出来就亮了。")

        assertEquals(1, dao.markRatingPushed("al-1", "subsonic", "p", sentRating = 7.5f, now = 2L))
        assertEquals(1, dao.markReviewPushed("al-1", "subsonic", "p", "副歌一出来就亮了。", reviewUuid = "r-1", now = 2L))

        val after = row()
        assertFalse(after.ratingNeedsSync)
        assertFalse(after.reviewNeedsSync)
        assertEquals("r-1", after.neoDbReviewUuid)
        assertEquals(7.5f, after.rating)
    }

    @Test
    fun should_keepTheNewerEditDirty_when_itLandedDuringThePush() = runTest {
        // The push sent 7.0 and "first words"; meanwhile the user set 9.0 and rewrote the words.
        seed(rating = 9f, review = "second words")

        assertEquals(0, dao.markRatingPushed("al-1", "subsonic", "p", sentRating = 7f, now = 2L))
        assertEquals(0, dao.markReviewPushed("al-1", "subsonic", "p", "first words", reviewUuid = "r-1", now = 2L))

        val after = row()
        assertTrue(after.ratingNeedsSync)
        assertTrue(after.reviewNeedsSync)
        assertEquals(9f, after.rating)
        assertEquals("second words", after.review)
    }

    @Test
    fun should_matchAClearedReview_when_theSentTextWasNull() = runTest {
        seed(rating = 0f, review = null)

        assertEquals(1, dao.markReviewPushed("al-1", "subsonic", "p", sentReview = null, reviewUuid = null, now = 2L))
        assertFalse(row().reviewNeedsSync)
    }
}
