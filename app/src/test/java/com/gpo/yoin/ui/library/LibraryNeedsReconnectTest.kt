package com.gpo.yoin.ui.library

import com.gpo.yoin.R
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.common.UiText
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Library with an account in use whose source isn't coming (ACC-02: its
 * credentials couldn't be read at launch): it says what Home's dot says —
 * the account needs reconnecting — not that no profile is configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryNeedsReconnectTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_sayTheAccountNeedsReconnecting_when_theAccountInUseHasNoSourceComing() = runTest {
        val viewModel = libraryViewModel(sourcelessRepository(MutableStateFlow(true)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("an error page: $state", state is LibraryUiState.Error)
        assertEquals(UiText.Res(R.string.home_account_reconnect_needed), (state as LibraryUiState.Error).message)
    }

    @Test
    fun should_notSayTheAccountNeedsReconnecting_when_noAccountNeedsIt() = runTest {
        val viewModel = libraryViewModel(sourcelessRepository(MutableStateFlow(false)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("an error page: $state", state is LibraryUiState.Error)
        assertNotEquals(UiText.Res(R.string.home_account_reconnect_needed), (state as LibraryUiState.Error).message)
    }

    @Test
    fun should_sayTheAccountNeedsReconnecting_when_thatIsKnownOnlyAfterTheLoadFailed() = runTest {
        // ProfileManager works the signal out off the main thread: it can land
        // after the source settled, and after Library's load gave up.
        val needsReconnect = MutableStateFlow(false)
        val viewModel = libraryViewModel(sourcelessRepository(needsReconnect))
        advanceUntilIdle()
        val before = viewModel.uiState.value
        assertTrue("an error page: $before", before is LibraryUiState.Error)
        assertNotEquals(UiText.Res(R.string.home_account_reconnect_needed), (before as LibraryUiState.Error).message)

        needsReconnect.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("an error page: $state", state is LibraryUiState.Error)
        assertEquals(UiText.Res(R.string.home_account_reconnect_needed), (state as LibraryUiState.Error).message)
    }

    /** A real repository whose source has settled without coming, for an account in use. */
    private fun TestScope.sourcelessRepository(needsReconnect: MutableStateFlow<Boolean>) = YoinRepository(
        activeSource = MutableStateFlow<MusicSource?>(null),
        activeProfileId = MutableStateFlow<String?>("restored"),
        activeSourceSettled = flowOf(true),
        activeNeedsReconnect = needsReconnect,
        database = mockk(relaxed = true),
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
        repositoryScope = backgroundScope
    )

    private fun libraryViewModel(repository: YoinRepository) = LibraryViewModel(
        repository = repository,
        sortDispatcher = mainDispatcherRule.dispatcher,
        nameOrder = { String.CASE_INSENSITIVE_ORDER },
        scrollIndex = { JvmLibraryScrollIndex }
    )
}
