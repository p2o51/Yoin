package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one "needs reconnect" signal for the account in use
 * ([ProfileManager.activeNeedsReconnect], owner 2026-10-11) — what raises it,
 * what clears it — and Home's Reconnect ([ProfileManager.retryActiveSource]):
 * one more read of the credentials and one ping, published only when the
 * server answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerReconnectTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_needNoReconnect_when_noAccountIsSetUp() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = manager(scope)

        scope.advanceUntilIdle()

        assertFalse(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needReconnect_when_theSourceSettlesWithoutComing() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED)

        scope.advanceUntilIdle()

        // Settled with no source: its credentials couldn't be read.
        assertNull(manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needNoReconnectYet_when_theLaunchBuildIsStillUnderWay() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        // The launch build waits on its read of the account in use.
        profileDao.holdReads = CompletableDeferred()
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED)

        scope.runCurrent()

        // The account is known, its source isn't yet: no dot (a cold start never blinks one).
        assertEquals(RESTORED, manager.activeProfile.value?.id)
        assertFalse(manager.activeSourceSettled.first())
        assertFalse(manager.activeNeedsReconnect.value)

        profileDao.holdReads?.complete(Unit)
        scope.advanceUntilIdle()

        // Built without a source: now it is known.
        assertNull(manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needNoReconnectYet_when_aDeleteHandsOverToAnAccountWhoseBuildIsUnderWay() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryProfileCredentialsStore()
        val profileDao = InMemoryProfileDao().apply {
            upsert(readableRow(IN_USE, store))
            upsert(unreadableRow())
        }
        val manager = manager(scope, profileDao = profileDao, activeId = IN_USE, store = store)
        scope.advanceUntilIdle()
        assertNotNull(manager.activeSource.value)
        assertFalse(manager.activeNeedsReconnect.value)

        // Delete the account in use: the hand-over to the next one waits on its read.
        profileDao.holdReads = CompletableDeferred()
        scope.launch { manager.delete(IN_USE) }
        scope.runCurrent()

        assertEquals(RESTORED, manager.activeProfile.value?.id)
        assertNull(manager.activeSource.value)
        assertFalse(manager.activeSourceSettled.first())
        assertFalse(manager.activeNeedsReconnect.value)

        profileDao.holdReads?.complete(Unit)
        scope.advanceUntilIdle()

        // The next account's credentials couldn't be read: now it needs its owner.
        assertNull(manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needNoReconnect_when_theAccountsCredentialsRead() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = manager(scope)

        manager.create("qa", subsonic("https://music.example.test"))
        scope.advanceUntilIdle()

        assertNotNull(manager.activeSource.value)
        assertFalse(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needReconnect_when_spotifyRevokesTheGrant() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = manager(scope)
        val created = manager.create("Spotify", spotify(SpotifyAuthConfig.REQUIRED_SCOPES.toList()))
        scope.advanceUntilIdle()
        assertFalse(manager.activeNeedsReconnect.value)

        // The refresh endpoint said invalid_grant: the row is unchanged, the secret isn't.
        manager.markSpotifyProfileRevoked(created.id)
        scope.advanceUntilIdle()

        assertTrue(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_needReconnect_when_theSpotifyGrantLacksARequiredScope() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = manager(scope)

        manager.create("Spotify", spotify(listOf("user-read-private")))
        scope.advanceUntilIdle()

        assertNotNull(manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_clearReconnect_when_theCredentialsAreEnteredAgain() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED)
        scope.advanceUntilIdle()
        assertTrue(manager.activeNeedsReconnect.value)

        // The account's page: the same profile id, its credentials again.
        manager.update(RESTORED, credentials = subsonic("https://music.example.test"))
        scope.advanceUntilIdle()

        assertNotNull(manager.activeSource.value)
        assertFalse(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_clearReconnect_when_theBrokenAccountIsDeleted() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED)
        manager.create("qa", subsonic("https://music.example.test"))
        scope.advanceUntilIdle()
        assertTrue(manager.activeNeedsReconnect.value)

        manager.delete(RESTORED)
        scope.advanceUntilIdle()

        // The next account is in use, and its credentials read.
        assertNotNull(manager.activeSource.value)
        assertFalse(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_buildTheSourceAndClearReconnect_when_theRetryReadsTheCredentialsAndTheServerAnswers() = runTest {
        server.enqueue(MockResponse().setBody(PING_OK))
        server.start()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = LockableCredentialsStore()
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        store.inner.write(RESTORED, subsonic(server.url("/").toString()))
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED, store = store)
        scope.advanceUntilIdle()
        assertTrue(manager.activeNeedsReconnect.value)

        // The store reads again (it was only locked at launch).
        store.locked = false
        val fixed = withContext(Dispatchers.Default) { manager.retryActiveSource() }

        assertTrue(fixed)
        // Already clear as the retry returns: Home's dot goes with the answer.
        assertFalse(manager.activeNeedsReconnect.value)
        assertNotNull(manager.activeSource.value)
        assertEquals("/rest/ping.view", server.takeRequest().requestUrl?.encodedPath)
        // One ping, nothing else.
        assertEquals(1, server.requestCount)
        // Fanned out once, as a switch is: Library, Memories and the widgets read again.
        assertEquals(1, commits)
        scope.advanceUntilIdle()
        assertFalse(manager.activeNeedsReconnect.value)
        scope.cancelChildren()
    }

    @Test
    fun should_publishTheSourceAsAColdStartWould_when_theCredentialsReadButTheServerDoesNotAnswer() = runTest {
        // The server's down (or the phone offline): nothing listens there.
        val gone = MockWebServer().apply { start() }
        val unreachable = gone.url("/").toString()
        gone.shutdown()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = LockableCredentialsStore()
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        store.inner.write(RESTORED, subsonic(unreachable))
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED, store = store)
        scope.advanceUntilIdle()
        assertTrue(manager.activeNeedsReconnect.value)

        store.locked = false
        val fixed = withContext(Dispatchers.Default) { manager.retryActiveSource() }

        // The credentials are fine: no page to re-enter them. Home and Library
        // tell their own network errors, as after a cold start offline.
        assertTrue(fixed)
        assertNotNull(manager.activeSource.value)
        assertFalse(manager.activeNeedsReconnect.value)
        assertEquals(1, commits)
        scope.cancelChildren()
    }

    @Test
    fun should_keepReconnectAndAskNothing_when_theRetryStillCannotReadTheCredentials() = runTest {
        server.start()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = LockableCredentialsStore()
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        store.inner.write(RESTORED, subsonic(server.url("/").toString()))
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED, store = store)
        scope.advanceUntilIdle()

        val fixed = manager.retryActiveSource()

        assertFalse(fixed)
        assertTrue(manager.activeNeedsReconnect.value)
        assertNull(manager.activeSource.value)
        assertEquals(0, server.requestCount)
        assertEquals(0, commits)
        scope.cancelChildren()
    }

    @Test
    fun should_publishNoSource_when_theServerRefusesTheRetrysPing() = runTest {
        server.enqueue(MockResponse().setBody(PING_WRONG_PASSWORD))
        server.start()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = LockableCredentialsStore()
        val profileDao = InMemoryProfileDao().apply { upsert(unreadableRow()) }
        store.inner.write(RESTORED, subsonic(server.url("/").toString()))
        val manager = manager(scope, profileDao = profileDao, activeId = RESTORED, store = store)
        scope.advanceUntilIdle()

        store.locked = false
        val fixed = withContext(Dispatchers.Default) { manager.retryActiveSource() }

        // Read, but the server won't have them: the account's page is the way on.
        assertFalse(fixed)
        assertNull(manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        // Nothing changed for Library and the rest.
        assertEquals(0, commits)
        scope.cancelChildren()
    }

    @Test
    fun should_leaveTheSourceAlone_when_theRetryFindsItUp() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = manager(scope)
        val created = manager.create("Spotify", spotify(SpotifyAuthConfig.REQUIRED_SCOPES.toList()))
        manager.markSpotifyProfileRevoked(created.id)
        scope.advanceUntilIdle()
        val source = manager.activeSource.value

        val fixed = manager.retryActiveSource()

        // Revoked: no retry fixes that, and the source in use isn't rebuilt.
        assertFalse(fixed)
        assertSame(source, manager.activeSource.value)
        assertTrue(manager.activeNeedsReconnect.value)
        assertEquals(0, commits)
        scope.cancelChildren()
    }

    @Test
    fun should_refuseCredentials_when_theServiceTurnsThemDown() {
        assertTrue(SubsonicException(code = 40, message = "Wrong username or password").refusesCredentials())
        assertTrue(SubsonicException(code = 41, message = "Token authentication not supported").refusesCredentials())
        val revoked = SpotifyAuthException(code = 400, message = "refresh", oauthError = "invalid_grant")
        assertTrue(revoked.refusesCredentials())
        assertTrue(SpotifyAuthException(code = 401, message = "me").refusesCredentials())
        assertTrue(AppleMusicApiException(AppleMusicApiFailure.UserAuthorizationRequired).refusesCredentials())
        assertTrue(AppleMusicApiException(AppleMusicApiFailure.AccessDenied).refusesCredentials())
    }

    @Test
    fun should_notRefuseCredentials_when_theServerJustDoesNotAnswer() {
        assertFalse(IOException("unreachable").refusesCredentials())
        assertFalse(SubsonicException(code = 0, message = "Generic error").refusesCredentials())
        assertFalse(SpotifyAuthException(code = 503, message = "me").refusesCredentials())
        assertFalse(AppleMusicApiException(AppleMusicApiFailure.Http(500)).refusesCredentials())
        assertFalse(AppleMusicApiException(AppleMusicApiFailure.RateLimited).refusesCredentials())
    }

    // ProfileManager's onSwitchCommit (AppContainer: notifyMusicConfigurationChanged), counted.
    private var commits = 0

    private fun manager(
        scope: CoroutineScope,
        profileDao: ProfileDao = InMemoryProfileDao(),
        activeId: String? = null,
        store: ProfileCredentialsStore = InMemoryProfileCredentialsStore()
    ): ProfileManager = ProfileManager(
        profileDao = profileDao,
        activeIdStore = InMemoryActiveIdStore().apply { write(activeId) },
        credentialsStore = store,
        legacyCodec = PlaintextProfileCredentialsCodec(),
        scope = scope,
        onSwitchCommit = { commits++ }
    )

    /** A row whose credentials [store] reads (a Subsonic account on a server nobody asks). */
    private fun readableRow(id: String, store: ProfileCredentialsStore): Profile {
        store.write(id, subsonic("https://music.example.test"))
        return Profile(
            id = id,
            provider = "subsonic",
            displayName = id,
            credentialsJson = ProfileManager.STORE_MARKER_V1,
            createdAt = 0L
        )
    }

    private fun unreadableRow() = Profile(
        id = RESTORED,
        provider = "subsonic",
        displayName = RESTORED,
        credentialsJson = ProfileManager.STORE_MARKER_V1,
        createdAt = 1L
    )

    private fun subsonic(url: String) = ProfileCredentials.Subsonic(
        serverUrl = url.trimEnd('/'),
        username = "qa",
        password = "pw"
    )

    private fun spotify(scopes: List<String>) = ProfileCredentials.Spotify(
        accessToken = "a1",
        refreshToken = "r1",
        expiresAtEpochMs = 1L,
        scopes = scopes
    )

    private fun CoroutineScope.cancelChildren() {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    // ── fakes ──────────────────────────────────────────────────────────

    /** A store whose reads fail while [locked] — a key not there yet — then read as usual. */
    private class LockableCredentialsStore : ProfileCredentialsStore {
        val inner = InMemoryProfileCredentialsStore()
        var locked = true

        override fun write(profileId: String, credentials: ProfileCredentials) = inner.write(profileId, credentials)

        override fun read(profileId: String): ProfileCredentials? {
            check(!locked) { "credentials store locked" }
            return inner.read(profileId)
        }

        override fun delete(profileId: String) = inner.delete(profileId)
    }

    private class InMemoryProfileDao : ProfileDao {
        private val byId = linkedMapOf<String, Profile>()
        private val flow = MutableStateFlow<List<Profile>>(emptyList())

        /** While set and not complete, one-shot reads wait on it (a build of the active source, held). */
        var holdReads: CompletableDeferred<Unit>? = null

        override fun observeAll(): Flow<List<Profile>> = flow.asStateFlow()
        override suspend fun getAll(): List<Profile> {
            holdReads?.await()
            return byId.values.toList()
        }
        override suspend fun getById(id: String): Profile? {
            holdReads?.await()
            return byId[id]
        }
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

    private companion object {
        const val RESTORED = "restored"
        const val IN_USE = "in-use"
        const val PING_OK = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        const val PING_WRONG_PASSWORD = """{"subsonic-response":{"status":"failed","version":"1.16.1",""" +
            """"error":{"code":40,"message":"Wrong username or password"}}}"""
    }
}
