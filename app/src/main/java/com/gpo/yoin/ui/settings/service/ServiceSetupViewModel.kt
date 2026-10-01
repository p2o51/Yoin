package com.gpo.yoin.ui.settings.service

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.SpotifyConfig
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileLimitReachedException
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyOAuthResult
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import java.net.URI
import java.net.UnknownServiceException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns every "connect an account" flow: Subsonic create/edit, Spotify
 * Client ID + OAuth create/reconnect. Settings only lists and switches.
 */
class ServiceSetupViewModel(
    private val container: AppContainer,
    private val request: ServiceSetupRequest,
) : ViewModel() {
    val profileId: String? get() = request.profileId
    private val tag = "ServiceSetupViewModel"
    private val profileManager: ProfileManager = container.profileManager
    private val database = container.database

    private val subsonicForm = MutableStateFlow(SubsonicFormState(loaded = request.profileId == null))
    private val existingProfileName = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<ServiceSetupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ServiceSetupEvent> = _events.asSharedFlow()

    private val spotifyState: StateFlow<SpotifySetupState> = combine(
        database.spotifyConfigDao().getConfig().map { it?.clientId.orEmpty() },
        container.spotifyClientIdFlow,
        container.spotifyProviderStatus,
        profileManager.profiles,
    ) { override, effective, status, profiles ->
        val existing = request.profileId?.let { id -> profiles.firstOrNull { it.id == id } }
        val needsReconnect = existing?.let { profile ->
            val decoded = profileManager.decodeCredentials(profile) as? ProfileCredentials.Spotify
            decoded == null || decoded.revoked ||
                SpotifyAuthConfig.REQUIRED_SCOPES.any { it !in decoded.scopes }
        } ?: false
        SpotifySetupState(
            clientId = effective,
            usesBuildFallback = override.isBlank() && effective.isNotBlank(),
            accountIssue = when (status) {
                SpotifyProviderStatus.SpotifyAppMissing,
                SpotifyProviderStatus.NoPremium,
                is SpotifyProviderStatus.AuthFailure,
                -> status.userLabel
                else -> null
            },
            needsReconnect = needsReconnect,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SpotifySetupState())

    val uiState: StateFlow<ServiceSetupUiState> = combine(
        subsonicForm,
        spotifyState,
        existingProfileName,
        profileManager.profiles,
    ) { form, spotify, name, profiles ->
        ServiceSetupUiState(
            service = request.service,
            existingProfileName = name,
            isManaging = request.profileId != null,
            canAddProfile = request.profileId != null || profiles.size < ProfileManager.MAX_PROFILES,
            subsonic = form,
            spotify = spotify,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ServiceSetupUiState(service = request.service, isManaging = request.profileId != null),
    )

    init {
        request.profileId?.let(::loadExistingProfile)
    }

    private fun loadExistingProfile(profileId: String) {
        viewModelScope.launch {
            val profile = profileManager.profiles.first().firstOrNull { it.id == profileId } ?: return@launch
            existingProfileName.value = profile.displayName
            if (ProviderKind.fromKeyOrSubsonic(profile.provider) != ProviderKind.SUBSONIC) return@launch
            val credentials = profileManager.decodeCredentials(profile) as? ProfileCredentials.Subsonic
            subsonicForm.value = SubsonicFormState(
                initialUrl = credentials?.serverUrl.orEmpty(),
                initialUsername = credentials?.username.orEmpty(),
                initialPassword = credentials?.password.orEmpty(),
                credentialsMissing = credentials == null,
                loaded = true,
            )
        }
    }

    // ── Subsonic ──────────────────────────────────────────────────────

    fun testSubsonicConnection(url: String, username: String, password: String) {
        val credentials = normalizeSubsonic(url, username, password) ?: return
        subsonicForm.update { it.copy(isBusy = true, status = SubsonicStatus.Testing) }
        viewModelScope.launch {
            val result = runCatching {
                SubsonicMusicSource.fromProfileCredentials(credentials).library().ping()
            }
            subsonicForm.update {
                it.copy(
                    isBusy = false,
                    status = result.fold(
                        onSuccess = { SubsonicStatus.Reachable },
                        onFailure = { error -> SubsonicStatus.Failed(error.toConnectionErrorMessage()) },
                    ),
                )
            }
        }
    }

    fun saveSubsonicProfile(url: String, username: String, password: String) {
        val credentials = normalizeSubsonic(url, username, password) ?: return
        val displayName = buildSubsonicDisplayName(credentials.serverUrl, credentials.username)
        subsonicForm.update { it.copy(isBusy = true, status = SubsonicStatus.Saving) }
        viewModelScope.launch {
            try {
                val editingId = request.profileId
                if (editingId == null) {
                    finishWithNewProfile(profileManager.create(displayName, credentials).id)
                } else {
                    val wasActive = profileManager.activeProfileId.value == editingId
                    profileManager.update(editingId, displayName, credentials)
                    if (wasActive) {
                        // Kill playback bound to the stale stream URL and fan
                        // out to downstream VMs.
                        container.playbackManager.disconnect()
                        container.notifyMusicConfigurationChanged()
                    }
                    _events.tryEmit(ServiceSetupEvent.Done(activateProfileId = null))
                }
            } catch (limit: ProfileLimitReachedException) {
                subsonicForm.update {
                    it.copy(isBusy = false, status = SubsonicStatus.Failed("You can keep up to ${limit.limit} accounts"))
                }
            } catch (t: Throwable) {
                subsonicForm.update {
                    it.copy(isBusy = false, status = SubsonicStatus.Failed(t.message ?: "Couldn't save"))
                }
            }
        }
    }

    private fun normalizeSubsonic(url: String, username: String, password: String): ProfileCredentials.Subsonic? {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        val error = when {
            normalizedUrl.isBlank() -> "Server address is required"
            normalizedUsername.isBlank() -> "Username is required"
            password.isBlank() -> "Password is required"
            else -> null
        }
        if (error != null) {
            subsonicForm.update { it.copy(status = SubsonicStatus.Failed(error)) }
            return null
        }
        return ProfileCredentials.Subsonic(
            serverUrl = normalizedUrl,
            username = normalizedUsername,
            password = password,
        )
    }

    // ── Spotify ───────────────────────────────────────────────────────

    fun saveSpotifyClientId(clientId: String) {
        viewModelScope.launch {
            database.spotifyConfigDao().upsert(SpotifyConfig(clientId = clientId.trim()))
        }
    }

    fun connectSpotify() {
        if (container.spotifyClientIdFlow.value.isBlank()) {
            _events.tryEmit(ServiceSetupEvent.ShowError("Add your Spotify Client ID first"))
            return
        }
        _events.tryEmit(ServiceSetupEvent.LaunchSpotifyOAuth(targetProfileId = request.profileId))
    }

    fun commitSpotifyOAuth(result: SpotifyOAuthResult) {
        when (result) {
            SpotifyOAuthResult.Cancelled -> Unit
            is SpotifyOAuthResult.Failure -> _events.tryEmit(ServiceSetupEvent.ShowError(result.message))
            is SpotifyOAuthResult.Success -> viewModelScope.launch {
                try {
                    val targetProfileId = result.targetProfileId ?: request.profileId
                    Log.d(tag, "commitSpotifyOAuth: target=$targetProfileId")
                    if (targetProfileId != null) {
                        val wasActive = profileManager.activeProfileId.value == targetProfileId
                        val existing = profileManager.profiles.first().firstOrNull { it.id == targetProfileId }
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
                        _events.tryEmit(ServiceSetupEvent.Done(activateProfileId = null))
                    } else {
                        val created = profileManager.create(
                            displayName = result.displayName.ifBlank { "Spotify · ${result.userId}" },
                            credentials = result.credentials,
                        )
                        finishWithNewProfile(created.id)
                    }
                } catch (limit: ProfileLimitReachedException) {
                    _events.tryEmit(ServiceSetupEvent.ShowError("You can keep up to ${limit.limit} accounts"))
                } catch (t: Throwable) {
                    _events.tryEmit(ServiceSetupEvent.ShowError(t.message ?: "Couldn't save the Spotify account"))
                }
            }
        }
    }

    // ── Shared ────────────────────────────────────────────────────────

    /**
     * First-ever profile: ProfileManager already made it active, only the
     * downstream fan-out is needed. Otherwise hand the id back to Settings,
     * which runs the (overlay-backed) switch in its own scope.
     */
    private fun finishWithNewProfile(createdId: String) {
        val hadOtherActive = profileManager.activeProfileId.value.let { it != null && it != createdId }
        if (hadOtherActive) {
            _events.tryEmit(ServiceSetupEvent.Done(activateProfileId = createdId))
        } else {
            container.notifyMusicConfigurationChanged()
            _events.tryEmit(ServiceSetupEvent.Done(activateProfileId = null))
        }
    }

    private fun buildSubsonicDisplayName(serverUrl: String, username: String): String {
        val host = runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            host != null && username.isNotBlank() -> "$username @ $host"
            host != null -> host
            username.isNotBlank() -> username
            else -> serverUrl
        }
    }

    private fun Throwable.toConnectionErrorMessage(): String = when (this) {
        is SubsonicException -> message ?: "The server returned an error"
        is UnknownServiceException -> "Android blocks plain HTTP here — use https://"
        is IllegalArgumentException -> "Check the address — include http:// or https://"
        else -> message ?: "Couldn't reach the server"
    }

    class Factory(
        private val container: AppContainer,
        private val request: ServiceSetupRequest,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ServiceSetupViewModel(container, request) as T
    }
}
