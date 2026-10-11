package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LocalRatingDao.markSynced]: the provider's answer to a rating write clears
 * the row's needsSync only while the row still holds the rating that write
 * sent, so a late answer about an older value never rolls a newer one back.
 */
@RunWith(RobolectricTestRunner::class)
class LocalRatingDaoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var ratings: LocalRatingDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        ratings = database.localRatingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_clearNeedsSync_when_theRowStillHoldsTheSyncedRating() = runTest {
        ratings.upsert(rating(7.7f, needsSync = true, updatedAt = 10L))

        assertEquals(1, ratings.markSynced(SONG, MediaId.PROVIDER_SUBSONIC, PROFILE, 7.7f))

        val row = stored()
        assertEquals(7.7f, row.rating)
        assertFalse(row.needsSync)
        assertEquals(10L, row.updatedAt)
    }

    @Test
    fun should_keepTheNewerRating_when_anOlderWritesAnswerLandsLate() = runTest {
        // 3.0 went out first; 7.7 was stored while it was still in flight.
        ratings.upsert(rating(3.0f, needsSync = true, updatedAt = 10L))
        ratings.upsert(rating(7.7f, needsSync = true, updatedAt = 20L))

        assertEquals(0, ratings.markSynced(SONG, MediaId.PROVIDER_SUBSONIC, PROFILE, 3.0f))

        val row = stored()
        assertEquals(7.7f, row.rating)
        assertTrue(row.needsSync)
        assertEquals(20L, row.updatedAt)
    }

    @Test
    fun should_leaveOtherProfilesAlone_when_markingASyncedRating() = runTest {
        ratings.upsert(rating(7.7f, needsSync = true, updatedAt = 10L))
        ratings.upsert(rating(7.7f, needsSync = true, updatedAt = 10L).copy(profileId = "other"))

        ratings.markSynced(SONG, MediaId.PROVIDER_SUBSONIC, PROFILE, 7.7f)

        assertTrue(ratings.getRating(SONG, MediaId.PROVIDER_SUBSONIC, "other").first()!!.needsSync)
    }

    private suspend fun stored(): LocalRating = ratings.getRating(SONG, MediaId.PROVIDER_SUBSONIC, PROFILE).first()!!

    private fun rating(value: Float, needsSync: Boolean, updatedAt: Long) = LocalRating(
        profileId = PROFILE,
        songId = SONG,
        provider = MediaId.PROVIDER_SUBSONIC,
        rating = value,
        serverRating = (value / 2f).toInt(),
        needsSync = needsSync,
        updatedAt = updatedAt
    )

    private companion object {
        const val PROFILE = "profile"
        const val SONG = "tr-1116"
    }
}
