package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.gpo.yoin.data.model.LyricLine as SourceLyricLine

/**
 * A lyric action answers for the song it was started on. Gemini takes seconds: skipped meanwhile,
 * its translation used to be written over the next song's lyrics (the lyrics' owner was already
 * the next song, so nothing masked them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelLyricsActionTest {
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

    private fun synced(vararg texts: String) = YoinRepository.LoadedLyrics(
        lyrics = Lyrics.Synced(texts.mapIndexed { i, text -> SourceLyricLine(startMs = i * 1_000L, text = text) }),
        providerName = "test",
        providerSongId = null,
    )

    private fun playing(current: Track) = PlaybackState(
        currentTrack = current,
        isPlaying = true,
        duration = 240_000L,
        queue = listOf(a, b),
        currentIndex = listOf(a, b).indexOf(current),
        nextTrack = null,
        connectionPhase = ConnectionPhase.Ready,
    )

    private class Fixture(
        val playback: MutableStateFlow<PlaybackState>,
        val viewModel: NowPlayingViewModel,
    )

    private fun TestScope.fixture(translation: CompletableDeferred<YoinRepository.LyricsTranslationResult>): Fixture {
        val playbackState = MutableStateFlow(playing(a))
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns playbackState
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("profile")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { flowOf(FavoriteState(firstArg<Track>().isStarred)) }
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.Unknown)
        every { repository.resolveCoverUrl(any()) } returns null
        coEvery { repository.getLoadedLyrics(a.id, any(), any(), any()) } returns synced("a1", "a2")
        coEvery { repository.getLoadedLyrics(b.id, any(), any(), any()) } returns synced("b1", "b2")
        coEvery { repository.translateLyrics(a.id, any(), any(), any(), any(), any()) } coAnswers { translation.await() }
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        return Fixture(playbackState, viewModel)
    }

    private val Fixture.playingState: NowPlayingUiState.Playing
        get() = viewModel.uiState.value as NowPlayingUiState.Playing

    private val translated = YoinRepository.LyricsTranslationResult.Success(
        translations = mapOf(0 to "a1 (zh)", 1 to "a2 (zh)"),
    )

    @Test
    fun should_leaveTheNextSongsLyricsAlone_when_aTranslationLandsAfterASkip() = runTest {
        val translation = CompletableDeferred<YoinRepository.LyricsTranslationResult>()
        val f = fixture(translation)
        assertEquals(listOf("a1", "a2"), f.playingState.lyrics.map { it.text })

        f.viewModel.translateLyrics()
        runCurrent()
        f.playback.value = playing(b)
        runCurrent()
        translation.complete(translated)
        runCurrent()

        assertEquals(b.id.toString(), f.playingState.songId)
        assertEquals(listOf("b1", "b2"), f.playingState.lyrics.map { it.text })
        assertTrue(f.playingState.lyrics.all { it.translation == null })
        assertFalse(f.playingState.showLyricsTranslation)
        assertNull(f.playingState.lyricsActionInFlight)
    }

    @Test
    fun should_showTheTranslation_when_itLandsOnTheSongItWasAskedFor() = runTest {
        val translation = CompletableDeferred<YoinRepository.LyricsTranslationResult>()
        val f = fixture(translation)

        f.viewModel.translateLyrics()
        runCurrent()
        translation.complete(translated)
        runCurrent()

        assertEquals(listOf("a1 (zh)", "a2 (zh)"), f.playingState.lyrics.map { it.translation })
        assertTrue(f.playingState.showLyricsTranslation)
        assertNull(f.playingState.lyricsActionInFlight)
    }
}
