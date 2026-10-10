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
 * asked in the album page's own contains request, and is unknown (no row)
 * until one of them says; a save or removal shows at once, lands in the
 * mirror, rolls back when it fails, and isn't asked about again while
 * Spotify's answer couldn't beat it; a service that can't save albums
 * (Subsonic, Apple Music) gets no row and sends nothing.
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

        assertEquals(AlbumSavedState.Saved, state(album))
        repository.refreshFavoriteStates(listOf(track), album = album)

        // The tracks are still asked about; the album isn't.
        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = emptyList())), writeActions.lookups)
    }

    @Test
    fun should_askInTheTracksRequest_when_theMirrorDoesNotHaveTheAlbum() = runTest {
        writeActions.answer = mapOf(album.id to true, track.id to false)

        // Beyond the mirror's newest 200 it may well be saved: unknown, not "not saved".
        assertEquals(AlbumSavedState.Unknown, state(album))
        repository.refreshFavoriteStates(listOf(track), album = album)

        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = listOf(album.id))), writeActions.lookups)
        assertEquals(AlbumSavedState.Saved, state(album))
        // The album's answer doesn't turn into a track's heart, nor the reverse.
        assertEquals(
            FavoriteState(isStarred = false, fromAnswer = true, answeredAtMs = now),
            repository.observeFavoriteState(track).first()
        )
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
    fun should_leaveTheRowOutAndSendNothing_when_theGateIsClosedAndTheMirrorLacksTheAlbum() = runTest {
        gate.recordBackoff(PROFILE, retryAfterSeconds = 60)

        repository.refreshFavoriteStates(listOf(track), album = album)

        // No row: a Save here could neither be known right nor get past the gate.
        assertEquals(AlbumSavedState.Unknown, state(album))
        assertNull(state(album).savedOrNull)
        assertEquals(emptyList<Lookup>(), writeActions.lookups)
        assertEquals(emptyList<Pair<MediaId, Boolean>>(), writeActions.writes)
    }

    @Test
    fun should_stayUnknownUntilSpotifyAnswers_when_theCheckIsStillOut() = runTest {
        val lookup = CompletableDeferred<Unit>()
        writeActions.pendingLookup = lookup
        writeActions.answer = mapOf(album.id to false)
        val checking = async { repository.refreshFavoriteStates(listOf(track), album = album) }
        runCurrent()
        assertEquals(AlbumSavedState.Unknown, state(album))

        lookup.complete(Unit)
        checking.await()

        assertEquals(AlbumSavedState.NotSaved, state(album))
    }

    @Test
    fun should_showTheSaveAtOnceAndFileItInTheMirror_when_theWriteLands() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val saving = async { repository.setAlbumSaved(album, saved = true) }
        runCurrent()
        assertEquals(AlbumSavedState.Saved, state(album))

        write.complete(Result.success(Unit))
        assertTrue(saving.await().isSuccess)

        assertEquals(listOf(album.id to true), writeActions.writes)
        assertEquals(AlbumSavedState.Saved, state(album))
        val row = database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1")
        assertNotNull(row)
        assertEquals(true, row?.isSaved)
        // Filed at the top of Recently added, in Spotify's own format.
        assertEquals("2025-10-09T08:53:20Z", row?.addedAt)
    }

    @Test
    fun should_rollBackAndReturnTheReason_when_theSaveFails() = runTest {
        writeActions.answer = mapOf(album.id to false)
        repository.refreshFavoriteStates(emptyList(), album = album)
        assertEquals(AlbumSavedState.NotSaved, state(album))
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val saving = async { repository.setAlbumSaved(album, saved = true) }
        runCurrent()
        assertEquals(AlbumSavedState.Saved, state(album))

        val reason = SpotifyRateLimitException(retryAfterSeconds = 30, endpoint = "me/library")
        write.complete(Result.failure(reason))

        assertEquals(reason, saving.await().exceptionOrNull())
        assertEquals(AlbumSavedState.NotSaved, state(album))
        assertNull(database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1"))
    }

    @Test
    fun should_showTheRemovalAtOnceAndDropTheMirrorRow_when_theRemovalLands() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val removing = async { repository.setAlbumSaved(album, saved = false) }
        runCurrent()
        assertEquals(AlbumSavedState.NotSaved, state(album))

        write.complete(Result.success(Unit))
        assertTrue(removing.await().isSuccess)

        // The mirror row is gone, but the removal itself is known: still a row, saying Save.
        assertEquals(AlbumSavedState.NotSaved, state(album))
        assertNull(database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1"))
    }

    @Test
    fun should_rollBackToSaved_when_theRemovalFails() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))
        writeActions.writeResult = Result.failure(IllegalStateException("offline"))

        assertTrue(repository.setAlbumSaved(album, saved = false).isFailure)

        assertEquals(AlbumSavedState.Saved, state(album))
        assertEquals(true, database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1")?.isSaved)
    }

    @Test
    fun should_tellLibraryOncePerLandedWrite_when_anAlbumIsSavedOrRemoved() = runTest {
        assertEquals(0L, repository.libraryAlbumsRevision.first())

        assertTrue(repository.setAlbumSaved(album, saved = true).isSuccess)
        // Library hears of it once the mirror holds the save.
        assertEquals(1L, repository.libraryAlbumsRevision.first())
        assertEquals(true, database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1")?.isSaved)

        writeActions.writeResult = Result.failure(IllegalStateException("offline"))
        assertTrue(repository.setAlbumSaved(album, saved = false).isFailure)
        // A write that failed changed nothing Library lists.
        assertEquals(1L, repository.libraryAlbumsRevision.first())

        // Another account has heard of nothing.
        profileIds.value = "other-profile"
        assertEquals(0L, repository.libraryAlbumsRevision.first())
    }

    @Test
    fun should_holdTheSaveForTheGraceAndAskOnlyAfterIt_when_spotifysAnswerWouldLagBehind() = runTest {
        repository.setAlbumSaved(album, saved = true)
        database.spotifyLibraryCacheDao().deleteAlbum(PROFILE, "al1")
        writeActions.answer = mapOf(album.id to false)

        // Inside the grace an answer couldn't beat the save: not asked at all.
        now += 10_000L
        repository.refreshFavoriteStates(emptyList(), minIntervalMs = 0L, album = album)
        assertEquals(emptyList<Lookup>(), writeActions.lookups)
        assertEquals(AlbumSavedState.Saved, state(album))

        // Past the grace Spotify's answer is the truth (removed elsewhere).
        now += FAVORITE_WRITE_GRACE_MS
        repository.refreshFavoriteStates(emptyList(), minIntervalMs = 0L, album = album)
        assertEquals(listOf(Lookup(tracks = emptyList(), albums = listOf(album.id))), writeActions.lookups)
        assertEquals(AlbumSavedState.NotSaved, state(album))
    }

    @Test
    fun should_notAskAboutTheAlbumAlone_when_thePageResumesRightAfterARemoval() = runTest {
        database.spotifyLibraryCacheDao().upsertAlbum(mirrorRow("al1", cachedAt = now))
        repository.refreshFavoriteStates(listOf(track), album = album)
        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = emptyList())), writeActions.lookups)

        // Removed through Yoin: the mirror row goes, the album was never asked about.
        assertTrue(repository.setAlbumSaved(album, saved = false).isSuccess)
        assertNull(database.spotifyLibraryCacheDao().getAlbum(PROFILE, "al1"))

        // Back from "Open in Spotify" within the tracks' interval: nothing to send.
        now += 5_000L
        repository.refreshFavoriteStates(listOf(track), album = album)
        assertEquals(1, writeActions.lookups.size)
        assertEquals(AlbumSavedState.NotSaved, state(album))

        // Once the grace is over the album rides with its tracks again.
        now += FAVORITE_WRITE_GRACE_MS
        repository.refreshFavoriteStates(listOf(track), album = album)
        assertEquals(Lookup(tracks = listOf(track.id), albums = listOf(album.id)), writeActions.lookups.last())
    }

    @Test
    fun should_notAskAboutTheAlbum_when_itsSaveIsStillOut() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        writeActions.pendingWrite = write
        val saving = async { repository.setAlbumSaved(album, saved = true) }
        runCurrent()

        repository.refreshFavoriteStates(listOf(track), album = album)

        assertEquals(listOf(Lookup(tracks = listOf(track.id), albums = emptyList())), writeActions.lookups)
        write.complete(Result.success(Unit))
        saving.await()
    }

    @Test
    fun should_offerNoRowAndSendNothing_when_theServiceCannotSaveAlbums() = runTest {
        val subsonic = mockk<MusicSource>()
        every { subsonic.id } returns MediaId.PROVIDER_SUBSONIC
        every { subsonic.capabilities } returns ServiceFeatureCatalog.subsonic.capabilities
        every { subsonic.writeActions() } returns writeActions
        activeSource.value = subsonic
        val subsonicAlbum = album(MediaId(MediaId.PROVIDER_SUBSONIC, "al1"))

        assertEquals(AlbumSavedState.Unsupported, state(subsonicAlbum))
        val error = repository.setAlbumSaved(subsonicAlbum, saved = true).exceptionOrNull()
        repository.refreshFavoriteStates(emptyList(), album = subsonicAlbum)

        assertTrue(error is UnsupportedOperationException)
        assertEquals(emptyList<Pair<MediaId, Boolean>>(), writeActions.writes)
        assertEquals(emptyList<Lookup>(), writeActions.lookups)
    }

    @Test
    fun should_offerNoRowAndSendNothing_when_theServiceIsAppleMusic() = runTest {
        val appleMusic = mockk<MusicSource>()
        every { appleMusic.id } returns MediaId.PROVIDER_APPLE_MUSIC
        every { appleMusic.capabilities } returns ServiceFeatureCatalog.appleMusic.capabilities
        every { appleMusic.writeActions() } returns writeActions
        activeSource.value = appleMusic
        val appleAlbum = album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "l.al1"))

        assertEquals(AlbumSavedState.Unsupported, state(appleAlbum))
        val error = repository.setAlbumSaved(appleAlbum, saved = true).exceptionOrNull()
        repository.refreshFavoriteStates(appleAlbum.tracks, album = appleAlbum)

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
        assertEquals(AlbumSavedState.Saved, state(album))

        profileIds.value = OTHER_PROFILE
        profileIds.value = PROFILE

        assertEquals(AlbumSavedState.Unknown, state(album))
    }

    private suspend fun state(album: Album): AlbumSavedState = repository.observeAlbumSaved(album.id).first()

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
        var pendingLookup: CompletableDeferred<Unit>? = null
        var pendingWrite: CompletableDeferred<Result<Unit>>? = null
        var writeResult: Result<Unit> = Result.success(Unit)
        val lookups = mutableListOf<Lookup>()
        val writes = mutableListOf<Pair<MediaId, Boolean>>()

        override suspend fun favoriteStates(tracks: List<Track>, albums: List<MediaId>): Result<Map<MediaId, Boolean>> {
            lookups += Lookup(tracks.map(Track::id), albums)
            pendingLookup?.await()
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
