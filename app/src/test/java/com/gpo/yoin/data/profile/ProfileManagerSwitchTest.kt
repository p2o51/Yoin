package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A switch's stages say what it is doing — Connecting while the target's
 * server is asked (the ping), Priming only through the warm-up after it
 * (PIP-19: "Warming caches…" stood over a hanging ping) — and the target's
 * source is built off the caller's thread (a switch begun from a tap runs on
 * the main thread; its credentials are a file and a Keystore decrypt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerSwitchTest {

    private val server = MockWebServer()
    private val pingAsked = CountDownLatch(1)
    private val answerPing = CountDownLatch(1)
    private val warmUpAsked = CountDownLatch(1)
    private val answerWarmUp = CountDownLatch(1)
    private val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CALLER) }

    @After
    fun tearDown() {
        answerPing.countDown()
        answerWarmUp.countDown()
        server.shutdown()
        caller.shutdownNow()
    }

    @Test
    fun should_connectWhileTheServerIsAskedAndPrimeOnlyAfterIt_when_switching() = runTest {
        server.dispatcher = heldServer()
        server.start()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryProfileCredentialsStore()
        val manager = manager(scope, store)
        scope.advanceUntilIdle()

        val switch = async(caller.asCoroutineDispatcher()) { manager.switchTo(TARGET) }

        // The ping is out and unanswered: still connecting, not warming caches.
        assertTrue(pingAsked.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(
            ProfileManager.SwitchState.Switching(TARGET, ProfileManager.SwitchState.Stage.Connecting),
            manager.switchingState.value
        )

        // The server answered: the warm-up is what it is waiting on now.
        answerPing.countDown()
        assertTrue(warmUpAsked.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(
            ProfileManager.SwitchState.Switching(TARGET, ProfileManager.SwitchState.Stage.Priming),
            manager.switchingState.value
        )

        answerWarmUp.countDown()
        switch.await()
        assertEquals(ProfileManager.SwitchState.Idle, manager.switchingState.value)
        assertEquals(TARGET, manager.activeProfileId.value)
        scope.cancelChildren()
    }

    @Test
    fun should_readTheTargetsCredentialsOffTheCallersThread_when_switching() = runTest {
        server.dispatcher = heldServer()
        server.start()
        answerPing.countDown()
        answerWarmUp.countDown()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = ThreadRecordingStore()
        val manager = manager(scope, store)
        scope.advanceUntilIdle()

        async(caller.asCoroutineDispatcher()) { manager.switchTo(TARGET) }.await()

        assertEquals(TARGET, manager.activeProfileId.value)
        val readOn = store.readThreads(TARGET)
        assertTrue("the target's credentials were read", readOn.isNotEmpty())
        // (A thread's name carries the coroutine running on it in tests: "caller @coroutine#n".)
        assertFalse("read on the caller's thread: $readOn", readOn.any { it.startsWith(CALLER) })
        scope.cancelChildren()
    }

    private fun manager(scope: CoroutineScope, store: ProfileCredentialsStore): ProfileManager {
        val dao = InMemoryProfileDao()
        val url = server.url("/").toString().trimEnd('/')
        listOf(IN_USE, TARGET).forEachIndexed { index, id ->
            store.write(id, ProfileCredentials.Subsonic(serverUrl = url, username = id, password = "pw"))
            dao.rows[id] = Profile(
                id = id,
                provider = "subsonic",
                displayName = id,
                credentialsJson = ProfileManager.STORE_MARKER_V1,
                createdAt = index.toLong()
            )
        }
        return ProfileManager(
            profileDao = dao,
            activeIdStore = InMemoryActiveIdStore(IN_USE),
            credentialsStore = store,
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope
        )
    }

    /** The ping waits for [answerPing]; the warm-up's first read for [answerWarmUp]. */
    private fun heldServer() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            return if (path.endsWith("/ping.view")) {
                pingAsked.countDown()
                answerPing.await(WAIT_S, TimeUnit.SECONDS)
                MockResponse().setBody(PING_OK)
            } else {
                warmUpAsked.countDown()
                answerWarmUp.await(WAIT_S, TimeUnit.SECONDS)
                MockResponse().setResponseCode(404)
            }
        }
    }

    private fun CoroutineScope.cancelChildren() {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    // ── fakes ──────────────────────────────────────────────────────────

    /** Remembers the threads each profile's credentials were read on. */
    private class ThreadRecordingStore : ProfileCredentialsStore {
        private val inner = InMemoryProfileCredentialsStore()
        private val reads = mutableMapOf<String, MutableList<String>>()

        fun readThreads(profileId: String): List<String> = synchronized(reads) { reads[profileId].orEmpty().toList() }

        override fun write(profileId: String, credentials: ProfileCredentials) = inner.write(profileId, credentials)

        override fun read(profileId: String): ProfileCredentials? {
            synchronized(reads) { reads.getOrPut(profileId) { mutableListOf() } += Thread.currentThread().name }
            return inner.read(profileId)
        }

        override fun delete(profileId: String) = inner.delete(profileId)
    }

    private class InMemoryProfileDao : ProfileDao {
        val rows = linkedMapOf<String, Profile>()
        private val flow = MutableStateFlow<List<Profile>>(emptyList())

        override fun observeAll(): Flow<List<Profile>> = flow.asStateFlow()
        override suspend fun getAll(): List<Profile> = rows.values.toList()
        override suspend fun getById(id: String): Profile? = rows[id]
        override suspend fun count(): Int = rows.size
        override suspend fun upsert(profile: Profile) {
            rows[profile.id] = profile
            flow.value = rows.values.toList()
        }
        override suspend fun delete(profile: Profile) {
            rows.remove(profile.id)
            flow.value = rows.values.toList()
        }
        override suspend fun deleteById(id: String) {
            rows.remove(id)
            flow.value = rows.values.toList()
        }
    }

    private class InMemoryActiveIdStore(private var value: String?) : ProfileActiveIdStore {
        override fun read(): String? = value
        override fun write(id: String?) {
            value = id
        }
    }

    private companion object {
        const val IN_USE = "in-use"
        const val TARGET = "target"
        const val CALLER = "caller"
        const val WAIT_S = 10L
        const val PING_OK = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
    }
}
