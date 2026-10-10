package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
import com.gpo.yoin.data.local.SpotifyLibraryCacheDao
import com.gpo.yoin.data.local.SpotifyLibrarySyncMeta
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Library's Songs and Artists tabs read on each service. Spotify's
 * library is its favorites: Songs are the liked songs, newest like first, and
 * Artists only the followed ones — read from the synced cache, which keeps
 * every library artist for the saved-library search. Other services read
 * their source as before.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryLibraryTabsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: SpotifyLibraryCacheDao
    private val spotifyLibrary = mockk<MusicLibrary>()

    // A fake, not a mock: MockK hands a suspend fun's Result back boxed.
    private val spotifyWrites = FakeWriteActions()
    private val spotify = mockk<SpotifyMusicSource>()
    private val activeSource = MutableStateFlow<MusicSource?>(spotify)
    private var now = 1_760_000_000_000L
    private val profileId = "spotify-profile"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = database.spotifyLibraryCacheDao()
        every { spotify.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotify.profileId } returns profileId
        // Strict: the cache is fresh, so a read must not reach the network.
        every { spotify.library() } returns spotifyLibrary
        every { spotify.writeActions() } returns spotifyWrites
        // A TTL re-sync's first steps; reaching the network fails it, so the
        // cache serves — a test counts warmLibraryCaches to see a sync start.
        every { spotify.hasUnsettledFavoriteWrites() } returns false
        every { spotify.invalidateLibraryCaches(any()) } just runs
        coEvery { spotify.warmLibraryCaches() } throws IOException("offline")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_listLikedSongsNewestFirst_when_spotifySongsRead() = runTest {
        // Neither title order (middle, newest, no-date, older) nor insertion
        // order is the answer: the cache's read alone sorts by addedAt.
        likedCache(
            liked("older", addedAt = "2025-01-01T00:00:00Z"),
            liked("newest", addedAt = "2026-10-10T08:00:00Z"),
            liked("no-date", addedAt = null),
            liked("middle", addedAt = "2025-06-01T00:00:00Z"),
            liked("unliked", addedAt = "2026-10-10T09:00:00Z").copy(isSaved = false)
        )

        val songs = repository().getLibrarySongs(size = 500)

        // An unliked row is not a liked song; a row with no date goes last.
        assertEquals(listOf("newest", "middle", "older", "no-date"), songs.map { it.id.rawId })
        assertEquals(MediaId.PROVIDER_SPOTIFY, songs.first().id.provider)
    }

    @Test
    fun should_keepSpotifysOrder_when_likesShareOneSecond() = runTest {
        // A sync files /me/tracks in its own order; three likes saved at once
        // share a second, and Spotify lists them zulu, alpha, mike.
        likedCache(
            liked("newer", addedAt = "2026-10-10T00:00:00Z"),
            liked("zulu", addedAt = "2026-10-09T00:00:00Z"),
            liked("alpha", addedAt = "2026-10-09T00:00:00Z"),
            liked("mike", addedAt = "2026-10-09T00:00:00Z"),
            liked("older", addedAt = "2026-10-01T00:00:00Z")
        )

        val songs = repository().getLibrarySongs(size = 500)

        // Not by title: the next row is the next song Liked Songs plays.
        assertEquals(listOf("newer", "zulu", "alpha", "mike", "older"), songs.map { it.id.rawId })
    }

    @Test
    fun should_putNewLikeOnTop_when_spotifyTrackLikedThenSongsRead() = runTest {
        likedCache(
            liked("yesterday", addedAt = "2025-10-08T00:00:00Z"),
            liked("last-year", addedAt = "2024-10-09T00:00:00Z")
        )
        val written = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        spotifyWrites.onSetFavorite = { _, _ ->
            written.complete(Unit)
            settle.await()
            Result.success(Unit)
        }
        val repository = repository()
        val newLike = track(MediaId.spotify("new"), addedAt = null)

        val like = async { repository.setFavorite(newLike, favorite = true) }
        written.await()

        // On top while the write is out (the optimistic row), and after it lands.
        assertEquals("new", repository.readCachedLikedSongs()?.first()?.id?.rawId)
        settle.complete(Unit)
        assertTrue(like.await().isSuccess)
        val songs = repository.getLibrarySongs(size = 500)
        assertEquals(listOf("new", "yesterday", "last-year"), songs.map { it.id.rawId })
        assertEquals("2025-10-09T08:53:20Z", songs.first().addedAt)
    }

    @Test
    fun should_takeUnlikeOut_when_spotifyTrackUnlikedThenSongsRead() = runTest {
        likedCache(
            liked("kept", addedAt = "2025-10-08T00:00:00Z"),
            liked("gone", addedAt = "2025-10-07T00:00:00Z")
        )
        val repository = repository()

        repository.setFavorite(track(MediaId.spotify("gone"), addedAt = null), favorite = false)

        assertEquals(listOf("kept"), repository.readCachedLikedSongs()?.map { it.id.rawId })
    }

    @Test
    fun should_readCacheWithoutSync_when_cachedListsReadOnStaleCache() = runTest {
        likedCache(liked("a", addedAt = "2025-10-08T00:00:00Z"))
        dao.insertArtists(listOf(artistRow("followed", "Arca", isFollowed = true)))
        // Past the 1 h TTL: a getLibrarySongs or getArtists here starts a sync.
        dao.upsertSyncMeta(SpotifyLibrarySyncMeta(profileId = profileId, cachedAt = now - 2 * 60 * 60 * 1000L))
        val repository = repository()

        assertEquals(listOf("a"), repository.readCachedLikedSongs()?.map { it.id.rawId })
        assertEquals(listOf("Arca"), repository.followedArtistNames())
        coVerify(exactly = 0) { spotify.warmLibraryCaches() }
        verify(exactly = 0) { spotify.library() }

        // The tab's first load still checks freshness, which is what starts one.
        repository.getLibrarySongs(size = 500)
        coVerify(exactly = 1) { spotify.warmLibraryCaches() }
    }

    @Test
    fun should_fileFollowIntoArtists_when_spotifyArtistFollowed() = runTest {
        freshSync()
        dao.insertArtists(
            listOf(
                artistRow("followed", "Arca", isFollowed = true),
                artistRow("saved-album", "Album Artist", isFollowed = false)
            )
        )
        val newcomer = MediaId.spotify("newcomer")
        val written = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        spotifyWrites.onSetArtistFollowed = { id, _ ->
            if (id == newcomer) {
                written.complete(Unit)
                settle.await()
            }
            Result.success(Unit)
        }
        val repository = repository()
        val detail = ArtistDetail(
            id = newcomer,
            name = "Caroline",
            albumCount = 3,
            coverArt = CoverRef.Url("https://i.scdn.co/image/caroline")
        )

        val follow = async { repository.setArtistFollowed(newcomer, followed = true, artist = detail) }
        written.await()

        // Listed while the write is out, filed from the artist page's detail.
        assertEquals(listOf("Arca", "Caroline"), repository.followedArtistNames())
        settle.complete(Unit)
        assertTrue(follow.await().isSuccess)
        // A library artist not yet followed needs no detail: its row is there.
        repository.setArtistFollowed(MediaId.spotify("saved-album"), followed = true)

        val artists = repository.getArtists().flatMap(ArtistIndex::artists)
        assertEquals(listOf("Album Artist", "Arca", "Caroline"), artists.map(Artist::name))
        assertEquals(CoverRef.Url("https://i.scdn.co/image/caroline"), artists.last().coverArt)
        assertTrue(repository.favoriteOverrides.value.isEmpty())
        coVerify(exactly = 0) { spotify.warmLibraryCaches() }
    }

    @Test
    fun should_dropUnfollowFromArtists_when_spotifyArtistUnfollowed() = runTest {
        freshSync()
        dao.insertArtists(
            listOf(
                artistRow("followed-a", "Arca", isFollowed = true),
                artistRow("followed-b", "Bruit", isFollowed = true)
            )
        )
        val repository = repository()

        repository.setArtistFollowed(MediaId.spotify("followed-a"), followed = false)

        assertEquals(listOf("Bruit"), repository.followedArtistNames())
        // The row stays for the saved-library search, as an unfollowed artist.
        assertEquals(false, dao.getArtist(profileId, "followed-a")?.isFollowed)
    }

    @Test
    fun should_putArtistsBack_when_followWriteFails() = runTest {
        freshSync()
        dao.insertArtists(listOf(artistRow("followed", "Arca", isFollowed = true)))
        spotifyWrites.onSetArtistFollowed = { _, _ -> Result.failure(IOException("offline")) }
        val repository = repository()
        val detail = ArtistDetail(MediaId.spotify("newcomer"), "Caroline", albumCount = null, coverArt = null)

        repository.setArtistFollowed(MediaId.spotify("followed"), followed = false)
        repository.setArtistFollowed(MediaId.spotify("newcomer"), followed = true, artist = detail)

        assertEquals(listOf("Arca"), repository.followedArtistNames())
        assertNull(dao.getArtist(profileId, "newcomer"))
        assertTrue(repository.favoriteOverrides.value.isEmpty())
    }

    @Test
    fun should_readOnePageOfLikedSongs_when_sizeAndOffsetGiven() = runTest {
        likedCache(
            liked("a", addedAt = "2026-10-04T00:00:00Z"),
            liked("b", addedAt = "2026-10-03T00:00:00Z"),
            liked("c", addedAt = "2026-10-02T00:00:00Z"),
            liked("d", addedAt = "2026-10-01T00:00:00Z")
        )

        val page = repository().getLibrarySongs(size = 2, offset = 1)

        assertEquals(listOf("b", "c"), page.map { it.id.rawId })
    }

    @Test
    fun should_listOnlyFollowedArtists_when_spotifyArtistsRead() = runTest {
        freshSync()
        dao.insertArtists(
            listOf(
                artistRow("followed-b", "Bruit", isFollowed = true),
                artistRow("saved-album", "Album Artist", isFollowed = false),
                artistRow("followed-a", "Arca", isFollowed = true),
                artistRow("liked-song", "Song Artist", isFollowed = false)
            )
        )
        val repository = repository()

        val artists = repository.getArtists().flatMap(ArtistIndex::artists)

        assertEquals(listOf("Arca", "Bruit"), artists.map(Artist::name))
        // The saved-library search still sees every artist the library holds.
        assertEquals(
            setOf("followed-a", "followed-b", "saved-album", "liked-song"),
            repository.getSpotifyLocalSearchSnapshot()?.artists?.map { it.id.rawId }?.toSet()
        )
    }

    @Test
    fun should_readSourceSongsAndArtists_when_serviceIsNotSpotify() = runTest {
        val appleLibrary = mockk<MusicLibrary>()
        val appleSong = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "i.1"), addedAt = null)
        val notStarred = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "r.1"), "Artist", null, null)
        coEvery { appleLibrary.getLibrarySongs(100, 200) } returns listOf(appleSong)
        coEvery { appleLibrary.getArtists() } returns listOf(ArtistIndex("A", listOf(notStarred)))
        activeSource.value = mockk<MusicSource> {
            every { id } returns MediaId.PROVIDER_APPLE_MUSIC
            every { library() } returns appleLibrary
        }
        val repository = repository()

        assertEquals(listOf(appleSong), repository.getLibrarySongs(size = 100, offset = 200))
        // Only Spotify narrows Artists to followed ones.
        assertEquals(listOf(notStarred), repository.getArtists().flatMap(ArtistIndex::artists))
        coVerify(exactly = 1) { appleLibrary.getLibrarySongs(100, 200) }
        // Its lists aren't a cache to re-read: those reads have nothing.
        assertNull(repository.readCachedLikedSongs())
        assertNull(repository.readCachedFollowedArtists())
    }

    private fun repository() = YoinRepository(
        activeSource = activeSource,
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

    private suspend fun YoinRepository.followedArtistNames(): List<String>? =
        readCachedFollowedArtists()?.flatMap(ArtistIndex::artists)?.map(Artist::name)

    private suspend fun freshSync() {
        dao.upsertSyncMeta(SpotifyLibrarySyncMeta(profileId = profileId, cachedAt = now))
    }

    private suspend fun likedCache(vararg rows: SpotifyLibraryTrackCache) {
        freshSync()
        dao.insertTracks(rows.toList())
    }

    private fun liked(id: String, addedAt: String?) = SpotifyLibraryTrackCache(
        profileId = profileId,
        trackId = id,
        title = "Song $id",
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArtKey = null,
        durationSec = 200,
        addedAt = addedAt,
        isSaved = true,
        cachedAt = now
    )

    private fun artistRow(id: String, name: String, isFollowed: Boolean) = SpotifyLibraryArtistCache(
        profileId = profileId,
        artistId = id,
        name = name,
        albumCount = null,
        coverArtKey = null,
        isFollowed = isFollowed,
        cachedAt = now
    )

    private fun track(id: MediaId, addedAt: String?) = Track(
        id = id,
        title = "Song",
        artist = "Artist",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        addedAt = addedAt
    )

    private class FakeWriteActions : MusicWriteActions {
        var onSetFavorite: suspend (MediaId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        var onSetArtistFollowed: suspend (MediaId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) }

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = onSetFavorite(id, favorite)

        override suspend fun setArtistFollowed(id: MediaId, followed: Boolean): Result<Unit> =
            onSetArtistFollowed(id, followed)

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
