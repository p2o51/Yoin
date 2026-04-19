package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import com.gpo.yoin.data.local.ServerConfigDao
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the list of configured [Profile]s and the currently active
 * [MusicSource]. A profile switch rebuilds the source, disposes the old one,
 * and primes the new one (currently: a ping) so UI can block on real work
 * instead of faking a loading animation.
 *
 * v1 only handles Subsonic profiles; Spotify lands in B2 as an additional
 * branch in [buildSource] — no changes needed here.
 */
class ProfileManager(
    private val profileDao: ProfileDao,
    private val serverConfigDao: ServerConfigDao,
    private val activeIdStore: ProfileActiveIdStore,
    private val codec: ProfileCredentialsCodec,
    private val scope: CoroutineScope,
    private val spotifyClientIdProvider: () -> String = { "" },
    private val onSwitchPrepare: suspend () -> Unit = {},
    private val onSwitchCommit: suspend () -> Unit = {},
) {
    val profiles: Flow<List<Profile>> = profileDao.observeAll()

    private val _activeProfileId = MutableStateFlow(activeIdStore.read())
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    val activeProfile: StateFlow<Profile?> =
        combine(profiles, _activeProfileId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    private val _activeSource = MutableStateFlow<MusicSource?>(null)
    val activeSource: StateFlow<MusicSource?> = _activeSource.asStateFlow()

    private val _switchingState = MutableStateFlow<SwitchState>(SwitchState.Idle)
    val switchingState: StateFlow<SwitchState> = _switchingState.asStateFlow()

    init {
        // Build the initial source for whatever profile boot-migration left active.
        scope.launch {
            val initial = getActiveProfileSnapshot()
            if (initial != null) {
                _activeSource.value = buildSource(initial)
            }
        }
    }

    /**
     * Switch the active profile, priming its [MusicSource] (ping +
     * provider-owned warm-up via [MusicSource.prime]) before resolving.
     * Subscribers of [switchingState] see the full progression so UI can show
     * a loading overlay that blocks on real work.
     */
    suspend fun switchTo(id: String) {
        if (_switchingState.value is SwitchState.Switching) return
        val target = profileDao.getById(id) ?: return
        if (_activeProfileId.value == id) return

        val previousSource = _activeSource.value
        var newSource: MusicSource? = null

        _switchingState.value = SwitchState.Switching(id, SwitchState.Stage.Preparing)
        try {
            onSwitchPrepare()

            _switchingState.value = SwitchState.Switching(id, SwitchState.Stage.Connecting)
            newSource = buildSource(target)
                ?: throw IllegalStateException("Profile ${target.displayName} has invalid credentials")

            _switchingState.value = SwitchState.Switching(id, SwitchState.Stage.Priming)
            withTimeoutOrNull(PING_TIMEOUT_MS) { newSource.library().ping() }
                ?: throw IllegalStateException("Server did not respond within ${PING_TIMEOUT_MS / 1000}s")
            // Provider-specific warm-up; cold cache is not a fatal switch error.
            runCatching {
                withTimeoutOrNull(PRIME_TIMEOUT_MS) { newSource.prime() }
            }

            previousSource?.dispose()
            _activeSource.value = newSource
            setActive(id)
            onSwitchCommit()
            _switchingState.value = SwitchState.Idle
        } catch (t: Throwable) {
            if (newSource != null && newSource !== previousSource) {
                newSource.dispose()
            }
            _activeSource.value = previousSource
            _switchingState.value = SwitchState.Error(id, t.message ?: t.toString())
        }
    }

    fun acknowledgeSwitchError() {
        if (_switchingState.value is SwitchState.Error) {
            _switchingState.value = SwitchState.Idle
        }
    }

    suspend fun create(
        displayName: String,
        credentials: ProfileCredentials,
    ): Profile {
        val existing = profileDao.count()
        if (existing >= MAX_PROFILES) {
            throw ProfileLimitReachedException(MAX_PROFILES)
        }
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            provider = credentials.providerId,
            displayName = displayName,
            credentialsJson = codec.encode(credentials),
            createdAt = System.currentTimeMillis(),
        )
        profileDao.upsert(profile)
        if (_activeProfileId.value == null) {
            setActive(profile.id)
            _activeSource.value = buildSource(profile)
        }
        return profile
    }

    suspend fun update(
        id: String,
        displayName: String? = null,
        credentials: ProfileCredentials? = null,
    ) {
        val existing = profileDao.getById(id) ?: return
        val updated = existing.copy(
            provider = credentials?.providerId ?: existing.provider,
            displayName = displayName ?: existing.displayName,
            credentialsJson = credentials?.let(codec::encode) ?: existing.credentialsJson,
        )
        profileDao.upsert(updated)
        if (id == _activeProfileId.value && credentials != null) {
            _activeSource.value?.dispose()
            _activeSource.value = buildSource(updated)
        }
    }

    /**
     * Persist refreshed credentials without rebuilding [activeSource]. Used by
     * sources that rotate their own tokens in-place (e.g. Spotify's access
     * token refresh) — the live source already owns the new secret in memory,
     * Room just catches up.
     */
    suspend fun persistCredentialsSilently(id: String, credentials: ProfileCredentials) {
        val existing = profileDao.getById(id) ?: return
        profileDao.upsert(
            existing.copy(
                credentialsJson = codec.encode(credentials),
                provider = credentials.providerId,
            ),
        )
    }

    suspend fun delete(id: String) {
        val wasActive = _activeProfileId.value == id
        profileDao.deleteById(id)
        if (wasActive) {
            _activeSource.value?.dispose()
            _activeSource.value = null
            val remaining = profileDao.getAll().firstOrNull()
            if (remaining != null) {
                setActive(remaining.id)
                _activeSource.value = buildSource(remaining)
            } else {
                setActive(null)
            }
        }
    }

    fun setActive(id: String?) {
        _activeProfileId.value = id
        activeIdStore.write(id)
    }

    fun decodeCredentials(profile: Profile): ProfileCredentials? = runCatching {
        codec.decode(profile.credentialsJson)
    }.getOrNull()

    suspend fun getActiveProfileSnapshot(): Profile? {
        val activeId = _activeProfileId.value
        return if (activeId != null) {
            profileDao.getById(activeId) ?: profileDao.getAll().firstOrNull()
        } else {
            profileDao.getAll().firstOrNull()
        }
    }

    suspend fun profileCount(): Int = profileDao.count()

    /**
     * First-boot shim: if a legacy single-server `server_config` row exists
     * but `profiles` is empty, create a Subsonic profile from it and make it
     * active. Idempotent.
     */
    suspend fun migrateLegacyServerConfigIfNeeded() {
        if (profileDao.count() > 0) return
        val legacy = serverConfigDao.getActiveServer().firstOrNull() ?: return
        if (legacy.serverUrl.isBlank()) return
        val creds = ProfileCredentials.Subsonic(
            serverUrl = legacy.serverUrl,
            username = legacy.username,
            password = legacy.passwordHash,
        )
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            provider = ProviderKind.SUBSONIC.key,
            displayName = defaultDisplayName(legacy.serverUrl, legacy.username),
            credentialsJson = codec.encode(creds),
            createdAt = System.currentTimeMillis(),
        )
        profileDao.upsert(profile)
        setActive(profile.id)
        _activeSource.value = buildSource(profile)
    }

    private fun buildSource(profile: Profile): MusicSource? {
        val credentials = decodeCredentials(profile) ?: return null
        return when (credentials) {
            is ProfileCredentials.Subsonic -> SubsonicMusicSource(
                credentials = ServerCredentials(
                    serverUrl = credentials.serverUrl,
                    username = credentials.username,
                    password = credentials.password,
                ),
            )
            is ProfileCredentials.Spotify -> com.gpo.yoin.data.source.spotify.SpotifyMusicSource(
                initialCredentials = credentials,
                clientIdProvider = spotifyClientIdProvider,
                onCredentialsPersisted = { refreshed ->
                    scope.launch {
                        persistCredentialsSilently(profile.id, refreshed)
                    }
                },
                onCredentialsRevoked = {
                    scope.launch {
                        markSpotifyProfileRevoked(profile.id)
                    }
                },
            )
        }
    }

    /**
     * Mark a Spotify profile's stored credentials as revoked so the next
     * UI render knows to surface a Reconnect affordance. Decoded blob is
     * re-encoded with `revoked = true` and written back via
     * [persistCredentialsSilently] so the active source isn't rebuilt
     * (the in-memory tokens are already useless either way).
     *
     * No-op if the profile no longer exists, was deleted, or is not a
     * Spotify profile.
     */
    suspend fun markSpotifyProfileRevoked(id: String) {
        val profile = profileDao.getById(id) ?: return
        val credentials = decodeCredentials(profile) as? ProfileCredentials.Spotify
            ?: return
        if (credentials.revoked) return
        persistCredentialsSilently(id, credentials.copy(revoked = true))
    }

    private fun defaultDisplayName(serverUrl: String, username: String): String {
        val host = runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            host != null && username.isNotBlank() -> "$username @ $host"
            host != null -> host
            username.isNotBlank() -> username
            else -> serverUrl
        }
    }

    sealed interface SwitchState {
        data object Idle : SwitchState

        data class Switching(val profileId: String, val stage: Stage) : SwitchState

        data class Error(val profileId: String, val message: String) : SwitchState

        enum class Stage { Preparing, Connecting, Priming }
    }

    companion object {
        const val MAX_PROFILES = 5
        private const val PING_TIMEOUT_MS = 8_000L
        private const val PRIME_TIMEOUT_MS = 8_000L
    }
}

class ProfileLimitReachedException(val limit: Int) :
    Exception("Cannot add more than $limit profiles")
