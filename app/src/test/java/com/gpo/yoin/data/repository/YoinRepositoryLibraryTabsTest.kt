package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
import com.gpo.yoin.data.local.SpotifyLibraryCacheDao
import com.gpo.yoin.data.local.SpotifyLibrarySyncMeta
import com.gpo.yoin.data.local.SpotifyLibraryTrackCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyLibrarySyncCoordinator
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_listLikedSongsNewestFirst_when_spotifySongsRead() = runTest {
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
}
