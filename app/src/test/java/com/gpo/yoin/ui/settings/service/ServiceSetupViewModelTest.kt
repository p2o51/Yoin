package com.gpo.yoin.ui.settings.service

import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The account page's Subsonic form as the page first gets it: an edit form
 * starts unloaded — the page keys its fields on that — and is filled with
 * the account's stored credentials once they are read (ACC-06: they were
 * readable, yet the page opened empty with Save off).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceSetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val profile = Profile(
        id = "a",
        provider = "subsonic",
        displayName = "qa @ 127.0.0.1",
        credentialsJson = ProfileManager.STORE_MARKER_V1,
        createdAt = 0L
    )
    private val stored = ProfileCredentials.Subsonic(
        serverUrl = "http://127.0.0.1:4933",
        username = "qa",
        password = "pw"
    )
    private val profileManager = mockk<ProfileManager>().also { manager ->
        every { manager.profiles } returns MutableStateFlow(listOf(profile))
        every { manager.decodeCredentials(profile) } returns stored
    }
    private val container = mockk<AppContainer>().also { container ->
        every { container.profileManager } returns profileManager
        every { container.database.spotifyConfigDao().getConfig() } returns flowOf(null)
        every { container.spotifyClientIdFlow } returns MutableStateFlow("")
        every { container.spotifyProviderStatus } returns MutableStateFlow(SpotifyProviderStatus.Ready)
        every { container.profileAvatarStore.urls } returns MutableStateFlow(emptyMap())
    }

    @Test
    fun should_startUnloadedThenCarryTheStoredCredentials_when_managingASubsonicAccount() = runTest {
        val viewModel = ServiceSetupViewModel(container, ServiceSetupRequest(SetupService.Subsonic, profileId = "a"))

        // The page's first frame: not loaded yet (not a loaded, empty form).
        assertFalse(viewModel.uiState.value.subsonic.loaded)

        val form = viewModel.uiState.first { it.subsonic.loaded }.subsonic
        assertEquals("http://127.0.0.1:4933", form.initialUrl)
        assertEquals("qa", form.initialUsername)
        assertEquals("pw", form.initialPassword)
        assertFalse(form.credentialsMissing)
    }

    @Test
    fun should_sayThePasswordIsMissing_when_theStoredCredentialsCannotBeRead() = runTest {
        every { profileManager.decodeCredentials(profile) } returns null
        val viewModel = ServiceSetupViewModel(container, ServiceSetupRequest(SetupService.Subsonic, profileId = "a"))

        val form = viewModel.uiState.first { it.subsonic.loaded }.subsonic

        assertTrue(form.credentialsMissing)
        assertEquals("", form.initialPassword)
    }

    @Test
    fun should_startLoadedAndEmpty_when_addingASubsonicAccount() = runTest {
        val viewModel = ServiceSetupViewModel(container, ServiceSetupRequest(SetupService.Subsonic))

        val form = viewModel.uiState.value.subsonic
        assertTrue(form.loaded)
        assertEquals("", form.initialUrl)
        assertEquals("", form.initialUsername)
    }
}
