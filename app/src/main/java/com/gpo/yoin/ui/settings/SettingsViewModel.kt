package com.gpo.yoin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.NeoDBConfig
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.integration.neodb.NeoDBOAuthResult
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import java.net.URI
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

    private val geminiConfigFlow: StateFlow<GeminiConfig> =
        database.geminiConfigDao().getConfig()
            .map {
                it?.copy(
                    targetLanguage = GeminiConfig.normalizeTargetLanguage(it.targetLanguage),
                ) ?: GeminiConfig(apiKey = "")
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, GeminiConfig(apiKey = ""))

    /**
     * NeoDB 的 instance + token bundle。Instance 走 Room flow；token 走
     * [NeoDbTokenStore]（加密文件，不走备份）—— 文件 IO 没有 observable
     * 流，所以用 MutableStateFlow 在 save/clear 时显式刷。
     */
    private data class NeoDBSettingsBundle(
        val instance: String,
        val accessToken: String,
    )

    private val _neoDbTokenCache = MutableStateFlow(container.neoDbTokenStore.readToken().orEmpty())

    private val neoDbSettingsBundleFlow: StateFlow<NeoDBSettingsBundle> = combine(
        database.neoDbConfigDao().observe()
            .map { cfg -> cfg?.instance ?: NeoDBConfig.DEFAULT_INSTANCE },
        _neoDbTokenCache,
    ) { instance, token ->
        NeoDBSettingsBundle(instance = instance, accessToken = token)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        NeoDBSettingsBundle(NeoDBConfig.DEFAULT_INSTANCE, ""),
    )

    // Gemini key + NeoDB config 合并成一条 pair flow，腾出 combine 位子给
    // NeoDB section 而不用退到 Array 变长 combine。
    private data class MiscSettingsBundle(
        val geminiApiKey: String,
        val geminiTargetLanguage: String,
        val neoDb: NeoDBSettingsBundle,
    )

    private val miscSettingsBundleFlow: StateFlow<MiscSettingsBundle> = combine(
        geminiConfigFlow,
        neoDbSettingsBundleFlow,
    ) { geminiConfig, neoDb ->
        MiscSettingsBundle(
            geminiApiKey = geminiConfig.apiKey,
            geminiTargetLanguage = GeminiConfig.normalizeTargetLanguage(geminiConfig.targetLanguage),
            neoDb = neoDb,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            MiscSettingsBundle(
                "",
                GeminiConfig.DEFAULT_TARGET_LANGUAGE,
                NeoDBSettingsBundle(NeoDBConfig.DEFAULT_INSTANCE, ""),
            ),
        )

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(profileManager.profiles, container.profileAvatarStore.urls, ::Pair),
        profileManager.activeProfileId,
        cacheSizeFlow,
        miscSettingsBundleFlow,
        // Runtime Spotify status drives the per-card badges (No Client ID,
        // Premium, ...).
        container.spotifyProviderStatus,
    ) { (profiles, avatarUrls), activeId, cacheSize, misc, spotifyStatus ->
        val resolvedActiveId = activeId ?: profiles.firstOrNull()?.id
        val avatarShapes = assignAvatarShapes(profiles.map { it.id to it.createdAt })
        SettingsUiState.Content(
            profileCards = profiles.map {
                it.toCard(
                    activeProfileId = resolvedActiveId,
                    spotifyStatus = spotifyStatus,
                    avatarShape = avatarShapes[it.id] ?: 0,
                ).copy(photoUrl = avatarUrls[it.id])
            },
            activeProfileId = resolvedActiveId,
            canAddProfile = profiles.size < ProfileManager.MAX_PROFILES,
            cacheSizeBytes = cacheSize,
            geminiApiKey = misc.geminiApiKey,
            geminiTargetLanguage = misc.geminiTargetLanguage,
            neoDbInstance = misc.neoDb.instance,
            neoDbAccessToken = misc.neoDb.accessToken,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState.Loading)

    val switchingState: StateFlow<ProfileManager.SwitchState> = profileManager.switchingState

    init {
        // Keep the account in use's Spotify picture fresh (it can change, and
        // accounts added before pictures were kept have none yet). Only the
        // active profile has a live, refreshing token to ask with.
        viewModelScope.launch {
            val source = profileManager.activeSource.first { it != null } as? SpotifyMusicSource
                ?: return@launch
            val profileId = profileManager.activeProfileId.value ?: return@launch
            runCatching { source.profilePictureUrl() }
                .onSuccess { url -> container.profileAvatarStore.put(profileId, url) }
        }
    }

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

    // ── Add-account sheet ─────────────────────────────────────────────
    // Connecting / editing / reconnecting lives on the service setup page
    // (ServiceSetupViewModel); Settings only lists, switches and removes.

    fun showProviderPicker() {
        _providerPickerState.value = ProviderPickerState(visible = true)
    }

    fun hideProviderPicker() {
        _providerPickerState.value = ProviderPickerState(visible = false)
    }

    private fun emitEvent(event: SettingsOneShotEvent) {
        _events.tryEmit(event)
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
            container.profileAvatarStore.remove(pending.profileId)
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
            val current = database.geminiConfigDao().getConfig().first()
            database.geminiConfigDao().upsert(
                (current ?: GeminiConfig(apiKey = "")).copy(apiKey = key.trim()),
            )
        }
    }

    fun saveGeminiTargetLanguage(language: String) {
        val normalized = GeminiConfig.normalizeTargetLanguage(language)
        viewModelScope.launch {
            val current = database.geminiConfigDao().getConfig().first()
            val existing = current ?: GeminiConfig(apiKey = "")
            if (GeminiConfig.normalizeTargetLanguage(existing.targetLanguage) == normalized) return@launch
            database.geminiConfigDao().upsert(existing.copy(targetLanguage = normalized))
            database.songAboutEntryDao().deleteAll()
        }
    }

    /**
     * 保存 NeoDB BYOK 配置。Instance 落 Room；token 走 [NeoDbTokenStore]
     * （加密文件，不进云备份）。
     *
     * instance 留空时回 [NeoDBConfig.DEFAULT_INSTANCE]，省得用户把默认
     * 实例不小心清掉。
     */
    fun saveNeoDbConfig(instance: String, accessToken: String) {
        viewModelScope.launch {
            val normalizedInstance = normalizeNeoDbInstance(instance) ?: return@launch
            val normalizedToken = accessToken.trim()
            database.neoDbConfigDao().upsert(
                NeoDBConfig(instance = normalizedInstance),
            )
            container.neoDbTokenStore.writeToken(normalizedToken)
            _neoDbTokenCache.value = normalizedToken
        }
    }

    /**
     * 先记住用户输入的 instance，再打开该实例的网页登录页。这里落到
     * NeoDB/Mastodon OAuth 授权页。成功后回调到 App，自动把 access
     * token 写回本地加密存储，不再需要手动复制粘贴。
     */
    fun openNeoDbSignIn(instance: String) {
        viewModelScope.launch {
            val normalizedInstance = normalizeNeoDbInstance(instance) ?: return@launch
            database.neoDbConfigDao().upsert(NeoDBConfig(instance = normalizedInstance))
            emitEvent(SettingsOneShotEvent.LaunchNeoDbOAuth(normalizedInstance))
        }
    }

    fun commitNeoDbOAuth(result: NeoDBOAuthResult) {
        when (result) {
            NeoDBOAuthResult.Cancelled -> Unit
            is NeoDBOAuthResult.Failure ->
                emitEvent(SettingsOneShotEvent.ShowError(result.message))
            is NeoDBOAuthResult.Success ->
                saveNeoDbConfig(result.instance, result.accessToken)
        }
    }

    /** 「登出 NeoDB」—— 清空 token 但保留 instance，省得用户下次登录又填一遍。 */
    fun clearNeoDbToken() {
        viewModelScope.launch {
            container.neoDbTokenStore.clear()
            _neoDbTokenCache.value = ""
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            database.cacheMetadataDao().deleteOldest(0)
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private fun Profile.toCard(
        activeProfileId: String?,
        spotifyStatus: SpotifyProviderStatus,
        avatarShape: Int,
    ): ProfileCard {
        val provider = ProviderKind.fromKeyOrSubsonic(provider)
        // Subsonic: lead with the username, the server's host goes on the
        // service line ("Subsonic · host"). Other services have no "where".
        val subsonic = if (provider == ProviderKind.SUBSONIC) {
            profileManager.decodeCredentials(this) as? ProfileCredentials.Subsonic
        } else {
            null
        }
        val subtitle = subsonic?.let {
            runCatching { URI(it.serverUrl).host }.getOrNull()?.takeIf { host -> host.isNotBlank() }
        }
        val title = subsonic?.username?.takeIf { it.isNotBlank() } ?: displayName
        // Per-Spotify-profile scope drift (legacy profile missing newly-
        // required scopes) is a static credential check that
        // [SpotifyProviderStatus] doesn't capture — it describes the
        // *runtime* backend. Combine the two: runtime status first (a
        // global blocker like missing client id is more urgent than a
        // per-profile reconnect), then per-profile scope check.
        val needsCredentialsReentry = profileHasMissingSubsonicCredentials()
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
            ProviderKind.SUBSONIC -> when {
                needsCredentialsReentry -> "Credentials missing"
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
            title = title,
            avatarShape = avatarShape,
            unavailableReason = unavailableReason,
            requiresReconnect = provider == ProviderKind.SPOTIFY &&
                unavailableReason == "Reconnect",
            requiresCredentialsReentry = needsCredentialsReentry,
        )
    }

    private fun Profile.profileRequiresSpotifyReconnect(): Boolean {
        if (ProviderKind.fromKeyOrSubsonic(provider) != ProviderKind.SPOTIFY) return false
        val decoded = profileManager.decodeCredentials(this)
        // Three independent reasons a Spotify profile needs the user to redo
        // the OAuth flow:
        //   1. Credentials unavailable: either the Batch 3D file-backed
        //      secret is missing (post-restore / interrupted write) or a
        //      legacy inline blob is corrupt. Recover by running OAuth
        //      again — same profile id, new token.
        //   2. Runtime token revocation — the refresh endpoint last responded
        //      with `error: "invalid_grant"`, persisted as `revoked = true`.
        //   3. Static scope drift — the profile was authorised before the
        //      current `REQUIRED_SCOPES` set existed (e.g. before
        //      `playlist-modify-*` was added). Any missing required scope
        //      means the next API call against that scope will 403.
        if (decoded == null) return true
        val spotify = decoded as? ProfileCredentials.Spotify ?: return false
        if (spotify.revoked) return true
        return SpotifyAuthConfig.REQUIRED_SCOPES.any { required -> required !in spotify.scopes }
    }

    /**
     * Subsonic analogue to the Spotify recovery case. This covers both
     * post-restore missing files and pre-3D inline blobs that are now
     * corrupt / undecodable. UI surfaces "Credentials missing" and taps
     * into the edit form so the user can re-enter URL + username +
     * password against the same profile id.
     */
    private fun Profile.profileHasMissingSubsonicCredentials(): Boolean {
        if (ProviderKind.fromKeyOrSubsonic(provider) != ProviderKind.SUBSONIC) return false
        return profileManager.decodeCredentials(this) == null
    }

    private fun normalizeNeoDbInstance(instance: String): String? {
        val raw = instance.trim().trimEnd('/')
            .takeIf(String::isNotEmpty)
            ?: NeoDBConfig.DEFAULT_INSTANCE
        val candidate = if ("://" in raw) raw else "https://$raw"
        val normalized = runCatching {
            val uri = URI(candidate)
            require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
            uri.resolve("/").toString().trimEnd('/')
        }.getOrNull()
        if (normalized == null) {
            emitEvent(SettingsOneShotEvent.ShowError("Invalid NeoDB URL. Include a valid host."))
        }
        return normalized
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
