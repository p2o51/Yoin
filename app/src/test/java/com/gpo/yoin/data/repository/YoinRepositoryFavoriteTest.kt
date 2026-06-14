package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryCacheDao
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Focused coverage for [YoinRepository.setSpotifyFavorite] (fixes #4/#5/#11):
 * optimistic write, success/failure reconciliation, override lifecycle, and
 * the removal of any post-mutation `contains` verification.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryFavoriteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: SpotifyLibraryCacheDao
    private val library = mockk<MusicLibrary>(relaxed = true)
    private val writeActions = FakeWriteActions()
    private val source = mockk<SpotifyMusicSource>()
    private lateinit var coordinator: SpotifyLibrarySyncCoordinator
    private lateinit var repository: YoinRepository
    private var now = 1_000L

    private val profileId = "spotify-profile"
    private val trackId = MediaId.spotify("track-1")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.spotifyLibraryCacheDao()
        coordinator = SpotifyLibrarySyncCoordinator(
            database = database,
            rateLimitGate = SpotifyRateLimitGate(clock = { now }),
            scope = CoroutineScope(SupervisorJob()),
            clock = { now },
        )
        every { source.id } returns MediaId.PROVIDER_SPOTIFY
        every { source.profileId } returns profileId
        every { source.library() } returns library
        every { source.writeActions() } returns writeActions

        repository = YoinRepository(
            activeSource = MutableStateFlow(source),
            activeProfileId = MutableStateFlow(profileId),
            database = database,
            geminiService = mockk(relaxed = true),
            songAboutEntryDao = mockk(relaxed = true),
            geminiConfigDao = mockk(relaxed = true),
            lyricsCacheDao = mockk(relaxed = true),
            lyricsTranslationCacheDao = mockk(relaxed = true),
            songNoteDao = mockk(relaxed = true),
            albumNoteDao = mockk(relaxed = true),
            albumRatingDao = mockk(relaxed = true),
            memoryCopyCacheDao = mockk(relaxed = true),
            neoDbSyncService = mockk(relaxed = true),
            spotifyLibrarySyncCoordinator = coordinator,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun favorite_success_marks_row_saved_not_pending_and_clears_override() = runTest {
        writeActions.setFavoriteResult = Result.success(Unit)

        val result = repository.setFavorite(trackId, favorite = true)

        assertTrue(result.isSuccess)
        val row = dao.getTrack(profileId, trackId.rawId)
        requireNotNull(row)
        assertTrue(row.isSaved)
        assertFalse(row.pendingFavoriteAction)
        assertFalse(repository.favoriteOverrides.value.containsKey(trackId))
        // Mutation issued exactly once; no contains/verify step exists anymore.
        assertEquals(1, writeActions.setFavoriteCalls)
        assertEquals(true to trackId, writeActions.lastSetFavorite)
    }

    @Test
    fun unfavorite_success_deletes_cached_row_and_clears_override() = runTest {
        dao.upsertTrack(savedRow())
        writeActions.setFavoriteResult = Result.success(Unit)

        val result = repository.setFavorite(trackId, favorite = false)

        assertTrue(result.isSuccess)
        assertNull(dao.getTrack(profileId, trackId.rawId))
        assertFalse(repository.favoriteOverrides.value.containsKey(trackId))
        assertEquals(1, writeActions.setFavoriteCalls)
    }

    @Test
    fun favorite_failure_removes_optimistic_orphan_when_row_was_new() = runTest {
        writeActions.setFavoriteResult = Result.failure(IllegalStateException("boom"))

        val result = repository.setFavorite(trackId, favorite = true)

        assertFalse(result.isSuccess)
        // The optimistic row this call created must be dropped on failure.
        assertNull(dao.getTrack(profileId, trackId.rawId))
        assertFalse(repository.favoriteOverrides.value.containsKey(trackId))
    }

    @Test
    fun unfavorite_failure_removes_optimistic_orphan_when_row_was_new() = runTest {
        writeActions.setFavoriteResult = Result.failure(IllegalStateException("boom"))

        val result = repository.setFavorite(trackId, favorite = false)

        assertFalse(result.isSuccess)
        assertNull(dao.getTrack(profileId, trackId.rawId))
        assertFalse(repository.favoriteOverrides.value.containsKey(trackId))
    }

    @Test
    fun favorite_with_track_metadata_persists_title_and_artist() = runTest {
        writeActions.setFavoriteResult = Result.success(Unit)
        val track = com.gpo.yoin.data.model.Track(
            id = trackId,
            title = "Real Title",
            artist = "Real Artist",
            artistId = null,
            album = "Album",
            albumId = null,
            coverArt = null,
            durationSec = 200,
            trackNumber = 1,
            year = 2024,
            genre = null,
            userRating = null,
            isStarred = false,
        )

        val result = repository.setFavorite(track, favorite = true)

        assertTrue(result.isSuccess)
        val row = dao.getTrack(profileId, trackId.rawId)
        requireNotNull(row)
        assertEquals("Real Title", row.title)
        assertEquals("Real Artist", row.artist)
        assertTrue(row.isSaved)
    }

    @Test
    fun unfavorite_failure_rolls_back_to_previous_saved_state() = runTest {
        dao.upsertTrack(savedRow())
        writeActions.setFavoriteResult = Result.failure(IllegalStateException("boom"))

        val result = repository.setFavorite(trackId, favorite = false)

        assertFalse(result.isSuccess)
        val row = dao.getTrack(profileId, trackId.rawId)
        requireNotNull(row)
        // Pre-existing row stays saved (rolled back) and is no longer pending.
        assertTrue(row.isSaved)
        assertFalse(row.pendingFavoriteAction)
        assertFalse(repository.favoriteOverrides.value.containsKey(trackId))
    }

    private fun savedRow() = SpotifyLibraryTrackCache(
        profileId = profileId,
        trackId = trackId.rawId,
        title = "Song",
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArtKey = null,
        durationSec = null,
        addedAt = null,
        isSaved = true,
        cachedAt = now,
    )

    private class FakeWriteActions : MusicWriteActions {
        var setFavoriteResult: Result<Unit> = Result.success(Unit)
        var setFavoriteCalls = 0
        var lastSetFavorite: Pair<Boolean, MediaId>? = null

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> {
            setFavoriteCalls++
            lastSetFavorite = favorite to id
            return setFavoriteResult
        }

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> =
            Result.failure(UnsupportedOperationException())

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = Result.failure(UnsupportedOperationException())

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> = Result.failure(UnsupportedOperationException())

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?,
        ): Result<String?> = Result.failure(UnsupportedOperationException())
    }
}
