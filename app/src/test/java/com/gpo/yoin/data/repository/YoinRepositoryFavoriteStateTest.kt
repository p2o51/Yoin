package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.FavoriteStatesIncompleteException
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The repository's favorite-state overlay (P4) end to end: Spotify's answer
 * turns on a like the 200-track mirror can't show, a landed write holds for
 * the grace, an unlike stays an explicit false, a track is asked about once
 * per interval, a closed rate-limit gate asks nothing, a read stopped part
 * way keeps what it learned, and nothing crosses an account switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryFavoriteStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private val writeActions = FakeWriteActions()
    private val source = mockk<SpotifyMusicSource>()
    private val profileIds = MutableStateFlow<String?>(PROFILE)
    private var now = 1_760_000_000_000L
    private val gate = SpotifyRateLimitGate(clock = { now })
    private lateinit var repository: YoinRepository

    private val oldLike = track("old-like", isStarred = false)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { source.id } returns MediaId.PROVIDER_SPOTIFY
        every { source.profileId } answers { profileIds.value }
        every { source.capabilities } returns setOf(Capability.FAVORITES)
        every { source.library() } returns mockk<MusicLibrary>(relaxed = true)
        every { source.writeActions() } returns writeActions
        repository = YoinRepository(
            activeSource = MutableStateFlow(source),
            activeProfileId = profileIds,
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
            spotifyLibrarySyncCoordinator = SpotifyLibrarySyncCoordinator(
                database = database,
                rateLimitGate = gate,
                scope = CoroutineScope(SupervisorJob()),
                clock = { now }
            ),
            spotifyRateLimitGate = gate,
            // The account-switch clear runs at once.
            repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_showLiked_when_spotifySaysSavedForATrackOutsideTheMirror() = runTest {
        writeActions.answer = mapOf(oldLike.id to true)

        assertEquals(FavoriteState(isStarred = false), heart(oldLike))
        repository.refreshFavoriteStates(listOf(oldLike))

        assertEquals(FavoriteState(isStarred = true, fromUser = false), heart(oldLike))
    }

    @Test
    fun should_stayUnliked_when_theQueuesCopyStillSaysLiked() = runTest {
        val queued = track("t1", isStarred = true)

        repository.setFavorite(queued, favorite = false)

        assertEquals(FavoriteState(isStarred = false, fromUser = true), heart(queued))
    }

    @Test
    fun should_stopCountingAsTheUsersTap_when_theWritesGraceIsOver() = runTest {
        val track = track("t1", isStarred = false)
        repository.setFavorite(track, favorite = true)
        assertEquals(FavoriteState(isStarred = true, fromUser = true), heart(track))

        now += FAVORITE_WRITE_GRACE_MS

        // Still liked, but a later read (Now Playing coming back to it) is no tap to beat for.
        assertEquals(FavoriteState(isStarred = true, fromUser = false), heart(track))
    }

    @Test
    fun should_holdTheLikeForTheGrace_when_spotifyHasNotAppliedItYet() = runTest {
        val track = track("t1", isStarred = false)
        repository.setFavorite(track, favorite = true)
        writeActions.answer = mapOf(track.id to false)

        now += 10_000L
        repository.refreshFavoriteStates(listOf(track), minIntervalMs = 0L)
        assertEquals(FavoriteState(isStarred = true, fromUser = true), heart(track))

        // Past the grace Spotify's answer is the truth (unliked elsewhere).
        now += FAVORITE_WRITE_GRACE_MS
        repository.refreshFavoriteStates(listOf(track), minIntervalMs = 0L)
        assertEquals(FavoriteState(isStarred = false, fromUser = false), heart(track))
    }

    @Test
    fun should_followTheMirror_when_aSyncAfterTheAnswerSavedTheTrack() = runTest {
        writeActions.answer = mapOf(oldLike.id to false)
        repository.refreshFavoriteStates(listOf(oldLike))

        now += 1_000L
        database.spotifyLibraryCacheDao().upsertTrack(mirrorRow(oldLike.id.rawId, cachedAt = now))

        assertEquals(FavoriteState(isStarred = true), heart(oldLike))
    }

    @Test
    fun should_askOnce_when_refreshedTwiceWithinTheInterval() = runTest {
        writeActions.answer = mapOf(oldLike.id to true)

        repository.refreshFavoriteStates(listOf(oldLike))
        now += FAVORITE_RECHECK_INTERVAL_MS - 1
        repository.refreshFavoriteStates(listOf(oldLike, oldLike))
        assertEquals(1, writeActions.lookups.size)

        now += 1
        repository.refreshFavoriteStates(listOf(oldLike))
        assertEquals(2, writeActions.lookups.size)
    }

    @Test
    fun should_askNothing_when_theRateLimitGateIsClosed() = runTest {
        gate.recordBackoff(PROFILE, retryAfterSeconds = 60)

        repository.refreshFavoriteStates(listOf(oldLike))

        assertEquals(0, writeActions.lookups.size)
    }

    @Test
    fun should_keepTheAnsweredTracks_when_aLaterBatchIsRateLimited() = runTest {
        val unanswered = track("unanswered", isStarred = false)
        writeActions.failure = FavoriteStatesIncompleteException(
            answered = mapOf(oldLike.id to true),
            cause = SpotifyRateLimitException(retryAfterSeconds = 30, endpoint = "me/library/contains")
        )

        repository.refreshFavoriteStates(listOf(oldLike, unanswered))

        assertEquals(FavoriteState(isStarred = true), heart(oldLike))
        assertEquals(FavoriteState(isStarred = false), heart(unanswered))
        // The failure isn't retried: the unanswered track stays asked for the interval.
        writeActions.failure = null
        repository.refreshFavoriteStates(listOf(unanswered))
        assertEquals(1, writeActions.lookups.size)
    }

    @Test
    fun should_dropTheAnswer_when_theAccountSwitchedWhileItWasOut() = runTest {
        val answer = CompletableDeferred<Map<MediaId, Boolean>>()
        writeActions.pending = answer
        val check = async { repository.refreshFavoriteStates(listOf(oldLike)) }
        runCurrent()
        assertEquals(1, writeActions.lookups.size)

        profileIds.value = OTHER_PROFILE
        answer.complete(mapOf(oldLike.id to true))
        check.await()
        profileIds.value = PROFILE

        assertEquals(FavoriteState(isStarred = false), heart(oldLike))
    }

    @Test
    fun should_forgetLearnedStates_when_theAccountSwitches() = runTest {
        writeActions.answer = mapOf(oldLike.id to true)
        repository.refreshFavoriteStates(listOf(oldLike))
        repository.recordFavoriteState(PROFILE, MediaId.spotify("t2"), saved = true)

        profileIds.value = OTHER_PROFILE
        assertEquals(FavoriteState(isStarred = false), heart(oldLike))
        // An App Remote answer for the account that is gone is dropped.
        repository.recordFavoriteState(PROFILE, oldLike.id, saved = true)
        profileIds.value = PROFILE

        assertEquals(FavoriteState(isStarred = false), heart(oldLike))
        assertEquals(FavoriteState(isStarred = false), heart(track("t2", isStarred = false)))
    }

    private suspend fun heart(track: Track): FavoriteState = repository.observeFavoriteState(track).first()

    private fun track(rawId: String, isStarred: Boolean) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        isStarred = isStarred
    )

    private fun mirrorRow(rawId: String, cachedAt: Long) = SpotifyLibraryTrackCache(
        profileId = PROFILE,
        trackId = rawId,
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArtKey = null,
        durationSec = null,
        addedAt = null,
        isSaved = true,
        cachedAt = cachedAt
    )

    private class FakeWriteActions : MusicWriteActions {
        var answer: Map<MediaId, Boolean> = emptyMap()
        var failure: Exception? = null
        var pending: CompletableDeferred<Map<MediaId, Boolean>>? = null
        val lookups = mutableListOf<List<MediaId>>()

        override suspend fun favoriteStates(tracks: List<Track>, albums: List<MediaId>): Result<Map<MediaId, Boolean>> {
            lookups += tracks.map(Track::id)
            failure?.let { return Result.failure(it) }
            val states = pending?.await() ?: answer
            return Result.success(states.filterKeys { id -> tracks.any { it.id == id } })
        }

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = Result.success(Unit)

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> =
            Result.failure(UnsupportedOperationException())

        override suspend fun renamePlaylist(id: MediaId, name: String, description: String?): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> = Result.failure(UnsupportedOperationException())

        override suspend fun addTracksToPlaylist(playlistId: MediaId, tracks: List<MediaId>): Result<String?> =
            Result.failure(UnsupportedOperationException())

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?
        ): Result<String?> = Result.failure(UnsupportedOperationException())
    }

    private companion object {
        const val PROFILE = "spotify-a"
        const val OTHER_PROFILE = "spotify-b"
    }
}
