package com.gpo.yoin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.local.SpotifyConfig
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileLimitReachedException
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.spotify.SpotifyOAuthResult
import java.net.URI
import java.net.UnknownServiceException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val database = container.database
    private val profileManager: ProfileManager = container.profileManager

    private val cacheSizeFlow: StateFlow<Long> =
        database.cacheMetadataDao().getTotalCacheSize()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val geminiKeyFlow: StateFlow<String> =
        database.geminiConfigDao().getConfig()
            .map { it?.apiKey.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Pair the raw Room-row value with the effective (fallback-aware) value so
     * the 6-way `combine` can collapse back into a 5-parameter form —
     * `kotlinx.coroutines.flow.combine` only ships direct overloads up to 5.
     */
    private val spotifyClientIdPairFlow: StateFlow<Pair<String, String>> = combine(
        database.spotifyConfigDao().getConfig().map { it?.clientId.orEmpty() },
        container.spotifyClientIdFlow,
    ) { override, effective -> override to effective }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "" to "")

    val uiState: StateFlow<SettingsUiState> = combine(
        profileManager.profiles,
        profileManager.activeProfileId,
        cacheSizeFlow,
        geminiKeyFlow,
        spotifyClientIdPairFlow,
    ) { profiles, activeId, cacheSize, geminiKey, spotifyClientIds ->
        val (spotifyOverride, spotifyEffective) = spotifyClientIds
        val resolvedActiveId = activeId ?: profiles.firstOrNull()?.id
        SettingsUiState.Content(
            profileCards = profiles.map { it.toCard(resolvedActiveId) },
            activeProfileId = resolvedActiveId,
            canAddProfile = profiles.size < ProfileManager.MAX_PROFILES,
            cacheSizeBytes = cacheSize,
            geminiApiKey = geminiKey,
            spotifyClientId = spotifyEffective,
            spotifyClientIdUsesFallback =
                spotifyOverride.isBlank() && spotifyEffective.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState.Loading)

    val switchingState: StateFlow<ProfileManager.SwitchState> = profileManager.switchingState

    private val _profileFormSheet = MutableStateFlow<ProfileFormSheet>(ProfileFormSheet.Hidden)
    val profileFormSheet: StateFlow<ProfileFormSheet> = _profileFormSheet.asStateFlow()

    private val _providerPickerState = MutableStateFlow(ProviderPickerState())
    val providerPickerState: StateFlow<ProviderPickerState> = _providerPickerState.asStateFlow()

    private val _deleteConfirmState = MutableStateFlow<DeleteConfirmState>(DeleteConfirmState.Hidden)
    val deleteConfirmState: StateFlow<DeleteConfirmState> = _deleteConfirmState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsOneShotEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsOneShotEvent> = _events.asSharedFlow()

    // ── Profile switching ──────────────────────────────────────────────

    fun switchToProfile(profileId: String) {
        if (profileManager.activeProfileId.value == profileId) return
        viewModelScope.launch {
            profileManager.switchTo(profileId)
        }
    }

    fun dismissSwitchError() {
        profileManager.acknowledgeSwitchError()
    }

    // ── Provider picker + profile-form sheet ──────────────────────────

    fun showProviderPicker() {
        _providerPickerState.value = ProviderPickerState(visible = true)
    }

    fun hideProviderPicker() {
        _providerPickerState.value = ProviderPickerState(visible = false)
    }

    fun pickProviderForNewProfile(provider: ProviderKind) {
        hideProviderPicker()
        if (!provider.isAvailable) return
        when (provider) {
            ProviderKind.SUBSONIC -> {
                _profileFormSheet.value = ProfileFormSheet.Visible(
                    mode = ProfileFormSheet.Visible.Mode.Create,
                    provider = provider,
                )
            }
            ProviderKind.SPOTIFY -> {
                if (container.spotifyClientIdFlow.value.isBlank()) {
                    emitEvent(
                        SettingsOneShotEvent.ShowError(
                            "Spotify client id is missing. Scroll to Spotify section and save one.",
                        ),
                    )
                    return
                }
                emitEvent(SettingsOneShotEvent.LaunchSpotifyOAuth)
            }
            ProviderKind.LOCAL -> Unit // UI disables this; defensive.
        }
    }

    fun commitSpotifyProfile(result: SpotifyOAuthResult) {
        when (result) {
            SpotifyOAuthResult.Cancelled -> Unit
            is SpotifyOAuthResult.Failure ->
                emitEvent(SettingsOneShotEvent.ShowError(result.message))
            is SpotifyOAuthResult.Success -> viewModelScope.launch {
                val hadActiveProfile = profileManager.activeProfileId.value != null
                val displayName = result.displayName.ifBlank { "Spotify · ${result.userId}" }
                try {
                    val created = profileManager.create(
                        displayName = displayName,
                        credentials = result.credentials,
                    )
                    if (hadActiveProfile) {
                        switchToProfile(created.id)
                    } else {
                        container.notifyMusicConfigurationChanged()
                    }
                } catch (limit: ProfileLimitReachedException) {
                    emitEvent(SettingsOneShotEvent.ShowError("Profile 上限 ${limit.limit}"))
                } catch (t: Throwable) {
                    emitEvent(
                        SettingsOneShotEvent.ShowError(t.message ?: "Spotify profile 保存失败"),
                    )
                }
            }
        }
    }

    private fun emitEvent(event: SettingsOneShotEvent) {
        _events.tryEmit(event)
    }

    fun openEditActiveProfile() {
        viewModelScope.launch {
            val active = profileManager.getActiveProfileSnapshot() ?: return@launch
            openEditProfile(active.id)
        }
    }

    fun openEditProfile(profileId: String) {
        viewModelScope.launch {
            val profile = profileManager.profiles.first().firstOrNull { it.id == profileId } ?: return@launch
            val provider = ProviderKind.fromKeyOrSubsonic(profile.provider)
            val credentials = profileManager.decodeCredentials(profile)
            val (url, user, pass) = when (credentials) {
                is ProfileCredentials.Subsonic ->
                    Triple(credentials.serverUrl, credentials.username, credentials.password)
                else -> Triple("", "", "")
            }
            _profileFormSheet.value = ProfileFormSheet.Visible(
                mode = ProfileFormSheet.Visible.Mode.Edit(profileId),
                provider = provider,
                initialUrl = url,
                initialUsername = user,
                initialPassword = pass,
            )
        }
    }

    fun closeProfileFormSheet() {
        _profileFormSheet.value = ProfileFormSheet.Hidden
    }

    fun testConnection(url: String, username: String, password: String) {
        val normalized = validateAndNormalize(url, username, password) ?: return
        updateFormSheet { it.copy(isTesting = true, testResult = null, saveError = null) }
        viewModelScope.launch {
            val result = runCatching {
                val creds = ServerCredentials(
                    serverUrl = normalized.url,
                    username = normalized.username,
                    password = normalized.password,
                )
                val api = SubsonicApiFactory.create(
                    credentialsProvider = { creds },
                    loggingEnabled = false,
                )
                val repo = YoinRepository(
                    apiProvider = { api },
                    database = database,
                    credentials = { creds },
                )
                repo.testConnection()
            }
            updateFormSheet { sheet ->
                sheet.copy(
                    isTesting = false,
                    testResult = result.fold(
                        onSuccess = { ConnectionResult.Success },
                        onFailure = { ConnectionResult.Failure(it.toConnectionErrorMessage()) },
                    ),
                )
            }
        }
    }

    fun saveSubsonicProfile(url: String, username: String, password: String) {
        val current = _profileFormSheet.value as? ProfileFormSheet.Visible ?: return
        val normalized = validateAndNormalize(url, username, password) ?: return
        updateFormSheet { it.copy(isTesting = false, saveError = null) }

        val credentials = ProfileCredentials.Subsonic(
            serverUrl = normalized.url,
            username = normalized.username,
            password = normalized.password,
        )
        val displayName = buildProfileDisplayName(normalized.url, normalized.username)

        viewModelScope.launch {
            try {
                when (val mode = current.mode) {
                    is ProfileFormSheet.Visible.Mode.Create -> {
                        val hadActiveProfile = profileManager.activeProfileId.value != null
                        val created = profileManager.create(displayName, credentials)
                        syncLegacyServerConfig(normalized)
                        closeProfileFormSheet()
                        if (hadActiveProfile) {
                            switchToProfile(created.id)
                        } else {
                            container.notifyMusicConfigurationChanged()
                        }
                    }
                    is ProfileFormSheet.Visible.Mode.Edit -> {
                        val wasActive = profileManager.activeProfileId.value == mode.profileId
                        profileManager.update(
                            id = mode.profileId,
                            displayName = displayName,
                            credentials = credentials,
                        )
                        if (wasActive) {
                            syncLegacyServerConfig(normalized)
                            // Kill playback bound to the stale stream URL and
                            // fan out to downstream VMs.
                            container.playbackManager.disconnect()
                            container.notifyMusicConfigurationChanged()
                        }
                        closeProfileFormSheet()
                    }
                }
            } catch (limit: ProfileLimitReachedException) {
                updateFormSheet { it.copy(saveError = "Profile 上限 ${limit.limit}") }
            } catch (t: Throwable) {
                updateFormSheet { it.copy(saveError = t.message ?: "保存失败") }
            }
        }
    }

    // ── Delete ────────────────────────────────────────────────────────

    fun requestDeleteProfile(profileId: String) {
        viewModelScope.launch {
            val profile = profileManager.profiles.first().firstOrNull { it.id == profileId } ?: return@launch
            _deleteConfirmState.value = DeleteConfirmState.Confirming(
                profileId = profile.id,
                displayName = profile.displayName,
            )
        }
    }

    fun dismissDeleteConfirm() {
        _deleteConfirmState.value = DeleteConfirmState.Hidden
    }

    fun confirmDeleteProfile() {
        val pending = _deleteConfirmState.value as? DeleteConfirmState.Confirming ?: return
        _deleteConfirmState.value = DeleteConfirmState.Hidden
        viewModelScope.launch {
            val wasActive = profileManager.activeProfileId.value == pending.profileId
            profileManager.delete(pending.profileId)
            if (wasActive) {
                // If delete removed the active profile, AppContainer needs to
                // refresh downstream VMs (same as a switch).
                container.notifyMusicConfigurationChanged()
                container.playbackManager.disconnect()
            }
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────

    fun saveGeminiApiKey(key: String) {
        viewModelScope.launch {
            database.geminiConfigDao().upsert(GeminiConfig(apiKey = key.trim()))
        }
    }

    fun saveSpotifyClientId(clientId: String) {
        viewModelScope.launch {
            database.spotifyConfigDao().upsert(SpotifyConfig(clientId = clientId.trim()))
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            database.cacheMetadataDao().deleteOldest(0)
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private fun updateFormSheet(transform: (ProfileFormSheet.Visible) -> ProfileFormSheet.Visible) {
        val current = _profileFormSheet.value as? ProfileFormSheet.Visible ?: return
        _profileFormSheet.value = transform(current)
    }

    private data class NormalizedCredentials(
        val url: String,
        val username: String,
        val password: String,
    )

    private fun validateAndNormalize(
        url: String,
        username: String,
        password: String,
    ): NormalizedCredentials? {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        val error = when {
            normalizedUrl.isBlank() -> "Server URL is required"
            normalizedUsername.isBlank() -> "Username is required"
            password.isBlank() -> "Password is required"
            else -> null
        }
        if (error != null) {
            updateFormSheet { it.copy(saveError = error, testResult = null) }
            return null
        }
        return NormalizedCredentials(normalizedUrl, normalizedUsername, password)
    }

    private suspend fun syncLegacyServerConfig(creds: NormalizedCredentials) {
        // Phase 1 keeps writing the legacy `server_config` row because
        // `YoinRepository` still reads via `AppContainer.getCredentials()`
        // which falls back to it. Phase 2 of the refactor removes this.
        database.serverConfigDao().insert(
            ServerConfig(
                serverUrl = creds.url,
                username = creds.username,
                passwordHash = creds.password,
            ),
        )
    }

    private fun Profile.toCard(activeProfileId: String?): ProfileCard {
        val provider = ProviderKind.fromKeyOrSubsonic(provider)
        val subtitle = when (provider) {
            ProviderKind.SUBSONIC -> {
                val creds = profileManager.decodeCredentials(this) as? ProfileCredentials.Subsonic
                creds?.let {
                    runCatching { URI(it.serverUrl).host }
                        .getOrNull()?.takeIf { host -> host.isNotBlank() } ?: it.username
                }
            }
            ProviderKind.SPOTIFY -> "Spotify account"
            ProviderKind.LOCAL -> "Local files"
        }
        return ProfileCard(
            id = id,
            displayName = displayName,
            subtitle = subtitle,
            provider = provider,
            isActive = id == activeProfileId,
        )
    }

    private fun buildProfileDisplayName(serverUrl: String, username: String): String {
        val host = runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            host != null && username.isNotBlank() -> "$username @ $host"
            host != null -> host
            username.isNotBlank() -> username
            else -> serverUrl
        }
    }

    private fun Throwable.toConnectionErrorMessage(): String = when (this) {
        is SubsonicException -> message ?: "Subsonic server returned an error"
        is UnknownServiceException -> "HTTP connections are blocked by Android network security policy"
        is IllegalArgumentException -> "Invalid server URL. Include http:// or https://"
        else -> message ?: "Connection failed"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
