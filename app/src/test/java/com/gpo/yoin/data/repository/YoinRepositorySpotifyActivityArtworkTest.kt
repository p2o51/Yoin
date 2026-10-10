package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.cache.DetailCacheStore
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.SpotifyHomeArtistCache
import com.gpo.yoin.data.local.SpotifyLibraryArtistCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyArtistPortrait
import com.gpo.yoin.data.source.spotify.SpotifyImageObject
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyPlayHistoryObject
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitGate
import com.gpo.yoin.data.source.spotify.SpotifySimplifiedAlbumObject
import com.gpo.yoin.data.source.spotify.SpotifySimplifiedArtistObject
import com.gpo.yoin.data.source.spotify.SpotifyTrackObject
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
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
 * Owner Q16: the artists of Spotify's Home Activities (recently-played names
 * them without images) get portraits — from the device first, then one
 * GET /artists/{id} at a time, cached in spotify_home_artist_cache — and the
 * play's album cover until one is known.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositorySpotifyActivityArtworkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var detailCache: DetailCacheStore
    private lateinit var repository: YoinRepository
    private val source = mockk<SpotifyMusicSource>()
    private val gate = SpotifyRateLimitGate(clock = { now })
    private var now = 100L * DAY_MS
    private val requested = mutableListOf<String>()
    private val answers = mutableMapOf<String, () -> SpotifyArtistPortrait>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        detailCache = DetailCacheStore(dao = database.detailCacheDao(), clock = { now })
        every { source.id } returns MediaId.PROVIDER_SPOTIFY
        every { source.profileId } returns PROFILE
        coEvery { source.getArtistPortrait(any()) } answers {
            val artistId = firstArg<String>()
            requested += artistId
            answers[artistId]?.invoke() ?: SpotifyArtistPortrait(name = "Artist $artistId", url = portraitOf(artistId))
        }
        repository = YoinRepository(
            activeSource = MutableStateFlow<MusicSource?>(source),
            activeProfileId = MutableStateFlow(PROFILE),
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
            spotifyRateLimitGate = gate,
            clock = { now },
            detailCacheStore = detailCache
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_takePortraitsFromTheDevice_when_theFeedIsBuilt() = runTest {
        plays("followed", "visited", "opened", "cached")
        database.spotifyLibraryCacheDao().insertArtists(
            listOf(
                SpotifyLibraryArtistCache(
                    profileId = PROFILE,
                    artistId = "followed",
                    name = "Followed",
                    albumCount = null,
                    coverArtKey = portraitOf("followed"),
                    isFollowed = true,
                    cachedAt = 1L
                )
            )
        )
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "visited",
                profileId = PROFILE,
                provider = MediaId.PROVIDER_SPOTIFY,
                title = "Visited",
                subtitle = "Artist",
                coverArtId = portraitOf("visited"),
                artistId = "visited"
            )
        )
        detailCache.writeArtist(
            PROFILE,
            MediaId.spotify("opened").toString(),
            ArtistDetail(
                id = MediaId.spotify("opened"),
                name = "Opened",
                albumCount = 0,
                coverArt = CoverRef.Url(portraitOf("opened"))
            )
        )
        cache("cached", portraitOf("cached"), ageMs = 29 * DAY_MS)

        val feed = repository.getSpotifyRecentActivities(limit = 40)

        assertEquals(
            listOf("followed", "visited", "opened", "cached").associateWith(::portraitOf),
            feed.artistCovers()
        )
        repository.fillSpotifyActivityArtistPortraits(
            artistIds = listOf("followed", "visited", "opened", "cached"),
            awaitTurn = {},
            onPortrait = { _, _ -> error("nothing to ask for") }
        )
        assertTrue(requested.isEmpty())
    }

    @Test
    fun should_showThePlaysAlbumCover_when_noPortraitIsKnown() = runTest {
        plays("unknown", "faceless")
        answers["faceless"] = { SpotifyArtistPortrait(name = "Faceless", url = null) }

        val feed = repository.getSpotifyRecentActivities(limit = 40)

        assertEquals(
            mapOf("unknown" to albumCoverOf("unknown"), "faceless" to albumCoverOf("faceless")),
            feed.artistCovers()
        )

        // Spotify has no picture of "faceless": it keeps its stand-in, and
        // that answer is kept for a day.
        val portraits = fill("faceless")
        assertTrue(portraits.isEmpty())
        val row = database.spotifyHomeCacheDao().getArtists(PROFILE, listOf(MediaId.spotify("faceless").toString()))
        assertEquals(listOf<String?>(null), row.map { it.coverArtKey })
        assertEquals(albumCoverOf("faceless"), repository.getSpotifyRecentActivities(40).artistCovers()["faceless"])
    }

    @Test
    fun should_notRequest_when_theCachedAnswerIsFresh() = runTest {
        cache("portrait-29d", portraitOf("portrait-29d"), ageMs = 29 * DAY_MS)
        cache("none-23h", null, ageMs = 23 * HOUR_MS)
        cache("none-25h", null, ageMs = 25 * HOUR_MS)
        cache("portrait-31d", "https://i.scdn.co/image/old", ageMs = 31 * DAY_MS)
        plays("portrait-29d", "none-23h", "none-25h", "portrait-31d")

        // An expired portrait still beats the album cover until it is asked again.
        assertEquals(
            "https://i.scdn.co/image/old",
            repository.getSpotifyRecentActivities(40).artistCovers()["portrait-31d"]
        )
        val portraits = fill("portrait-29d", "none-23h", "none-25h", "portrait-31d")

        assertEquals(listOf("none-25h", "portrait-31d"), requested)
        assertEquals(
            mapOf("none-25h" to portraitOf("none-25h"), "portrait-31d" to portraitOf("portrait-31d")),
            portraits
        )
    }

    @Test
    fun should_keepTheAnswer_when_spotifyHasAPortrait() = runTest {
        plays("asked")

        val portraits = fill("asked")

        assertEquals(mapOf("asked" to portraitOf("asked")), portraits)
        assertEquals(portraitOf("asked"), repository.getSpotifyRecentActivities(40).artistCovers()["asked"])
        fill("asked")
        assertEquals(listOf("asked"), requested)
    }

    @Test
    fun should_keepNothing_when_theReadFails() = runTest {
        answers["offline"] = { throw IOException("offline") }

        val portraits = fill("offline", "next")

        // Nothing kept, and the pass ends there: "next" waits for a later one.
        assertTrue(portraits.isEmpty())
        assertEquals(listOf("offline"), requested)
        assertTrue(database.spotifyHomeCacheDao().getFreshArtists(PROFILE, 0L).isEmpty())

        answers.remove("offline")
        assertEquals(mapOf("offline" to portraitOf("offline"), "next" to portraitOf("next")), fill("offline", "next"))
    }

    @Test
    fun should_notRequest_when_theRateLimitGateIsClosed() = runTest {
        gate.recordBackoff(PROFILE, retryAfterSeconds = 60)

        val portraits = fill("a", "b")

        assertTrue(portraits.isEmpty())
        coVerify(exactly = 0) { source.getArtistPortrait(any()) }
    }

    @Test
    fun should_stopForTheProcess_when_spotifyAnswers429() = runTest {
        answers["first"] = {
            gate.recordBackoff(PROFILE, retryAfterSeconds = 30)
            throw SpotifyRateLimitException(retryAfterSeconds = 30, endpoint = "v1/artists/first")
        }

        assertTrue(fill("first", "second").isEmpty())
        assertEquals(listOf("first"), requested)
        assertNull(
            database.spotifyHomeCacheDao()
                .getArtists(PROFILE, listOf(MediaId.spotify("first").toString()))
                .firstOrNull()
        )

        // The gate reopens, but this process asks no more.
        now += 60_000L
        assertTrue(fill("first", "second").isEmpty())
        assertEquals(listOf("first"), requested)
    }

    @Test
    fun should_askForAtMostTwenty_when_moreArtistsAreShown() = runTest {
        val shown = (1..25).map { "artist-$it" }

        fill(*shown.toTypedArray())

        assertEquals(shown.take(20), requested)
    }

    @Test
    fun should_waitItsTurn_when_eachRequestGoesOut() = runTest {
        var turns = 0

        repository.fillSpotifyActivityArtistPortraits(
            artistIds = listOf("a", "b", "c"),
            awaitTurn = {
                turns++
                assertEquals(turns - 1, requested.size)
            },
            onPortrait = { _, _ -> }
        )

        assertEquals(3, turns)
    }

    private suspend fun fill(vararg artistIds: String): Map<String, String> {
        val portraits = linkedMapOf<String, String>()
        repository.fillSpotifyActivityArtistPortraits(
            artistIds = artistIds.toList(),
            awaitTurn = {},
            onPortrait = { artistId, url -> portraits[artistId] = url }
        )
        return portraits
    }

    /** One play per artist, newest first, each on its own album. */
    private fun plays(vararg artistIds: String) {
        val history = artistIds.mapIndexed { index, artistId ->
            SpotifyPlayHistoryObject(
                track = SpotifyTrackObject(
                    id = "track-$artistId",
                    name = "Song by $artistId",
                    artists = listOf(SpotifySimplifiedArtistObject(id = artistId, name = "Artist $artistId")),
                    album = SpotifySimplifiedAlbumObject(
                        id = "album-$artistId",
                        name = "Album of $artistId",
                        images = listOf(SpotifyImageObject(url = albumCoverOf(artistId)))
                    )
                ),
                playedAt = "2026-10-10T10:${(59 - index).toString().padStart(2, '0')}:00Z"
            )
        }
        coEvery { source.getRecentlyPlayed(any()) } returns history
    }

    private suspend fun cache(artistId: String, url: String?, ageMs: Long) {
        database.spotifyHomeCacheDao().insertArtists(
            listOf(
                SpotifyHomeArtistCache(
                    profileId = PROFILE,
                    artistId = MediaId.spotify(artistId).toString(),
                    name = "Artist $artistId",
                    coverArtKey = url,
                    sortOrder = 0,
                    cachedAt = now - ageMs
                )
            )
        )
    }

    private fun List<ActivityEvent>.artistCovers(): Map<String, String?> =
        filter { it.entityType == ActivityEntityType.ARTIST.name }.associate { it.entityId to it.coverArtId }

    private companion object {
        const val PROFILE = "spotify-activity-artwork"
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS

        fun portraitOf(artistId: String) = "https://i.scdn.co/image/portrait-$artistId"

        fun albumCoverOf(artistId: String) = "https://i.scdn.co/image/album-$artistId"
    }
}
