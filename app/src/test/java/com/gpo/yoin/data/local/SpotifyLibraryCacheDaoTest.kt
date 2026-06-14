package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyLibraryCacheDaoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: SpotifyLibraryCacheDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.spotifyLibraryCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_isolate_cache_rows_by_profile() = runTest {
        dao.insertTracks(
            listOf(
                SpotifyLibraryTrackCache(
                    profileId = "profile-a",
                    trackId = "track-1",
                    title = "Song A",
                    artist = "Artist",
                    artistId = "artist-1",
                    album = "Album",
                    albumId = "album-1",
                    coverArtKey = null,
                    durationSec = 200,
                    addedAt = null,
                    isSaved = true,
                    cachedAt = 1_000L,
                ),
                SpotifyLibraryTrackCache(
                    profileId = "profile-b",
                    trackId = "track-1",
                    title = "Song B",
                    artist = "Artist",
                    artistId = "artist-1",
                    album = "Album",
                    albumId = "album-1",
                    coverArtKey = null,
                    durationSec = 200,
                    addedAt = null,
                    isSaved = true,
                    cachedAt = 1_000L,
                ),
            ),
        )

        val profileATracks = dao.getFreshTracks("profile-a", minCachedAt = 0L)
        assertEquals(1, profileATracks.size)
        assertEquals("Song A", profileATracks.first().title)
        assertTrue(dao.getFreshTracks("profile-b", minCachedAt = 0L).isNotEmpty())
    }

    @Test
    fun should_persist_pending_favorite_state_until_confirmed() = runTest {
        dao.upsertTrack(
            SpotifyLibraryTrackCache(
                profileId = "profile-a",
                trackId = "track-1",
                title = "Song",
                artist = "Artist",
                artistId = null,
                album = null,
                albumId = null,
                coverArtKey = null,
                durationSec = null,
                addedAt = null,
                isSaved = true,
                cachedAt = 1_000L,
                pendingFavoriteAction = true,
            ),
        )

        dao.updateTrackFavoriteState(
            profileId = "profile-a",
            trackId = "track-1",
            isSaved = false,
            pending = false,
            lastSyncError = null,
            cachedAt = 2_000L,
        )

        val row = dao.getTrack("profile-a", "track-1")
        requireNotNull(row)
        assertEquals(false, row.isSaved)
        assertEquals(false, row.pendingFavoriteAction)
    }

    @Test
    fun getPendingTracks_returns_only_rows_awaiting_favorite_confirmation() = runTest {
        dao.upsertTrack(trackRow("pending", pending = true))
        dao.upsertTrack(trackRow("settled", pending = false))

        val pending = dao.getPendingTracks("profile-a")

        assertEquals(1, pending.size)
        assertEquals("pending", pending.first().trackId)
    }

    @Test
    fun deleteTrack_removes_only_the_targeted_profile_row() = runTest {
        dao.upsertTrack(trackRow("track-1"))
        dao.upsertTrack(trackRow("track-2"))

        dao.deleteTrack("profile-a", "track-1")

        assertEquals(null, dao.getTrack("profile-a", "track-1"))
        assertTrue(dao.getTrack("profile-a", "track-2") != null)
    }

    private fun trackRow(trackId: String, pending: Boolean = false) = SpotifyLibraryTrackCache(
        profileId = "profile-a",
        trackId = trackId,
        title = trackId,
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArtKey = null,
        durationSec = null,
        addedAt = null,
        isSaved = true,
        cachedAt = 1_000L,
        pendingFavoriteAction = pending,
    )
}
