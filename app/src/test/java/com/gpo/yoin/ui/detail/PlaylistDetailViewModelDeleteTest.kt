package com.gpo.yoin.ui.detail

import app.cash.turbine.test
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Delete on the playlist page: the page leaves as Delete is confirmed and
 * hands the deletion to PlaylistDeletions, which outlives it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelDeleteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val handedOver = mutableListOf<MediaId>()

    private val repository: YoinRepository = mockk<YoinRepository>(relaxed = true).also { repository ->
        coEvery { repository.awaitActiveSource(any()) } returns mockk<MusicSource>()
        coEvery { repository.getPlaylist(any()) } returns Playlist(
            id = PLAYLIST_ID,
            name = "Morning",
            owner = null,
            coverArt = null,
            songCount = 0,
            durationSec = 0,
            canWrite = true,
        )
        every { repository.observeTracksWithNotes(any()) } returns flowOf(emptySet())
    }

    private fun page() = PlaylistDetailViewModel(
        playlistId = PLAYLIST_ID.toString(),
        repository = repository,
        deletePlaylist = { handedOver += it },
    )

    @Test
    fun should_leaveAtOnceAndHandTheDeletionOver_when_deleteIsConfirmed() = runTest {
        val page = page()
        advanceUntilIdle()
        assertTrue(page.takesNotices.value)

        page.deleted.test {
            page.delete()
            runCurrent()

            // No wait for the service: the page leaves now.
            awaitItem()
            assertFalse(page.takesNotices.value)
            assertEquals(listOf(PLAYLIST_ID), handedOver)
            // The page itself sends nothing; nor does it say anything on its own snackbar.
            coVerify(exactly = 0) { repository.deletePlaylist(any()) }
        }
        page.messages.test { expectNoEvents() }
    }

    @Test
    fun should_handTheDeletionOverOnce_when_deleteIsConfirmedTwice() = runTest {
        val page = page()
        advanceUntilIdle()

        page.deleted.test {
            page.delete()
            page.delete()
            runCurrent()

            awaitItem()
            expectNoEvents()
            assertEquals(listOf(PLAYLIST_ID), handedOver)
        }
    }

    private companion object {
        val PLAYLIST_ID = MediaId.subsonic("pl-1")
    }
}
