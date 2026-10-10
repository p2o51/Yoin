package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A follow or an album save made through Yoin while a library sync is
 * reading Spotify's lists: the sync rewrites the mirror from lists read
 * before the write, and must keep the write over them, not wipe it.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositorySpotifyMirrorWritesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var repository: YoinRepository
    private val library = mockk<MusicLibrary>()
    private val writes = FakeWriteActions()
    private val spotify = mockk<SpotifyMusicSource>()
    private val profileId = "spotify-profile"
    private var now = 1_760_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { spotify.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotify.profileId } returns profileId
        every { spotify.capabilities } returns setOf(Capability.ALBUM_SAVE)
        every { spotify.library() } returns library
        every { spotify.writeActions() } returns writes
        every { spotify.hasUnsettledFavoriteWrites() } returns false
        every { spotify.invalidateLibraryCaches(any()) } just runs
        coEvery { spotify.warmLibraryCaches() } just runs
        coEvery { library.getArtists() } returns listOf(
            ArtistIndex(name = "A", artists = listOf(artist("arca", "Arca")))
        )
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns emptyList()
        coEvery { library.getPlaylists() } returns emptyList()
        coEvery { library.getStarred() } returns Starred()
        repository = YoinRepository(
            activeSource = MutableStateFlow(spotify),
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
            spotifyLibrarySyncCoordinator = SpotifyLibrarySyncCoordinator(
                database = database,
                rateLimitGate = SpotifyRateLimitGate(clock = { now }),
                scope = CoroutineScope(SupervisorJob()),
                clock = { now }
            ),
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_keepTheFollowAndTheSave_when_aSyncReadItsListsBeforeThem() = runTest {
        repository.refreshSpotifyLibrary(force = true)
        val newcomer = ArtistDetail(MediaId.spotify("caroline"), "Caroline", albumCount = 3, coverArt = null)
        duringSyncRead {
            assertTrue(repository.setArtistFollowed(newcomer.id, followed = true, artist = newcomer).isSuccess)
            assertTrue(repository.setArtistFollowed(MediaId.spotify("arca"), followed = false).isSuccess)
            assertTrue(repository.setAlbumSaved(album("new-album"), saved = true).isSuccess)
        }

        now += 1_000L
        repository.refreshSpotifyLibrary(force = true)

        assertEquals(listOf("Caroline"), followedArtistNames())
        assertEquals(listOf("new-album"), repository.getAlbumList("newest").map { it.id.rawId })
    }

    @Test
    fun should_letTheListsWin_when_theFollowWriteFails() = runTest {
        repository.refreshSpotifyLibrary(force = true)
        writes.followFailure = IOException("offline")
        duringSyncRead {
            assertTrue(repository.setArtistFollowed(MediaId.spotify("arca"), followed = false).isFailure)
        }

        now += 1_000L
        repository.refreshSpotifyLibrary(force = true)

        assertEquals(listOf("Arca"), followedArtistNames())
    }

    /** Runs [write] as the sync reads its last list, after the artists and the albums. */
    private fun duringSyncRead(write: suspend () -> Unit) {
        var ran = false
        coEvery { library.getStarred() } coAnswers {
            if (!ran) {
                ran = true
                write()
            }
            Starred()
        }
    }

    private suspend fun followedArtistNames(): List<String>? =
        repository.readCachedFollowedArtists()?.flatMap(ArtistIndex::artists)?.map(Artist::name)

    private fun artist(id: String, name: String) = Artist(
        id = MediaId.spotify(id),
        name = name,
        albumCount = 1,
        coverArt = CoverRef.Url("https://i.scdn.co/image/$id"),
        isStarred = true
    )

    private fun album(id: String) = Album(
        id = MediaId.spotify(id),
        name = "Album $id",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 1,
        durationSec = null,
        year = 2024,
        genre = null
    )

    private class FakeWriteActions : MusicWriteActions {
        var followFailure: Exception? = null

        override suspend fun setArtistFollowed(id: MediaId, followed: Boolean): Result<Unit> =
            followFailure?.let { Result.failure(it) } ?: Result.success(Unit)

        override suspend fun setAlbumSaved(album: Album, saved: Boolean): Result<Unit> = Result.success(Unit)

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
}
