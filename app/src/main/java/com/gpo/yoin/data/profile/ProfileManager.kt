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
 * Storage layout (Batch 3D):
 * - Room `profiles` table holds **metadata + a marker** in
 *   `credentialsJson`. The marker is [STORE_MARKER_V1] (`"store:v1"`)
 *   for any profile whose secret has been moved into the encrypted
 *   on-disk store.
 * - Per-profile secrets live in [credentialsStore], a file-backed
 *   store under `noBackupFilesDir` so secrets aren't sucked into
 *   Android's auto-backup.
 * - [legacyCodec] is used **only** by the one-shot startup migration
 *   that thaws inline blobs persisted by pre-3D builds and re-writes
 *   them through the store. After that migration runs, normal
 *   read/write traffic never touches the legacy codec.
 */
class ProfileManager(
    private val profileDao: ProfileDao,
    private val serverConfigDao: ServerConfigDao,
    private val activeIdStore: ProfileActiveIdStore,
    private val credentialsStore: ProfileCredentialsStore,
    private val legacyCodec: ProfileCredentialsCodec,
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
        val profileId = UUID.randomUUID().toString()
        // Write the secret first — if Room insert fails after, we're left
        // with an orphan `.bin` (cleaned up next launch when it doesn't
        // match any profile id), not a Room row pointing at nothing.
        credentialsStore.write(profileId, credentials)
        val profile = Profile(
            id = profileId,
            provider = credentials.providerId,
            displayName = displayName,
            credentialsJson = STORE_MARKER_V1,
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
        if (credentials != null) {
            credentialsStore.write(id, credentials)
        }
        val updated = existing.copy(
            provider = credentials?.providerId ?: existing.provider,
            displayName = displayName ?: existing.displayName,
            credentialsJson = if (credentials != null) STORE_MARKER_V1 else existing.credentialsJson,
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
     * the encrypted file just catches up.
     */
    suspend fun persistCredentialsSilently(id: String, credentials: ProfileCredentials) {
        val existing = profileDao.getById(id) ?: return
        credentialsStore.write(id, credentials)
        // If the row is still on the legacy inline marker (never migrated),
        // promote it to `store:v1` here as a side effect — token refresh
        // is the most common write path and a natural migration moment.
        if (existing.credentialsJson != STORE_MARKER_V1 ||
            existing.provider != credentials.providerId
        ) {
            profileDao.upsert(
                existing.copy(
                    credentialsJson = STORE_MARKER_V1,
                    provider = credentials.providerId,
                ),
            )
        }
    }

    suspend fun delete(id: String) {
        val wasActive = _activeProfileId.value == id
        profileDao.deleteById(id)
        // Drop the encrypted file too — leaving it would leak the secret
        // forever on disk under a profile id no Room row references.
        credentialsStore.delete(id)
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

    /**
     * Read credentials for a profile.
     *
     * Branches on the row's `credentialsJson`:
     * - [STORE_MARKER_V1] → primary path, read from [credentialsStore].
     *   `null` means "marker says secrets exist but the file's missing"
     *   (typical post-restore case) — caller surfaces as a recoverable
     *   missing-credentials state.
     * - Anything else → legacy inline blob from a pre-3D build. Decoded
     *   via [legacyCodec]; the next mutation through [create] /
     *   [update] / [persistCredentialsSilently] migrates it into the
     *   store and rewrites the row to the marker. The startup migration
     *   in [migrateInlineCredentialsIfNeeded] does this proactively.
     *
     * Returns `null` for any unrecoverable failure (corrupt file,
     * undecryptable, malformed legacy JSON) so callers can render
     * "credentials unavailable" without crashing.
     */
    fun decodeCredentials(profile: Profile): ProfileCredentials? = runCatching {
        if (profile.credentialsJson == STORE_MARKER_V1) {
            credentialsStore.read(profile.id)
        } else {
            legacyCodec.decode(profile.credentialsJson)
        }
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
     * One-shot startup migration. Runs three steps in order:
     *
     *  1. Legacy `server_config` → Subsonic profile (pre-multi-profile
     *     installs). Skipped when `profiles` already has rows.
     *  2. Inline `credentialsJson` blobs (pre-3D installs) → encrypted
     *     file store + `STORE_MARKER_V1` row. Idempotent — rows already
     *     on the marker are skipped.
     *  3. Clear `server_config` so the next launch doesn't keep the
     *     plaintext password row alive after we've copied it forward.
     *
     * All-in-one method so callers don't have to think about ordering.
     * Crash mid-migration is recoverable — each step is idempotent on
     * the next launch.
     */
    suspend fun runStartupMigrations() {
        migrateLegacyServerConfigIfNeeded()
        migrateInlineCredentialsIfNeeded()
        clearLegacyServerConfig()
    }

    private suspend fun migrateLegacyServerConfigIfNeeded() {
        if (profileDao.count() > 0) return
        val legacy = serverConfigDao.getActiveServer().firstOrNull() ?: return
        if (legacy.serverUrl.isBlank()) return
        val creds = ProfileCredentials.Subsonic(
            serverUrl = legacy.serverUrl,
            username = legacy.username,
            password = legacy.passwordHash,
        )
        val profileId = UUID.randomUUID().toString()
        credentialsStore.write(profileId, creds)
        val profile = Profile(
            id = profileId,
            provider = ProviderKind.SUBSONIC.key,
            displayName = defaultDisplayName(legacy.serverUrl, legacy.username),
            credentialsJson = STORE_MARKER_V1,
            createdAt = System.currentTimeMillis(),
        )
        profileDao.upsert(profile)
        setActive(profile.id)
        _activeSource.value = buildSource(profile)
    }

    /**
     * Pull pre-3D inline credential blobs out of `profiles.credentialsJson`
     * and into [credentialsStore]. Idempotent: rows already on
     * [STORE_MARKER_V1] are skipped; rows whose blob can't be decoded
     * are left alone (UI surfaces them as "credentials unavailable" via
     * [decodeCredentials] returning null) so a single corrupt row
     * doesn't poison the rest of the migration.
     */
    private suspend fun migrateInlineCredentialsIfNeeded() {
        for (profile in profileDao.getAll()) {
            if (profile.credentialsJson == STORE_MARKER_V1) continue
            val decoded = runCatching { legacyCodec.decode(profile.credentialsJson) }.getOrNull()
                ?: continue
            credentialsStore.write(profile.id, decoded)
            profileDao.upsert(profile.copy(credentialsJson = STORE_MARKER_V1))
        }
    }

    /**
     * Wipe the legacy `server_config` table once the migration has
     * forwarded the password into a profile. Keeping the row would
     * leave a plaintext Subsonic password sitting in the database
     * forever — exactly the leak Batch 3D is here to close.
     */
    private suspend fun clearLegacyServerConfig() {
        serverConfigDao.deleteAll()
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

        /**
         * Sentinel value persisted in `Profile.credentialsJson` when the
         * profile's secrets have been moved to [credentialsStore].
         * Pre-3D rows hold an inline JSON blob; post-migration rows hold
         * exactly this string. Public so tests and DAO assertions can
         * reference it without re-deriving the literal.
         */
        const val STORE_MARKER_V1 = "store:v1"

        private const val PING_TIMEOUT_MS = 8_000L
        private const val PRIME_TIMEOUT_MS = 8_000L
    }
}

class ProfileLimitReachedException(val limit: Int) :
    Exception("Cannot add more than $limit profiles")
