package com.gpo.yoin.ui.nowplaying

import androidx.media3.common.Player
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import com.gpo.yoin.data.model.LyricLine as SourceLyricLine

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelUpNextTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private fun track(id: String) = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, id),
        title = "Song $id", artist = "Artist", artistId = null,
        album = null, albumId = null, coverArt = null,
        durationSec = 240, trackNumber = null, year = null,
        genre = null, userRating = null,
    )

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    private fun synced(vararg texts: String) = YoinRepository.LoadedLyrics(
        lyrics = Lyrics.Synced(texts.mapIndexed { i, text -> SourceLyricLine(startMs = i * 1_000L, text = text) }),
        providerName = "test",
        providerSongId = null,
    )

    private fun playing(current: Track, next: Track?, queue: List<Track> = listOf(a, b, c)) = PlaybackState(
        currentTrack = current,
        isPlaying = true,
        duration = 240_000L,
        queue = queue,
        currentIndex = queue.indexOf(current),
        nextTrack = next,
        connectionPhase = ConnectionPhase.Ready,
    )

    private class Fixture(
        val playback: MutableStateFlow<PlaybackState>,
        val repository: YoinRepository,
        val viewModel: NowPlayingViewModel,
    )

    private fun TestScope.fixture(initial: PlaybackState, configure: (YoinRepository) -> Unit): Fixture {
        val playbackState = MutableStateFlow(initial)
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns playbackState
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("profile")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeSpotifyFavorite(any()) } returns flowOf(null)
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.Unknown)
        every { repository.resolveCoverUrl(any()) } returns null
        configure(repository)
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        return Fixture(playbackState, repository, viewModel)
    }

    private val Fixture.playingState: NowPlayingUiState.Playing
        get() = viewModel.uiState.value as NowPlayingUiState.Playing

    @Test
    fun should_openTheNextSongWithItsPreloadedLyrics_when_itStarts() = runTest {
        val f = fixture(playing(a, next = b)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1", "a2")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns synced("b1", "b2")
        }
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(b.id.toString(), f.playingState.upNextLyrics?.songId)

        f.playback.value = playing(b, next = c)
        runCurrent()

        assertEquals(b.id.toString(), f.playingState.songId)
        assertEquals(listOf("b1", "b2"), f.playingState.lyrics.map { it.text })
        assertFalse(f.playingState.lyricsLoading)
        coVerify(exactly = 1) { f.repository.getLoadedLyrics(b.id, any(), any()) }
    }

    @Test
    fun should_neverEmitALoadingFrame_when_aPreloadedSongStarts() = runTest {
        val f = fixture(playing(a, next = b)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns synced("b1", "b2")
        }
        advanceTimeBy(4_001)
        runCurrent()
        val seen = mutableListOf<NowPlayingUiState>()
        backgroundScope.launch { f.viewModel.uiState.collect { seen += it } }
        runCurrent()

        f.playback.value = playing(b, next = c)
        runCurrent()

        val bFrames = seen.filterIsInstance<NowPlayingUiState.Playing>().filter { it.songId == b.id.toString() }
        assertFalse(bFrames.isEmpty())
        bFrames.forEach { frame ->
            assertFalse("B never shows a loading frame", frame.lyricsLoading)
            assertEquals(listOf("b1", "b2"), frame.lyrics.map { it.text })
        }
    }

    @Test
    fun should_keepThePreload_when_theQueueRunsOutAtTheSongChange() = runTest {
        // b is the last song: nextTrack turns null in the very emission that
        // starts b. The preload used to be wiped right there (loading beat).
        val f = fixture(playing(a, next = b, queue = listOf(a, b))) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns synced("b1")
        }
        advanceTimeBy(4_001)
        runCurrent()

        f.playback.value = playing(b, next = null, queue = listOf(a, b))
        runCurrent()

        assertEquals(listOf("b1"), f.playingState.lyrics.map { it.text })
        assertFalse(f.playingState.lyricsLoading)
        assertNull(f.playingState.upNextLyrics)
        coVerify(exactly = 1) { f.repository.getLoadedLyrics(b.id, any(), any()) }
    }

    @Test
    fun should_preloadFromTheQueue_when_theBackendReportsNoNextTrack() = runTest {
        // Spotify App Remote: no nextTrack, but Yoin's mirrored queue in order.
        val f = fixture(playing(a, next = null)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns synced("b1")
        }
        advanceTimeBy(4_001)
        runCurrent()

        assertEquals(b.id.toString(), f.playingState.upNextLyrics?.songId)
    }

    @Test
    fun should_notGuessTheNextSong_when_shuffleHidesIt() = runTest {
        val f = fixture(playing(a, next = null).copy(shuffleEnabled = true)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns synced("b1")
        }
        advanceTimeBy(30_000)
        runCurrent()

        assertNull(f.playingState.upNextLyrics)
        coVerify(exactly = 0) { f.repository.getLoadedLyrics(b.id, any(), any()) }
    }

    @Test
    fun should_retryThePreload_when_theFirstFetchFails() = runTest {
        var calls = 0
        val f = fixture(playing(a, next = b)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } answers {
                calls += 1
                if (calls == 1) throw IOException("offline") else synced("b1")
            }
        }
        advanceTimeBy(4_001)
        runCurrent()
        assertNull(f.playingState.upNextLyrics)

        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(b.id.toString(), f.playingState.upNextLyrics?.songId)
    }

    @Test
    fun should_preloadUntimedLyricsWithoutChoreographingThem_when_theNextSongIsUntimed() = runTest {
        val f = fixture(playing(a, next = b)) { repo ->
            coEvery { repo.getLoadedLyrics(a.id, any(), any()) } returns synced("a1")
            coEvery { repo.getLoadedLyrics(b.id, any(), any()) } returns YoinRepository.LoadedLyrics(
                lyrics = Lyrics.Unsynced("plain one\nplain two"),
                providerName = "test",
                providerSongId = null,
            )
        }
        advanceTimeBy(4_001)
        runCurrent()
        assertNull(f.playingState.upNextLyrics)

        f.playback.value = playing(b, next = c)
        runCurrent()

        assertEquals(listOf("plain one", "plain two"), f.playingState.lyrics.map { it.text })
        assertFalse(f.playingState.lyricsLoading)
        coVerify(exactly = 1) { f.repository.getLoadedLyrics(b.id, any(), any()) }
    }

    @Test
    fun should_tagThePlayheadWithItsSong_when_positionTicks() = runTest {
        val f = fixture(playing(a, next = b).copy(position = 1_234L)) { repo ->
            coEvery { repo.getLoadedLyrics(any(), any(), any()) } returns null
        }

        assertEquals(NowPlayingPlayhead(a.id.toString(), 1_234L), f.viewModel.playhead.value)

        f.playback.value = playing(b, next = c).copy(position = 0L)
        runCurrent()
        assertEquals(NowPlayingPlayhead(b.id.toString(), 0L), f.viewModel.playhead.value)
    }

    @Test
    fun should_resolveTheNextTrack_when_theBackendOrQueueKnowsIt() {
        assertEquals(c, playing(a, next = c).resolvedNextTrack())
        assertEquals(b, playing(a, next = null).resolvedNextTrack())
        assertNull(playing(c, next = null).resolvedNextTrack())
        assertEquals(a, playing(c, next = null).copy(repeatMode = Player.REPEAT_MODE_ALL).resolvedNextTrack())
        assertNull(playing(a, next = null).copy(repeatMode = Player.REPEAT_MODE_ONE).resolvedNextTrack())
        assertNull(playing(a, next = null).copy(shuffleEnabled = true).resolvedNextTrack())
        // Current track not where the queue says it is: don't guess.
        assertNull(playing(a, next = null).copy(currentIndex = 2).resolvedNextTrack())
    }
}
