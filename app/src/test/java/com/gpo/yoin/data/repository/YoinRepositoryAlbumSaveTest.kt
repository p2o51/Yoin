package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryAlbumCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.ServiceFeatureCatalog
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A Spotify album saved to the library (Q11) end to end in the repository:
 * the row starts from the saved-albums mirror, or else from Spotify's answer
 * asked in the album page's own contains request; a save or removal shows at
 * once, lands in the mirror, and rolls back when it fails; a service that
 * can't save albums gets no row and sends nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryAlbumSaveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private val writeActions = FakeWriteActions()
    private val spotify = mockk<SpotifyMusicSource>()
    private val activeSource = MutableStateFlow<MusicSource?>(spotify)
    private val profileIds = MutableStateFlow<String?>(PROFILE)
    private var now = 1_760_000_000_000L
    private val gate = SpotifyRateLimitGate(clock = { now })
    private lateinit var repository: YoinRepository

    private val album = album(MediaId.spotify("al1"))
    private val track = track(MediaId.spotify("t1"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { spotify.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotify.profileId } answers { profileIds.value }
        every { spotify.capabilities } returns ServiceFeatureCatalog.spotify.capabilities
        every { spotify.library() } returns mockk<MusicLibrary>(relaxed = true)
        every { spotify.writeActions() } returns writeActions
        repository = YoinRepository(
            activeSource = activeSource,
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
    fun should_startFromTheMirrorAndAskNothing_when_theAlbumIsAmongTheCachedSavedAlbums() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))

        assertEquals(true, saved(album))
        repository.refreshFavoriteStates(listOf(track), album = album)

        // The tracks are still asked about; the album isn't.
        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = emptyList())), writeActions.lookups)
    }

    @Test
    fun should_askInTheTracksRequest_when_theMirrorDoesNotHaveTheAlbum() = runTest {
        writeActions.answer = mapOf(album.id to true, track.id to false)

        assertEquals(false, saved(album))
        repository.refreshFavoriteStates(listOf(track), album = album)

        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = listOf(album.id))), writeActions.lookups)
        assertEquals(true, saved(album))
        // The album's answer doesn't turn into a track's heart, nor the reverse.
        assertEquals(FavoriteState(isStarred = false), repository.observeFavoriteState(track).first())
    }

    @Test
    fun should_askAboutTheAlbumOnce_when_thePageResumesWithinTheInterval() = runTest {
        repository.refreshFavoriteStates(listOf(track), album = album)
        now += FAVORITE_RECHECK_INTERVAL_MS - 1
        repository.refreshFavoriteStates(listOf(track), album = album)
        assertEquals(1, writeActions.lookups.size)

        now += 1
        repository.refreshFavoriteStates(emptyList(), album = album)
        assertEquals(Lookup(tracks = emptyList(), albums = listOf(album.id)), writeActions.lookups.last())
    }

    @Test
    fun should_askNothing_when_theRateLimitGateIsClosed() = runTest {
        gate.recordBackoff(PROFILE, retryAfterSeconds = 60)

        repository.refreshFavoriteStates(listOf(track), album = album)

        assertEquals(emptyList<Lookup>(), writeActions.lookups)
    }

    @Test
    fun should_showTheSaveAtOnceAndFileItInTheMirror_when_theWriteLands() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val saving = async { repository.setAlbumSaved(album, saved = true) }
        runCurrent()
        assertEquals(true, saved(album))

        write.complete(Result.success(Unit))
        assertTrue(saving.await().isSuccess)

        assertEquals(listOf(album.id to true), writeActions.writes)
        assertEquals(true, saved(album))
        val row = database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1")
        assertNotNull(row)
        assertEquals(true, row?.isSaved)
        // Filed at the top of Recently added, in Spotify's own format.
        assertEquals("2025-10-09T08:53:20Z", row?.addedAt)
    }

    @Test
    fun should_rollBackAndReturnTheReason_when_theSaveFails() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val saving = async { repository.setAlbumSaved(album, saved = true) }
        runCurrent()
        assertEquals(true, saved(album))

        val reason = SpotifyRateLimitException(retryAfterSeconds = 30, endpoint = "me/library")
        write.complete(Result.failure(reason))

        assertEquals(reason, saving.await().exceptionOrNull())
        assertEquals(false, saved(album))
        assertNull(database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1"))
    }

    @Test
    fun should_showTheRemovalAtOnceAndDropTheMirrorRow_when_theRemovalLands() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val removing = async { repository.setAlbumSaved(album, saved = false) }
        runCurrent()
        assertEquals(false, saved(album))

        write.complete(Result.success(Unit))
        assertTrue(removing.await().isSuccess)

        assertEquals(false, saved(album))
        assertNull(database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1"))
    }

    @Test
    fun should_rollBackToSaved_when_theRemovalFails() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))
        writeActions.writeResult = Result.failure(IllegalStateException("offline"))

        assertTrue(repository.setAlbumSaved(album, saved = false).isFailure)

        assertEquals(true, saved(album))
        assertEquals(true, database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1")?.isSaved)
    }

    @Test
    fun should_holdTheSaveForTheGrace_when_spotifysAnswerLagsBehind() = runTest {
        repository.setAlbumSaved(album, saved = true)
        database.spotifyLibraryCacheDao().deleteAlbum(PROFILE, "al1")
        writeActions.answer = mapOf(album.id to false)

        now += 10_000L
        repository.refreshFavoriteStates(emptyList(), minIntervalMs = 0L, album = album)
        assertEquals(true, saved(album))

        // Past the grace Spotify's answer is the truth (removed elsewhere).
        now += FAVORITE_WRITE_GRACE_MS
        repository.refreshFavoriteStates(emptyList(), minIntervalMs = 0L, album = album)
        assertEquals(false, saved(album))
    }

    @Test
    fun should_offerNoRowAndSendNothing_when_theServiceCannotSaveAlbums() = runTest {
        val subsonic = mockk<MusicSource>()
        every { subsonic.id } returns MediaId.PROVIDER_SUBSONIC
        every { subsonic.capabilities } returns ServiceFeatureCatalog.subsonic.capabilities
        every { subsonic.writeActions() } returns writeActions
        activeSource.value = subsonic
        val subsonicAlbum = album(MediaId(MediaId.PROVIDER_SUBSONIC, "al1"))

        assertNull(repository.observeAlbumSaved(subsonicAlbum.id).first())
        val error = repository.setAlbumSaved(subsonicAlbum, saved = true).exceptionOrNull()
        repository.refreshFavoriteStates(emptyList(), album = subsonicAlbum)

        assertTrue(error is UnsupportedOperationException)
        assertEquals(emptyList<Pair<MediaId, Boolean>>(), writeActions.writes)
        assertEquals(emptyList<Lookup>(), writeActions.lookups)
    }

    @Test
    fun should_declareAlbumSaveOnlyForSpotify_when_readingTheServiceCatalog() {
        // Only Spotify declares the capability the row is gated on.
        val declaring = ServiceFeatureCatalog.entries.filter { Capability.ALBUM_SAVE in it.capabilities }.map { it.id }

        assertEquals(listOf(MediaId.PROVIDER_SPOTIFY), declaring)
    }

    @Test
    fun should_forgetTheAnswer_when_theAccountSwitches() = runTest {
        writeActions.answer = mapOf(album.id to true)
        repository.refreshFavoriteStates(emptyList(), album = album)
        assertEquals(true, saved(album))

        profileIds.value = OTHER_PROFILE
        profileIds.value = PROFILE

        assertEquals(false, saved(album))
    }

    private suspend fun saved(album: Album): Boolean? = repository.observeAlbumSaved(album.id).first()

    private fun album(id: MediaId) = Album(
        id = id,
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 1,
        durationSec = null,
        year = 2020,
        genre = null,
        tracks = listOf(track(MediaId(id.provider, "t1")))
    )

    private fun track(id: MediaId) = Track(
        id = id,
        title = id.rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private fun mirrorRow(rawId: String, cachedAt: Long) = SpotifyLibraryAlbumCache(
        profileId = PROFILE,
        albumId = rawId,
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArtKey = null,
        songCount = 1,
        year = 2020,
        isSaved = true,
        addedAt = "2024-01-01T00:00:00Z",
        cachedAt = cachedAt
    )

    private data class Lookup(val tracks: List<MediaId>, val albums: List<MediaId>)

    private class FakeWriteActions : MusicWriteActions {
        var answer: Map<MediaId, Boolean> = emptyMap()
        var pendingWrite: CompletableDeferred<Result<Unit>>? = null
        var writeResult: Result<Unit> = Result.success(Unit)
        val lookups = mutableListOf<Lookup>()
        val writes = mutableListOf<Pair<MediaId, Boolean>>()

        override suspend fun favoriteStates(tracks: List<Track>, albums: List<MediaId>): Result<Map<MediaId, Boolean>> {
            lookups += Lookup(tracks.map(Track::id), albums)
            val asked = tracks.map(Track::id) + albums
            return Result.success(answer.filterKeys { it in asked })
        }

        override suspend fun setAlbumSaved(album: Album, saved: Boolean): Result<Unit> {
            writes += album.id to saved
            return pendingWrite?.await() ?: writeResult
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
