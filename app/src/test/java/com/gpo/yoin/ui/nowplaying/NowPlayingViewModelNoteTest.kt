package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.component.NoteDraft
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.component.NoteTarget
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelNoteTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private fun track(raw: String, title: String) = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, raw),
        title = title, artist = "Night QA Band", artistId = null,
        album = null, albumId = null, coverArt = null,
        durationSec = 60, trackNumber = null, year = null,
        genre = null, userRating = null,
    )

    @Test
    fun should_saveToDraftSongWithItsAnchor_when_songChangesBeforeSave() = runTest {
        val songA = track("nq-t1", "Harbour Lights")
        val songB = track("nq-t2", "Second Platform")
        val playbackState = MutableStateFlow(PlaybackState(currentTrack = songA, queue = listOf(songA, songB)))
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns playbackState
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileId() } returns "p"
        every { repository.currentProfileIdFlow } returns MutableStateFlow<String?>("p")
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { flowOf(FavoriteState(firstArg<Track>().isStarred)) }
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.NotAdded)
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        runCurrent()

        // Writing starts at 0:58 of song A …
        val draft = NoteDraftState()
        val targetA = NoteTarget(songA.id.toString(), "Harbour Lights", "Night QA Band")
        draft.followIfEmpty(targetA, positionMs = 58_000L)
        draft.edit("the outro hits", targetA, positionMs = 59_000L)
        // … A ends, B plays, then 记下.
        playbackState.value = PlaybackState(currentTrack = songB, queue = listOf(songA, songB), currentIndex = 1)
        val targetB = NoteTarget(songB.id.toString(), "Second Platform", "Night QA Band")
        viewModel.saveCurrentNote(requireNotNull(draft.takeSaveRequest(targetB, positionMs = 2_000L)))
        runCurrent()

        coVerify(exactly = 1) { repository.addNote(songA, "the outro hits", 58_000L) }
        coVerify(exactly = 0) { repository.addNote(songB, any(), any()) }
    }

    @Test
    fun should_discardDraft_when_activeProfileChanges() = runTest {
        val song = track("nq-t1", "Harbour Lights")
        val profileId = MutableStateFlow<String?>("p")
        val (viewModel, repository) = viewModel(song, profileId)
        val target = NoteTarget(song.id.toString(), "Harbour Lights", "Night QA Band")
        viewModel.noteDraft.followIfEmpty(target, positionMs = 58_000L)
        viewModel.noteDraft.edit("half a thought", target, positionMs = 59_000L)

        // Now Playing closed, Settings switches to another server.
        profileId.value = "q"
        every { repository.currentProfileId() } returns "q"
        runCurrent()

        assertEquals(NoteDraft(), viewModel.noteDraft.draft)
    }

    @Test
    fun should_keepDraft_when_profileIsUnchanged() = runTest {
        val song = track("nq-t1", "Harbour Lights")
        val profileId = MutableStateFlow<String?>("p")
        val (viewModel, _) = viewModel(song, profileId)
        val target = NoteTarget(song.id.toString(), "Harbour Lights", "Night QA Band")
        viewModel.noteDraft.followIfEmpty(target, positionMs = 58_000L)
        viewModel.noteDraft.edit("half a thought", target, positionMs = 59_000L)

        profileId.value = "p"
        runCurrent()

        assertEquals("half a thought", viewModel.noteDraft.draft.text)
        assertEquals(58_000L, viewModel.noteDraft.draft.anchorMs)
    }

    @Test
    fun should_refuseSave_when_draftWasWrittenUnderAnotherProfile() = runTest {
        val song = track("nq-t1", "Harbour Lights")
        val (viewModel, repository) = viewModel(song, MutableStateFlow<String?>("p"))
        val target = NoteTarget(song.id.toString(), "Harbour Lights", "Night QA Band")
        viewModel.noteDraft.followIfEmpty(target, positionMs = 58_000L)
        viewModel.noteDraft.edit("half a thought", target, positionMs = 59_000L)
        val request = requireNotNull(viewModel.noteDraft.takeSaveRequest(target, positionMs = 60_000L))

        // The switch has landed in the repository, but not yet reached the draft.
        every { repository.currentProfileId() } returns "q"
        viewModel.saveCurrentNote(request)
        runCurrent()

        coVerify(exactly = 0) { repository.addNote(any(), any(), any()) }
    }

    @Test
    fun should_refileNoteAtNewMoment_when_realigned() = runTest {
        val song = track("nq-t1", "Harbour Lights")
        val (viewModel, repository) = viewModel(song, MutableStateFlow<String?>("p"))
        val note = SongNote(
            id = "n1", profileId = "p", trackId = "nq-t1", content = "placeholder words",
            createdAt = 1L, updatedAt = 1L, title = "Harbour Lights", artist = "Night QA Band",
            positionMs = 12_000L,
        )

        viewModel.realignNote(note, positionMs = 95_000L)
        runCurrent()

        // Same note (id, words, creation time), new moment.
        coVerify(exactly = 1) { repository.updateNote(note.copy(positionMs = 95_000L), "placeholder words") }
    }

    private fun TestScope.viewModel(
        song: Track,
        profileId: MutableStateFlow<String?>,
    ): Pair<NowPlayingViewModel, YoinRepository> {
        val playback = mockk<PlaybackManager>(relaxed = true)
        every { playback.playbackState } returns
            MutableStateFlow(PlaybackState(currentTrack = song, queue = listOf(song)))
        every { playback.currentActivityContext } returns MutableStateFlow<ActivityContext>(ActivityContext.None)
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProfileId() } returns profileId.value
        every { repository.currentProfileIdFlow } returns profileId
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.getRating(any()) } returns flowOf(null)
        every { repository.observeFavoriteState(any()) } answers { flowOf(FavoriteState(firstArg<Track>().isStarred)) }
        every { repository.observeLibraryMembership(any()) } returns flowOf(LibraryMembership.NotAdded)
        val viewModel = NowPlayingViewModel(playback, repository, mockk<CastManager>(relaxed = true))
        runCurrent()
        return viewModel to repository
    }
}
