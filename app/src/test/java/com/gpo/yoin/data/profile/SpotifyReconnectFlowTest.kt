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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyReconnectFlowTest {

    @Test
    fun markSpotifyProfileRevoked_persists_reconnect_state_without_rebuilding_source() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            serverConfigDao = EmptyServerConfigDao,
            activeIdStore = InMemoryActiveIdStore(),
            credentialsStore = credentialsStore,
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )

        val created = manager.create(
            displayName = "Spotify",
            credentials = ProfileCredentials.Spotify(
                accessToken = "a1",
                refreshToken = "r1",
                expiresAtEpochMs = 1L,
                scopes = listOf("user-read-private"),
            ),
        )
        scope.advanceUntilIdle()

        val sourceBefore = manager.activeSource.value
        assertNotNull(sourceBefore)

        manager.markSpotifyProfileRevoked(created.id)
        scope.advanceUntilIdle()

        val sourceAfter = manager.activeSource.value
        assertSame(sourceBefore, sourceAfter)
        val stored = credentialsStore.snapshot(created.id) as ProfileCredentials.Spotify
        assertTrue(stored.revoked)
        scope.cancelChildren()
    }

    private fun CoroutineScope.cancelChildren() {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

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
