package com.gpo.yoin.ui.settings.service

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
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
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.settings.assignAvatarShapes
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
                SpotifyProviderStatus.SpotifyAppMissing -> UiText.Res(R.string.settings_spotify_install)
                SpotifyProviderStatus.NoPremium -> UiText.Res(R.string.settings_spotify_premium_required)
                is SpotifyProviderStatus.AuthFailure -> UiText.Res(R.string.settings_account_reconnect)
                else -> null
            },
            needsReconnect = needsReconnect,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SpotifySetupState())

    val uiState: StateFlow<ServiceSetupUiState> = combine(
        subsonicForm,
        spotifyState,
        existingProfileName,
        combine(profileManager.profiles, container.profileAvatarStore.urls, ::Pair),
    ) { form, spotify, name, (profiles, avatarUrls) ->
        val existing = request.profileId?.let { id -> profiles.firstOrNull { it.id == id } }
        ServiceSetupUiState(
            service = request.service,
            existingProfileName = name,
            // Until the profile list arrives (and if the account is removed
            // while its page is open) the tapped card's face stands in.
            account = existing?.let { profile ->
                // Same face as the account's Settings card: a Subsonic account
                // leads with its username and lives on its server's host.
                val subsonic = request.service == SetupService.Subsonic
                AccountFace(
                    title = form.initialUsername.takeIf { subsonic && it.isNotBlank() } ?: profile.displayName,
                    detail = if (subsonic) {
                        runCatching { URI(form.initialUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    },
                    avatarShape = assignAvatarShapes(profiles.map { it.id to it.createdAt })[profile.id] ?: 0,
                    photoUrl = avatarUrls[profile.id],
                )
            } ?: request.face,
            isManaging = request.profileId != null,
            canAddProfile = request.profileId != null || profiles.size < ProfileManager.MAX_PROFILES,
            subsonic = form,
            spotify = spotify,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ServiceSetupUiState(
            service = request.service,
            isManaging = request.profileId != null,
            account = request.face,
        ),
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
                    it.copy(
                        isBusy = false,
                        status = SubsonicStatus.Failed(
                            UiText.Res(R.string.settings_setup_account_limit, listOf(limit.limit)),
                        ),
                    )
                }
            } catch (t: Throwable) {
                subsonicForm.update {
                    it.copy(
                        isBusy = false,
                        status = SubsonicStatus.Failed(
                            t.message?.let(UiText::Raw) ?: UiText.Res(R.string.settings_setup_save_failed),
                        ),
                    )
                }
            }
        }
    }

    private fun normalizeSubsonic(url: String, username: String, password: String): ProfileCredentials.Subsonic? {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        val error = when {
            normalizedUrl.isBlank() -> UiText.Res(R.string.settings_setup_address_required)
            normalizedUsername.isBlank() -> UiText.Res(R.string.settings_setup_username_required)
            password.isBlank() -> UiText.Res(R.string.settings_setup_password_required)
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
            _events.tryEmit(ServiceSetupEvent.ShowError(UiText.Res(R.string.settings_setup_spotify_need_client_error)))
            return
        }
        _events.tryEmit(ServiceSetupEvent.LaunchSpotifyOAuth(targetProfileId = request.profileId))
    }

    fun commitSpotifyOAuth(result: SpotifyOAuthResult) {
        when (result) {
            SpotifyOAuthResult.Cancelled -> Unit
            is SpotifyOAuthResult.Failure -> _events.tryEmit(ServiceSetupEvent.ShowError(UiText.Raw(result.message)))
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
                        container.profileAvatarStore.put(targetProfileId, result.avatarUrl)
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
                        container.profileAvatarStore.put(created.id, result.avatarUrl)
                        finishWithNewProfile(created.id)
                    }
                } catch (limit: ProfileLimitReachedException) {
                    _events.tryEmit(
                        ServiceSetupEvent.ShowError(
                            UiText.Res(R.string.settings_setup_spotify_account_limit, listOf(limit.limit)),
                        ),
                    )
                } catch (t: Throwable) {
                    _events.tryEmit(
                        ServiceSetupEvent.ShowError(
                            t.message?.let(UiText::Raw) ?: UiText.Res(R.string.settings_setup_spotify_save_failed),
                        ),
                    )
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

    private fun Throwable.toConnectionErrorMessage(): UiText = when (this) {
        is SubsonicException -> message?.let(UiText::Raw) ?: UiText.Res(R.string.settings_setup_server_error)
        is UnknownServiceException -> UiText.Res(R.string.settings_setup_http_blocked)
        is IllegalArgumentException -> UiText.Res(R.string.settings_setup_check_address)
        else -> message?.let(UiText::Raw) ?: UiText.Res(R.string.settings_setup_unreachable)
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
