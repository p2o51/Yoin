package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun should_clearSearchFocusRequest_when_openingLibraryNormallyAfterShortcut() = runTest {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.capabilities } returns flowOf(emptySet())
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        coEvery { repository.getArtists() } returns emptyList()

        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        viewModel.openSearchShortcut(LibrarySearchScope.CurrentLibrary)
        val shortcutState = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(shortcutState.searchFocusRequestId > 0L)

        viewModel.showLibraryHome()

        val normalEntryState = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(0L, normalEntryState.searchFocusRequestId)
        assertEquals(LibrarySearchScope.CurrentLibrary, normalEntryState.searchScope)
    }
}
