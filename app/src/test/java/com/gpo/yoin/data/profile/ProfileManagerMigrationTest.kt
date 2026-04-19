package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
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

/** Covers the remaining pre-3D inline-blob startup migration. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerMigrationTest {

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

}
