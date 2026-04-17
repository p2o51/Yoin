package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.local.ServerConfigDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard: refreshed credentials must persist silently
 * ([ProfileManager.persistCredentialsSilently]) without rebuilding
 * [ProfileManager.activeSource]. In contrast, [ProfileManager.update] with a
 * new credentials bundle still rebuilds the source for the active profile.
 * Those two paths are easy to mix up by accident — this keeps them distinct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerPersistenceTest {

    @Test
    fun persistCredentialsSilently_does_not_rebuild_active_source() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val activeIdStore = InMemoryActiveIdStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = EmptyServerConfigDao,
            activeIdStore = activeIdStore,
            codec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )

        val original = manager.create(
            displayName = "demo",
            credentials = ProfileCredentials.Spotify(
                accessToken = "t1",
                refreshToken = "r1",
                expiresAtEpochMs = 1L,
                scopes = listOf("user-read-private"),
            ),
        )
        scope.advanceUntilIdle()
        val sourceBefore = manager.activeSource.value
        assertNotNull("create() should build the initial source", sourceBefore)

        manager.persistCredentialsSilently(
            id = original.id,
            credentials = ProfileCredentials.Spotify(
                accessToken = "t2",
                refreshToken = "r2",
                expiresAtEpochMs = 999_999L,
                scopes = listOf("user-read-private"),
            ),
        )
        scope.advanceUntilIdle()

        val sourceAfter = manager.activeSource.value
        assertSame("silent persist must not rebuild the active source", sourceBefore, sourceAfter)

        val stored = profileDao.getById(original.id)!!
        assertTrue("Room row must reflect the new token", stored.credentialsJson.contains("t2"))
        scope.cancelChildren()
    }

    @Test
    fun update_rebuilds_active_source_when_credentials_change() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val activeIdStore = InMemoryActiveIdStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = EmptyServerConfigDao,
            activeIdStore = activeIdStore,
            codec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )

        val profile = manager.create(
            displayName = "demo",
            credentials = ProfileCredentials.Subsonic(
                serverUrl = "https://example.test",
                username = "demo",
                password = "pw1",
            ),
        )
        scope.advanceUntilIdle()
        val before = manager.activeSource.value
        assertNotNull(before)

        manager.update(
            id = profile.id,
            credentials = ProfileCredentials.Subsonic(
                serverUrl = "https://example.test",
                username = "demo",
                password = "pw2",
            ),
        )
        scope.advanceUntilIdle()

        val after = manager.activeSource.value
        assertNotNull(after)
        assertEquals(
            "update() should replace the source instance",
            false,
            before === after,
        )
        scope.cancelChildren()
    }

    private fun CoroutineScope.cancelChildren() {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
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

    private object EmptyServerConfigDao : ServerConfigDao {
        override fun getActiveServer(): Flow<ServerConfig?> = MutableStateFlow(null).asStateFlow()
        override suspend fun insert(config: ServerConfig) = Unit
        override suspend fun update(config: ServerConfig) = Unit
        override suspend fun deleteAll() = Unit
    }
}
