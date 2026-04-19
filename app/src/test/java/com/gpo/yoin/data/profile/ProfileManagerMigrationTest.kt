package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.local.ServerConfigDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Batch 3D startup migration:
 *
 *  1. Pre-multi-profile: legacy `server_config` row → Subsonic profile
 *     with credentials in the file store + `STORE_MARKER_V1` row.
 *  2. Pre-3D multi-profile: inline `credentialsJson` blobs → file store
 *     + marker, idempotent across launches.
 *  3. After (1) succeeds the legacy `server_config` row is wiped so the
 *     plaintext password doesn't linger in the database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerMigrationTest {

    @Test
    fun legacy_server_config_is_migrated_into_profile_then_cleared() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val serverConfigDao = InMemoryServerConfigDao().apply {
            insert(
                ServerConfig(
                    serverUrl = "https://music.example",
                    username = "alice",
                    passwordHash = "hunter2",
                    isActive = true,
                ),
            )
        }
        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = serverConfigDao,
            activeIdStore = InMemoryActiveIdStore(),
            credentialsStore = credentialsStore,
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )

        manager.runStartupMigrations()
        scope.advanceUntilIdle()

        val profile = profileDao.getAll().single()
        assertEquals(
            "row should be on the Batch 3D marker, no inline JSON",
            ProfileManager.STORE_MARKER_V1,
            profile.credentialsJson,
        )
        val moved = credentialsStore.snapshot(profile.id) as ProfileCredentials.Subsonic
        assertEquals("https://music.example", moved.serverUrl)
        assertEquals("hunter2", moved.password)
        assertNull(
            "server_config must be cleared so the plaintext password isn't preserved",
            serverConfigDao.currentValue,
        )
    }

    @Test
    fun pre_3D_inline_blob_is_moved_into_store() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val codec = PlaintextProfileCredentialsCodec()
        val original = ProfileCredentials.Spotify(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            expiresAtEpochMs = 1L,
            scopes = listOf("user-read-private"),
        )
        val inlineProfile = Profile(
            id = "inline-id",
            provider = ProviderKind.SPOTIFY.key,
            displayName = "Legacy",
            credentialsJson = codec.encode(original),
        )
        profileDao.upsert(inlineProfile)

        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = InMemoryServerConfigDao(), // empty
            activeIdStore = InMemoryActiveIdStore(),
            credentialsStore = credentialsStore,
            legacyCodec = codec,
            scope = scope,
        )

        manager.runStartupMigrations()
        scope.advanceUntilIdle()

        val migrated = profileDao.getById("inline-id")!!
        assertEquals(ProfileManager.STORE_MARKER_V1, migrated.credentialsJson)
        assertEquals(original, credentialsStore.snapshot("inline-id"))
    }

    @Test
    fun migration_is_idempotent_on_repeated_calls() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val credentialsStore = InMemoryProfileCredentialsStore()
        // Profile that's already on the marker — must NOT be re-migrated
        // (would require a decode of a marker string, which would fail).
        profileDao.upsert(
            Profile(
                id = "already-migrated",
                provider = ProviderKind.SUBSONIC.key,
                displayName = "Already migrated",
                credentialsJson = ProfileManager.STORE_MARKER_V1,
            ),
        )
        credentialsStore.write(
            "already-migrated",
            ProfileCredentials.Subsonic("a", "b", "c"),
        )
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = InMemoryServerConfigDao(),
            activeIdStore = InMemoryActiveIdStore(),
            credentialsStore = credentialsStore,
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )

        manager.runStartupMigrations()
        scope.advanceUntilIdle()
        manager.runStartupMigrations() // second pass — should be a no-op
        scope.advanceUntilIdle()

        val row = profileDao.getById("already-migrated")!!
        assertEquals(ProfileManager.STORE_MARKER_V1, row.credentialsJson)
        assertEquals(1, credentialsStore.size)
    }

    @Test
    fun corrupt_inline_blob_is_left_alone_for_other_rows_to_migrate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val codec = PlaintextProfileCredentialsCodec()
        val good = ProfileCredentials.Subsonic("https://x.test", "u", "p")
        profileDao.upsert(
            Profile(id = "good", provider = ProviderKind.SUBSONIC.key, displayName = "Good", credentialsJson = codec.encode(good)),
        )
        profileDao.upsert(
            Profile(id = "broken", provider = ProviderKind.SUBSONIC.key, displayName = "Broken", credentialsJson = "not-valid-json"),
        )
        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = InMemoryServerConfigDao(),
            activeIdStore = InMemoryActiveIdStore(),
            credentialsStore = credentialsStore,
            legacyCodec = codec,
            scope = scope,
        )

        manager.runStartupMigrations()
        scope.advanceUntilIdle()

        val goodAfter = profileDao.getById("good")!!
        assertEquals(ProfileManager.STORE_MARKER_V1, goodAfter.credentialsJson)
        assertEquals(good, credentialsStore.snapshot("good"))

        val brokenAfter = profileDao.getById("broken")!!
        assertTrue(
            "broken row should not be touched — UI surfaces it as missing",
            brokenAfter.credentialsJson == "not-valid-json",
        )
        assertNull(credentialsStore.snapshot("broken"))
    }

    // ── fakes ──────────────────────────────────────────────────────────

    private class InMemoryProfileDao : ProfileDao {
        private val byId = linkedMapOf<String, Profile>()
        private val flow = MutableStateFlow<List<Profile>>(emptyList())

        override fun observeAll(): Flow<List<Profile>> = flow.asStateFlow()
        override suspend fun getAll(): List<Profile> = byId.values.toList()
        override suspend fun getById(id: String): Profile? = byId[id]
        override suspend fun count(): Int = byId.size
        override suspend fun upsert(profile: Profile) {
            byId[profile.id] = profile
            flow.value = byId.values.toList()
        }
        override suspend fun delete(profile: Profile) {
            byId.remove(profile.id)
            flow.value = byId.values.toList()
        }
        override suspend fun deleteById(id: String) {
            byId.remove(id)
            flow.value = byId.values.toList()
        }
    }

    private class InMemoryActiveIdStore : ProfileActiveIdStore {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(id: String?) {
            value = id
        }
    }

    private class InMemoryServerConfigDao : ServerConfigDao {
        private val state = MutableStateFlow<ServerConfig?>(null)
        val currentValue: ServerConfig? get() = state.value
        override fun getActiveServer(): Flow<ServerConfig?> = state.asStateFlow()
        override suspend fun insert(config: ServerConfig) { state.value = config }
        override suspend fun update(config: ServerConfig) { state.value = config }
        override suspend fun deleteAll() { state.value = null }
    }
}
