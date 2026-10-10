package com.gpo.yoin.data.source.spotify

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyLibrarySyncCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var gate: SpotifyRateLimitGate
    private lateinit var coordinator: SpotifyLibrarySyncCoordinator
    private val library = mockk<MusicLibrary>()
    private val source = mockk<MusicSource>()
    private var now = 10_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        gate = SpotifyRateLimitGate(clock = { now })
        coordinator = SpotifyLibrarySyncCoordinator(
            database = database,
            rateLimitGate = gate,
            scope = CoroutineScope(SupervisorJob()),
            clock = { now },
        )
        every { source.id } returns MediaId.PROVIDER_SPOTIFY
        every { source.library() } returns library
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_skip_network_when_cache_is_fresh() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = false,
        )
        coEvery { library.getArtists() } throws IllegalStateException("network should not run")

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = false,
        )

        assertTrue(result.isSuccess)
        assertTrue(coordinator.isCacheFresh("profile-a"))
    }

    @Test
    fun should_keep_stale_cache_visible_during_rate_limit() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )
        gate.recordBackoff("profile-a", retryAfterSeconds = 30L)
        coEvery { library.getArtists() } throws SpotifyRateLimitException(30L, "library-sync")

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, coordinator.readArtists("profile-a").size)
    }

    @Test
    fun should_fail_without_cache_when_rate_limited_on_cold_start() = runTest {
        gate.recordBackoff("profile-a", retryAfterSeconds = 30L)

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is SpotifyRateLimitException)
    }

    @Test
    fun should_return_cached_rows_after_ttl_expires_and_refresh_fails() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )
        assertEquals(1, coordinator.readArtists("profile-a").size)

        now += SpotifyLibrarySyncCoordinator.DEFAULT_TTL_MS + 1L
        assertFalse(coordinator.isCacheFresh("profile-a"))
        coEvery { library.getArtists() } throws IllegalStateException("network down")

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, coordinator.readArtists("profile-a").size)
    }

    @Test
    fun should_preserve_pending_favorite_through_full_sync() = runTest {
        val dao = database.spotifyLibraryCacheDao()
        dao.upsertTrack(
            SpotifyLibraryTrackCache(
                profileId = "profile-a",
                trackId = "pending-track",
                title = "Pending Song",
                artist = "Artist",
                artistId = null,
                album = null,
                albumId = null,
                coverArtKey = null,
                durationSec = null,
                addedAt = null,
                isSaved = true,
                cachedAt = now,
                pendingFavoriteAction = true,
            ),
        )
        seedRemoteLibrary()

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )

        assertTrue(result.isSuccess)
        assertNotNull(dao.getTrack("profile-a", "pending-track"))
        assertTrue(dao.getPendingTracks("profile-a").any { it.trackId == "pending-track" })
    }

    @Test
    fun should_merge_fresh_metadata_when_reinserting_pending_track() = runTest {
        val dao = database.spotifyLibraryCacheDao()
        dao.upsertTrack(
            SpotifyLibraryTrackCache(
                profileId = "profile-a",
                trackId = "track-1",
                title = "Stale Title",
                artist = "Stale Artist",
                artistId = null,
                album = null,
                albumId = null,
                coverArtKey = null,
                durationSec = null,
                addedAt = null,
                isSaved = true,
                cachedAt = now,
                pendingFavoriteAction = true,
            ),
        )
        seedRemoteLibrary()

        val result = coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )

        assertTrue(result.isSuccess)
        val row = dao.getTrack("profile-a", "track-1")
        requireNotNull(row)
        assertEquals("Song", row.title)
        assertEquals("Taylor", row.artist)
        assertTrue(row.pendingFavoriteAction)
    }

    @Test
    fun should_share_single_sync_across_concurrent_refreshes() = runTest {
        var getArtistsCalls = 0
        val firstCallEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        seedRemoteLibrary()
        coEvery { library.getArtists() } coAnswers {
            getArtistsCalls++
            firstCallEntered.complete(Unit)
            release.await()
            listOf(ArtistIndex(name = "T", artists = emptyList()))
        }

        withContext(Dispatchers.IO) {
            val first = async {
                coordinator.refreshLibrary(
                    profileId = "profile-a",
                    source = source,
                    force = true,
                )
            }
            withTimeout(5_000L) { firstCallEntered.await() }
            val second = async {
                coordinator.refreshLibrary(
                    profileId = "profile-a",
                    source = source,
                    force = true,
                )
            }
            // Give the second caller real time to reach the single-flight join
            // (it awaits the same in-flight Deferred) BEFORE unblocking the
            // first. Releasing immediately races the join against job-1's
            // completion — the source of the prior flakiness. We're on
            // Dispatchers.IO here, so this delay is real wall-clock time.
            delay(300)
            release.complete(Unit)
            withTimeout(5_000L) {
                assertTrue(first.await().isSuccess)
                assertTrue(second.await().isSuccess)
            }
        }

        assertEquals(1, getArtistsCalls)
    }

    @Test
    fun markStale_should_make_cache_not_fresh() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = source,
            force = true,
        )
        assertTrue(coordinator.isCacheFresh("profile-a"))

        coordinator.markStale("profile-a")

        assertFalse(coordinator.isCacheFresh("profile-a"))
    }

    @Test
    fun readTracks_should_filter_unsaved_rows() = runTest {
        val dao = database.spotifyLibraryCacheDao()
        dao.upsertTrack(
            SpotifyLibraryTrackCache(
                profileId = "profile-a",
                trackId = "saved-track",
                title = "Saved",
                artist = "Artist",
                artistId = null,
                album = null,
                albumId = null,
                coverArtKey = null,
                durationSec = null,
                addedAt = null,
                isSaved = true,
                cachedAt = now,
            ),
        )
        dao.upsertTrack(
            SpotifyLibraryTrackCache(
                profileId = "profile-a",
                trackId = "phantom-track",
                title = null,
                artist = null,
                artistId = null,
                album = null,
                albumId = null,
                coverArtKey = null,
                durationSec = null,
                addedAt = null,
                isSaved = false,
                cachedAt = now,
            ),
        )

        val tracks = coordinator.readTracks("profile-a")

        assertEquals(1, tracks.size)
        assertEquals(MediaId.spotify("saved-track"), tracks.single().id)
    }

    @Test
    fun forced_refresh_should_invalidate_stale_in_memory_source_cache() = runTest {
        val spotifySource = mockk<SpotifyMusicSource>(relaxed = true)
        every { spotifySource.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotifySource.library() } returns library
        every { spotifySource.profileId } returns "profile-a"

        var remoteCallCount = 0
        val firstArtist = Artist(
            id = MediaId.spotify("artist-1"),
            name = "Taylor",
            albumCount = 1,
            coverArt = CoverRef.Url("https://example.com/a.jpg"),
            isStarred = true,
        )
        val secondArtist = firstArtist.copy(
            id = MediaId.spotify("artist-2"),
            name = "Updated",
        )
        coEvery { library.getArtists() } answers {
            remoteCallCount++
            val artist = if (remoteCallCount == 1) firstArtist else secondArtist
            listOf(ArtistIndex(name = "T", artists = listOf(artist)))
        }
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns emptyList()
        coEvery { library.getPlaylists() } returns emptyList()
        coEvery { library.getStarred() } returns Starred(emptyList(), emptyList(), emptyList())

        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = spotifySource,
            force = true,
        )
        assertEquals("Taylor", coordinator.readArtists("profile-a").single().name)

        coordinator.refreshLibrary(
            profileId = "profile-a",
            source = spotifySource,
            force = true,
        )

        assertEquals("Updated", coordinator.readArtists("profile-a").single().name)
        assertEquals(2, remoteCallCount)
        // Guards the actual A1 fix: each forced sync must drop the source's
        // in-memory caches so the next library.getX() re-fetches. Without this
        // verify, the test passes even if the invalidate call is deleted
        // (the standalone library mock always returns fresh data).
        verify(exactly = 2) { spotifySource.invalidateLibraryCaches() }
    }

    @Test
    fun should_rereadWarmCaches_when_firstSyncFollowsUnsettledLike() = runTest {
        val spotifySource = emptySpotifySource(unsettledFavoriteWrites = true)

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        verify(exactly = 1) { spotifySource.invalidateLibraryCaches() }
    }

    @Test
    fun should_keepWarmCaches_when_firstSyncHasNoUnsettledWrites() = runTest {
        val spotifySource = emptySpotifySource(unsettledFavoriteWrites = false)

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        verify(exactly = 0) { spotifySource.invalidateLibraryCaches(any()) }
    }

    @Test
    fun should_joinRecentLibraryReads_when_ttlResyncHasNoUnsettledLikes() = runTest {
        val spotifySource = emptySpotifySource(unsettledFavoriteWrites = false)
        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)
        now += SpotifyLibrarySyncCoordinator.DEFAULT_TTL_MS + 1L

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        // A read an album open started moments ago serves the re-sync too.
        verify(exactly = 1) { spotifySource.invalidateLibraryCaches(keepRecentLoads = true) }
        verify(exactly = 0) { spotifySource.invalidateLibraryCaches(keepRecentLoads = false) }
    }

    @Test
    fun should_rereadLibraryLists_when_ttlResyncFollowsUnsettledLike() = runTest {
        val spotifySource = emptySpotifySource(unsettledFavoriteWrites = false)
        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)
        now += SpotifyLibrarySyncCoordinator.DEFAULT_TTL_MS + 1L
        every { spotifySource.hasUnsettledFavoriteWrites() } returns true

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        // A saved-tracks read already out may predate the like: read again.
        verify(exactly = 1) { spotifySource.invalidateLibraryCaches(keepRecentLoads = false) }
        verify(exactly = 0) { spotifySource.invalidateLibraryCaches(keepRecentLoads = true) }
    }

    @Test
    fun should_rereadLibraryLists_when_refreshIsForced() = runTest {
        val spotifySource = emptySpotifySource(unsettledFavoriteWrites = false)
        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource, force = true)

        verify(exactly = 1) { spotifySource.invalidateLibraryCaches(keepRecentLoads = false) }
        verify(exactly = 0) { spotifySource.invalidateLibraryCaches(keepRecentLoads = true) }
    }

    @Test
    fun should_keepAnAlbumSavedThroughYoin_when_aSyncReadItsListsBeforeTheSave() = runTest {
        seedRemoteLibrary()
        val saved = album("album-new", "New Album")
        // The save lands while the sync is reading: after its saved-albums list.
        duringSyncRead {
            coordinator.recordAlbumSaved("profile-a", saved, saved = true, addedAt = "2026-10-11T00:00:00Z")
        }

        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertEquals(listOf("Album", "New Album"), coordinator.readAlbums("profile-a").map { it.name }.sorted())
    }

    @Test
    fun should_notBringBackAnAlbumRemovedThroughYoin_when_aSyncReadItsListsBeforeTheRemoval() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)
        duringSyncRead {
            coordinator.recordAlbumSaved("profile-a", album("album-1", "Album"), saved = false, addedAt = "")
        }

        now += 1_000L
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertTrue(coordinator.readAlbums("profile-a").isEmpty())
    }

    @Test
    fun should_keepAFollowThroughYoin_when_aSyncReadItsListsBeforeTheFollow() = runTest {
        seedRemoteLibrary()
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)
        val dao = database.spotifyLibraryCacheDao()
        val newcomer = artistRow("artist-new", "Newcomer", isFollowed = true)
        duringSyncRead {
            coordinator.holdArtistFollow("profile-a", "artist-new", followed = true, row = newcomer)
            dao.upsertArtist(newcomer)
            // An unfollow of the listed artist, too.
            coordinator.holdArtistFollow("profile-a", "artist-1", followed = false, row = null)
            dao.upsertArtist(requireNotNull(dao.getArtist("profile-a", "artist-1")).copy(isFollowed = false))
        }

        now += 1_000L
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertEquals(listOf("Newcomer"), coordinator.readStarred("profile-a").artists.map { it.name })
        assertEquals(false, dao.getArtist("profile-a", "artist-1")?.isFollowed)
    }

    @Test
    fun should_letTheListsWin_when_aFollowWriteFailed() = runTest {
        seedRemoteLibrary()
        val dao = database.spotifyLibraryCacheDao()
        val newcomer = artistRow("artist-new", "Newcomer", isFollowed = true)
        duringSyncRead {
            val ticket = coordinator.holdArtistFollow("profile-a", "artist-new", followed = true, row = newcomer)
            dao.upsertArtist(newcomer)
            // Refused: let go before the row is put back.
            coordinator.forgetArtistFollow("profile-a", "artist-new", ticket)
            dao.deleteArtist("profile-a", "artist-new")
        }

        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertNull(dao.getArtist("profile-a", "artist-new"))
        assertEquals(listOf("Taylor"), coordinator.readStarred("profile-a").artists.map { it.name })
    }

    @Test
    fun should_settleTheWrite_when_aLaterSyncsListsShowIt() = runTest {
        seedRemoteLibrary()
        val saved = album("album-new", "New Album")
        duringSyncRead {
            coordinator.recordAlbumSaved("profile-a", saved, saved = true, addedAt = "2026-10-11T00:00:00Z")
        }
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        // The next sync's list has it: settled, and nothing is laid back any more.
        coEvery { library.getStarred() } returns Starred()
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns listOf(saved)
        now += 1_000L
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)
        // Removed in Spotify later, past the grace: gone with the list.
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns emptyList()
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertTrue(coordinator.readAlbums("profile-a").isEmpty())
    }

    @Test
    fun should_dateMirrorRowsByWhenTheirListsWereRead_when_aSyncUsesEarlierReads() = runTest {
        // A cold start's first sync takes the lists prime() read before it; the sync ends later.
        seedRemoteLibrary()
        val spotifySource = spotifySourceOver(library)
        coEvery { spotifySource.warmLibraryCaches() } coAnswers {
            now = 13_000L
            SpotifyLibraryReadTimes(
                savedTracksMs = 4_000L,
                savedAlbumsMs = 4_100L,
                playlistsMs = 4_200L,
                followedArtistsMs = 4_300L
            )
        }

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        val dao = database.spotifyLibraryCacheDao()
        // An answer Yoin got about a track at 5 000 is newer than the list: the row mustn't outrank it.
        assertEquals(4_000L, dao.getTrack("profile-a", "track-1")?.cachedAt)
        assertEquals(4_100L, dao.getAlbum("profile-a", "album-1")?.cachedAt)
        assertEquals(listOf(4_200L), dao.getFreshPlaylists("profile-a", 0L).map { it.cachedAt })
        assertEquals(listOf(4_300L), dao.getFreshArtists("profile-a", 0L).map { it.cachedAt })
        // The sync itself counts from its end, as before: no earlier re-sync.
        assertEquals(13_000L, dao.getSyncMeta("profile-a")?.cachedAt)
    }

    @Test
    fun should_dateMirrorRowsByTheSyncsStart_when_theReadTimesAreUnknown() = runTest {
        seedRemoteLibrary()
        val spotifySource = spotifySourceOver(library)
        coEvery { spotifySource.warmLibraryCaches() } coAnswers {
            now = 13_000L
            SpotifyLibraryReadTimes(savedTracksMs = 0L, savedAlbumsMs = 0L, playlistsMs = 0L, followedArtistsMs = 0L)
        }

        coordinator.refreshLibrary(profileId = "profile-a", source = spotifySource)

        // The earliest the lists can be from in this sync, not when it ended.
        val dao = database.spotifyLibraryCacheDao()
        assertEquals(10_000L, dao.getTrack("profile-a", "track-1")?.cachedAt)
        assertEquals(10_000L, dao.getAlbum("profile-a", "album-1")?.cachedAt)
    }

    @Test
    fun should_letTheListsWin_when_theWritesGraceIsOver() = runTest {
        seedRemoteLibrary()
        val saved = album("album-new", "New Album")
        coordinator.recordAlbumSaved("profile-a", saved, saved = true, addedAt = "2026-10-11T00:00:00Z")

        // Spotify still doesn't list it a while later (undone elsewhere): the list is the truth.
        now += SpotifyLibrarySyncCoordinator.PENDING_WRITE_GRACE_MS
        coordinator.refreshLibrary(profileId = "profile-a", source = source, force = true)

        assertEquals(listOf("Album"), coordinator.readAlbums("profile-a").map { it.name })
    }

    /** Runs [write] as the sync reads its last list (the starred one), after the albums and artists. */
    private fun duringSyncRead(write: suspend () -> Unit) {
        val starred = Starred()
        var ran = false
        coEvery { library.getStarred() } coAnswers {
            if (!ran) {
                ran = true
                write()
            }
            starred
        }
    }

    private fun album(id: String, name: String) = Album(
        id = MediaId.spotify(id),
        name = name,
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 1,
        durationSec = null,
        year = 2024,
        genre = null,
        tracks = emptyList(),
        isStarred = true
    )

    private fun artistRow(id: String, name: String, isFollowed: Boolean) = SpotifyLibraryArtistCache(
        profileId = "profile-a",
        artistId = id,
        name = name,
        albumCount = null,
        coverArtKey = null,
        isFollowed = isFollowed,
        cachedAt = now
    )

    /** A Spotify source whose lists are [lists], with no write waiting to settle. */
    private fun spotifySourceOver(lists: MusicLibrary): SpotifyMusicSource {
        val spotifySource = mockk<SpotifyMusicSource>(relaxed = true)
        every { spotifySource.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotifySource.library() } returns lists
        every { spotifySource.profileId } returns "profile-a"
        every { spotifySource.hasUnsettledFavoriteWrites() } returns false
        return spotifySource
    }

    private fun emptySpotifySource(unsettledFavoriteWrites: Boolean): SpotifyMusicSource {
        val spotifySource = mockk<SpotifyMusicSource>(relaxed = true)
        every { spotifySource.id } returns MediaId.PROVIDER_SPOTIFY
        every { spotifySource.library() } returns library
        every { spotifySource.profileId } returns "profile-a"
        every { spotifySource.hasUnsettledFavoriteWrites() } returns unsettledFavoriteWrites
        coEvery { library.getArtists() } returns emptyList()
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns emptyList()
        coEvery { library.getPlaylists() } returns emptyList()
        coEvery { library.getStarred() } returns Starred(emptyList(), emptyList(), emptyList())
        return spotifySource
    }

    private fun seedRemoteLibrary() {
        val artist = Artist(
            id = MediaId.spotify("artist-1"),
            name = "Taylor",
            albumCount = 1,
            coverArt = CoverRef.Url("https://example.com/a.jpg"),
            isStarred = true,
        )
        val track = Track(
            id = MediaId.spotify("track-1"),
            title = "Song",
            artist = "Taylor",
            artistId = artist.id,
            album = "Album",
            albumId = MediaId.spotify("album-1"),
            coverArt = CoverRef.Url("https://example.com/t.jpg"),
            durationSec = 200,
            trackNumber = 1,
            year = 2024,
            genre = null,
            userRating = null,
            isStarred = true,
        )
        val album = Album(
            id = MediaId.spotify("album-1"),
            name = "Album",
            artist = "Taylor",
            artistId = artist.id,
            coverArt = CoverRef.Url("https://example.com/al.jpg"),
            songCount = 1,
            durationSec = null,
            year = 2024,
            genre = null,
            tracks = emptyList(),
            isStarred = true,
        )
        val playlist = Playlist(
            id = MediaId.spotify("playlist-1"),
            name = "Favorites",
            owner = "me",
            coverArt = null,
            songCount = 1,
            durationSec = null,
            canWrite = true,
        )
        coEvery { library.getArtists() } returns listOf(ArtistIndex(name = "T", artists = listOf(artist)))
        coEvery { library.getAlbumList("alphabeticalByName", Int.MAX_VALUE) } returns listOf(album)
        coEvery { library.getPlaylists() } returns listOf(playlist)
        coEvery { library.getStarred() } returns Starred(
            tracks = listOf(track),
            albums = listOf(album),
            artists = listOf(artist),
        )
    }
}
