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
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyOAuthResult
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import java.net.URI
import java.net.UnknownServiceException
import android.util.Log
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
    private val tag = "SettingsViewModel"

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
     * Fan three Spotify signals into one bundle so the top-level `combine`
     * stays inside the 5-arity typed overload:
     *  1. raw Room-row client id (for the "using fallback" badge)
     *  2. effective (fallback-aware) client id
     *  3. `SpotifyProviderStatus` — the single-source runtime status
     *     reflected in profile-card badges (NoClientId / NoPremium / etc)
     */
    private data class SpotifySettingsBundle(
        val overrideClientId: String,
        val effectiveClientId: String,
        val status: SpotifyProviderStatus,
    )

    private val spotifySettingsBundleFlow: StateFlow<SpotifySettingsBundle> = combine(
        database.spotifyConfigDao().getConfig().map { it?.clientId.orEmpty() },
        container.spotifyClientIdFlow,
        container.spotifyProviderStatus,
    ) { override, effective, status ->
        SpotifySettingsBundle(override, effective, status)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SpotifySettingsBundle("", "", SpotifyProviderStatus.Ready),
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        profileManager.profiles,
        profileManager.activeProfileId,
        cacheSizeFlow,
        geminiKeyFlow,
        spotifySettingsBundleFlow,
    ) { profiles, activeId, cacheSize, geminiKey, spotifyBundle ->
        val spotifyOverride = spotifyBundle.overrideClientId
        val spotifyEffective = spotifyBundle.effectiveClientId
        val spotifyStatus = spotifyBundle.status
        val resolvedActiveId = activeId ?: profiles.firstOrNull()?.id
        val spotifyReconnectProfile = profiles
            .firstOrNull { profile ->
                ProviderKind.fromKeyOrSubsonic(profile.provider) == ProviderKind.SPOTIFY &&
                    profile.profileRequiresSpotifyReconnect()
            }
        SettingsUiState.Content(
            profileCards = profiles.map {
                it.toCard(
                    activeProfileId = resolvedActiveId,
                    spotifyStatus = spotifyStatus,
                )
            },
            activeProfileId = resolvedActiveId,
            canAddProfile = profiles.size < ProfileManager.MAX_PROFILES,
            cacheSizeBytes = cacheSize,
            geminiApiKey = geminiKey,
            spotifyClientId = spotifyEffective,
            spotifyClientIdUsesFallback =
                spotifyOverride.isBlank() && spotifyEffective.isNotBlank(),
            spotifyReconnectProfileId = spotifyReconnectProfile?.id,
            spotifyReconnectProfileName = spotifyReconnectProfile?.displayName,
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
                viewModelScope.launch {
                    launchSpotifyOAuth(
                        targetProfileId = reconnectTargetProfileId() ?: null,
                    )
                }
            }
            ProviderKind.LOCAL -> Unit // UI disables this; defensive.
        }
    }

    fun reconnectSpotifyProfile(profileId: String) {
        if (container.spotifyClientIdFlow.value.isBlank()) {
            emitEvent(
                SettingsOneShotEvent.ShowError(
                    "Spotify client id is missing. Scroll to Spotify section and save one.",
                ),
            )
            return
        }
        launchSpotifyOAuth(targetProfileId = profileId)
    }

    fun commitSpotifyProfile(result: SpotifyOAuthResult) {
        val requestedTargetProfileId = when (result) {
            is SpotifyOAuthResult.Success -> result.targetProfileId
            is SpotifyOAuthResult.Failure -> result.targetProfileId
            SpotifyOAuthResult.Cancelled -> null
        }
        Log.d(tag, "commitSpotifyProfile: result=${result::class.java.simpleName} targetProfileId=$requestedTargetProfileId")
        when (result) {
            SpotifyOAuthResult.Cancelled -> Unit
            is SpotifyOAuthResult.Failure ->
                emitEvent(SettingsOneShotEvent.ShowError(result.message))
            is SpotifyOAuthResult.Success -> viewModelScope.launch {
                try {
                    val targetProfileId = requestedTargetProfileId ?: reconnectTargetProfileId()
                    if (targetProfileId != null) {
                        val wasActive = profileManager.activeProfileId.value == targetProfileId
                        val existing = profileManager.profiles.first()
                            .firstOrNull { it.id == targetProfileId }
                        Log.d(
                            tag,
                            "commitSpotifyProfile: updating existing profile=$targetProfileId scopes=${result.credentials.scopes.joinToString(",")}",
                        )
                        profileManager.update(
                            id = targetProfileId,
                            displayName = existing?.displayName
                                ?: result.displayName.ifBlank { "Spotify · ${result.userId}" },
                            credentials = result.credentials,
                        )
                        if (wasActive) {
                            container.playbackManager.disconnect()
                            container.notifyMusicConfigurationChanged()
                        }
                    } else {
                        val hadActiveProfile = profileManager.activeProfileId.value != null
                        val displayName = result.displayName.ifBlank { "Spotify · ${result.userId}" }
                        Log.d(
                            tag,
                            "commitSpotifyProfile: creating profile displayName=$displayName scopes=${result.credentials.scopes.joinToString(",")}",
                        )
                        val created = profileManager.create(
                            displayName = displayName,
                            credentials = result.credentials,
                        )
                        if (hadActiveProfile) {
                            switchToProfile(created.id)
                        } else {
                            container.notifyMusicConfigurationChanged()
                        }
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

    private fun launchSpotifyOAuth(targetProfileId: String?) {
        Log.d(tag, "launchSpotifyOAuth: targetProfileId=$targetProfileId")
        emitEvent(SettingsOneShotEvent.LaunchSpotifyOAuth(targetProfileId))
    }

    private suspend fun reconnectTargetProfileId(): String? =
        profileManager.profiles.first()
            .firstOrNull { profile ->
                ProviderKind.fromKeyOrSubsonic(profile.provider) == ProviderKind.SPOTIFY &&
                    profile.profileRequiresSpotifyReconnect()
            }
            ?.id

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
                SubsonicMusicSource(credentials = creds).library().ping()
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

    private fun Profile.toCard(
        activeProfileId: String?,
        spotifyStatus: SpotifyProviderStatus,
    ): ProfileCard {
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
        // Per-Spotify-profile scope drift (legacy profile missing newly-
        // required scopes) is a static credential check that
        // [SpotifyProviderStatus] doesn't capture — it describes the
        // *runtime* backend. Combine the two: runtime status first (a
        // global blocker like missing client id is more urgent than a
        // per-profile reconnect), then per-profile scope check.
        val unavailableReason: String? = when (provider) {
            ProviderKind.SPOTIFY -> when {
                spotifyStatus is SpotifyProviderStatus.NoClientId ->
                    spotifyStatus.userLabel
                spotifyStatus is SpotifyProviderStatus.SpotifyAppMissing ->
                    spotifyStatus.userLabel
                spotifyStatus is SpotifyProviderStatus.NoPremium ->
                    spotifyStatus.userLabel
                spotifyStatus is SpotifyProviderStatus.AuthFailure ->
                    spotifyStatus.userLabel
                profileRequiresSpotifyReconnect() -> "Reconnect"
                else -> null
            }
            else -> null
        }
        return ProfileCard(
            id = id,
            displayName = displayName,
            subtitle = subtitle,
            provider = provider,
            isActive = id == activeProfileId,
            unavailableReason = unavailableReason,
            requiresReconnect = provider == ProviderKind.SPOTIFY &&
                unavailableReason == "Reconnect",
        )
    }

    private fun Profile.profileRequiresSpotifyReconnect(): Boolean {
        val spotify = profileManager.decodeCredentials(this) as? ProfileCredentials.Spotify ?: return false
        // A profile is stuck if its token was minted against an older scope
        // list — we check the full required-scopes set rather than any single
        // scope, so adding new required scopes (e.g. playlist-modify-*)
        // automatically triggers reconnect for every legacy profile.
        return SpotifyAuthConfig.REQUIRED_SCOPES.any { required -> required !in spotify.scopes }
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
