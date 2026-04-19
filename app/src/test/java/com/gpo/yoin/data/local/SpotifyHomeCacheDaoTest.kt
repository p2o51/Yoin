package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyHomeCacheDaoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: SpotifyHomeCacheDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.spotifyHomeCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_filter_ttl_and_profile_when_loading_fresh_cache_rows() = runTest {
        dao.insertAlbums(
            listOf(
                SpotifyHomeAlbumCache(
                    profileId = "spotify-a",
                    albumId = MediaId.spotify("album-stale").toString(),
                    name = "Stale Album",
                    artist = "Artist",
                    artistId = MediaId.spotify("artist-stale").toString(),
                    coverArtKey = "https://example.com/stale.jpg",
                    songCount = 9,
                    year = 2020,
                    sortOrder = 2,
                    cachedAt = 100L,
                ),
                SpotifyHomeAlbumCache(
                    profileId = "spotify-a",
                    albumId = MediaId.spotify("album-fresh").toString(),
                    name = "Fresh Album",
                    artist = "Artist",
                    artistId = MediaId.spotify("artist-fresh").toString(),
                    coverArtKey = "https://example.com/fresh.jpg",
                    songCount = 10,
                    year = 2024,
                    sortOrder = 1,
                    cachedAt = 900L,
                ),
                SpotifyHomeAlbumCache(
                    profileId = "spotify-b",
                    albumId = MediaId.spotify("album-other").toString(),
                    name = "Other Album",
                    artist = "Other",
                    artistId = MediaId.spotify("artist-other").toString(),
                    coverArtKey = "https://example.com/other.jpg",
                    songCount = 11,
                    year = 2025,
                    sortOrder = 0,
                    cachedAt = 950L,
                ),
            ),
        )
        dao.insertArtists(
            listOf(
                SpotifyHomeArtistCache(
                    profileId = "spotify-a",
                    artistId = MediaId.spotify("artist-stale").toString(),
                    name = "Stale Artist",
                    coverArtKey = "https://example.com/stale-artist.jpg",
                    sortOrder = 1,
                    cachedAt = 200L,
                ),
                SpotifyHomeArtistCache(
                    profileId = "spotify-a",
                    artistId = MediaId.spotify("artist-fresh").toString(),
                    name = "Fresh Artist",
                    coverArtKey = "https://example.com/fresh-artist.jpg",
                    sortOrder = 0,
                    cachedAt = 950L,
                ),
            ),
        )

        val freshAlbums = dao.getFreshAlbums(profileId = "spotify-a", minCachedAt = 500L)
        val freshArtists = dao.getFreshArtists(profileId = "spotify-a", minCachedAt = 500L)

        assertEquals(listOf(MediaId.spotify("album-fresh").toString()), freshAlbums.map { it.albumId })
        assertEquals(listOf(MediaId.spotify("artist-fresh").toString()), freshArtists.map { it.artistId })
    }
}
