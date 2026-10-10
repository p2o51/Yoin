package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.SPOTIFY_START_MAX_URIS
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The artist page's Play / Shuffle / Add to queue loads: a few albums at a
 * time in a sliding window, in discography order, and on Spotify and Apple
 * Music only the albums their start can use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistPlayTracksTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    /** A fake album read: [tracksPerAlbum] tracks each, later albums answering first. */
    private inner class Loader(private val tracksPerAlbum: (MediaId) -> Int = { 10 }) {
        val loaded: MutableList<MediaId> = Collections.synchronizedList(mutableListOf())
        var inFlight = 0
        var maxInFlight = 0

        suspend fun load(id: MediaId): List<Track> {
            loaded += id
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(1_000L - index(id))
            inFlight--
            return List(tracksPerAlbum(id)) { track(id, it) }
        }
    }

    private fun releases(count: Int, songCount: Int? = 10) =
        List(count) { ArtistRelease(MediaId.spotify("al$it"), songCount) }

    @Test
    fun should_stopLoadingAlbums_when_spotifyStartHasEnoughTracks() = runTest {
        val loader = Loader()

        val tracks = loadArtistPlayTracks(
            releases = releases(60),
            shuffle = false,
            startLimit = SPOTIFY_START_MAX_URIS,
            loadAlbum = loader::load
        )

        // Ten albums of ten fill the start; the other fifty are never read.
        assertEquals((0 until 10).map { MediaId.spotify("al$it") }, loader.loaded)
        assertEquals(100, tracks.size)
        assertEquals((0 until 10).flatMap { album -> List(10) { "al$album-$it" } }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_stopOnRealTrackCounts_when_albumsReportNoCount() = runTest {
        val loader = Loader()

        val tracks = loadArtistPlayTracks(
            releases = releases(60, songCount = null),
            shuffle = false,
            startLimit = SPOTIFY_START_MAX_URIS,
            loadAlbum = loader::load
        )

        // Three in flight until the albums read hold 100: twelve albums, cut to the start's 100.
        assertEquals(12, loader.loaded.size)
        assertEquals((0 until 10).flatMap { album -> List(10) { "al$album-$it" } }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_cutPlayToTheStartLimit_when_anAlbumHoldsMoreThanItReports() = runTest {
        // A box set reporting 10 tracks that holds 250.
        val loader = Loader(tracksPerAlbum = { if (index(it) == 1) 250 else 10 })

        val tracks = loadArtistPlayTracks(
            releases = releases(60),
            shuffle = false,
            startLimit = SPOTIFY_START_MAX_URIS,
            loadAlbum = loader::load
        )

        // Its real count is enough on its own: one more album was already reading, none after.
        assertEquals((0 until 4).map { MediaId.spotify("al$it") }, loader.loaded)
        assertEquals(100, tracks.size)
        assertEquals(List(10) { "al0-$it" } + List(90) { "al1-$it" }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_startTheNextAlbumAtOnce_when_anyReadFinishes() = runTest {
        val startedAt = mutableMapOf<MediaId, Long>()

        val tracks = loadArtistTracks(releases(6)) { id ->
            startedAt[id] = currentTime
            delay(if (index(id) == 0) 10_000L else 100L)
            List(10) { track(id, it) }
        }

        // The slow first album holds one slot; the other two keep turning over beside it.
        val starts = (0 until 6).map { startedAt.getValue(MediaId.spotify("al$it")) }
        assertEquals(listOf(0L, 0L, 0L, 100L, 100L, 200L), starts)
        assertEquals(10_000L, currentTime)
        assertEquals((0 until 6).flatMap { album -> List(10) { "al$album-$it" } }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_readPastASlowAlbum_when_playFillsTheStart() = runTest {
        val loaded = mutableListOf<MediaId>()

        val tracks = loadArtistPlayTracks(
            releases = releases(60),
            shuffle = false,
            startLimit = SPOTIFY_START_MAX_URIS
        ) { id ->
            loaded += id
            delay(if (index(id) == 0) 10_000L else 100L)
            List(10) { track(id, it) }
        }

        // The other nine albums are read while the first is still out, so the start waits only on it.
        assertEquals((0 until 10).map { MediaId.spotify("al$it") }, loaded)
        assertEquals(10_000L, currentTime)
        assertEquals((0 until 10).flatMap { album -> List(10) { "al$album-$it" } }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_loadEveryAlbumInDiscographyOrder_when_providerStartHasNoLimit() = runTest {
        val loader = Loader()

        val tracks = loadArtistPlayTracks(
            releases = releases(20),
            shuffle = false,
            startLimit = null,
            loadAlbum = loader::load
        )

        assertEquals(20, loader.loaded.size)
        // Later albums answer first; the list still reads in discography order.
        assertEquals((0 until 20).flatMap { album -> List(10) { "al$album-$it" } }, tracks.map { it.id.rawId })
    }

    @Test
    fun should_keepAtMostThreeAlbumLoadsInFlight_when_loadingADiscography() = runTest {
        val all = Loader()
        val play = Loader()
        val shuffle = Loader()

        loadArtistTracks(releases(20), all::load)
        loadArtistPlayTracks(releases(60, songCount = 2), shuffle = false, startLimit = 100, loadAlbum = play::load)
        loadArtistPlayTracks(
            releases(60),
            shuffle = true,
            startLimit = 100,
            random = Random(7),
            loadAlbum = shuffle::load
        )

        listOf(all, play, shuffle).forEach { loader ->
            assertEquals(ARTIST_ALBUM_LOADS_IN_FLIGHT, loader.maxInFlight)
        }
        assertEquals(3, ARTIST_ALBUM_LOADS_IN_FLIGHT)
    }

    @Test
    fun should_drawShuffleOverTheWholeDiscography_when_spotifyLoadsOnlyTheAlbumsItNeeds() = runTest {
        val loader = Loader()
        val releases = releases(300)

        val tracks = loadArtistPlayTracks(
            releases = releases,
            shuffle = true,
            startLimit = SPOTIFY_START_MAX_URIS,
            random = Random(42),
            loadAlbum = loader::load
        )

        assertEquals(100, tracks.size)
        assertEquals(100, tracks.map { it.id }.toSet().size)
        // Only the albums the drawn start lands in are read, and in discography order.
        val drawnAlbums = tracks.map { it.albumId!! }.toSet()
        assertEquals(drawnAlbums, loader.loaded.toSet())
        assertEquals(loader.loaded.sortedBy(::index), loader.loaded.toList())
        assertTrue(loader.loaded.size < releases.size)
        // Drawn from the whole discography, not the first few albums.
        assertTrue(drawnAlbums.any { index(it) >= 250 })
        assertTrue(drawnAlbums.any { index(it) < 50 })
        // Not in album order.
        assertTrue(tracks.map { index(it.albumId!!) } != tracks.map { index(it.albumId!!) }.sorted())
    }

    @Test
    fun should_loadAndShuffleEveryAlbum_when_anAlbumReportsNoCount() = runTest {
        val loader = Loader()
        val releases = releases(30) + ArtistRelease(MediaId.spotify("al30"), songCount = null)

        val tracks = loadArtistPlayTracks(
            releases = releases,
            shuffle = true,
            startLimit = SPOTIFY_START_MAX_URIS,
            random = Random(3),
            loadAlbum = loader::load
        )

        assertEquals(31, loader.loaded.size)
        // All 310 shuffled, of which the start takes its 100.
        assertEquals(100, tracks.size)
        assertEquals(100, tracks.map { it.id }.toSet().size)
    }

    @Test
    fun should_shuffleEveryTrack_when_theDiscographyFitsTheStart() = runTest {
        val loader = Loader()

        val tracks = loadArtistPlayTracks(
            releases = releases(8),
            shuffle = true,
            startLimit = SPOTIFY_START_MAX_URIS,
            random = Random(5),
            loadAlbum = loader::load
        )

        assertEquals(8, loader.loaded.size)
        val everyTrack = (0 until 8).flatMap { album -> List(10) { "al$album-$it" } }
        assertEquals(everyTrack.toSet(), tracks.map { it.id.rawId }.toSet())
        assertEquals(80, tracks.size)
    }

    @Test
    fun should_dropDrawnPlaces_when_anAlbumIsShorterThanItsCount() = runTest {
        // Every album reports 10 tracks but holds 5: places 5–9 have no track.
        val loader = Loader(tracksPerAlbum = { 5 })

        val tracks = loadArtistPlayTracks(
            releases = releases(60),
            shuffle = true,
            startLimit = SPOTIFY_START_MAX_URIS,
            random = Random(11),
            loadAlbum = loader::load
        )

        assertTrue(tracks.isNotEmpty())
        assertTrue(tracks.size < 100)
        assertTrue(tracks.all { it.trackNumber!! < 5 })
    }

    @Test
    fun should_capPlayToTheSpotifyStart_when_artistIsOnSpotify() = runTest {
        val loads = mutableListOf<MediaId>()
        val viewModel = viewModelFor(MediaId.spotify("ar-1"), albums = 40, loads = loads)

        val tracks = viewModel.getPlayTracks(shuffle = false)

        assertEquals(10, loads.size)
        assertEquals(100, tracks.size)
    }

    @Test
    fun should_capPlayAndShuffleToTheStartWindow_when_artistIsOnAppleMusic() = runTest {
        val loads = mutableListOf<MediaId>()
        val viewModel = viewModelFor(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "ar-1"), albums = 200, loads = loads)

        val play = viewModel.getPlayTracks(shuffle = false)

        // MusicKit asks for every id in one catalog request (Apple caps it at 300):
        // Play reads the first ten albums for 100 tracks, not all 200 for 2,000.
        assertEquals((0 until 10).map { MediaId(MediaId.PROVIDER_APPLE_MUSIC, "al$it") }, loads)
        assertEquals(SPOTIFY_START_MAX_URIS, play.size)

        loads.clear()
        val shuffled = viewModel.getPlayTracks(shuffle = true)

        assertEquals(SPOTIFY_START_MAX_URIS, shuffled.size)
        assertEquals(shuffled.map { it.albumId }.toSet(), loads.toSet())
        assertTrue(loads.size <= SPOTIFY_START_MAX_URIS)
    }

    @Test
    fun should_loadOnlyDrawnAlbums_when_spotifyArtistShuffles() = runTest {
        val loads = mutableListOf<MediaId>()
        val viewModel = viewModelFor(MediaId.spotify("ar-1"), albums = 200, loads = loads)

        val tracks = viewModel.getPlayTracks(shuffle = true)

        assertEquals(SPOTIFY_START_MAX_URIS, tracks.size)
        assertEquals(tracks.size, tracks.toSet().size)
        // 100 places drawn over 2,000 reported tracks: only their albums are read, each once.
        assertEquals(tracks.map { it.albumId }.toSet(), loads.toSet())
        assertEquals(loads.distinct(), loads)
        assertTrue(loads.size <= SPOTIFY_START_MAX_URIS)
    }

    @Test
    fun should_dropPlacesOfFailedAlbums_when_shuffleLoadFails() = runTest {
        val loads = mutableListOf<MediaId>()
        // Every even album's read fails, as reads queued behind a closed 429 gate do.
        val viewModel = viewModelFor(
            MediaId.spotify("ar-1"),
            albums = 200,
            loads = loads,
            failing = { index(it) % 2 == 0 }
        )

        val tracks = viewModel.getPlayTracks(shuffle = true)

        // Their drawn places drop out rather than being redrawn: a shorter start, no error.
        assertTrue(tracks.isNotEmpty())
        assertTrue(tracks.size < SPOTIFY_START_MAX_URIS)
        assertTrue(tracks.all { index(it.albumId!!) % 2 == 1 })
        assertTrue(loads.any { index(it) % 2 == 0 })
        assertEquals(loads.distinct(), loads)
    }

    @Test
    fun should_queueTheWholeDiscography_when_artistIsOnSubsonic() = runTest {
        val loads = mutableListOf<MediaId>()
        val viewModel = viewModelFor(MediaId.subsonic("ar-1"), albums = 40, loads = loads)

        val play = viewModel.getPlayTracks(shuffle = false)
        val shuffled = viewModel.getPlayTracks(shuffle = true)

        assertEquals(80, loads.size)
        assertEquals(400, play.size)
        assertEquals(play.toSet(), shuffled.toSet())
        assertEquals(400, viewModel.getAllTracks().size)
    }

    private fun TestScope.viewModelFor(
        artistId: MediaId,
        albums: Int,
        loads: MutableList<MediaId>,
        failing: (MediaId) -> Boolean = { false }
    ): ArtistDetailViewModel {
        val repository = mockk<YoinRepository>(relaxed = true)
        val albumIds = List(albums) { MediaId(artistId.provider, "al$it") }
        coEvery { repository.getArtist(any()) } returns ArtistDetail(
            id = artistId,
            name = "Artist",
            albumCount = albums,
            coverArt = null,
            albums = albumIds.map { album(it, tracks = emptyList()) }
        )
        coEvery { repository.getArtistListening(any(), any(), any(), any()) } returns null
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.resolveCoverUrl(any(), any()) } returns null
        coEvery { repository.getAlbum(any()) } answers {
            val id = firstArg<MediaId>()
            loads += id
            if (failing(id)) throw IOException("HTTP 429")
            album(id, tracks = List(10) { track(id, it) })
        }
        val viewModel = ArtistDetailViewModel(artistId.toString(), repository)
        advanceUntilIdle()
        // Opening the page preloads the newest six albums; count only Play's reads.
        loads.clear()
        return viewModel
    }

    private fun album(id: MediaId, tracks: List<Track>) = Album(
        id = id,
        name = "Album ${id.rawId}",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = null,
        genre = null,
        tracks = tracks
    )

    private fun track(album: MediaId, number: Int) = Track(
        id = MediaId(album.provider, "${album.rawId}-$number"),
        title = "Track $number",
        artist = "Artist",
        artistId = null,
        album = "Album ${album.rawId}",
        albumId = album,
        coverArt = null,
        durationSec = 200,
        trackNumber = number,
        year = null,
        genre = null,
        userRating = null
    )

    private fun index(album: MediaId): Int = album.rawId.removePrefix("al").toInt()
}
