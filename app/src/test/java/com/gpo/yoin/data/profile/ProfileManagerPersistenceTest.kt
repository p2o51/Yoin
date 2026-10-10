package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            activeIdStore = activeIdStore,
            credentialsStore = credentialsStore,
            legacyCodec = PlaintextProfileCredentialsCodec(),
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
        assertEquals(
            "Room row holds only the marker now; secret lives in the store",
            ProfileManager.STORE_MARKER_V1,
            stored.credentialsJson,
        )
        val updatedCreds = credentialsStore.snapshot(original.id) as ProfileCredentials.Spotify
        assertEquals("store must reflect the new access token", "t2", updatedCreds.accessToken)
        assertEquals("store must reflect the new refresh token", "r2", updatedCreds.refreshToken)
        scope.cancelChildren()
    }

    @Test
    fun update_rebuilds_active_source_when_credentials_change() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val profileDao = InMemoryProfileDao()
        val activeIdStore = InMemoryActiveIdStore()
        val credentialsStore = InMemoryProfileCredentialsStore()
        val manager = ProfileManager(
            profileDao = profileDao,
            activeIdStore = activeIdStore,
            credentialsStore = credentialsStore,
            legacyCodec = PlaintextProfileCredentialsCodec(),
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

    @Test
    fun should_invokeOnProfileDeleted_when_profileDeleted() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val deleted = mutableListOf<String>()
        val manager = manager(scope, onProfileDeleted = { deleted += it })
        manager.create(displayName = "active", credentials = subsonic("a"))
        val other = manager.create(displayName = "other", credentials = subsonic("b"))
        scope.advanceUntilIdle()

        manager.delete(other.id)

        assertEquals(listOf(other.id), deleted)
        scope.cancelChildren()
    }

    @Test
    fun should_runOnProfileDeletedAfterSwitch_when_deletingActiveProfile() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var activeDuringCleanup: String? = null
        lateinit var manager: ProfileManager
        manager = manager(scope, onProfileDeleted = { activeDuringCleanup = manager.activeProfileId.value })
        val active = manager.create(displayName = "active", credentials = subsonic("a"))
        val remaining = manager.create(displayName = "remaining", credentials = subsonic("b"))
        scope.advanceUntilIdle()
        assertEquals(active.id, manager.activeProfileId.value)

        manager.delete(active.id)

        assertEquals(remaining.id, activeDuringCleanup)
        scope.cancelChildren()
    }

    @Test
    fun should_completeDelete_when_onProfileDeletedThrows() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao()
        val manager = manager(scope, profileDao = profileDao, onProfileDeleted = { error("disk full") })
        val profile = manager.create(displayName = "doomed", credentials = subsonic("a"))
        scope.advanceUntilIdle()

        manager.delete(profile.id)

        assertEquals(null, profileDao.getById(profile.id))
        assertEquals(null, manager.activeProfileId.value)
        scope.cancelChildren()
    }

    @Test
    fun should_settleWithoutSource_when_activeCredentialsAreUnreadable() = runTest {
        // The row's marker points at the store, but the secret is gone (a
        // backup restored onto another device): the launch build ends with no
        // source, and says so rather than leave waiters to their timeout.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao()
        profileDao.upsert(
            Profile(
                id = "restored",
                provider = "subsonic",
                displayName = "restored",
                credentialsJson = ProfileManager.STORE_MARKER_V1
            )
        )
        val manager = ProfileManager(
            profileDao = profileDao,
            activeIdStore = InMemoryActiveIdStore().apply { write("restored") },
            credentialsStore = InMemoryProfileCredentialsStore(),
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope
        )
        assertFalse(manager.activeSourceSettled.first())

        scope.advanceUntilIdle()

        assertTrue(manager.activeSourceSettled.first())
        assertNull(manager.activeSource.value)
        scope.cancelChildren()
    }

    private fun manager(
        scope: CoroutineScope,
        profileDao: ProfileDao = InMemoryProfileDao(),
        onProfileDeleted: suspend (String) -> Unit,
    ): ProfileManager = ProfileManager(
        profileDao = profileDao,
        activeIdStore = InMemoryActiveIdStore(),
        credentialsStore = InMemoryProfileCredentialsStore(),
        legacyCodec = PlaintextProfileCredentialsCodec(),
        scope = scope,
        onProfileDeleted = onProfileDeleted,
    )

    private fun subsonic(user: String) = ProfileCredentials.Subsonic(
        serverUrl = "https://example.test",
        username = user,
        password = "pw",
    )

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
}
